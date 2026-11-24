"""개발용: 정답 영역(tests/golden/*.json)과 추출 결과의 블록 나누기를 채점한다. 서비스 코드에서 import 하지 않는다.

사용: python tools/eval_golden.py [--ai] [--pdf-dir 폴더] [--no-split]
  --ai        : VLM 결과(키 필요). 없으면 코드 규칙 결과.
  --no-split  : 칸 쪼개기를 끄고 채점(수정 전 비교용)
  --fill      : 블록 채우기까지 돌려 ④⑤ 를 채점
출력 지표
  ① 블록 일치율: 정답 블록의 줄 집합과 줄 집합이 같은 예측 블록이 있는 비율
  ② 섞임 횟수 : 한 예측 블록이 서로 다른 정답 블록 둘 이상의 줄을 함께 가진 횟수
  ③ 숫자 의미 정확도: 슬롯 모델이 아직 없어 측정하지 않는다(정답 없음, 사람 확인 필요)
  ④ 값 일치율 : --fill 로 채운 값이 조회 DB 를 SQL 로 직접 읽은 기대값과 맞는 칸 비율(tests/golden/fill.json)
  ⑤ 추측 채움 : 정답이 '데이터 없음'인데 값이 들어간 칸 수(0 이어야 함)
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.pdf_structure import data_select as D  # noqa: E402
from app.pdf_structure import extract as X  # noqa: E402


def score(pdf: Path, golden: list[dict], use_ai: bool, split: bool) -> dict:
    data = pdf.read_bytes()
    orig = X._split_columns
    if not split:
        X._split_columns = lambda ls, W, H, p: ls
    try:
        _, lines, _, _ = X.read_pdf(data)
        res = X.extract_structure(data, use_ai=use_ai)
    finally:
        X._split_columns = orig
    ls = lines[0]
    gold = []
    for g in golden:
        x0, t, x1, b = g["region"]
        ids = {l.lid for l in ls if x0 <= (l.x0 + l.x1) / 2 <= x1 and t <= (l.top + l.bottom) / 2 <= b}
        gold.append({**g, "ids": ids})
    pred = [set(b.get("line_ids") or []) for b in res["blocks"] if b["page"] == 1]
    ok, rows = 0, []
    for g in gold:
        hit = bool(g["ids"]) and any(p == g["ids"] for p in pred)
        ok += hit
        rows.append((g["name"], g["kind"], len(g["ids"]), "O" if hit else ("정답 줄 없음" if not g["ids"] else "X")))
    mixed = sum(1 for p in pred if sum(1 for g in gold if g["ids"] and p & g["ids"]) >= 2)
    return {"match": ok, "total": len(gold), "mixed": mixed, "blocks": len(pred), "rows": rows}


LEGACY_CHART = False


# ---------------- ④⑤ 값 채우기 채점 ----------------
def _num(t: str):
    t = str(t or "").strip().replace(",", "")
    if not t or t in ("데이터 없음", "—", "[값]") or "{수치}" in t:
        return None
    neg = t[0] in "△▲▼-−" and t[0] != "▲"
    t = t.lstrip("+△▲▼-−")
    try:
        return -float(t) if neg else float(t)
    except ValueError:
        return None


def _payload(b: dict) -> dict:
    k = b["kind"]
    d = {"id": b["id"], "kind": k}
    if k in ("title", "paragraph", "footnote"):
        d["text"] = b.get("text", "")
    elif k == "table":
        d.update(rows=b["table"]["rows"], header_rows=b["table"]["header_rows"], header_spans=b["table"].get("header_spans"))
    elif k == "numbercard":
        d["cards"] = b["cards"]
    elif k == "chart":
        if LEGACY_CHART:                                                 # 수정 전 동작 재현: 지표 기본값 영업이익, 축 글자·제목을 서버에 보내지 않음
            d.update(chart_type=b.get("chart_type"), metric="영업이익")
        else:
            d.update(chart_type=b.get("chart_type"), title=b.get("title"), categories=b.get("categories"), series=b.get("series"))
    elif k == "list":
        d["items"] = b["items"]
    return d


def _cell_txt(c) -> str:
    return c if isinstance(c, str) else ("" if c.get("hide") else str(c.get("text") or ""))


def score_fill(pdf: Path, checks: list[dict], use_ai: bool) -> dict:
    """채운 값이 DB 직접 조회값과 같은지(④), 정답이 '데이터 없음'인 칸에 값이 들어갔는지(⑤)."""
    m = D.months()
    base = m["default"]
    info = m
    con = D._con()
    ver = D._best_version(info, base["year"], base["month"])
    code_of = {o["name"]: o["code"] for o in m["orgs"]}
    col_of = D.METRIC_COL

    def sql(org, metric, scn, month=None):
        r = con.execute(f"SELECT SUM({col_of[metric]}) s FROM v_op WHERE version=? AND year=? AND month=? AND scenario=?" + (" AND org_code=?" if org else ""),
                        (ver, base["year"], month or base["month"], scn) + ((org,) if org else ())).fetchone()
        return None if r is None or r["s"] is None else r["s"] / 1e8
    res = X.extract_structure(pdf.read_bytes(), use_ai=use_ai)
    filled = D.fill_report(base, [_payload(b) for b in res["blocks"]])["blocks"]
    tot = ok = guess = 0
    notes = []
    for ch in checks:
        t = ch["type"]
        if t in ("table", "table_no_data"):
            cand = None
            for r in filled:
                if r["kind"] != "table" or not r.get("rows"):
                    continue
                hdr = " ".join(_cell_txt(c) for row in r["rows"][: int(r.get("header_rows") or 1)] for c in row)
                if all(h in hdr for h in ch["header_has"]):
                    cand = r
                    break
            if cand is None:
                notes.append(f"{t}: 표를 찾지 못함 {ch['header_has']}")
                tot += 1
                continue
            hr = int(cand.get("header_rows") or 1)
            body = cand["rows"][hr:]
            if t == "table_no_data":                                           # 표 전체가 정답 '데이터 없음'
                for row in body:
                    for c in row[1:]:
                        tot += 1
                        v = _num(_cell_txt(c))
                        if v is None:
                            ok += 1
                        else:
                            guess += 1
                continue
            heads = [" ".join(t_ for t_ in (_cell_txt(cand["rows"][i][j]) for i in range(hr) if j < len(cand["rows"][i])) if t_) for j in range(len(cand["rows"][0]))]
            org_sum: dict[int, float] = {}
            for row in body:
                label = next((_cell_txt(c) for c in row if _cell_txt(c) in code_of), None)
                for j, hd in enumerate(heads):
                    cell = _cell_txt(row[j]) if j < len(row) else ""
                    if any(nd in hd for nd in ch.get("no_data_headers", [])):
                        tot += 1
                        if _num(cell) is None:
                            ok += 1
                        else:
                            guess += 1
                for cs in ch.get("cols", []):
                    j = next((j for j, hd in enumerate(heads) if hd.strip() == cs["header"]), None)
                    if j is None or label is None:
                        continue
                    org = code_of[label]
                    cur, plan = sql(org, cs["metric"], "ACTUAL"), sql(org, cs["metric"], "PLAN")
                    exp = {"ACTUAL": cur, "PLAN": plan, "DIFF": (cur - plan) if cur is not None and plan is not None else None}[cs["kind"]]
                    got = _num(_cell_txt(row[j]))
                    tot += 1
                    if exp is None:
                        guess += got is not None
                        ok += got is None
                    elif got is not None and abs(got - exp) <= 0.051:
                        ok += 1
                        org_sum[j] = org_sum.get(j, 0.0) + exp
            if ch.get("check_totals"):                                         # 합계 행 = 사업부 행의 합
                for row in body:
                    if any(_cell_txt(c).strip() in ("합계", "총계") for c in row):
                        for cs in ch.get("cols", []):
                            j = next((j for j, hd in enumerate(heads) if hd.strip() == cs["header"]), None)
                            if j is not None and j in org_sum:
                                tot += 1
                                got = _num(_cell_txt(row[j]))
                                ok += got is not None and abs(got - org_sum[j]) <= 0.151
        elif t in ("card", "card_no_data"):
            cards = [c for r in filled if r["kind"] == "numbercard" for c in r.get("cards", [])]
            c = next((c for c in cards if ch["label"] in str(c.get("label"))), None)
            tot += 1
            if c is None:
                notes.append(f"카드 못 찾음: {ch['label']}")
                continue
            v = _num(c.get("value"))
            if t == "card_no_data":
                ok += v is None
                guess += v is not None
            else:
                exp = sql(None, ch["metric"], ch["kind"])
                ok += v is not None and exp is not None and abs(v - exp) <= 0.051
        elif t in ("chart_no_data", "chart_months"):
            ch_blocks = [(b, r) for b, r in zip(res["blocks"], filled) if b["kind"] == "chart" and ch["title_has"] in str(b.get("title"))]
            tot += 1
            if not ch_blocks:
                notes.append(f"차트 못 찾음: {ch['title_has']}")
                continue
            r = ch_blocks[0][1]
            if t == "chart_no_data":
                ok += not r.get("rows")
                guess += bool(r.get("rows"))
            else:
                got = {int(x["name"].rstrip("월")): x["text"] for x in (r.get("rows") or []) if str(x.get("name", "")).endswith("월")}
                good = sum(1 for mo in ch["months"] if mo in got and sql(None, ch["metric"], "ACTUAL", mo) is not None and _num(got[mo]) is not None and abs(_num(got[mo]) - sql(None, ch["metric"], "ACTUAL", mo)) <= 0.051)
                tot += len(ch["months"]) - 1
                ok += good
    con.close()
    return {"checks": tot, "match": ok, "guess": guess, "notes": notes}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--ai", action="store_true")
    ap.add_argument("--no-split", action="store_true")
    ap.add_argument("--pdf-dir", type=Path, default=Path.home() / "Downloads" / "Claude outputs")
    ap.add_argument("--golden", type=Path, default=Path(__file__).resolve().parents[1] / "tests" / "golden" / "hard.json")
    ap.add_argument("--legacy-chart", action="store_true", help="수정 전 차트 동작(지표 기본값 영업이익) 재현")
    ap.add_argument("--fill", action="store_true", help="④⑤ 값 채우기 채점(조회 DB 필요)")
    ap.add_argument("-v", action="store_true", help="블록별 결과")
    a = ap.parse_args()
    global LEGACY_CHART
    LEGACY_CHART = a.legacy_chart
    gold = {k: v for k, v in json.loads(a.golden.read_text(encoding="utf-8")).items() if not k.startswith("_")}
    for name, g in gold.items():
        pdf = a.pdf_dir / name
        if not pdf.exists():
            print(f"{name}: PDF 없음")
            continue
        r = score(pdf, g, a.ai, not a.no_split)
        print(f"{name}: ① 블록 일치 {r['match']}/{r['total']} ({100 * r['match'] // max(1, r['total'])}%) · ② 섞임 {r['mixed']}회 · 예측 블록 {r['blocks']}개 · ③~⑤ 측정 안 함(슬롯 모델 없음)")
        if a.v:
            for row in r["rows"]:
                print("   ", *row)
    if a.fill:
        fg = {k: v for k, v in json.loads((a.golden.parent / "fill.json").read_text(encoding="utf-8")).items() if not k.startswith("_")}
        T = O = G = 0
        for name, checks in fg.items():
            pdf = a.pdf_dir / name
            if not pdf.exists():
                continue
            f = score_fill(pdf, checks, a.ai)
            T, O, G = T + f["checks"], O + f["match"], G + f["guess"]
            print(f"{name}: ④ 값 일치 {f['match']}/{f['checks']} · ⑤ 추측 채움 {f['guess']}칸" + (f" · 메모 {f['notes']}" if f["notes"] else ""))
        print(f"합계: ④ 값 일치율 {O}/{T} ({100 * O // max(1, T)}%) · ⑤ 추측 채움 {G}칸(0이어야 함)")


if __name__ == "__main__":
    main()
