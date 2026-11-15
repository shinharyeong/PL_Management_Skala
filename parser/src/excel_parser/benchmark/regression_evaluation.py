"""완료된 그룹 평가를 재채점·원본 읽기 없이 최종 81개 평가로 합친다."""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
import time
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path

from .reconstruction_selection import _checked_path, _index_batches, _sha256
from .regression import _allowed_root, _read_json, _saved_index, _write_json

EVALUATION_FILES = ("errors.jsonl.gz", "totals.json", "truth_metadata.json", "summary.json", "by_domain.json")


def _evaluation_rows(report: dict, label: str) -> dict[tuple[str, str], dict]:
    """완료 평가의 배치 키·계수·필수 검증 상태를 확인한다.

    Args: report - 저장 validation 사전. label - 오류 위치.
    Returns: 유형·기준월별 요약 색인.
    Raises: ValueError - 중복·미완료·필수 상태·계수 오류.
    Side Effects: 메모리만 사용한다. 점수를 다시 계산하거나 수정하지 않는다.
    """
    rows = report.get("rows")
    if not isinstance(rows, list) or not rows or report.get("generated_count") != len(rows) or report.get("detailed_and_total_compared_count") != len(rows):
        raise ValueError(label + ": 평가 완료 계수가 다릅니다")
    indexed = {}
    fields = None
    for row in rows:
        key = (row.get("type"), row.get("closing_month"))
        if None in key or key in indexed:
            raise ValueError(label + ": 평가 키 누락 또는 중복")
        if fields is None:
            fields = set(row)
        if fields != set(row):
            raise ValueError(label + ": 월별 요약 열이 일치하지 않습니다")
        if row.get("detailed_and_total_compared") is not True or row.get("reference_unchanged") is not True or row.get("stored_totals_verified") is not True:
            raise ValueError(label + ": 평가·기준 원본 불변·저장 합계 검증이 완료되지 않았습니다")
        for field in ("truth_count", "confirmed_output_count", "match_count", "false_positive", "false_negative",
                      "total_groups", "total_matching_groups"):
            if type(row.get(field)) is not int or row[field] < 0:
                raise ValueError(label + ": 유효한 평가 건수가 필요합니다")
        if row["match_count"] > min(row["truth_count"], row["confirmed_output_count"]) or row["total_matching_groups"] > row["total_groups"]:
            raise ValueError(label + ": 평가 건수 범위가 틀립니다")
        for field in ("strict_detail_and_total_match", "reconciled_detail_and_total_match", "all_totals_match"):
            if type(row.get(field)) is not bool:
                raise ValueError(label + ": 평가 완료 판단 필드가 없습니다")
        indexed[key] = row
    return indexed


def combine_evaluation_data(final: dict, groups: list[dict], reports: list[dict], *, group_paths: list[str]) -> dict:
    """최종 생성 키와 정확히 대응하는 비중복 그룹 평가를 원래 값 그대로 합친다.

    Args: final - join 완료 generation. groups - 새 그룹 생성 목록들.
        reports - 각 그룹의 완료 평가. group_paths - 실제 그룹 generation 경로.
    Returns: 기존 evaluate와 같은 rows 및 완료·일치 계수를 가진 사전.
    Raises: ValueError - 범위·구현·계보·상세 해시·원본 대응·중복·누락 오류.
    Side Effects: 없음. 입력 사전·점수·계산 규칙을 변경하지 않는다.
    """
    final_index = _saved_index(final, "final")
    if len(final_index) != 81 or not groups or len(groups) != len(reports) or len(groups) != len(group_paths):
        raise ValueError("최종 81개와 같은 수의 그룹·평가·출처 목록이 필요합니다")
    selected = {}
    columns = None
    for group, report, source in zip(groups, reports, group_paths):
        indexed = _index_batches(group, source)
        evaluated = _evaluation_rows(report, source)
        if set(indexed) != set(evaluated):
            raise ValueError("그룹의 생성 배치와 평가 배치가 다릅니다")
        if group["inventory_sha256"] != final["inventory_sha256"] or group["implementation_sha256"] != final["implementation_sha256"]:
            raise ValueError("평가 그룹의 inventory 또는 구현이 최종 생성과 다릅니다")
        for key, batch in indexed.items():
            if key in selected or key not in final_index:
                raise ValueError("중복 또는 최종 범위 밖 평가 배치")
            final_batch = final_index[key]
            for field in ("directory", "master_details_sha256", "organization_files", "organization_count", "master"):
                if batch.get(field) != final_batch.get(field):
                    raise ValueError(f"{key}: 최종 생성과 {field}가 다릅니다")
            if final_batch.get("lineage", {}).get("manifest") != source:
                raise ValueError("최종 배치의 실제 생성 그룹 계보가 다릅니다")
            row = evaluated[key]
            if row.get("generation_status") != batch.get("status"):
                raise ValueError("평가한 생성 상태와 최종 상태가 다릅니다")
            if columns is None:
                columns = set(row)
            if columns != set(row):
                raise ValueError("그룹 사이 평가 요약 열이 다릅니다")
            selected[key] = deepcopy(row)
    if set(selected) != set(final_index):
        raise ValueError("그룹 평가 합집합이 정확한 최종 81개가 아닙니다")
    rows = [selected[(batch["type"], batch["closing_month"])] for batch in final["batches"]]
    return {"rows": rows, "generated_count": len(rows), "detailed_and_total_compared_count": len(rows),
            "strict_detail_and_total_match_count": sum(row["strict_detail_and_total_match"] for row in rows),
            "reconciled_detail_and_total_match_count": sum(row["reconciled_detail_and_total_match"] for row in rows),
            "all_totals_match_count": sum(row["all_totals_match"] for row in rows)}


def merge_evaluations(groups: list[Path], current: Path, output: Path, *, write_output: bool = False) -> dict:
    """저장 평가 파일을 해시 대조하여 최종 evaluation에 바이트 그대로 복사한다.

    Args: groups - 평가 완료 그룹 폴더. current - join 완료 final.
        output - 아직 없는 final/evaluation. write_output - 명시 저장 요청.
    Returns: 합성 평가 요약과 실제 평가/합성 읽기 경계를 기록한 계보.
    Raises: OSError, ValueError - 경계·기존 출력·배치·파일·해시 오류.
    Side Effects: 기존 산출물만 읽는다. 요청 시 새 평가 사본·요약·계보를 만든다.
        조직 Excel과 기준 마스터 Excel을 열거나 평가기를 다시 실행하지 않는다.
    """
    started = time.perf_counter()
    allowed = _allowed_root()
    current = _checked_path(current, allowed, exists=True)
    output = _checked_path(output, allowed, exists=False)
    if output != current / "evaluation" or output.exists():
        raise ValueError("아직 없는 final/evaluation만 출력으로 허용합니다")
    groups = [_checked_path(path, allowed, exists=True) for path in groups]
    if len(set(groups)) != len(groups) or current in groups:
        raise ValueError("중복 없는 원래 평가 그룹이 필요합니다")
    final, final_hash = _read_json(current / "generation.json")
    manifests, reports, paths, observed_hashes, group_provenance = [], [], [], {}, []
    observed_hashes[str(current / "generation.json")] = final_hash
    for directory in groups:
        manifest_path, evaluation_path = directory / "generation.json", directory / "evaluation/validation.json"
        manifest, manifest_hash = _read_json(manifest_path)
        report, evaluation_hash = _read_json(evaluation_path)
        manifests.append(manifest)
        reports.append(report)
        paths.append(str(manifest_path))
        observed_hashes[str(manifest_path)], observed_hashes[str(evaluation_path)] = manifest_hash, evaluation_hash
        group_provenance.append({"directory": str(directory), "generation_manifest": str(manifest_path),
                                 "generation_sha256": manifest_hash, "evaluation_manifest": str(evaluation_path),
                                 "evaluation_sha256": evaluation_hash, "actual_evaluation_command": report.get("command"),
                                 "evaluated_reference_workbooks": len(report.get("rows", []))})
    combined = combine_evaluation_data(final, manifests, reports, group_paths=paths)
    plans = []
    for directory, manifest, report in zip(groups, manifests, reports):
        evaluated = _evaluation_rows(report, str(directory))
        for batch in manifest["batches"]:
            monthly = _checked_path(directory / "evaluation" / batch["type"] / batch["closing_month"], allowed, exists=True)
            names = {entry.name for entry in monthly.iterdir()}
            if names != set(EVALUATION_FILES):
                raise ValueError("월 평가 파일 구성이 현재 evaluate 계약과 다릅니다: " + str(monthly))
            generated = _checked_path(Path(batch["directory"]) / "details.jsonl.gz", allowed, exists=True)
            if _sha256(generated) != batch["master_details_sha256"]:
                raise ValueError("평가에 대응하는 생성 상세 해시가 달라졌습니다")
            summary, summary_hash = _read_json(monthly / "summary.json")
            if summary != evaluated[(batch["type"], batch["closing_month"])]:
                raise ValueError("월별 summary와 그룹 validation 행이 다릅니다")
            observed_hashes[str(monthly / "summary.json")] = summary_hash
            for name in EVALUATION_FILES:
                source = _checked_path(monthly / name, allowed, exists=True)
                destination = output / batch["type"] / batch["closing_month"] / name
                digest = _sha256(source)
                plans.append({"source": str(source), "destination": str(destination), "sha256": digest})
                observed_hashes[str(source)] = digest
    if any(_sha256(Path(path)) != digest for path, digest in observed_hashes.items()):
        raise ValueError("합성 준비 중 원래 평가 산출물이 바뀌었습니다")
    provenance = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "command": sys.argv,
                  "postprocessing_implementation_sha256": _sha256(Path(__file__).resolve()),
                  "generation_implementation_sha256": final["implementation_sha256"],
                  "postprocessing_does_not_reparse_or_rescore": True,
                  "source_excel_reads_during_merge": 0, "reference_excel_reads_during_merge": 0,
                  "actual_group_evaluation_events": sum(item["evaluated_reference_workbooks"] for item in group_provenance),
                  "group_evaluation_event_definition": "한 월 배치의 기존 평가 실행 1건; 파일 해시 확인을 위한 운영체제 open 횟수가 아님",
                  "groups": group_provenance, "input_sha256": observed_hashes,
                  "copied_file_count": len(plans) if write_output else 0, "copy_plan_count": len(plans),
                  "files": plans, "byte_identical_copies_verified": False, "source_groups_preserved": True}
    combined.update(command=sys.argv, evaluation_merged_without_rescoring=True,
                    merge_reference_excel_reads=0, original_group_evaluation_events=provenance["actual_group_evaluation_events"],
                    source_group_evaluations=group_provenance)
    if write_output:
        output.mkdir(exist_ok=False)
        for item in plans:
            source, destination = Path(item["source"]), Path(item["destination"])
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                raise FileExistsError("기존 평가 파일을 덮어쓰지 않습니다")
            shutil.copyfile(source, destination)
            if _sha256(source) != item["sha256"] or _sha256(destination) != item["sha256"]:
                raise ValueError("평가 파일 복사 전후 SHA256이 다릅니다")
        if any(_sha256(Path(path)) != digest for path, digest in observed_hashes.items()):
            raise ValueError("평가 복사 도중 원래 산출물이 바뀌었습니다")
        provenance["byte_identical_copies_verified"] = True
        provenance["merge_seconds"] = time.perf_counter() - started
        _write_json(output / "validation.json", combined)
        with (output / "validation.csv").open("x", encoding="utf-8-sig", newline="") as stream:
            fields = [field for field in combined["rows"][0] if field != "error_counts"]
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows({key: value for key, value in row.items() if key != "error_counts"} for row in combined["rows"])
        _write_json(output / "evaluation_provenance.json", provenance)
    return {"combined": combined, "provenance": provenance, "written": write_output}


def main(argv: list[str] | None = None) -> int:
    """원래 그룹 평가만 읽는 후처리 CLI를 실행한다.

    Args: argv - CLI 인자. None이면 실제 명령행.
    Returns: 정상 검증·합성 완료 0.
    Raises: OSError, ValueError - 읽기·검증·합성 오류.
    Side Effects: --write-output에서만 새 최종 평가 사본과 계보를 쓴다.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--groups", nargs="+", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--write-output", action="store_true")
    options = parser.parse_args(argv)
    result = merge_evaluations(options.groups, options.current, options.output, write_output=options.write_output)
    print(json.dumps({"merged_batches": result["combined"]["generated_count"], "written": result["written"],
                      "actual_group_evaluation_events": result["provenance"]["actual_group_evaluation_events"],
                      "merge_reference_excel_reads": 0, "byte_identical": result["provenance"]["byte_identical_copies_verified"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
