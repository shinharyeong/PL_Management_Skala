"""규칙 파서의 추출·매핑·독립 검증을 저장 없이 실행하는 경계."""

from pathlib import Path
from time import perf_counter

from ..extraction import extract_workbook, file_fingerprint
from ..mapping import map_workbook
from ..validation import validate_extraction, validate_provenance


def run_rule_adapter(path: Path, *, metadata: dict | None = None, root: Path | None = None) -> dict:
    """규칙 파서를 새 원본 추출부터 실행하고 단계별 시간과 검증 결과를 남긴다.

    Args:
        path: 읽기 전용 Excel 입력 경로.
        metadata: 같은 파일에 적용할 명시적인 제출·조직 메타데이터.
        root: 원본 출처 경로를 상대 표시할 선택 루트.
    Returns:
        원본·매핑·검증·해시·시간·오류를 담은 메모리 결과.
        completed는 처리 완료와 원본 보존 여부이며 검증 통과는 validation_passed다.
    Raises:
        KeyboardInterrupt: 사용자 중단을 호출자에게 그대로 전파한다.
    Side Effects:
        원본을 추출·독립 검증·해시 계산을 위해 읽는다.
        파일 생성과 네트워크 호출은 하지 않는다.
    """
    source = Path(path).expanduser().resolve()
    result = {
        "parser": "rule",
        "source": {"path": str(source)},
        "workbook": None,
        "mapped": None,
        "validation": {"extraction": None, "provenance": None},
        "source_integrity": {"sha256_before": None, "sha256_after": None, "unchanged": False},
        "timings": {},
        "errors": [],
        "completed": False,
        "validation_passed": False,
    }
    started = perf_counter()
    stage = "source_hash_before"
    stage_started = started
    try:
        result["source_integrity"]["sha256_before"] = file_fingerprint(source)
        result["timings"][stage + "_seconds"] = perf_counter() - stage_started
        stage = "extraction"
        stage_started = perf_counter()
        workbook = extract_workbook(source, root=root)
        result["workbook"] = workbook
        result["source"] = workbook["file"]
        result["timings"][stage + "_seconds"] = perf_counter() - stage_started
        stage = "extraction_validation"
        stage_started = perf_counter()
        raw_check = validate_extraction(source, workbook)
        result["validation"]["extraction"] = raw_check
        result["timings"][stage + "_seconds"] = perf_counter() - stage_started
        stage = "interpretation"
        stage_started = perf_counter()
        mapped = map_workbook(workbook, metadata)
        result["mapped"] = mapped
        result["timings"][stage + "_seconds"] = perf_counter() - stage_started
        stage = "provenance_validation"
        stage_started = perf_counter()
        result["validation"]["provenance"] = validate_provenance(workbook, mapped)
        result["timings"][stage + "_seconds"] = perf_counter() - stage_started
    except Exception as error:
        result["timings"].setdefault(stage + "_seconds", perf_counter() - stage_started)
        result["errors"].append({"stage": stage, "error_type": type(error).__name__, "message": str(error)})
    finally:
        hash_started = perf_counter()
        try:
            after = file_fingerprint(source)
            integrity = result["source_integrity"]
            integrity["sha256_after"] = after
            integrity["unchanged"] = integrity["sha256_before"] is not None and integrity["sha256_before"] == after
            if integrity["sha256_before"] is not None and not integrity["unchanged"]:
                result["errors"].append({
                    "stage": "source_integrity", "error_type": "SourceChanged",
                    "message": "처리 전후 원본 해시가 다릅니다.",
                })
        except Exception as error:
            result["errors"].append({"stage": "source_hash_after", "error_type": type(error).__name__, "message": str(error)})
        result["timings"]["source_hash_after_seconds"] = perf_counter() - hash_started
        result["timings"]["total_seconds"] = perf_counter() - started
    result["completed"] = bool(
        not result["errors"]
        and result["source_integrity"]["unchanged"]
        and result["mapped"] is not None
        and result["validation"]["provenance"] is not None
    )
    result["validation_passed"] = bool(
        result["source_integrity"]["unchanged"]
        and (result["validation"]["extraction"] or {}).get("passed")
        and (result["validation"]["provenance"] or {}).get("passed")
    )
    return result
