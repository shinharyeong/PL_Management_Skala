"""단계 0: 룰 파서(parser/)로 제출본 63개를 파싱해 비교용 compact 구조로 만든다. 파서 코드는 import만 한다."""
from __future__ import annotations

import hashlib
import json
import os
import pickle
import re
import sys
import time
from collections import Counter
from concurrent.futures import ProcessPoolExecutor
from decimal import Decimal
from pathlib import Path

from ..config import get_settings
from .common import (
    OPX_SHEET, PARSER_SRC, REPO, REQUEST_YEAR, REV_SHEET, inputs, nfc,
)

sys.path.insert(0, str(PARSER_SRC))
from excel_parser.extraction import extract_workbook  # noqa: E402
from excel_parser.mapping import map_workbook  # noqa: E402
from excel_parser.validation import validate_extraction, validate_provenance  # noqa: E402

ANS_SHEET = "답안지_IFRS15後"
FOLDER_ROLE = {"annual_plan": "PLAN", "baseline_2026_10": "BASELINE", "submission_2026_11": "SUBMISSION", "revisions": "REVISION"}
DESC_DIMS = {"담당자", "거래내역 및 산출근거"}  # 비교 의미에서 제외(설명 원문 정규화 규칙이 문서에 없음)


def inventory() -> list[dict]:
    files = []
    root = inputs()["INPUT_DIR"]
    for folder, role in FOLDER_ROLE.items():
        for p in sorted((root / folder).rglob("*.xlsx")):
            files.append({"path": p, "folder": folder, "role_folder": role, "rel": nfc(str(p.relative_to(root.parent)))})
    return files


def _compact(wb, mapped) -> dict:
    amounts, ans, sheet_all = [], [], []
    for r in mapped["records"]:
        s, pr, v = r["source"], r["period"], r["value"]
        if r["mapping"]["status"] != "mapped" or v.get("normalized_value") is None:
            continue
        sheet = s["sheet"]
        cell = s["cell"]
        row = int(re.sub(r"[A-Z]+", "", cell))
        col = re.sub(r"\d+", "", cell)
        if sheet in (REV_SHEET, OPX_SHEET) and v["value_kind"] == "amount" and pr["aggregation"] == "month" and pr["year"]:
            dims = {k: x["value"] for k, x in r["dimensions"].items()}
            if row <= 3:  # 소계 수식 행(헤더 위쪽)은 상세가 아님
                continue
            kind = "REVENUE" if sheet == REV_SHEET else "COST"
            item = dims.get("관리분류") if kind == "REVENUE" else dims.get("실적집계기준")
            amounts.append({
                "org": r["organization"]["code"], "kind": kind, "sheet": sheet, "cell": cell, "row": row, "col": col,
                "item": item, "dims": dims, "icode": r["item"].get("code"), "iname": r["item"].get("name"),
                "y": pr["year"], "mo": pr["month"], "sc": pr["scenario"], "amount": Decimal(v["normalized_value"]),
                "bv": (r.get("business_validation") or {}).get("status"),
            })
        if sheet == ANS_SHEET and pr["aggregation"] == "month" and pr["year"] == REQUEST_YEAR and pr["scenario"] in ("actual", "estimate", "plan") and r["item"].get("header_path"):
            # 답안지 시트 전체(부서 합계 아래 매출/영업비용/영업이익과 세부 항목 계층 × 월). 기존 amounts/ans 와 별개로 모은다.
            # 계층 깊이: 같은 행의 라벨 칸(B=1, C=2, D=3…) 중 가장 왼쪽. 같은 이름이 두 칸에 있는 행(예: 접속/접속)은 왼쪽 칸 기준
            lab = [ord(re.sub(r"\d+", "", q["cell"])[0]) - ord("A") for q in r["item"].get("provenance") or []
                   if q.get("sheet") == ANS_SHEET and int(re.sub(r"[A-Z]+", "", q["cell"])) == row and re.sub(r"\d+", "", q["cell"]) != "A"]
            sheet_all.append((tuple(r["item"]["header_path"][1:]), pr["scenario"], pr["month"], Decimal(v["normalized_value"]), row, cell, min(lab) if lab else len(r["item"]["header_path"]) - 1))
        if sheet == ANS_SHEET and pr["aggregation"] == "month" and len(r["item"].get("header_path") or []) == 2:
            nm = r["item"].get("name")
            if nm in ("매출", "영업비용", "영업이익"):
                ans.append({"name": nm, "y": pr["year"], "mo": pr["month"], "sc": pr["scenario"], "cell": cell, "amount": Decimal(v["normalized_value"])})
    return {"amounts": amounts, "ans": ans, "sheet_all": sheet_all}


PARSE_CACHE_VERSION = "p4-sheetall"   # 파싱 결과 구조가 바뀌면 올린다(이전 캐시는 쓰지 않음)


def parse_core(path_str: str) -> dict:
    """파일 하나를 파싱한다(계산은 예전 parse_all 의 반복문 본문과 같다). 프로세스 병렬 실행을 위해 최상위 함수로 둔다."""
    path = Path(path_str)
    t0 = time.perf_counter()
    wb = extract_workbook(path, root=REPO)
    t1 = time.perf_counter()
    val = validate_extraction(path, wb)
    t2 = time.perf_counter()
    mapped = map_workbook(wb, metadata={})
    t3 = time.perf_counter()
    prov = validate_provenance(wb, mapped)
    t4 = time.perf_counter()
    c = _compact(wb, mapped)
    t5 = time.perf_counter()
    org = Counter(a["org"] for a in c["amounts"]).most_common(1)[0][0]
    org_rec = next(r for r in mapped["records"] if r["organization"].get("code") == org)
    bv = Counter(a["bv"] for a in c["amounts"])
    return {
        "org": org, "org_name": org_rec["organization"]["name"],
        "amounts": c["amounts"], "ans": c["ans"], "sheet_all": c["sheet_all"],
        "quality": {
            "raw_extraction_complete": bool(val.get("passed")), "provenance_passed": bool(prov.get("passed")),
            "standard_records": len(mapped["records"]), "mapped": mapped["summary"].get("mapped"),
            "diagnostics": sorted({x.get("code") for x in mapped["diagnostics"]}),
            "business_validation": {str(k): v for k, v in bv.items()},
            "detail_amount_records": len(c["amounts"]),
        },
        "timing": {"extract": t1 - t0, "validate": t2 - t1, "map": t3 - t2, "provenance": t4 - t3, "compact": t5 - t4, "total": t5 - t0},
    }


def _workers(n_jobs: int) -> int:
    w = get_settings().parse_workers or min(os.cpu_count() or 2, 8)
    return max(1, min(w, n_jobs))


def parse_all(use_cache: bool = True, workers: int | None = None) -> list[dict]:
    """모든 엑셀을 파싱한다. 파일 내용(sha256)이 같으면 이전 결과를 다시 쓰고(use_cache), 나머지는 여러 프로세스로 동시에 파싱한다."""
    files = inventory()
    cache_dir = get_settings().ai_cache_dir / "parse_cache"
    cores: dict[int, dict] = {}
    jobs: list[int] = []
    shas: dict[int, str] = {}
    for i, f in enumerate(files):
        shas[i] = hashlib.sha256(f["path"].read_bytes()).hexdigest()
        cp = cache_dir / f"{shas[i]}_{PARSE_CACHE_VERSION}.pkl"
        if use_cache and cp.exists():
            try:
                cores[i] = {**pickle.loads(cp.read_bytes()), "cached": True}
                continue
            except Exception:  # noqa: BLE001 — 깨진 캐시는 무시하고 다시 파싱
                pass
        jobs.append(i)
    if jobs:
        w = workers if workers is not None else _workers(len(jobs))
        results = None
        if w > 1:
            try:
                with ProcessPoolExecutor(max_workers=w) as ex:
                    results = list(ex.map(parse_core, [str(files[i]["path"]) for i in jobs]))
            except Exception:  # noqa: BLE001 — 병렬 실행이 안 되는 환경이면 순서대로 처리
                results = None
        if results is None:
            results = [parse_core(str(files[i]["path"])) for i in jobs]
        for i, core in zip(jobs, results):
            cores[i] = {**core, "cached": False}
            if use_cache:
                cache_dir.mkdir(parents=True, exist_ok=True)
                (cache_dir / f"{shas[i]}_{PARSE_CACHE_VERSION}.pkl").write_bytes(pickle.dumps(core))
    out = []
    for i, f in enumerate(files):
        out.append({**{k: f[k] for k in ("folder", "role_folder", "rel")}, "path": str(f["path"]),
                    "division": int(re.match(r"(\d+)_", f["path"].parent.name).group(1)), **cores[i]})
    return out


def build_submissions(parsed: list[dict]) -> dict:
    """파일 → 제출본(ID·역할) 연결. ID는 02_scenarios/approval_snapshots.json 과 시나리오 JSON에서 읽는다."""
    snap = json.loads(inputs()["APPROVALS_PATH"].read_text(encoding="utf-8"))
    final_by_path = {nfc(s["file"]): s["submission_id"] for s in snap["all_approved"]["sources"]}
    superseded = set(snap["superseded_files"])
    excluded = {e["submission_id"]: e["reason"] for e in snap["excluded_files"]}
    by_org = {}
    for p in parsed:
        by_org.setdefault(p["org"], []).append(p)
    subs = {}
    notes = []
    for org, ps in by_org.items():
        rec = {"org": org, "name": ps[0]["org_name"], "division": ps[0]["division"]}
        for p in ps:
            role = p["role_folder"]
            if role == "PLAN":
                p["submission_id"], p["role"] = f"{org}-2026-PLAN-01", "PLAN"
            elif role == "BASELINE":
                p["submission_id"], p["role"] = f"{org}-2026-10-01", "BASELINE"
            else:
                pid = final_by_path.get(p["rel"])
                if pid:
                    p["submission_id"], p["role"] = pid, "FINAL"
                else:
                    nov = [x for x in ps if x["role_folder"] in ("SUBMISSION", "REVISION")]
                    cand = [i for i in superseded | set(excluded) if i.startswith(org + "-")]
                    p["submission_id"] = cand[0] if cand else f"{org}-2026-11-??"
                    p["role"] = "EXCLUDED" if p["submission_id"] in excluded else "SUPERSEDED"
                    p["excluded_reason"] = excluded.get(p["submission_id"])
                    notes.append(f"{p['rel']} -> {p['submission_id']} ({p['role']})")
        for p in ps:
            subs.setdefault(org, {})[p["role"]] = p
        subs[org]["_meta"] = rec
    # 파서 업무 검증으로 제외 사유 교차확인
    cross = []
    for org, m in subs.items():
        for role, p in m.items():
            if role == "_meta":
                continue
            bad = p["quality"]["business_validation"].get("invalid", 0)
            cross.append({"submission_id": p["submission_id"], "role": role, "parser_invalid_detail_records": bad})
    return {"subs": subs, "notes": notes, "cross": cross, "snapshots": snap}
