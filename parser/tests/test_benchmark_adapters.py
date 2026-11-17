"""합성 OOXML로 규칙 파서의 실제 추출·독립 검증과 무저장을 확인한다."""

import hashlib
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

from helpers import PROJECT_ROOT, extract_memory, make_package, open_memory_archive

from excel_parser.benchmark.adapters import run_rule_adapter
from excel_parser.validation import validate_extraction

@contextmanager
def _memory_io(package: bytes, *, fingerprints: list[str] | None = None):
    """원본 읽기 경계만 메모리로 대체하고 모든 결과 쓰기를 금지한다.

    Args:
        package: 합성 OOXML ZIP 바이트.
        fingerprints: 원본 변경 검사를 위한 선택 해시 반환 순서.
    Returns:
        새로 수행한 실제 추출의 호출을 확인할 mock을 yield한다.
    Raises:
        AssertionError: 어댑터 또는 파서가 디스크 캐시나 파일 쓰기를 시도한 경우.
    Side Effects:
        테스트 범위의 파일 경계만 일시 대체한다. 파일을 만들지 않는다.
    """
    digest = hashlib.sha256(package).hexdigest()
    with (
        patch("excel_parser.benchmark.adapters.file_fingerprint", return_value=digest, side_effect=fingerprints),
        patch(
            "excel_parser.benchmark.adapters.extract_workbook",
            side_effect=lambda path, **kwargs: extract_memory(package, name=path.name),
        ) as extraction,
        patch("excel_parser.validation.ZipFile", side_effect=lambda _: open_memory_archive(package)),
        patch.object(Path, "mkdir", side_effect=AssertionError("폴더 생성 금지")),
        patch.object(Path, "write_text", side_effect=AssertionError("텍스트 쓰기 금지")),
        patch.object(Path, "write_bytes", side_effect=AssertionError("바이트 쓰기 금지")),
        patch.object(Path, "read_text", side_effect=AssertionError("디스크 캐시 읽기 금지")),
        patch.object(Path, "replace", side_effect=AssertionError("파일 교체 금지")),
        patch("urllib.request.urlopen", side_effect=AssertionError("테스트 중 실제 API 금지")),
    ):
        yield extraction

class BenchmarkAdapterTests(unittest.TestCase):
    """실제 파서와 검증기를 우회하지 않고 실행 경계 계약을 확인한다."""

    def test_rule_executes_real_extraction_mapping_and_validation_without_output(self) -> None:
        """규칙 실행이 원본 검증부터 출처 검증까지 저장 없이 수행되는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실제 단계가 누락되거나 파일 쓰기가 발생한 경우.
        Side Effects:
            합성 메모리 자료만 사용한다.
        """
        with _memory_io(make_package()) as extraction:
            result = run_rule_adapter(PROJECT_ROOT / "__memory__/adapter.xlsx")
        self.assertEqual(extraction.call_count, 1)
        self.assertTrue(result["completed"], result["errors"])
        self.assertTrue(result["validation_passed"])
        self.assertEqual(len(result["mapped"]["records"]), 2)
        self.assertTrue(result["validation"]["extraction"]["passed"])
        self.assertTrue(result["validation"]["provenance"]["passed"])
        self.assertTrue(result["source_integrity"]["unchanged"])
        for stage in ("extraction", "extraction_validation", "interpretation", "provenance_validation"):
            self.assertGreaterEqual(result["timings"][stage + "_seconds"], 0)
        self.assertGreaterEqual(result["timings"]["total_seconds"], sum(
            seconds for name, seconds in result["timings"].items() if name != "total_seconds"
        ))

    def test_extraction_validation_failure_keeps_mapping_and_provenance_results(self) -> None:
        """원본 검증 불일치를 보존하면서 규칙 매핑과 출처 검증을 끝까지 수행한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 검증 불일치로 해석을 생략하거나 검증 성공으로 표시한 경우.
        Side Effects:
            실제 원본 검증 결과에 합성 불일치만 메모리로 추가한다.
        """
        with (
            _memory_io(make_package()),
            patch(
                "excel_parser.benchmark.adapters.validate_extraction",
                side_effect=lambda path, workbook: {
                    **validate_extraction(path, workbook), "passed": False,
                    "issues": [{"code": "COMMENT_MISMATCH", "message": "합성 불일치"}],
                },
            ),
        ):
            path = PROJECT_ROOT / "__memory__/adapter.xlsx"
            result = run_rule_adapter(path)
        self.assertTrue(result["completed"], result["errors"])
        self.assertFalse(result["validation_passed"])
        self.assertFalse(result["validation"]["extraction"]["passed"])
        self.assertEqual(result["validation"]["extraction"]["issues"][0]["code"], "COMMENT_MISMATCH")
        self.assertTrue(result["validation"]["provenance"]["passed"])
        self.assertEqual(len(result["mapped"]["records"]), 2)

    def test_changed_source_hash_prevents_success(self) -> None:
        """처리 전후 원본 해시가 다르면 검증이 통과해도 성공으로 표시하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 원본 변경을 성공으로 표시한 경우.
        Side Effects:
            해시 반환값만 대체하며 실제 원본은 변경하지 않는다.
        """
        with _memory_io(make_package(), fingerprints=["before", "after"]):
            result = run_rule_adapter(PROJECT_ROOT / "__memory__/adapter.xlsx")
        self.assertFalse(result["completed"])
        self.assertFalse(result["validation_passed"])
        self.assertFalse(result["source_integrity"]["unchanged"])
        self.assertEqual(result["errors"][0]["error_type"], "SourceChanged")

if __name__ == "__main__":
    unittest.main()
