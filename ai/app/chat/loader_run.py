"""(a) 업로드 실행 결과 → 챗봇 DB 적재.

입력: <AI_CACHE_DIR>/runs/<run_id>/result.json + output/master_final.json, master_provisional.json
- fact: master 의 답안지(부서별 월 1~12 × 항목 계층)에서 자식이 있는 합계 행은 빼고 가장 작은 행만 넣는다(현재=실적·추정, 계획=PLAN).
- gap / explanation: result.json 의 Gap 과 소명. 소명 원문(answer)은 수락된 것만 저장하고 나머지는 NULL.
- 버전: 확정 master(실행 결과의 master 버전 이름 그대로), 잠정(provisional-<연도>-<월>, 연·월은 결과의 master 월에서 읽음).
같은 버전을 다시 적재하면 그 버전의 행을 지우고 다시 넣는다.
"""
from __future__ import annotations

import json
from datetime import datetime
from decimal import Decimal
from pathlib import Path

from ..config import Settings, get_settings
from . import db, schema

NUMBER_KINDS = ("매출", "영업비용")     # 영업이익은 합계(파생) 행이라 fact 에 넣지 않는다(v_op 뷰가 계산)


def latest_run_id(s: Settings) -> str | None:
    root = s.ai_cache_dir / "runs"
    if not root.exists():
        return None
    runs = [p for p in root.iterdir() if (p / "result.json").exists()]
    return max(runs, key=lambda p: (p / "result.json").stat().st_mtime).name if runs else None


def _int(v) -> int | None:
    if v is None:
        return None
    return int(Decimal(str(v)).quantize(Decimal(1)))


def leaf_rows(rows: list[dict]) -> list[dict]:
    """답안지 행(시트 순서, level 포함)에서 자식이 없는 가장 작은 행만 고른다."""
    out = []
    for i, r in enumerate(rows):
        nxt = rows[i + 1]["level"] if i + 1 < len(rows) else 0
        if nxt <= r["level"]:
            out.append(r)
    return out


def fact_rows(version: str, org: dict, year: int) -> list[tuple]:
    rows = sorted(org["rows"], key=lambda r: r["row"])
    out = []
    for r in leaf_rows(rows):
        path = r["path"]
        if path[0] not in NUMBER_KINDS:
            continue
        cat1 = path[1] if len(path) > 1 else None
        cat2 = path[2] if len(path) > 2 else None
        src = f"답안지_IFRS15後!행{r['row']}"
        for key, plan in (("current", False), ("plan", True)):
            for m, w in r[key].items():
                mo = int(m)
                sc = "PLAN" if plan else ("ACTUAL" if org["scenario_by_month"][m] == "actual" else "ESTIMATE")
                out.append((version, org["org_code"], org["org_name"], path[0], cat1, cat2, path[-1], None, None, None, src, year, mo, sc, _int(w)))
    return out


def load_run(run_id: str | None = None, settings: Settings | None = None) -> dict:
    s = settings or get_settings()
    t0 = datetime.now()
    run_id = run_id or latest_run_id(s)
    if not run_id:
        raise FileNotFoundError("적재할 업로드 실행 결과가 없습니다(.cache/runs/<id>/result.json). 먼저 flow.html 에서 업로드 실행을 하세요.")
    run_dir = s.ai_cache_dir / "runs" / run_id
    result = json.loads((run_dir / "result.json").read_text(encoding="utf-8"))
    if result.get("mode") == "generic":              # 일반 모드 실행: 보관된 업로드 원본을 다시 읽어 적재한다
        from ..generic import build as generic_build
        root = s.ai_cache_dir / "uploads" / run_id / "data" / "01_excel"
        if not root.exists():
            raise FileNotFoundError("업로드 원본이 보관되지 않아 이 실행을 챗봇 DB 에 다시 적재할 수 없습니다(AI_UPLOAD_KEEP=false 로 지워졌을 수 있습니다).")
        out = generic_build.reload(root, s)
        return {"run_id": run_id, "mode": "generic", "versions": out["versions"], "seconds": round((datetime.now() - t0).total_seconds(), 2)}
    mf = json.loads((run_dir / "output" / "master_final.json").read_text(encoding="utf-8"))
    mp = json.loads((run_dir / "output" / "master_provisional.json").read_text(encoding="utf-8"))
    st = result["stages"]
    report = (st.get("report") or {}).get("data") or {}
    gaps = (st.get("gaps") or {}).get("gaps") or []
    records, gaps_full = report.get("records") or [], report.get("gaps") or []
    legacy_to_gap = {}
    for g in gaps_full:
        for e in g.get("explanations", []):
            legacy_to_gap.setdefault(e["legacy_gap_key"], g["gap_id"])

    versions = []
    master = mf.get("master")
    if master:
        y, m = (int(x) for x in master["month"].split("-"))
        versions.append({"version": master["version"], "kind": "master", "label": f"확정 {master['version']} (승인 {mf['approved_orgs']}/{mf['requested_orgs']})", "year": y, "month": m,
                         "answer": master.get("answer_sheet") or {}, "state": "final"})
    pa = mp.get("provisional_aggregate")
    if pa:
        y, m = (int(x) for x in pa["month"].split("-"))
        versions.append({"version": f"provisional-{y}-{m:02d}", "kind": "provisional", "label": f"잠정 (승인 {mp['approved_orgs']}/{mp['requested_orgs']}, 공식 master 아님)", "year": y, "month": m,
                         "answer": pa.get("answer_sheet") or {}, "state": "provisional"})
    if not versions:
        raise ValueError("master 결과가 없어 적재할 수 없습니다(마스터 단계가 실패했을 수 있습니다).")

    db.clear_all(s)
    con = db.open_writer(s)
    summary = {"run_id": run_id, "versions": []}
    try:
        with con:
            for v in versions:
                ver = v["version"]
                for t in ("fact", "gap", "explanation"):
                    con.execute(f"DELETE FROM {t} WHERE version=?", (ver,))
                con.execute("DELETE FROM dim_version WHERE version=?", (ver,))
                facts = []
                for org in v["answer"].values():
                    facts += fact_rows(ver, org, v["year"])
                    con.execute("INSERT OR REPLACE INTO dim_org(org_code, org_name) VALUES(?,?)", (org["org_code"], org["org_name"]))
                con.executemany("INSERT INTO fact VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", facts)
                con.executemany("INSERT INTO gap VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                [(ver, g["gap_id"], g["gap_type"], g["org_code"], g["org_name"], g["item"], g["year"], g["month"], g["scenario"], g["basis"], _int(g["previous_value_won"]),
                                  _int(g["current_value_won"]), _int(g["diff_won"]), g.get("rate_percent"), g.get("status_in_final"), g.get("explanation_status")) for g in gaps])
                ex_rows = []
                for r in records:
                    status = (r["st_final"] if v["state"] == "final" else r["st_prov"])[0]
                    hist = r.get("history") or []
                    accepted = status == "수락" and r.get("latest_verdict") == "ACCEPTED"
                    ex_rows.append((ver, hist[-1]["explanation_id"] if hist else None, r["id"], legacy_to_gap.get(r["id"]), r["org_code"], r["org_name"], r["kind"], r["item"], r["year"], r["month"],
                                    r["scenario"], r["link_type"], status, r.get("latest_verdict"), len(hist), r.get("latest_answer") if accepted else None,
                                    f"{r.get('sheet') or ''}!{r.get('cell') or ''}"))
                con.executemany("INSERT INTO explanation VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", ex_rows)
                con.execute("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES(?,?,?,?,?,?,?,?,0)",
                            (ver, v["kind"], v["label"], v["year"], v["month"], datetime.now().isoformat(timespec="seconds"), f"run:{run_id}", len(facts)))
                summary["versions"].append({"version": ver, "facts": len(facts), "gaps": len(gaps), "explanations": len(ex_rows), "answers_stored": sum(1 for x in ex_rows if x[15])})
            db.mark_latest(con)
        con.executescript(schema.INDEXES + "ANALYZE;")
    finally:
        con.close()
    summary["seconds"] = round((datetime.now() - t0).total_seconds(), 2)
    return summary
