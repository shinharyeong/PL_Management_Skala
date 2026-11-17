"""파일 업로드 접수: 경로 이탈(zip slip) 방지, 확장자·개수·크기 상한, 파일명으로 조직·역할 판별, 누락 점검.

업로드 파일은 요청마다 <AI_CACHE_DIR>/uploads/<run_id>/data/ 아래에 저장한다(경로는 설정에서만 정해진다).
  data/01_excel/<annual_plan|baseline_2026_10|submission_2026_11|revisions>/<NN_사업부N>/<Fxx_YYYY-PLAN|YYYY-MM>.xlsx
  data/02_scenarios/explanations.json, approval_snapshots.json, (선택) NN_*.json
파일 이름에는 폴더 경로가 들어 있어야 한다(브라우저 폴더 선택 = webkitRelativePath, curl = filename=경로). 이름만 있으면 역할을 확정할 수 없어 위반으로 본다.
나중에 백엔드의 SUB-01(업로드)·REV-01(소명)·MAS-04(마스터)로 교체될 임시 입력 경로다.
"""
from __future__ import annotations

import io
import json
import re
import unicodedata
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

FOLDER_ROLE = {"annual_plan": "PLAN", "baseline_2026_10": "BASELINE", "submission_2026_11": "SUBMISSION", "revisions": "REVISION"}
NAME_RE = re.compile(r"^(F\d+)_(\d{4})-(PLAN|\d{2})\.xlsx$")
ORGDIR_RE = re.compile(r"^\d+_")
SCENARIO_RE = re.compile(r"^\d{2}_.+\.json$")
ALLOWED_EXT = (".xlsx", ".json")
CONTAINER_EXT = ".zip"          # 압축 묶음은 풀어서 같은 규칙으로 검사한다(안의 zip 은 거부)
ZIP_BOMB_RATIO = 200            # 압축 해제 크기 / 압축 크기 상한(1MB 이상일 때)


@dataclass
class Entry:
    name: str
    size: int = 0
    kind: str = ""           # xlsx | json | zip
    role: str = ""           # PLAN | BASELINE | SUBMISSION | REVISION | EXPLANATIONS | APPROVALS | SCENARIO | IGNORED
    org_code: str | None = None
    folder: str | None = None
    ok: bool = True
    code: str | None = None  # 위반 코드
    message: str | None = None
    stored: str | None = None
    data: bytes | None = field(default=None, repr=False)

    def public(self) -> dict:
        return {"name": self.name, "size": self.size, "kind": self.kind, "role": self.role, "org_code": self.org_code, "folder": self.folder,
                "ok": self.ok, "code": self.code, "message": self.message}


def _norm(name: str) -> tuple[list[str] | None, str | None]:
    """업로드 이름을 안전한 경로 조각으로 바꾼다. 위험하면 (None, 사유코드)."""
    n = unicodedata.normalize("NFC", (name or "").strip())
    if not n or "\x00" in n:
        return None, "BAD_NAME"
    if "\\" in n or re.match(r"^[A-Za-z]:", n) or n.startswith("/"):
        return None, "PATH_NOT_ALLOWED"        # 역슬래시·드라이브·절대경로
    parts = n.split("/")
    if any(p in ("", ".", "..") for p in parts):
        return None, "PATH_TRAVERSAL"           # zip slip / ../ 경로 이탈
    return parts, None


def classify(name: str, data: bytes, *, from_zip: bool = False) -> Entry:
    e = Entry(name=name, size=len(data), data=data)
    parts, err = _norm(name)
    if err:
        e.ok, e.code, e.message = False, err, "허용되지 않는 경로입니다(상위 폴더로 나가거나 절대경로·역슬래시 사용)."
        return e
    base = parts[-1]
    if base.startswith("~$"):      # 엑셀이 파일을 열어 둘 때 생기는 임시 잠금 파일: 무시한다
        e.kind, e.role, e.message = "temp", "IGNORED", "엑셀 임시 잠금 파일이라 무시했습니다(엑셀을 닫으면 사라집니다)."
        e.data = None
        return e
    ext = Path(base).suffix.lower()
    if ext == CONTAINER_EXT:
        e.kind = "zip"
        if from_zip:
            e.ok, e.code, e.message = False, "NESTED_ZIP", "압축 파일 안의 압축 파일은 받지 않습니다."
        return e
    if ext not in ALLOWED_EXT:
        e.ok, e.code, e.message = False, "EXT_NOT_ALLOWED", f"'{ext or '(없음)'}' 형식은 받지 않습니다. 엑셀(.xlsx)과 JSON(.json)만 올릴 수 있습니다."
        return e
    e.kind = ext[1:]
    if ext == ".xlsx":
        if not (data[:4] == b"PK\x03\x04" and zipfile.is_zipfile(io.BytesIO(data))):
            e.ok, e.code, e.message = False, "NOT_XLSX", "엑셀 파일 형식이 아닙니다(.xlsx 로 이름만 바꾼 파일일 수 있습니다)."
            return e
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                if "[Content_Types].xml" not in z.namelist():
                    raise zipfile.BadZipFile
        except zipfile.BadZipFile:
            e.ok, e.code, e.message = False, "NOT_XLSX", "엑셀 파일 구조가 아닙니다."
            return e
        m = NAME_RE.match(base)
        if not m:
            e.ok, e.code, e.message = False, "BAD_FILENAME", "파일 이름은 {조직코드}_YYYY-MM.xlsx 또는 {조직코드}_YYYY-PLAN.xlsx 형식이어야 합니다."
            return e
        e.org_code = m.group(1)
        idx = next((i for i, p in enumerate(parts[:-1]) if p in FOLDER_ROLE), None)
        if idx is None:
            e.ok, e.code, e.message = False, "NO_FOLDER", "역할 폴더(annual_plan / baseline_2026_10 / submission_2026_11 / revisions)가 경로에 없어 역할을 알 수 없습니다."
            return e
        folder, rest = parts[idx], parts[idx + 1:]
        e.folder, e.role = folder, FOLDER_ROLE[folder]
        if len(rest) < 2 or not ORGDIR_RE.match(rest[0]):
            e.ok, e.code, e.message = False, "NO_ORG_FOLDER", "조직 폴더(예: 01_사업부1/)가 경로에 없습니다."
            return e
        kind = m.group(3)
        want = {"annual_plan": "PLAN", "baseline_2026_10": "10", "submission_2026_11": "11", "revisions": "11"}[folder]
        if kind != want:
            e.ok, e.code, e.message = False, "NAME_FOLDER_MISMATCH", f"파일 이름의 '{m.group(2)}-{kind}'과 폴더 '{folder}'가 맞지 않습니다."
            return e
        e.stored = "/".join(["01_excel", folder, *rest])
        return e
    # .json
    low = base.lower()
    if low == "explanations.json":
        e.role, e.stored = "EXPLANATIONS", "02_scenarios/explanations.json"
    elif low == "approval_snapshots.json":
        e.role, e.stored = "APPROVALS", "02_scenarios/approval_snapshots.json"
    elif SCENARIO_RE.match(base):
        e.role, e.stored = "SCENARIO", f"02_scenarios/{base}"
    else:
        e.role = "IGNORED"
    if e.role != "IGNORED":
        try:
            json.loads(data.decode("utf-8-sig"))
        except Exception:
            e.ok, e.code, e.message = False, "BAD_JSON", "JSON 형식이 올바르지 않습니다."
    return e


def expand(files: list[tuple[str, bytes]], s) -> tuple[list[Entry], list[dict]]:
    """업로드 목록(이름, 바이트)을 검사해 Entry 목록과 요청 전체 위반(개수·총량)을 돌려준다."""
    entries: list[Entry] = []
    request_issues: list[dict] = []
    max_file, max_total = s.upload_max_file_mb * 1024 * 1024, s.upload_max_total_mb * 1024 * 1024
    total = 0

    def add(e: Entry):
        nonlocal total
        if len(entries) >= s.upload_max_files:
            if not request_issues or request_issues[-1]["code"] != "TOO_MANY_FILES":
                request_issues.append({"code": "TOO_MANY_FILES", "message": f"파일이 너무 많습니다(상한 {s.upload_max_files}개). 초과분은 받지 않았습니다."})
            return
        if e.ok and e.size > max_file:
            e.ok, e.code, e.message = False, "TOO_LARGE", f"파일이 너무 큽니다(상한 {s.upload_max_file_mb}MB)."
            e.data = None
        total += e.size
        if total > max_total:
            if not request_issues or request_issues[-1]["code"] != "TOTAL_TOO_LARGE":
                request_issues.append({"code": "TOTAL_TOO_LARGE", "message": f"전체 크기가 너무 큽니다(상한 {s.upload_max_total_mb}MB)."})
            e.ok, e.code, e.message, e.data = False, "TOTAL_TOO_LARGE", "전체 크기 상한을 넘어 받지 않았습니다.", None
        entries.append(e)

    for name, data in files:
        e = classify(name, data)
        if e.kind == "zip" and e.ok:
            try:
                zf = zipfile.ZipFile(io.BytesIO(data))
            except zipfile.BadZipFile:
                e.ok, e.code, e.message = False, "BAD_ZIP", "압축 파일을 열 수 없습니다."
                add(e)
                continue
            members = [i for i in zf.infolist() if not i.is_dir()]
            e.size, e.data = len(data), None
            e.role, e.message = "ARCHIVE", f"압축 파일(안의 파일 {len(members)}개)"
            add(e)
            if len(members) > s.upload_max_files:
                request_issues.append({"code": "TOO_MANY_FILES", "message": f"압축 파일 안의 파일이 너무 많습니다(상한 {s.upload_max_files}개)."})
                continue
            for info in members:
                inner = f"{name}!{info.filename}"
                parts, err = _norm(info.filename)
                if err:  # zip slip: 이름이 상위 경로로 나가거나 절대경로
                    add(Entry(name=inner, kind="zip-member", ok=False, code="ZIP_SLIP" if err in ("PATH_TRAVERSAL", "PATH_NOT_ALLOWED") else err,
                              message="압축 파일 안의 경로가 안전하지 않아 받지 않았습니다(상위 폴더로 나가는 경로)."))
                    continue
                if info.file_size > max_file or (info.compress_size and info.file_size > 1024 * 1024 and info.file_size / max(info.compress_size, 1) > ZIP_BOMB_RATIO):
                    add(Entry(name=inner, size=info.file_size, ok=False, code="TOO_LARGE", message=f"압축 해제 크기가 상한({s.upload_max_file_mb}MB)을 넘거나 비정상적으로 큽니다."))
                    continue
                inner_e = classify(info.filename, zf.read(info), from_zip=True)
                inner_e.name = inner
                add(inner_e)
        else:
            add(e)
    return entries, request_issues


def completeness(entries: list[Entry]) -> dict:
    """필수 파일 점검: 소명 JSON·승인 JSON 이 있어야 하고, 승인 목록의 조직마다 계획·baseline·최종 제출 파일이 있어야 한다."""
    ok = [e for e in entries if e.ok]
    roles = {}
    for e in ok:
        roles.setdefault(e.role, []).append(e)
    missing: list[dict] = []
    if not roles.get("EXPLANATIONS"):
        missing.append({"code": "NO_EXPLANATIONS", "message": "소명 파일(explanations.json)이 없습니다. 소명을 Gap 에 연결할 수 없어 실행할 수 없습니다."})
    if not roles.get("APPROVALS"):
        missing.append({"code": "NO_APPROVALS", "message": "승인 현황 파일(approval_snapshots.json)이 없습니다. 승인 수·master 를 정할 수 없어 실행할 수 없습니다."})
    stored = {unicodedata.normalize("NFC", e.stored) for e in ok if e.stored and e.kind == "xlsx"}
    orgs_by_role: dict[str, set] = {}
    for e in ok:
        if e.kind == "xlsx":
            orgs_by_role.setdefault(e.role, set()).add(e.org_code)
    expected_orgs: list[str] = []
    final_files: dict[str, str] = {}
    if roles.get("APPROVALS"):
        snap = json.loads(roles["APPROVALS"][0].data.decode("utf-8-sig"))
        src = snap.get("all_approved", {}).get("sources", [])
        expected_orgs = sorted({x["organization_code"] for x in src})
        final_files = {x["organization_code"]: unicodedata.normalize("NFC", x["file"]) for x in src}
    for org in expected_orgs:
        for role, label in (("PLAN", "연간 계획"), ("BASELINE", "baseline(10월)")):
            if org not in orgs_by_role.get(role, set()):
                missing.append({"code": "MISSING_FILE", "org_code": org, "role": role, "message": f"{org} 의 {label} 파일이 없습니다."})
        if final_files.get(org) and final_files[org] not in stored:
            missing.append({"code": "MISSING_FILE", "org_code": org, "role": "FINAL", "message": f"{org} 의 최종 제출 파일({final_files[org]})이 없습니다."})
    return {"expected_orgs": expected_orgs, "missing": missing}


def save(entries: list[Entry], data_dir: Path) -> None:
    """통과한 파일만 data_dir 아래에 저장한다(경로 이탈 이중 방어)."""
    base = data_dir.resolve()
    seen: dict[str, str] = {}
    for e in entries:
        if not (e.ok and e.stored and e.data is not None):
            continue
        if e.stored in seen:
            e.ok, e.code, e.message = False, "DUPLICATE", f"같은 경로의 파일이 두 번 올라왔습니다({seen[e.stored]})."
            continue
        dest = (base / e.stored).resolve()
        if not dest.is_relative_to(base):
            e.ok, e.code, e.message = False, "ZIP_SLIP", "저장 경로가 업로드 폴더 밖입니다."
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(e.data)
        seen[e.stored] = e.name
        e.data = None
    for e in entries:
        e.data = None


def summarize(entries: list[Entry], request_issues: list[dict], comp: dict) -> dict:
    files = [e.public() for e in entries]
    violations = [{"name": e.name, "code": e.code, "message": e.message} for e in entries if not e.ok] + request_issues
    by_role: dict[str, int] = {}
    for e in entries:
        if e.ok:
            by_role[e.role] = by_role.get(e.role, 0) + 1
    xl = sum(1 for e in entries if e.ok and e.kind == "xlsx")
    orgs = sorted({e.org_code for e in entries if e.ok and e.org_code})
    if violations or comp["missing"]:
        status = "FAIL"
        bits = []
        if violations:
            bits.append(f"받을 수 없는 파일 {len(violations)}건")
        if comp["missing"]:
            bits.append(f"빠진 파일 {len(comp['missing'])}건")
        message = "업로드를 진행할 수 없습니다: " + ", ".join(bits) + ". 아래 목록을 고쳐 다시 올려 주세요."
    else:
        status = "OK"
        message = f"엑셀 {xl}개({len(orgs)}개 조직)와 소명·승인 파일을 확인했습니다."
    extra = [e for e in entries if e.ok and e.role in ("IGNORED",)]
    if status == "OK" and extra:
        status, message = "WARN", message + f" 사용하지 않는 JSON {len(extra)}개는 무시합니다."
    if status == "OK" and not by_role.get("SCENARIO"):
        status, message = "WARN", message + " 시나리오 JSON(02_scenarios/NN_*.json)이 없어 소명의 gap_key 를 셀로 풀 수 없으면 소명 연결이 제한됩니다."
    return {"status": status, "message": message, "files": files, "violations": violations, "missing": comp["missing"], "expected_orgs": comp["expected_orgs"],
            "counts": {"received": len(entries), "accepted": sum(1 for e in entries if e.ok), "xlsx": xl, "orgs": len(orgs), "by_role": by_role}}
