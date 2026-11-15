"""명시한 유형의 원본 기반 재생성 결과만 선택하여 최종 81개 목록을 구성한다.

생성 manifest와 압축 상세의 해시만 읽는다. 조직 Excel·기준 마스터·평가 결과는
읽지 않으며 기존 생성물은 이동·복사·변경하지 않는다.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import csv
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import time


def _sha256(path: Path) -> str:
    """검사된 생성 산출물 또는 현재 구현 파일의 해시를 읽는다.

    Args:
        path: 호출자가 허용 범위를 확인한 JSON·gzip 또는 현재 Python 경로.
    Returns:
        SHA256 16진수 문자열.
    Raises:
        OSError: 파일 읽기 오류.
    Side Effects:
        지정 파일만 읽으며 파일을 만들지 않는다.
    """
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def _checked_path(path: Path, allowed: Path, *, exists: bool) -> Path:
    """승인된 비교 산출물 안의 실제 경로인지 확인한다.

    Args:
        path: 읽거나 새로 쓸 경로.
        allowed: parser/output/comparisons의 실제 경로.
        exists: 읽기 대상이면 True, 새 출력이면 False.
    Returns:
        심볼릭 링크와 Data 경로가 없는 절대 경로.
    Raises:
        ValueError: 범위 이탈·Data·심볼릭 링크.
        FileNotFoundError: 필요한 입력 부재.
    Side Effects:
        경로 메타데이터만 조회한다.
    """
    absolute = path.expanduser().absolute()
    if absolute == allowed or not absolute.is_relative_to(allowed):
        raise ValueError("comparisons 안의 실행 산출물 경로만 허용합니다")
    if any(part.lower() == "data" for part in absolute.parts):
        raise ValueError("Data 경로는 생성 목록 선택의 입력이 아닙니다")
    for current in (absolute, *absolute.parents):
        if current == allowed.parent:
            break
        if current.is_symlink():
            raise ValueError("심볼릭 링크 경로는 허용하지 않습니다")
    resolved = absolute.resolve(strict=exists)
    if not resolved.is_relative_to(allowed):
        raise ValueError("실제 경로가 비교 산출물 폴더 밖입니다")
    return resolved


def _index_batches(manifest: dict, label: str) -> dict[tuple[str, str], dict]:
    """완료된 원본 전용 생성 목록의 최소 계약과 중복을 검증한다.

    Args:
        manifest: 이미 읽은 generation.json 사전.
        label: 실패 위치를 설명할 실행 이름.
    Returns:
        유형·기준월을 키로 한 원래 배치 사전 색인.
    Raises:
        ValueError: 미완료·원본 전용 계약·해시·중복·원본 목록 오류.
    Side Effects:
        없음. 원본 경로 문자열을 열거나 의미를 추론하지 않는다.
    """
    if manifest.get("reference_masters_read") != 0 or manifest.get("records_source") != "organization_inputs_only":
        raise ValueError(label + ": 원본 조직만 읽은 생성 실행이어야 합니다")
    seconds = manifest.get("generation_seconds")
    if type(seconds) not in (int, float) or not math.isfinite(seconds) or seconds < 0:
        raise ValueError(label + ": 완료된 생성 시간이 필요합니다")
    for field in ("inventory_sha256", "implementation_sha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(manifest.get(field, ""))):
            raise ValueError(label + ": 유효한 " + field + "가 필요합니다")
    batches = manifest.get("batches")
    if not isinstance(batches, list) or not batches:
        raise ValueError(label + ": 생성 배치가 없습니다")
    indexed = {}
    for batch in batches:
        type_name, closing_month = batch.get("type"), batch.get("closing_month")
        if not isinstance(type_name, str) or not re.fullmatch(r"\d{2}_.+", type_name):
            raise ValueError(label + ": 유형 이름 형식 오류")
        if not isinstance(closing_month, str) or not re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])", closing_month):
            raise ValueError(label + ": 기준월 형식 오류")
        key = (type_name, closing_month)
        if key in indexed:
            raise ValueError(label + ": 유형·기준월 중복")
        sources = batch.get("organization_files")
        if not isinstance(sources, list) or not sources or any(not isinstance(value, str) or not value for value in sources):
            raise ValueError(label + ": 조직 원본 경로 목록이 필요합니다")
        if len(set(sources)) != len(sources) or batch.get("organization_count") != len(sources):
            raise ValueError(label + ": 조직 원본 중복 또는 파일 수 불일치")
        if batch.get("master") in sources or any("마스터" in Path(value).name for value in sources):
            raise ValueError(label + ": 조직 입력 목록에 기준 마스터가 있습니다")
        if not isinstance(batch.get("master"), str) or not batch["master"]:
            raise ValueError(label + ": 평가 전용 기준 마스터 경로가 없습니다")
        if not re.fullmatch(r"[0-9a-f]{64}", str(batch.get("master_details_sha256", ""))):
            raise ValueError(label + ": 생성 상세 해시 오류")
        indexed[key] = batch
    return indexed


def select_generation(base: dict, replacement: dict, *, base_manifest: str, replacement_manifest: str,
                      base_manifest_sha256: str, replacement_manifest_sha256: str,
                      replace_types: tuple[str, ...] = ("05",)) -> dict:
    """81개 중 명시한 유형의 동일 원본 9개월만 교체한다.

    Args:
        base: 처음 생성한 9개 유형·9개월 목록.
        replacement: 명시한 유형의 각 9개월 재생성 목록.
        base_manifest: 최초 generation.json의 기록용 경로.
        replacement_manifest: 수정 실행 generation.json의 기록용 경로.
        base_manifest_sha256: 최초 목록 파일의 확인된 SHA256.
        replacement_manifest_sha256: 수정 목록 파일의 확인된 SHA256.
        replace_types: 명시적으로 교체할 유형 코드. 기본은 05 하나이다.
    Returns:
        선택된 81개 경로와 배치별 구현·원 실행·교체 계보를 가진 새 사전.
        단일 구현으로 생성했다고 주장하지 않는다.
    Raises:
        ValueError: 범위·inventory·유형·월·조직 원본·기준 마스터 대응 불일치.
    Side Effects:
        없음. 입력 객체·원본·생성물을 변경하거나 읽지 않는다.
    """
    original = _index_batches(base, "base")
    corrected = _index_batches(replacement, "replacement")
    if base["inventory_sha256"] != replacement["inventory_sha256"]:
        raise ValueError("두 생성 실행의 inventory SHA256이 다릅니다")
    types = {key[0] for key in original}
    months = {key[1] for key in original}
    if len(original) != 81 or len(types) != 9 or len(months) != 9 or {name[:2] for name in types} != {f"{index:02d}" for index in range(1, 10)}:
        raise ValueError("최초 생성은 01~09 유형의 9개월, 총 81개여야 합니다")
    if set(original) != {(type_name, month) for type_name in types for month in months}:
        raise ValueError("최초 유형별 기준월 범위가 일치하지 않습니다")
    codes = tuple(replace_types)
    if not codes or len(set(codes)) != len(codes) or any(code not in {name[:2] for name in types} for code in codes):
        raise ValueError("교체 유형은 최초 목록의 중복 없는 명시 코드여야 합니다")
    replace_keys = {key for key in original if key[0][:2] in codes}
    if len(corrected) != len(codes) * 9 or set(corrected) != replace_keys:
        raise ValueError("교체 실행에는 명시한 유형의 정확한 9개월씩만 있어야 합니다")
    for key, revised in corrected.items():
        previous = original[key]
        for field in ("organization_files", "organization_count", "master"):
            if previous[field] != revised[field]:
                raise ValueError(f"{key}: {field}가 최초 목록과 다릅니다")
    inputs = []
    for role, manifest, path, digest in (("base", base, base_manifest, base_manifest_sha256),
                                         ("replacement", replacement, replacement_manifest, replacement_manifest_sha256)):
        inputs.append({"role": role, "manifest": path, "manifest_sha256": digest,
                       "generation_implementation_sha256": manifest["implementation_sha256"],
                       "created_at_utc": manifest.get("created_at_utc"),
                       "generation_seconds": manifest["generation_seconds"], "batch_count": len(manifest["batches"])})
    selected = []
    for previous in base["batches"]:
        key = (previous["type"], previous["closing_month"])
        changed = key in corrected
        item = deepcopy(corrected[key] if changed else previous)
        parent = inputs[1] if changed else inputs[0]
        item["generation_implementation_sha256"] = parent["generation_implementation_sha256"]
        item["lineage"] = {**deepcopy(parent), "replaced": changed,
                           "original_batch_directory": previous["directory"],
                           "original_master_details_sha256": previous["master_details_sha256"],
                           "selected_batch_directory": item["directory"],
                           "selection_reason": "explicit_source_only_regeneration" if changed else "retained_initial_generation"}
        selected.append(item)
    return {"contract_version": "selected-source-only-technical-master-v1",
            "inventory": base.get("inventory"), "inventory_sha256": base["inventory_sha256"],
            "reference_masters_read": 0, "records_source": "organization_inputs_only",
            "source_excel_reads_during_selection": 0, "reference_excel_reads_during_selection": 0,
            "implementation_sha256": None, "implementation_profile": "per_batch_lineage",
            "selection_policy": "retain initial batches except explicitly requested complete nine-month type replacements",
            "replacement_type_codes": list(codes),
            "base_batch_count": len(original), "retained_batch_count": len(original) - len(corrected), "replacement_batch_count": len(corrected),
            "generation_inputs": inputs, "batches": selected,
            "generation_seconds": sum(item["generation_seconds"] for item in inputs),
            "generation_seconds_scope": "all_parent_generation_runs_including_replaced_initial_batches",
            "xlsx_export_status": "not_checked_by_selection"}


def final_index_rows(manifest: dict) -> list[dict]:
    """최종 JSON·Excel 예상 경로와 원래 배치·교체 이력을 CSV 행으로 만든다.

    Args:
        manifest: select_generation의 결과.
    Returns:
        입력 순서를 유지하는 81개 색인 행. XLSX 존재·재읽기는 주장하지 않는다.
    Raises:
        KeyError: 내부 선택 계약 위반.
    Side Effects:
        없음. 경로를 문자열로 조합할 뿐 파일을 읽지 않는다.
    """
    return [{"type": batch["type"], "closing_month": batch["closing_month"],
             "master_json": str(Path(batch["directory"]) / "master.json"),
             "master_xlsx": str(Path(batch["directory"]) / "master.xlsx"),
             "selected_batch_directory": batch["directory"],
             "original_batch_directory": batch["lineage"]["original_batch_directory"],
             "replaced": batch["lineage"]["replaced"],
             "source_generation_manifest": batch["lineage"]["manifest"],
             "generation_implementation_sha256": batch["generation_implementation_sha256"],
             "master_details_sha256": batch["master_details_sha256"],
             "xlsx_export_status": "not_checked_by_selection"} for batch in manifest["batches"]]


def compose_generation(base: Path, replacement: Path, output: Path, *, replace_types: tuple[str, ...] = ("05",),
                       write_output: bool = False) -> dict:
    """완료 생성 목록과 상세 해시를 검증하고 별도 최종 선택 목록을 저장한다.

    Args:
        base: 최초 full 생성 실행 폴더.
        replacement: 명시 유형을 다시 생성한 실행 폴더.
        output: 같은 실행 루트의 아직 없는 final 폴더.
        replace_types: 명시적으로 교체할 유형 코드. 기본은 05 하나이다.
        write_output: 사용자가 허용한 산출물 저장을 실행할 때 True.
    Returns:
        계보·검증 해시·실제 명령·처리 시간을 포함한 최종 선택 manifest.
    Raises:
        OSError, ValueError: 경로·완료 목록·동일 원본·해시 검증 실패.
        FileExistsError: 기존 final 폴더가 있는 경우.
    Side Effects:
        generation JSON 두 개와 81개 선택 상세 gzip의 해시를 읽는다.
        True일 때만 새 final/generation.json과 finalmaster_index.csv를 만든다.
    """
    started = time.perf_counter()
    allowed = (Path(__file__).resolve().parents[4] / "parser/output/comparisons").resolve()
    base = _checked_path(base, allowed, exists=True)
    replacement = _checked_path(replacement, allowed, exists=True)
    output = _checked_path(output, allowed, exists=False)
    if base == replacement or len({base.parent, replacement.parent, output.parent}) != 1:
        raise ValueError("최초·수정·최종 폴더는 같은 새 실행 루트의 서로 다른 하위 폴더여야 합니다")
    if output.exists():
        raise FileExistsError("기존 선택 결과를 덮어쓰지 않습니다")
    manifests, hashes, paths = [], [], []
    for directory in (base, replacement):
        path = _checked_path(directory / "generation.json", allowed, exists=True)
        before = _sha256(path)
        manifests.append(json.loads(path.read_text(encoding="utf-8")))
        if _sha256(path) != before:
            raise ValueError("읽는 중 생성 manifest가 변경되었습니다")
        paths.append(path)
        hashes.append(before)
    result = select_generation(manifests[0], manifests[1], base_manifest=str(paths[0]),
                               replacement_manifest=str(paths[1]), base_manifest_sha256=hashes[0],
                               replacement_manifest_sha256=hashes[1], replace_types=replace_types)
    for batch in result["batches"]:
        parent = replacement if batch["lineage"]["replaced"] else base
        directory = _checked_path(Path(batch["directory"]), allowed, exists=True)
        if directory != parent / batch["type"] / batch["closing_month"]:
            raise ValueError("선택 배치가 원 실행의 유형·기준월 폴더와 다릅니다")
        details = _checked_path(directory / "details.jsonl.gz", allowed, exists=True)
        if _sha256(details) != batch["master_details_sha256"]:
            raise ValueError("선택 배치의 고정 상세 해시가 다릅니다")
        batch["master_details_hash_verified"] = True
    if [_sha256(path) for path in paths] != hashes:
        raise ValueError("선택 도중 생성 manifest가 변경되었습니다")
    result.update(created_at_utc=datetime.now(timezone.utc).isoformat(), command=sys.argv,
                  selection_implementation_sha256=_sha256(Path(__file__).resolve()),
                  selection_seconds=time.perf_counter() - started, selection_inputs_unchanged=True,
                  output_directory=str(output), written=write_output)
    if write_output:
        output.mkdir(exist_ok=False)
        with (output / "generation.json").open("x", encoding="utf-8") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
        rows = final_index_rows(result)
        with (output / "finalmaster_index.csv").open("x", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
    return result


def main(argv: list[str] | None = None) -> int:
    """최초·수정 실행을 받는 독립 선택 명령을 실행한다.

    Args:
        argv: 선택 명령 인자. None이면 실제 명령행을 사용한다.
    Returns:
        정상 검증·선택이면 0.
    Raises:
        OSError, ValueError, FileExistsError: 입력·경계·선택·저장 실패.
    Side Effects:
        허용 산출물을 조회하고 --write-output일 때만 새 목록·CSV를 생성한다.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--replacement", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--replace-types", default="05", help="각 9개월을 교체할 명시 유형 코드, 예: 05,07")
    parser.add_argument("--write-output", action="store_true")
    options = parser.parse_args(argv)
    result = compose_generation(options.base, options.replacement, options.output,
                                replace_types=tuple(code.strip() for code in options.replace_types.split(",")),
                                write_output=options.write_output)
    print(json.dumps({"selected_batches": len(result["batches"]), "retained": result["retained_batch_count"],
                      "replaced": result["replacement_batch_count"], "written": result["written"],
                      "output": result["output_directory"], "source_excel_reads": 0, "reference_excel_reads": 0}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
