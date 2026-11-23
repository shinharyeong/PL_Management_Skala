"""구성 추출의 주역: 페이지 그림(숫자 가림)을 VLM 이 읽어 블록 구조 JSON 을 만든다. 코드는 검사·대조만 한다.

VLM 이 뽑는 것: 블록 종류(제목/문장/표/계층 목록/차트/각주/숫자 카드), 계층(I·II·III, 가·나·다, 중첩 불릿), 합쳐진 표 머리칸,
각주 연결(※, 1) 2)), 날짜 의미('26.11월 → 기준 월, 전년比 → 전년 동월 대비, 추정比 → 직전 추정 대비, 추이 → 최근 N개월).
숫자는 그림에서 칠해 보내고, VLM 은 숫자 칸을 null/{수치} 로만 돌려준다. 키 없음·상한·실패 시 호출 쪽이 코드 규칙으로 대체한다.
노란 표시(needs_review): 모델이 애매하다고 한 것 · pdfplumber 글자와 대조 실패 · 지표 사전에 없음 · 범위가 조직에 없음 · 날짜 해석.
"""
from __future__ import annotations

import re
import unicodedata
from difflib import SequenceMatcher

from . import extract as X
from . import table_header as TH

VLM_KINDS = ("title", "paragraph", "table", "list", "footnote", "chart", "numbercard")
PROMPT_VERSION = "pdf-vlm-v5"
MAX_LINES_PER_PAGE = 200                                            # 이보다 줄이 많은 쪽은 줄 목록을 보내지 않는다(입력 토큰 폭증 방지)
MEANINGS = ("base_month", "yoy", "vs_estimate", "vs_plan", "vs_prev_month", "trend_n", "other")

SYSTEM = """당신은 보고서 그림(페이지 한 장)의 구성을 읽는 도우미다. 페이지 그림과, 있으면 코드가 뽑은 "줄 목록"을 받는다. 금액·비율 같은 숫자는 검게 가려져 있다(줄 목록에서는 {수치}).
줄 목록의 각 줄: id, text(글자), cells(간격이 큰 덩어리 수), size(글자 크기), bold, x(왼쪽 위치), x1(오른쪽 끝), top(위쪽 위치).
같은 높이에 있는 줄들이 서로 다른 칸(x 위치가 다름)이면 서로 다른 블록일 수 있다. 줄 id 로만 묶는다.
그림 속 내용을 위에서 아래(왼쪽에서 오른쪽) 순서의 블록으로 나눠 JSON 으로 돌려준다. 종류(kind):
 title(제목·소제목), paragraph(문장), table(표), list(번호·불릿·계층 목록), footnote(※·*·1) 2) 각주), chart(그래프: 막대·선·도넛·워터폴), numbercard(큰 숫자 한 개와 이름이 있는 카드).
공통: lines=이 블록이 차지하는 줄 id 목록(읽는 순서, id 숫자만), uncertain(true/false: 종류나 경계가 애매하면 true), uncertain_reason(짧게).
위치는 좌표로 적지 않는다. 박스는 코드가 lines 의 실제 글자 위치로 만든다.
줄 목록이 있으면: 모든 줄 id 는 블록 하나의 lines 에 정확히 한 번만 쓴다. 장식·로고·쪽 번호 줄은 최상위 ignored_lines 에 적는다. 글자·숫자는 lines 에 적지 않는다(id 만).
줄 목록이 없거나, 그림(차트 등)에 글자 줄이 없으면 lines 는 빈 배열로 두고 bbox=[x0,y0,x1,y1] (그림 크기를 1000 으로 본 상대 좌표)를 대신 적는다.
- title: text, level(1 문서 제목 / 2 소제목)
- paragraph: text
- table: rows(행 목록, 각 행은 칸 목록. 글자 칸은 그대로, 가려진 숫자 칸은 null), header_rows(머리줄 수), header_spans=[{"row":0,"col":1,"colspan":2,"rowspan":1}] (합쳐진 머리칸만; 병합된 머리 칸은 header_spans 에 반드시 적고, rows 의 모든 줄은 같은 칸 수로 맞춘다. 병합으로 가려진 칸은 null),
  date_labels=[{"text":"머리칸 글자","meaning":"base_month|yoy|vs_estimate|vs_plan|vs_prev_month|trend_n|other","n":숫자또는null}]
  (예: '26.11월 → base_month, 전년比 → yoy, 추정比 → vs_estimate, 계획比 → vs_plan, 전월比 → vs_prev_month, 최근 6개월 추이 → trend_n 에 n=6)
- list: items=[{"marker":"I." 또는 "가." 또는 "1." 또는 "•","text":...,"level":0|1|2}] (들여쓰기·기호로 계층을 읽는다: I·II·III > 가·나·다 > 불릿)
- footnote: marker(※, *, 1) 등), text, footnote_of(붙은 블록의 번호, blocks 배열 순서 0부터, 모르면 null)
- chart: chart_type(bar|line|donut|waterfall|other), title(있으면), categories(축 글자 목록), series(범례 글자 목록). 값은 적지 않는다. 축 글자·제목 줄이 줄 목록에 있으면 그 id 를 lines 에 넣는다.
- numbercard: cards=[{"label":"카드 이름","unit":"단위 글자(억원 등)","delta_label":"전년比 같은 보조 글자 또는 null"}]. 숫자는 적지 않는다. 카드 이름 줄이 줄 목록에 있으면 그 id 를 lines 에 넣는다.
글자 속 금액·비율·건수 같은 숫자는 적지 말고 그 자리에 {수치} 라고 쓴다. 날짜(연·월·차)는 그대로 쓴다. 장식·로고·페이지 번호는 블록으로 만들지 않는다.
JSON 하나만: {"blocks":[{"kind":"title","lines":[0],"text":"...","level":1},...],"ignored_lines":[]}"""


def _norm(t: str) -> str:
    t = unicodedata.normalize("NFC", str(t or ""))
    t = re.sub(r"\{수치\}|[\s·•\-–—:：,.()\[\]※*<>]", "", t)
    return t.lower()


def _page_text(lines) -> str:
    return _norm(" ".join(X.mask(l.raw)[0] for l in lines))


def _texts(b: dict) -> list[str]:
    k = b["kind"]
    if k in ("title", "paragraph", "footnote"):
        return [b.get("text") or ""]
    if k == "table":
        return [c["text"] for r in b["table"]["rows"] for c in r if c["type"] == "text"]
    if k == "list":
        out = []

        def walk(items):
            for it in items:
                out.append(it["text"])
                walk(it.get("children") or [])
        walk(b["items"])
        return out
    if k == "chart":
        return list(b.get("categories") or []) + list(b.get("series") or [])
    if k == "numbercard":
        return [c.get("label") or "" for c in b.get("cards") or []]
    return []


def _present(t: str, page_norm: str) -> bool:
    n = _norm(t)
    if len(n) < 2:
        return True
    if n in page_norm:
        return True
    if len(n) <= 3:
        return False
    # 부분 일치: 가장 비슷한 같은 길이 구간과 비교(글자 한두 개 오독은 통과, 크게 다르면 실패)
    best = 0.0
    step = max(1, len(n) // 4)
    for i in range(0, max(1, len(page_norm) - len(n) + 1), step):
        best = max(best, SequenceMatcher(None, n, page_norm[i:i + len(n)]).ratio())
        if best >= 0.8:
            return True
    return False


def _pool(lines) -> list[str]:
    """PDF 글자 후보(줄 전체와 칸 하나하나, 숫자는 가림)."""
    out = []
    for l in lines:
        out.append(X.mask(l.raw)[0].strip())
        out += [X.mask(c["text"])[0].strip() for c in l.cells]
    more = []
    for t in out:                                                  # 목록 항목은 앞의 번호·글머리와 뒤의 숫자 자리를 뗀 글자로도 맞춰 본다('3. 일시적/정정 효과 {수치}' → '일시적/정정 효과')
        core = re.sub(r"\s*(\{수치\}\s*)+\S{0,3}$", "", re.sub(r"^\s*(?:\d+[.)]|[•·●○■□▪\-])\s*", "", t)).strip()
        if core and core != t:
            more.append(core)
    return [t for t in dict.fromkeys(out + more) if t]


def _best(t: str, pool: list[str]) -> tuple[str | None, float]:
    n = _norm(t)
    best, br = None, 0.0
    for p in pool:
        r = SequenceMatcher(None, n, _norm(p)).ratio()
        if r > br:
            best, br = p, r
    return best, br


def repair_text(blocks: list[dict], page_lines: dict[int, list], min_ratio: float = 0.62) -> int:
    """VLM 이 그림에서 잘못 읽은 글자를 PDF 에 실제로 있는 글자로 바로잡는다(구조는 VLM, 글자는 PDF). 반환: 고친 글자 수."""
    fixed = 0
    for b in blocks:
        lines = page_lines.get(b["page"])
        if not lines:
            continue
        pool = _pool(lines)

        def fix(t: str) -> str:
            nonlocal fixed
            if not t or len(_norm(t)) < 2 or _norm(t) in _page_text(lines):
                return t
            cand, r = _best(t, pool)
            if cand and r >= min_ratio:
                fixed += 1
                return cand
            return t
        k = b["kind"]
        if k in ("title", "paragraph", "footnote"):
            b["text"] = fix(b["text"])
        elif k == "table":
            for r in b["table"]["rows"]:
                for c in r:
                    if c["type"] == "text":
                        c["text"] = fix(c["text"])
        elif k == "list":
            def walk(items):
                for it in items:
                    it["text"] = fix(it["text"])
                    walk(it.get("children") or [])
            walk(b["items"])
    return fixed


_WORD = re.compile(r"[가-힣]{2,}")


def _page_words(lines) -> set[str]:
    return set(_WORD.findall(" ".join(X.mask(l.raw)[0] for l in lines)))


def _near_word(w: str, words: set[str]) -> str | None:
    """같은 길이이고 글자 대부분이 같은 PDF 단어가 하나뿐일 때만 그 단어(월정액↔원정액 같은 한두 글자 오독)."""
    if len(w) < 3:
        return None
    c = [p for p in words if len(p) == len(w) and SequenceMatcher(None, w, p).ratio() >= 0.6]
    return c[0] if len(c) == 1 else None


def repair_words(blocks: list[dict], page_lines: dict[int, list]) -> int:
    """글자 PDF 의 단어 사전으로 단어 단위 오독을 바로잡는다. 블록 전체가 길게 합쳐 읽혀 줄 단위 대조가 안 맞는 경우를 위한 보정. 반환: 고친 단어 수."""
    fixed = 0
    for b in blocks:
        lines = page_lines.get(b["page"])
        if not lines:
            continue
        words = _page_words(lines)

        def fx(t: str) -> str:
            nonlocal fixed

            def sub(m):
                nonlocal fixed
                w = m.group(0)
                if w in words:
                    return w
                n = _near_word(w, words)
                if n:
                    fixed += 1
                    return n
                return w
            return _WORD.sub(sub, t) if t else t
        k = b["kind"]
        if k in ("title", "paragraph", "footnote"):
            b["text"] = fx(b["text"])
        elif k == "table":
            for r in b["table"]["rows"]:
                for c in r:
                    if c["type"] == "text":
                        c["text"] = fx(c["text"])
        elif k == "list":
            def walk(items):
                for it in items:
                    it["text"] = fx(it["text"])
                    walk(it.get("children") or [])
            walk(b["items"])
    return fixed


def _best_rule_box(b: dict, rule_blocks: list[dict] | None) -> list | None:
    """같은 쪽·같은 종류의 규칙 블록 중 단어가 가장 겹치는 것(Jaccard >= 0.3)의 박스. 없으면 None."""
    if not rule_blocks:
        return None
    mine = {w for t in _texts(b) for w in _WORD.findall(t or "")}
    if not mine:
        return None
    best, bj = None, 0.0
    for r in rule_blocks:
        if r["kind"] != b["kind"] or r["page"] != b["page"]:
            continue
        theirs = {w for t in _texts(r) for w in _WORD.findall(t or "")}
        j = len(mine & theirs) / max(1, len(mine | theirs))
        if j > bj:
            best, bj = r, j
    return list(best["bbox"]) if best is not None and bj >= 0.3 else None


def fill_missing_lines(missing: dict[int, list[int]], page_lines: dict[int, list], rule_blocks: list[dict]) -> list[dict]:
    """VLM 이 어느 블록에도 넣지 않은 줄을 코드 규칙 블록으로 채우고 노란 표시(vlm_missed)를 붙인다.
    규칙 블록의 줄이 전부 빠진 줄이면 그 블록을, 규칙 블록과 맞지 않는 줄은 이어진 것끼리 문장 블록으로 만든다."""
    added: list[dict] = []
    for pg, ids in missing.items():
        left = set(ids)
        lines = {l.lid: l for l in page_lines.get(pg, [])}
        for r in rule_blocks:
            if r["page"] != pg or not r.get("line_ids") or not set(r["line_ids"]) <= left:
                continue
            nb = dict(r)
            nb["needs_review"] = list(r["needs_review"]) + [X._flag("vlm_missed", "VLM 이 이 줄들을 블록에 넣지 않아 코드 규칙으로 채운 블록입니다")]
            nb.update({"from_vlm": False, "from_rules_fill": True})
            added.append(nb)
            left -= set(r["line_ids"])
        run: list[int] = []
        for i in sorted(left) + [None]:                                # 이어진 줄 번호끼리 한 문장 블록
            if i is not None and (not run or i == run[-1] + 1):
                run.append(i)
                continue
            if run:
                ls = [lines[j] for j in run if j in lines]
                if ls:
                    text, cnt = X.mask(" ".join(l.raw for l in ls))
                    added.append({"kind": "paragraph", "page": pg, "bbox": X._bbox(ls), "text": text, "numbers_masked": cnt, "line_ids": list(run), "from_ai": True, "from_vlm": False, "from_rules_fill": True,
                                  "needs_review": [X._flag("vlm_missed", "VLM 이 이 줄을 블록에 넣지 않아 문장으로 채웠습니다. 종류를 확인하세요")]})
            run = [i] if i is not None else []
    return added


def snap_bboxes(blocks: list[dict], page_lines: dict[int, list], page_dims: dict[int, tuple[float, float]], rule_blocks: list[dict] | None = None) -> int:
    """모델이 그린 박스는 글자 위치와 어긋나기 쉽다(특히 세로). 박스 위치의 근거는 모델 좌표가 아니라 PDF 줄 좌표다.
    표·목록은 글자가 가장 많이 겹치는 규칙 블록의 박스를 쓰고, 나머지 글자 블록은 '글자 내용 + 읽는 순서'로 줄을 배정해 그 줄들의 합집합으로 맞춘다.
    끝내 맞추지 못한 블록은 규칙 박스로 대체하거나 box_unresolved 플래그를 단다(경고 없이 떠다니는 박스 금지). 반환: 맞춘 블록 수."""
    SNAP = ("title", "paragraph", "footnote", "table", "list")
    done: set[int] = set()
    exact = {id(b) for b in blocks if b.get("line_ids")}               # 줄 번호로 박스를 만든 블록은 이미 정확하다: 맞출 대상이 아니고, 그 영역의 줄은 다른 블록에 배정하지 않는다
    for b in blocks:                                                   # 표·목록: 같은 쪽의 규칙 블록 중 글자가 가장 겹치는 것의 박스
        if id(b) not in exact and b["kind"] in ("table", "list"):
            fb = _best_rule_box(b, rule_blocks)
            if fb is not None:
                b["bbox"] = fb
                done.add(id(b))
    by_page: dict[int, list[dict]] = {}
    for b in blocks:
        if id(b) not in done and id(b) not in exact and b["kind"] in SNAP:
            by_page.setdefault(b["page"], []).append(b)
    for pg, bs in by_page.items():
        lines = page_lines.get(pg) or []
        taken = [b["bbox"] for b in blocks if (id(b) in done or id(b) in exact) and b["page"] == pg]      # 이미 맞춘 영역의 줄은 다시 배정하지 않는다
        words = {id(b): {w for t in _texts(b) for w in _WORD.findall(t or "")} for b in bs}
        got: dict[int, list] = {id(b): [] for b in bs}
        cursor = 0                                                     # 줄이 배정된 가장 뒤 블록 순번. 앞 순번으로 되돌아가 배정하지 않는다(읽는 순서)
        for l in lines:
            cy = (l.top + l.bottom) / 2
            if any(t[1] <= cy <= t[3] for t in taken):
                continue
            lw = _WORD.findall(X.mask(l.raw)[0].replace("{수치}", " "))
            if not lw:
                continue
            best, bf, bi = None, 0.0, -1
            for i in range(cursor, len(bs)):
                frac = sum(1 for w in lw if w in words[id(bs[i])]) / len(lw)
                if frac > bf:                                          # 같은 값이면 앞 순번(현재 위치에 가까운 쪽)을 쓴다
                    best, bf, bi = bs[i], frac, i
            if best is not None and bf >= 0.7:
                got[id(best)].append(l)
                cursor = bi
        for b in bs:
            hit = got[id(b)]
            if hit:
                b["bbox"] = [round(min(l.x0 for l in hit) - 2, 1), round(min(l.top for l in hit) - 2, 1), round(max(l.x1 for l in hit) + 2, 1), round(max(l.bottom for l in hit) + 2, 1)]
                done.add(id(b))
                continue
            fb = _best_rule_box(b, rule_blocks)
            if fb is not None:
                b["bbox"] = fb
                done.add(id(b))
            elif not any(f["code"] == "box_unresolved" for f in b["needs_review"]):
                b["needs_review"].append(X._flag("box_unresolved", "박스 위치를 PDF 글자와 맞추지 못했습니다. 위치가 부정확할 수 있습니다"))
    return len(done)


def cross_check(blocks: list[dict], page_lines: dict[int, list]) -> None:
    """VLM 이 읽은 글자를 pdfplumber 글자와 대조한다(글자 PDF 에서만). 맞지 않는 블록은 노란 표시."""
    for b in blocks:
        lines = page_lines.get(b["page"])
        if not lines:
            continue
        pn = _page_text(lines)
        bad = [t for t in _texts(b) if t and not _present(t, pn)]
        words = _page_words(lines)
        odd = sorted({w for t in _texts(b) for w in _WORD.findall(t or "") if w not in words})     # PDF 에 없는 단어(1~2글자 오독도 잡는다)
        if not bad and odd and b["kind"] not in ("chart", "numbercard"):
            b["needs_review"].append(X._flag("text_mismatch", f"VLM 이 읽은 단어 {len(odd)}개가 PDF 글자에 없습니다(예: ‘{odd[0]}’{', ‘' + odd[1] + '’' if len(odd) > 1 else ''})", {"words": odd[:12]}))
        if bad:
            b["needs_review"].append(X._flag("text_mismatch", f"VLM 이 읽은 글자 {len(bad)}곳이 PDF 글자와 맞지 않습니다(예: ‘{bad[0][:20]}’)", {"words": [t[:30] for t in bad[:6]]}))


def semantic_flags(blocks: list[dict], known: dict) -> None:
    """지표 사전·조직 범위·날짜 해석을 확인해 노란 표시를 붙인다. known = {metrics, orgs(이름·코드), months[(y,m)]}"""
    metrics = {_norm(m) for m in known.get("metrics", [])}
    orgs = {_norm(o) for o in known.get("orgs", [])}
    for b in blocks:
        if b["kind"] != "table":
            continue
        t = b["table"]
        hr = t["header_rows"]
        labels = [r[0]["text"] for r in t["rows"][hr:] if r and r[0]["type"] == "text"]
        unknown = [l for l in labels if _norm(l) and _norm(l) not in metrics and _norm(l) not in orgs and not _norm(l).startswith(("합계", "소계", "총계")) and _norm(l) != "계" and "전사" not in l]
        org_like = [l for l in labels if re.search(r"사업부|본부|팀|부문", l)]
        miss_org = [l for l in org_like if _norm(l) not in orgs]
        if miss_org:
            b["needs_review"].append(X._flag("scope_unknown", f"범위가 조직 목록에 없음: {', '.join(miss_org[:4])}"))
        miss_metric = [l for l in unknown if l not in miss_org]
        if miss_metric and metrics:
            b["needs_review"].append(X._flag("metric_unknown", f"지표 사전에 없음: {', '.join(miss_metric[:4])}"))
        for d in b.get("date_labels") or []:
            if d.get("meaning") == "other" or d.get("meaning") not in MEANINGS:
                b["needs_review"].append(X._flag("date_semantics", f"날짜·비교 표현 ‘{str(d.get('text'))[:12]}’ 의미를 확정하지 못했습니다"))


def lead_header_rows(rows: list, hr: int) -> int:
    """머리줄 수 보정: 맨 위에서부터 숫자 칸이 하나도 없는 줄은 머리줄로 본다(두 줄짜리 머리를 한 줄로 읽은 경우)."""
    n = 0
    for r in rows:
        if any(c.get("type") == "number" for c in r):
            break
        n += 1
    return max(hr, min(n, max(1, len(rows) - 1)))


_IGNORABLE = re.compile(r"[\s\d.,%+\-−–△▲▼/()'~:]*")


def _ignorable(raw: str) -> bool:
    """VLM 이 블록에 넣지 않고 무시해도 되는 줄: 숫자·기호뿐인 줄(쪽 번호 '- 1 -', 차트 눈금·데이터 라벨)."""
    return bool(_IGNORABLE.fullmatch(raw or ""))


_BOX_WHY = "박스 위치를 PDF 글자와 맞추지 못했습니다. 위치가 부정확할 수 있습니다"
_SNAPPABLE = ("title", "paragraph", "footnote", "table", "list")


def _vlm_bbox(g: dict, W: float, H: float) -> list[float] | None:
    bb = g.get("bbox")
    if isinstance(bb, list) and len(bb) == 4 and all(isinstance(v, (int, float)) for v in bb):
        x0, y0, x1, y1 = [max(0.0, min(1000.0, float(v))) for v in bb]
        return [round(x0 / 1000 * W, 1), round(y0 / 1000 * H, 1), round(max(x1, x0 + 1) / 1000 * W, 1), round(max(y1, y0 + 1) / 1000 * H, 1)]
    return None


def build_blocks(out: dict, page: dict, lines: list | None = None, report: dict | None = None) -> list[dict]:
    """VLM JSON 한 쪽 → 블록. 형식이 어긋나면 ValueError(그 쪽은 코드 규칙으로 대체).
    박스는 VLM 이 알려 준 줄 id 들의 실제 PDF 좌표 합집합으로 만든다(VLM 좌표는 글자 줄이 없는 블록에만 쓴다).
    report 를 주면 줄 검증 결과를 채운다: {"missing": 어느 블록에도 없는 줄 id, "overlap": 여러 블록에 걸친 줄 id}."""
    groups = out.get("blocks")
    if not isinstance(groups, list) or not groups:
        raise ValueError("blocks 가 없음")
    W, H = page["width"], page["height"]
    by_id = {l.lid: l for l in (lines or [])}
    owner: dict[int, int] = {}                                         # 줄 id → 먼저 배정한 블록 순번
    overlap: list[int] = []
    res = []
    for gi, g in enumerate(groups):
        if not isinstance(g, dict) or g.get("kind") not in VLM_KINDS:
            raise ValueError("블록 종류 형식 오류")
        k = g["kind"]
        ids, dup = [], []
        for v in (g.get("lines") or []) if isinstance(g.get("lines"), list) else []:
            if isinstance(v, bool) or not isinstance(v, int) or v not in by_id or v in ids:
                continue
            if v in owner:
                dup.append(v)                                          # 먼저 배정된 블록이 갖는다
            else:
                ids.append(v)
        for v in ids:
            owner[v] = gi
        overlap += dup
        if ids:
            bbox = X._bbox([by_id[i] for i in ids])
        else:
            bbox = _vlm_bbox(g, W, H) or [0.0, 0.0, W, H]
        b = {"kind": k, "page": page["page"], "bbox": bbox, "needs_review": [], "line_ids": ids, "from_ai": True, "from_vlm": True}
        if dup:
            b["needs_review"].append(X._flag("kind_ambiguous", "한 줄이 두 블록에 걸쳐 있습니다"))
        if not ids and (not by_id or k not in _SNAPPABLE):             # 글자 줄이 없는 쪽·차트 등은 VLM 좌표 그대로라 위치를 보증할 수 없다(글자 블록은 뒤의 snap 이 맞추거나 표시)
            b["needs_review"].append(X._flag("box_unresolved", _BOX_WHY))
        if g.get("uncertain") is True:
            b["needs_review"].append(X._flag("vlm_uncertain", "모델이 애매하다고 표시: " + str(g.get("uncertain_reason") or "이유 없음")[:60]))
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
            cells = [[({"type": "number"} if (c is None or (isinstance(c, str) and (X.NUM_CELL.match(c.strip()) or c.strip() == "{수치}"))) else {"type": "text", "text": X.mask(str(c))[0]}) for c in r] for r in rows]
            if any(len(r) != n for r in rows):
                b["needs_review"].append(X._flag("table_alignment", "행마다 칸 수가 다릅니다"))
            hr = g.get("header_rows") if isinstance(g.get("header_rows"), int) else 1
            unit = next((c["text"] for r in cells[:2] for c in r if c["type"] == "text" and "단위" in c["text"]), None)
            b["table"] = {"columns": n, "header_rows": lead_header_rows(cells, max(0, min(hr, len(rows) - 1))), "unit_hint": unit, "rows": cells,
                          "number_cells": sum(c["type"] == "number" for r in cells for c in r)}
            spans = [s for s in (g.get("header_spans") or []) if isinstance(s, dict) and isinstance(s.get("row"), int) and isinstance(s.get("col"), int)]
            if spans:
                b["table"]["header_spans"] = [{"row": s["row"], "col": s["col"], "colspan": int(s.get("colspan") or 1), "rowspan": int(s.get("rowspan") or 1)} for s in spans]
            lay = TH.header_layout(cells, b["table"]["header_rows"], b["table"].get("header_spans"))       # 머리 격자(병합 반영): 화면은 이 격자로 머리를 그린다
            b["table"]["header_grid"], b["table"]["header_cells"] = lay["grid"], lay["cells"]
            if lay["ambiguous"] and b["table"]["header_rows"] > 1:
                b["needs_review"].append(X._flag("header_ambiguous", "머리가 여러 줄인데 병합된 칸의 범위를 알 수 없습니다(줄마다 칸 수가 다르거나 header_spans 가 없음). 열 이름을 확인하세요"))
            b["date_labels"] = [{"text": str(d.get("text") or ""), "meaning": d.get("meaning") if d.get("meaning") in MEANINGS else "other", "n": d.get("n") if isinstance(d.get("n"), int) else None}
                                for d in (g.get("date_labels") or []) if isinstance(d, dict)]
        elif k == "list":
            items = g.get("items")
            if not isinstance(items, list) or not items:
                raise ValueError("목록 항목 없음")
            tree, stack = [], []
            for it in items:
                if not isinstance(it, dict):
                    raise ValueError("목록 항목 형식 오류")
                raw_t = str(it.get("text") or "").strip()
                mk = str(it.get("marker") or "").strip()
                if mk and raw_t.startswith(mk):                          # 모델이 번호를 marker 와 text 에 두 번 적은 경우('1. 1. …')
                    raw_t = raw_t[len(mk):].strip()
                raw_t = re.sub(r"^[•·●○■□▪]\s*", "", raw_t)               # 번호 뒤에 남은 글머리 기호
                text, cnt = X.mask(raw_t)
                lv = it.get("level") if it.get("level") in (0, 1, 2) else 0
                node = {"text": text, "marker": str(it.get("marker") or "•")[:4], "level": 0, "children": [], "numbers_masked": cnt}
                lv = min(lv, len(stack))
                if lv == 0 or not stack:
                    tree.append(node)
                    stack = [node]
                else:
                    node["level"] = lv
                    stack[lv - 1]["children"].append(node)
                    stack = stack[:lv] + [node]
            b["items"] = tree
        elif k == "chart":
            b.update({"chart_type": g.get("chart_type") if g.get("chart_type") in ("bar", "line", "donut", "waterfall") else "other", "title": str(g.get("title") or "")[:80],
                      "categories": [str(x)[:30] for x in (g.get("categories") or [])][:40], "series": [str(x)[:30] for x in (g.get("series") or [])][:10]})
        else:
            cards = [{"label": str(c.get("label") or "")[:40], "unit": str(c.get("unit") or "")[:10], "delta_label": (str(c.get("delta_label"))[:20] if c.get("delta_label") else None)}
                     for c in (g.get("cards") or []) if isinstance(c, dict)]
            if not cards:
                raise ValueError("카드 없음")
            b["cards"] = cards
        res.append(b)
    for gi, b in enumerate(res):                                       # 줄이 떨어져 있으면(사이에 다른 블록의 줄이 끼어 있음) 박스가 넓어진다 → 확인 요청
        ids = b["line_ids"]
        if len(ids) >= 2 and any(owner.get(i) not in (None, gi) for i in range(min(ids), max(ids) + 1)):
            b["needs_review"].append(X._flag("kind_ambiguous", "이 블록의 줄 사이에 다른 블록의 줄이 있어 박스가 넓습니다. 묶음을 확인하세요"))
    if report is not None:
        ignored = {v for v in (out.get("ignored_lines") or []) if isinstance(v, int) and v in by_id and _ignorable(by_id[v].raw)} if isinstance(out.get("ignored_lines"), list) else set()      # 쪽 번호·숫자뿐인 줄(차트 눈금·데이터 라벨)만 무시 허용. 글자가 있는 머리말·꼬리말은 놓친 줄로 보고 채워 표시한다
        report["missing"] = [l.lid for l in (lines or []) if l.lid not in owner and l.lid not in ignored]
        report["overlap"] = sorted(set(overlap))
    return res


def extract_pages(s, pages: list[dict], all_lines: list[list], images: list[bytes], rule_blocks: list[dict], *, call, allow_raw_image: bool, max_pages: int, ready) -> tuple[list[dict], dict]:
    """쪽마다 VLM 을 부르고, 실패한 쪽은 코드 규칙 블록으로 채운다. call(png, lines)->(json,tin,tout). lines 는 그 쪽 줄 목록(너무 많거나 글자가 없으면 빈 목록)"""
    info = {"used": False, "mode": "rules", "note": "", "calls": 0, "input_tokens": 0, "output_tokens": 0, "model": s.openai_model, "engine": "vlm", "prompt_version": PROMPT_VERSION, "pages_vlm": 0, "pages_rules": 0}
    out_blocks: list[dict] = []
    done = 0
    for pi, lines in enumerate(all_lines, 1):
        page_rules = [b for b in rule_blocks if b["page"] == pi]
        if not lines and not allow_raw_image:
            info["note"] += f"{pi}쪽은 글자가 없는 그림이라 숫자를 가릴 수 없어 읽지 않았습니다(옵션을 켜면 숫자가 그대로 전송됩니다). "
            info["pages_rules"] += 1
            continue
        if done >= max_pages:
            out_blocks += page_rules
            info["pages_rules"] += 1
            info["note"] += f"{pi}쪽부터는 AI 상한({max_pages}쪽)이라 코드 규칙으로 읽었습니다. "
            continue
        try:
            ready()
            sent = lines if 0 < len(lines) <= MAX_LINES_PER_PAGE else []
            if len(lines) > MAX_LINES_PER_PAGE:
                info["note"] += f"{pi}쪽: 줄이 {len(lines)}개로 많아 줄 목록 없이 그림만 보냈습니다(박스는 글자 대조로 맞춤). "
            out, tin, tout = call(images[pi - 1], sent)
            rep: dict = {}
            built = build_blocks(out, pages[pi - 1], sent, rep)
            if rep.get("missing"):
                info.setdefault("missing_lines", {})[pi] = rep["missing"]
        except Exception as e:  # noqa: BLE001  (AiUnavailable·ValueError·KeyError 모두 그 쪽만 규칙으로 대체)
            out_blocks += page_rules
            info["pages_rules"] += 1
            info["note"] += f"{pi}쪽: {e} — 코드 규칙 결과 사용. "
            continue
        done += 1
        info["calls"] += 1
        info["input_tokens"] += tin
        info["output_tokens"] += tout
        info["pages_vlm"] += 1
        info["used"] = True
        out_blocks += built
    if info["used"]:
        info["mode"] = "vlm"
    return out_blocks, info


def _row_texts(table: dict) -> list[str]:
    return [" ".join(str(c.get("text") or "") for c in r if c.get("type") == "text").strip() for r in table["rows"]]


def _row_diff(t: dict, rt: dict) -> dict:
    """모델 표와 PDF 글자 표의 줄 차이: PDF 에만 있는 줄(모델이 놓침) · 모델에만 있는 줄."""
    mine, theirs = _row_texts(t), _row_texts(rt)
    nm, nt = [_norm(x) for x in mine], [_norm(x) for x in theirs]
    return {"model_rows": len(mine), "pdf_rows": len(theirs),
            "missing_in_model": [theirs[i] for i, x in enumerate(nt) if x and x not in nm][:6],
            "extra_in_model": [mine[i] for i, x in enumerate(nm) if x and x not in nt][:6]}


def reconcile_tables(vlm_blocks: list[dict], rule_blocks: list[dict]) -> int:
    """VLM 이 읽은 표와 PDF 글자로 읽은 표의 모양(행·열 수)이 다르면 글자 PDF 쪽 표를 쓰고 노란 표시를 한다(머리줄 누락 등). 반환: 바꾼 표 수."""
    fixed = 0
    for b in vlm_blocks:
        if b["kind"] != "table":
            continue
        mine = {_norm(c["text"]) for r in b["table"]["rows"] for c in r if c["type"] == "text" and _norm(c["text"])}
        best, bj = None, 0.0
        for r in rule_blocks:
            if r["kind"] != "table" or r["page"] != b["page"]:
                continue
            theirs = {_norm(c["text"]) for rr in r["table"]["rows"] for c in rr if c["type"] == "text" and _norm(c["text"])}
            j = len(mine & theirs) / max(1, len(mine | theirs))
            if j > bj:
                best, bj = r, j
        if best is None or bj < 0.35:
            continue
        t, rt = b["table"], best["table"]
        if (len(t["rows"]), t["columns"]) != (len(rt["rows"]), rt["columns"]):
            if len(rt["rows"]) > len(t["rows"]) and rt["columns"] == t["columns"]:      # VLM 이 줄을 빠뜨린 경우(예: 머리줄 누락)만 PDF 글자 표를 쓴다
                b["needs_review"].append(X._flag("table_shape", f"VLM 표 {len(t['rows'])}행×{t['columns']}열 ≠ PDF 글자 표 {len(rt['rows'])}행×{rt['columns']}열 — PDF 글자 표를 사용했습니다", _row_diff(t, rt)))
                for k in ("rows", "columns", "header_rows", "unit_hint", "number_cells"):
                    t[k] = rt[k]
                t["header_rows"] = lead_header_rows(t["rows"], t["header_rows"])
                fixed += 1
            else:                                                                        # 규칙 표가 더 어긋난 경우(칸이 섞임 등)는 VLM 표를 두고 확인만 요청
                b["needs_review"].append(X._flag("table_shape", f"VLM 표 {len(t['rows'])}행×{t['columns']}열, PDF 글자 표 {len(rt['rows'])}행×{rt['columns']}열 — 서로 달라 VLM 표를 두었습니다. 확인 필요", _row_diff(t, rt)))
    return fixed


def add_missed(vlm_blocks: list[dict], rule_blocks: list[dict], page_dims: dict[int, tuple[float, float]]) -> list[dict]:
    """VLM 이 놓친 글자 블록을 코드 규칙 블록으로 채워 넣고 노란 표시(vlm_missed)를 붙인다. 그래프·숫자 카드 안쪽에서 뽑힌 조각은 넣지 않는다."""
    by_page: dict[int, str] = {}
    for b in vlm_blocks:
        by_page[b["page"]] = by_page.get(b["page"], "") + " " + " ".join(_texts(b))
    pnorm = {p: _norm(t) for p, t in by_page.items()}
    zones = [(b["page"], b["bbox"]) for b in vlm_blocks if b["kind"] in ("chart", "numbercard")]
    added = []
    owned = {(b["page"], i) for b in vlm_blocks for i in (b.get("line_ids") or [])}
    for r in rule_blocks:
        if r["kind"] not in ("title", "paragraph", "footnote"):          # 표·목록은 쪼개 읽는 방식이 달라 중복이 생기므로 채우지 않는다
            continue
        if r.get("line_ids") and all((r["page"], i) in owned for i in r["line_ids"]):      # 그 줄들은 이미 VLM 블록(예: 표 캡션을 표 안에 넣음)이 가졌다: 중복으로 채우지 않는다
            continue
        txt = _norm(" ".join(_texts(r)))
        if len(txt) < 2:
            continue
        if txt in pnorm.get(r["page"], ""):
            continue
        rw = [w for t in _texts(r) for w in _WORD.findall(t or "")]
        bag = set(w for b in vlm_blocks if b["page"] == r["page"] for t in _texts(b) for w in _WORD.findall(t or ""))
        if rw and sum(1 for w in rw if w in bag) >= 0.8 * len(rw):          # 모델이 다른 블록에 합쳐 읽었을 뿐이면 놓친 것이 아니다
            continue
        if any(SequenceMatcher(None, txt, _norm(t)).ratio() >= 0.6 for b in vlm_blocks if b["page"] == r["page"] for t in _texts(b) if t):
            continue
        # 일부 글자만 겹쳐도(합쳐 읽은 경우) 놓친 것으로 보지 않는다
        if sum(1 for t in _texts(r) if t and _norm(t) in pnorm.get(r["page"], "")) >= max(1, len(_texts(r)) // 2):
            continue
        cx, cy = (r["bbox"][0] + r["bbox"][2]) / 2, (r["bbox"][1] + r["bbox"][3]) / 2
        W, H = page_dims.get(r["page"], (1.0, 1.0))
        if any(p == r["page"] and z[0] - 0.02 * W <= cx <= z[2] + 0.02 * W and z[1] - 0.02 * H <= cy <= z[3] + 0.02 * H for p, z in zones):
            continue
        nb = dict(r)
        nb["needs_review"] = list(r["needs_review"]) + [X._flag("vlm_missed", "VLM 이 읽지 못해 코드 규칙으로 채운 블록입니다")]
        nb["from_vlm"] = False
        nb["from_rules_fill"] = True
        added.append(nb)
    return added


def coverage(vlm_blocks: list[dict], rule_blocks: list[dict]) -> dict:
    """VLM 결과와 코드 규칙 결과를 글자로 맞춰 본다: 서로 놓친 블록 수."""
    def key(b):
        return _norm(" ".join(_texts(b)))[:60]
    vk = [key(b) for b in vlm_blocks if b["kind"] not in ("chart", "numbercard")]
    rk = [key(b) for b in rule_blocks]

    def match(a, pool):
        return any(a and SequenceMatcher(None, a, p).ratio() >= 0.6 for p in pool if p)
    return {"vlm": len(vlm_blocks), "rules": len(rule_blocks), "rules_not_in_vlm": sum(not match(r, vk) for r in rk), "vlm_not_in_rules": sum(not match(v, rk) for v in vk)}
