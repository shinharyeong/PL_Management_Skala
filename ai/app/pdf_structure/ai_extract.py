"""AI 로 PDF 구성을 읽는다. 처음 보는 양식도 다루기 위해, 코드 규칙이 못 맞추는 곳을 AI 가 페이지 그림을 보고 묶어 준다.

역할 나눔(숫자·내용은 AI 가 만들지 않는다):
- 코드: 글자·줄·칸·위치 추출, 숫자 가리기(그림의 숫자 칸을 검게 칠하고, 줄 글자는 {수치} 로 가린 뒤 전송), 결과 조립과 검사
- AI : "어느 줄들이 어떤 종류의 블록인지" 묶음만 돌려준다(줄 번호 목록 + 종류). 글자·숫자는 돌려주지 않는다.
- 코드가 다시 검사: 모든 줄이 정확히 한 번씩 쓰였는지, 종류가 허용 값인지, 표 줄이 칸을 갖는지, 코드 규칙 결과와 같은지.
  다르거나 이상하면 needs_review 로 표시한다(모델 확신도가 아니라 시스템 검사로).
키가 없거나 하루 상한이거나 호출이 실패하면 코드 규칙 결과를 그대로 쓴다.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re

from ..config import get_settings
from ..services import openai_client
from ..services.call_log import CallLog
from . import extract as X

PROMPT_VERSION = "pdf-struct-v1"
KINDS = ("title", "paragraph", "table", "list", "footnote")
MAX_AI_PAGES = 6

SYSTEM = """당신은 보고서 PDF 의 구성을 읽는 도우미다. 페이지 그림(숫자는 검게 가려져 있다)과, 코드가 뽑은 줄 목록을 받는다.
줄 목록의 각 줄: id, 글자(금액·비율은 {수치}), cells(간격이 큰 덩어리 수), size(글자 크기), bold, x(왼쪽 위치), top(위쪽 위치).
할 일: 연속한 줄들을 블록으로 묶고 종류를 정한다. 종류는 title(제목·소제목), paragraph(문장), table(표), list(번호·불릿 목록), footnote(※·* 각주) 중 하나다.
- table 은 칸이 맞춰진 여러 줄이다(머리줄 포함). header_rows 에 머리줄 수를 적는다.
- list 는 "1." 같은 번호나 불릿이 이어지는 줄들이다.
- footnote 는 ※·*·숫자) 로 시작하는 주석이다. footnote_of 에 그 각주가 붙은 블록의 번호(blocks 배열 순서, 0부터)를 적고, 모르면 null.
- title 이면 level 에 1(문서 제목) 또는 2(소제목)를 적는다.
규칙: 모든 줄 id 를 정확히 한 번씩, 읽는 순서대로 쓴다. 글자와 숫자는 절대 적지 않는다(줄 id 만).
JSON 하나만 돌려준다: {"blocks":[{"kind":"title","lines":[0],"level":1},{"kind":"table","lines":[3,4,5],"header_rows":1},{"kind":"footnote","lines":[9],"footnote_of":2}]}"""


VISION_SYSTEM = """당신은 보고서 그림(페이지 한 장)의 구성을 읽는 도우미다. 글자 정보 없이 그림만 받는다.
그림 속 내용을 위에서 아래 순서의 블록으로 나눠 JSON 으로 돌려준다. 종류: title(제목·소제목), paragraph(문장), table(표), list(번호·불릿 목록), footnote(※·* 각주).
각 블록: kind, bbox=[x0,y0,x1,y1] (그림 크기를 1000 으로 본 상대 좌표), 그리고
- title: text, level(1 문서 제목 / 2 소제목)
- paragraph: text
- table: rows(행 목록, 각 행은 칸 목록), header_rows. 숫자 칸은 문자열 대신 null 로 쓴다(숫자는 절대 옮겨 적지 않는다). 글자 칸은 글자 그대로.
- list: items=[{"marker":"1." 또는 "I." 또는 "•","text":...,"level":0 또는 1}]
- footnote: marker, text, footnote_of(붙은 블록의 번호 0부터, 모르면 null)
글자 속 금액·비율·건수 같은 숫자는 적지 말고 그 자리에 {수치} 라고 쓴다. 날짜(연·월·차)는 그대로 쓴다.
JSON 하나만: {"blocks":[...]}"""


class AiUnavailable(Exception):
    pass


def _ready(s) -> None:
    if not s.key_present:
        raise AiUnavailable("키가 없어 코드 규칙으로 읽었습니다")
    if CallLog(s.log_dir or s.output_dir).calls_today() >= s.ai_daily_call_limit:
        raise AiUnavailable("오늘 AI 호출 상한에 도달해 코드 규칙으로 읽었습니다")


def _line_rows(lines) -> list[dict]:
    return [{"id": l.lid, "text": X.mask(l.raw)[0], "cells": len(l.cells), "size": l.size, "bold": l.all_bold, "x": round(l.x0), "top": round(l.top)} for l in lines]


def _call(s, image_png: bytes, lines) -> tuple[dict, int, int]:
    _ready(s)
    log = CallLog(s.log_dir or s.output_dir)
    rows = json.dumps(_line_rows(lines), ensure_ascii=False)
    meta = {"task": "pdf_structure", "status": "-", "model": s.openai_model, "prompt_version": PROMPT_VERSION, "facts_hash": hashlib.sha256(rows.encode("utf-8")).hexdigest()[:12]}
    data_url = "data:image/png;base64," + base64.b64encode(image_png).decode()
    try:
        client = openai_client.make_client(s)
        resp = client.chat.completions.create(
            model=s.openai_model, temperature=0, max_tokens=1500, response_format={"type": "json_object"},
            messages=[{"role": "system", "content": SYSTEM},
                      {"role": "user", "content": [{"type": "text", "text": "줄 목록:\n" + rows}, {"type": "image_url", "image_url": {"url": data_url, "detail": "high"}}]}])
        out = json.loads(resp.choices[0].message.content)
        if not isinstance(out, dict):
            raise ValueError("JSON object expected")
        tin, tout = int(getattr(resp.usage, "prompt_tokens", 0) or 0), int(getattr(resp.usage, "completion_tokens", 0) or 0)
    except Exception as e:  # noqa: BLE001 — 오류 종류만 기록(키·응답 원문 없음)
        kind = type(e).__name__ + (f" HTTP {e.status_code}" if getattr(e, "status_code", None) else "")
        log.append({**meta, "outcome": "error", "input_tokens": 0, "output_tokens": 0, "error": kind})
        raise AiUnavailable(f"AI 호출 실패({kind})") from e
    log.append({**meta, "outcome": "success", "input_tokens": tin, "output_tokens": tout})
    return out, tin, tout


def _validate(out: dict, lines) -> list[dict]:
    """AI 묶음을 검사한다. 줄이 빠지거나 겹치거나 순서가 어긋나면 오류."""
    groups = out.get("blocks")
    if not isinstance(groups, list) or not groups:
        raise ValueError("blocks 가 없음")
    ids = [l.lid for l in lines]
    seen: list[int] = []
    clean = []
    for g in groups:
        if not isinstance(g, dict) or g.get("kind") not in KINDS or not isinstance(g.get("lines"), list) or not g["lines"]:
            raise ValueError("블록 형식 오류")
        ls = [int(i) for i in g["lines"]]
        seen += ls
        clean.append({**g, "lines": ls})
    if seen != ids:
        raise ValueError("줄이 빠지거나 겹치거나 순서가 다름")
    return clean


def _build(g: dict, by_id: dict, page: int) -> dict:
    ls = [by_id[i] for i in g["lines"]]
    k = g["kind"]
    b = {"kind": k, "page": page, "bbox": X._bbox(ls), "needs_review": [], "line_ids": g["lines"], "from_ai": True}
    if k == "table":
        if len(ls) < 2 or not all(len(l.cells) >= 2 for l in ls):
            b["kind"] = "paragraph"
            text, cnt = X.mask(" ".join(l.raw for l in ls))
            b.update({"text": text, "numbers_masked": cnt})
            b["needs_review"].append(X._flag("kind_ambiguous", "AI 가 표라고 했지만 칸이 맞춰진 줄이 2줄 미만이라 문장으로 두었습니다"))
            return b
        t, flags = X._table(ls)
        hr = g.get("header_rows")
        if isinstance(hr, int) and 0 <= hr <= len(ls) - 1:
            t["header_rows"] = hr
        b["table"], b["needs_review"] = t, flags
    elif k == "list":
        b["items"] = X._list_items(ls)
    elif k == "title":
        text, cnt = X.mask(" ".join(l.raw for l in ls))
        b.update({"level": 1 if g.get("level") == 1 else 2, "text": text, "numbers_masked": cnt})
    else:
        raw = " ".join(l.raw for l in ls)
        fm = X.FOOT.match(raw)
        text, cnt = X.mask(raw[fm.end():] if (k == "footnote" and fm) else raw)
        b.update({"text": text, "numbers_masked": cnt})
        if k == "footnote":
            b.update({"marker": (fm.group(1) + (fm.group(2) or "")) if fm else "※", "link": None})
    return b


def _compare(ai_blocks: list[dict], rule_blocks: list[dict]) -> None:
    """같은 줄 묶음에서 종류가 같으면 일치. 다르면 시스템 검사 결과로 확인 필요를 붙인다."""
    by_first = {b["line_ids"][0]: b for b in rule_blocks if b.get("line_ids")}
    for b in ai_blocks:
        r = by_first.get(b["line_ids"][0])
        if r is None or r["line_ids"] != b["line_ids"]:
            b["needs_review"].append(X._flag("ai_rule_mismatch", "AI 와 코드 규칙이 줄을 다르게 묶었습니다"))
        elif r["kind"] != b["kind"]:
            b["needs_review"].append(X._flag("ai_rule_mismatch", f"AI 는 {b['kind']}, 코드 규칙은 {r['kind']} 로 봤습니다"))


def _vision_call(s, png: bytes, system: str | None = None, task: str = "pdf_structure_vision", max_tokens: int = 4000) -> tuple[dict, int, int]:
    _ready(s)
    log = CallLog(s.log_dir or s.output_dir)
    model = (getattr(s, "vlm_model", "") or s.openai_model) if task == "pdf_structure_vlm" else s.openai_model
    meta = {"task": task, "status": "-", "model": model, "prompt_version": PROMPT_VERSION, "facts_hash": hashlib.sha256(png).hexdigest()[:12]}
    data_url = "data:image/png;base64," + base64.b64encode(png).decode()
    try:
        client = openai_client.make_client(s)
        resp = client.chat.completions.create(
            model=model, temperature=0, max_tokens=max_tokens, response_format={"type": "json_object"},
            messages=[{"role": "system", "content": system or VISION_SYSTEM}, {"role": "user", "content": [{"type": "text", "text": "이 페이지의 구성을 읽어 주세요."}, {"type": "image_url", "image_url": {"url": data_url, "detail": "high"}}]}])
        out = json.loads(resp.choices[0].message.content)
        if not isinstance(out, dict):
            raise ValueError("JSON object expected")
        tin, tout = int(getattr(resp.usage, "prompt_tokens", 0) or 0), int(getattr(resp.usage, "completion_tokens", 0) or 0)
    except Exception as e:  # noqa: BLE001
        kind = type(e).__name__ + (f" HTTP {e.status_code}" if getattr(e, "status_code", None) else "")
        log.append({**meta, "outcome": "error", "input_tokens": 0, "output_tokens": 0, "error": kind})
        raise AiUnavailable(f"AI 호출 실패({kind})") from e
    log.append({**meta, "outcome": "success", "input_tokens": tin, "output_tokens": tout})
    return out, tin, tout


def _vision_blocks(out: dict, page: dict) -> list[dict]:
    """그림만 보고 읽은 결과를 블록으로 조립. 글자는 코드가 다시 한 번 숫자를 가리고, 표의 숫자 칸은 값 없이 표시한다."""
    groups = out.get("blocks")
    if not isinstance(groups, list) or not groups:
        raise ValueError("blocks 가 없음")
    W, H = page["width"], page["height"]
    res = []
    for g in groups:
        if not isinstance(g, dict) or g.get("kind") not in KINDS:
            raise ValueError("블록 형식 오류")
        k = g["kind"]
        bb = g.get("bbox")
        if isinstance(bb, list) and len(bb) == 4 and all(isinstance(v, (int, float)) for v in bb):
            x0, y0, x1, y1 = [max(0.0, min(1000.0, float(v))) for v in bb]
            bbox = [round(x0 / 1000 * W, 1), round(y0 / 1000 * H, 1), round(max(x1, x0 + 1) / 1000 * W, 1), round(max(y1, y0 + 1) / 1000 * H, 1)]
        else:
            bbox = [0.0, 0.0, W, H]
        b = {"kind": k, "page": page["page"], "bbox": bbox, "needs_review": [], "line_ids": [], "from_ai": True}
        if k in ("title", "paragraph", "footnote"):
            text, cnt = X.mask(str(g.get("text") or ""))
            if not text.strip():
                raise ValueError("글자가 없는 블록")
            b.update({"text": text, "numbers_masked": cnt})
            if k == "title":
                b["level"] = 1 if g.get("level") == 1 else 2
            if k == "footnote":
                b.update({"marker": str(g.get("marker") or "※")[:4], "link": None, "_tgt": g.get("footnote_of")})
        elif k == "table":
            rows = g.get("rows")
            if not isinstance(rows, list) or not rows or not all(isinstance(r, list) for r in rows):
                raise ValueError("표 행 형식 오류")
            n = max(len(r) for r in rows)
            cells = [[({"type": "number"} if (c is None or (isinstance(c, str) and X.NUM_CELL.match(c.strip()))) else {"type": "text", "text": X.mask(str(c))[0]}) for c in r] for r in rows]
            if any(len(r) != n for r in rows):
                b["needs_review"].append(X._flag("table_alignment", "행마다 칸 수가 다릅니다"))
            hr = g.get("header_rows") if isinstance(g.get("header_rows"), int) else 1
            unit = next((c["text"] for c in cells[0] if c["type"] == "text" and "단위" in c["text"]), None)
            b["table"] = {"columns": n, "header_rows": max(0, min(hr, len(rows) - 1)), "unit_hint": unit, "rows": cells,
                          "number_cells": sum(c["type"] == "number" for r in cells for c in r)}
        else:
            items = g.get("items")
            if not isinstance(items, list) or not items:
                raise ValueError("목록 항목 없음")
            tree, parent = [], None
            for it in items:
                if not isinstance(it, dict):
                    raise ValueError("목록 항목 형식 오류")
                text, cnt = X.mask(str(it.get("text") or ""))
                node = {"text": text, "marker": str(it.get("marker") or "•")[:4], "level": 1 if it.get("level") == 1 else 0, "children": [], "numbers_masked": cnt}
                if node["level"] == 1 and parent is not None:
                    parent["children"].append(node)
                else:
                    node["level"] = 0
                    tree.append(node)
                    parent = node
            b["items"] = tree
        res.append(b)
    return res


def refine_with_ai(pdf_bytes: bytes, pages: list[dict], all_lines: list[list], rule_blocks: list[dict], body: float, *, allow_raw_image: bool = False, images: list | None = None) -> tuple[list[dict], dict]:
    s = get_settings()
    info = {"used": False, "mode": "rules", "note": "", "calls": 0, "input_tokens": 0, "output_tokens": 0, "model": s.openai_model}
    try:
        _ready(s)
    except AiUnavailable as e:
        info["note"] = str(e)
        return rule_blocks, info
    imgs = images if images is not None else X.read_pdf(pdf_bytes, masked_images=True)[3]          # 숫자를 칠한 그림(전송용)
    vision_pages = 0
    out_blocks: list[dict] = []
    ai_pages = 0
    for pi, lines in enumerate(all_lines, 1):
        page_rules = [b for b in rule_blocks if b["page"] == pi]
        if not lines:                                                  # 글자가 없는 그림 쪽: 그림만 보고 읽어야 한다
            if not allow_raw_image:
                info["note"] += f"{pi}쪽은 글자가 없는 그림이라 숫자를 가릴 수 없어 읽지 않았습니다(옵션을 켜면 숫자가 그대로 AI 로 전송됩니다). "
                continue
            if ai_pages >= MAX_AI_PAGES:
                continue
            try:
                out, tin, tout = _vision_call(s, imgs[pi - 1])
                blocks_p = _vision_blocks(out, pages[pi - 1])
            except (AiUnavailable, ValueError, KeyError, TypeError) as e:
                info["note"] += f"{pi}쪽(그림): {e}. "
                continue
            ai_pages += 1
            vision_pages += 1
            info["calls"] += 1
            info["input_tokens"] += tin
            info["output_tokens"] += tout
            out_blocks += blocks_p
            info["used"] = True
            continue
        if ai_pages >= MAX_AI_PAGES:
            out_blocks += page_rules
            info["note"] += f"{pi}쪽부터는 AI 상한({MAX_AI_PAGES}쪽)이라 코드 규칙으로 읽었습니다. "
            continue
        try:
            out, tin, tout = _call(s, imgs[pi - 1], lines)
            groups = _validate(out, lines)
        except (AiUnavailable, ValueError, KeyError, TypeError) as e:
            out_blocks += page_rules
            info["note"] += f"{pi}쪽: {e} — 코드 규칙 결과 사용. "
            if isinstance(e, AiUnavailable):
                info["calls"] += 0 if "상한" in str(e) or "키가" in str(e) else 1
            continue
        ai_pages += 1
        info["calls"] += 1
        info["input_tokens"] += tin
        info["output_tokens"] += tout
        by_id = {l.lid: l for l in lines}
        built = [_build(g, by_id, pi) for g in groups]
        _compare(built, page_rules)
        # 각주 연결: AI 가 가리킨 블록, 같은 줄 묶음의 코드 규칙 추측과 비교
        for g, b in zip(groups, built):
            if b["kind"] != "footnote":
                continue
            tgt = g.get("footnote_of")
            b["_tgt"] = tgt if isinstance(tgt, int) and 0 <= tgt < len(built) and tgt != built.index(b) else None
        out_blocks += built
        info["used"] = True
    if not info["used"]:
        return rule_blocks, info
    info["mode"] = "ai"
    if vision_pages:
        info["note"] += f"그림만 보고 읽은 쪽 {vision_pages}개(숫자 가림 없이 전송, 위치는 근사). "
    for i, b in enumerate(out_blocks, 1):
        b["id"] = f"b{i}"
    # AI 가 가리킨 번호(페이지 안 순서) → 블록 id 로 바꾸고, 코드의 위치 기반 추측과 맞춰 본다
    X._link([b for b in out_blocks], body)
    pos = {}
    for b in out_blocks:
        pos.setdefault(b["page"], []).append(b)
    for pg, bs in pos.items():
        for b in bs:
            if b["kind"] != "footnote":
                continue
            t = b.pop("_tgt", None)
            guess = b["link"]["to"] if b.get("link") else None
            ai_to = bs[t]["id"] if isinstance(t, int) and 0 <= t < len(bs) and bs[t] is not b else None
            b["needs_review"] = [f for f in b["needs_review"] if f["code"] != "footnote_link"]
            if ai_to is not None:
                b["link"] = {"to": ai_to, "basis": "ai"}
            if ai_to is None or guess is None or ai_to != guess:
                why = "AI 와 위치 기준 추측이 다르거나 연결 대상이 없어 확정할 수 없습니다" if (ai_to or guess) else "연결된 블록이 없습니다"
                b["needs_review"].append(X._flag("footnote_link", why))
    for b in out_blocks:
        b.pop("_tgt", None)
    info["note"] = (info["note"] + f"AI {info['calls']}회 호출 · 입력 {info['input_tokens']} / 출력 {info['output_tokens']} 토큰 · 숫자는 가려서 전송").strip()
    return out_blocks, info


def vlm_primary(pages: list[dict], all_lines: list[list], rule_blocks: list[dict], body: float, *, allow_raw_image: bool = False, images: list | None = None, pdf_bytes: bytes | None = None, known: dict | None = None) -> tuple[list[dict], dict]:
    """구성 추출의 주역: VLM(그림)이 블록·계층·머리칸·각주·날짜 의미를 읽고, 코드는 글자 대조·지표·범위 검사만 한다."""
    from . import vlm as V
    s = get_settings()
    info0 = {"used": False, "mode": "rules", "note": "", "calls": 0, "input_tokens": 0, "output_tokens": 0, "model": s.openai_model, "engine": "vlm"}
    try:
        _ready(s)
    except AiUnavailable as e:
        info0["note"] = str(e)
        return rule_blocks, info0
    imgs = images if images is not None else X.read_pdf(pdf_bytes, masked_images=True)[3]
    blocks, info = V.extract_pages(s, pages, all_lines, imgs, rule_blocks, call=lambda png: _vision_call(s, png, V.SYSTEM, "pdf_structure_vlm", 6000),
                                   allow_raw_image=allow_raw_image, max_pages=MAX_AI_PAGES, ready=lambda: _ready(s))
    if not info["used"]:
        info["note"] = info["note"] or "VLM 호출 없음"
        return rule_blocks, info
    vb0 = [b for b in blocks if b.get("from_vlm")]
    for pg in {b["page"] for b in vb0}:                                  # 각주가 가리킨 번호(그 쪽 VLM 블록 순서)를 블록 자체로 바꿔 둔다(뒤에 블록이 끼어도 안 어긋남)
        pl = [b for b in vb0 if b["page"] == pg]
        for b in pl:
            if b["kind"] == "footnote":
                t = b.get("_tgt")
                b["_tgt"] = pl[t] if isinstance(t, int) and 0 <= t < len(pl) and pl[t] is not b else None
    added = V.add_missed(vb0, rule_blocks, {p["page"]: (p["width"], p["height"]) for p in pages}) if all_lines and any(all_lines) else []
    if added:
        blocks = sorted(blocks + added, key=lambda b: (b["page"], round(b["bbox"][1] / 6), b["bbox"][0]))
        info["added_from_rules"] = len(added)
    for i, b in enumerate(blocks, 1):
        b["id"] = f"b{i}"
    vb = [b for b in blocks if b.get("from_vlm")]
    info["tables_replaced"] = V.reconcile_tables(vb, rule_blocks)
    V.repair_text(vb, {i + 1: ls for i, ls in enumerate(all_lines)})
    V.cross_check(vb, {i + 1: ls for i, ls in enumerate(all_lines)})
    V.semantic_flags(blocks, known or {})
    info["coverage"] = V.coverage([b for b in blocks if b.get("from_vlm")], rule_blocks)
    X._link(blocks, body)
    pos = {}
    for b in blocks:
        pos.setdefault(b["page"], []).append(b)
    for bs in pos.values():
        for b in bs:
            if b["kind"] != "footnote":
                continue
            t = b.pop("_tgt", None)
            guess = b["link"]["to"] if b.get("link") else None
            ai_to = t["id"] if isinstance(t, dict) else None
            b["needs_review"] = [f for f in b["needs_review"] if f["code"] != "footnote_link"]
            if ai_to is not None:
                b["link"] = {"to": ai_to, "basis": "ai"}
            if ai_to is None or guess is None or ai_to != guess:
                b["needs_review"].append(X._flag("footnote_link", "VLM 과 위치 기준 추측이 다르거나 연결 대상이 없어 확정할 수 없습니다" if (ai_to or guess) else "연결된 블록이 없습니다"))
    for b in blocks:
        b.pop("_tgt", None)
    info["model"] = (getattr(s, "vlm_model", "") or s.openai_model)
    info["note"] = (info["note"] + f"VLM {info['calls']}회 호출 · 입력 {info['input_tokens']} / 출력 {info['output_tokens']} 토큰 · 숫자는 가려서 전송").strip()
    return blocks, info
