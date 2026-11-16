"""최종 생성 목록의 제한된 교체·계보·읽기 경계를 메모리에서 검증한다."""

from copy import deepcopy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from excel_parser.benchmark import reconstruction_selection as selection


def generation_fixture(*, replacement: bool = False, replacement_types: tuple[int, ...] = (5,)) -> dict:
    """원본 파일을 열지 않고 독립적인 81개 또는 명시 유형 목록을 만든다.

    Args:
        replacement: True이면 05 재생성 목록을 준비한다.
        replacement_types: 수정 목록에 포함할 합성 유형 번호.
    Returns:
        원본 경로 문자열·고정 해시를 가진 합성 생성 사전.
    Raises:
        없음.
    Side Effects:
        없음. 어떤 파일도 생성하거나 읽지 않는다.
    """
    folder = "corrected-" + "-".join(f"{number:02d}" for number in replacement_types) if replacement else "full"
    batches = []
    for type_code in (replacement_types if replacement else range(1, 10)):
        for month in range(1, 10):
            type_name, closing_month = f"{type_code:02d}_test", f"2026-{month:02d}"
            sources = [f"/source/{type_name}/{closing_month}/org-{number:02d}.xlsx" for number in range(1, 18)]
            batches.append({"type": type_name, "closing_month": closing_month,
                            "organization_files": sources, "organization_count": 17,
                            "master": f"/reference/{type_name}/{closing_month}/master.xlsx",
                            "directory": f"/artifacts/run/{folder}/{type_name}/{closing_month}",
                            "master_details_sha256": "d" * 64, "status": "complete", "idempotent_rerun": True})
    return {"created_at_utc": "2026-11-13T00:00:00+00:00", "inventory": "/artifacts/inventory.json",
            "inventory_sha256": "a" * 64, "implementation_sha256": ("c" if replacement else "b") * 64,
            "reference_masters_read": 0, "records_source": "organization_inputs_only",
            "generation_seconds": 10 if replacement else 90, "batches": batches}


def select_fixture(base: dict, replacement: dict, *, replace_types: tuple[str, ...] = ("05",)) -> dict:
    """고정된 목록 출처를 사용하여 순수 선택 함수를 호출한다.

    Args:
        base: 합성 최초 목록.
        replacement: 합성 수정 목록.
        replace_types: 명시적으로 교체할 합성 유형 코드.
    Returns:
        최종 선택 manifest.
    Raises:
        ValueError: 선택 계약 위반.
    Side Effects:
        없음.
    """
    return selection.select_generation(base, replacement,
                                       base_manifest="/artifacts/run/full/generation.json",
                                       replacement_manifest="/artifacts/run/corrected-05/generation.json",
                                       base_manifest_sha256="e" * 64, replacement_manifest_sha256="f" * 64,
                                       replace_types=replace_types)


class ReconstructionSelectionTests(unittest.TestCase):
    """원본이나 결과 파일 없이 05 교체 범위와 계보를 검증한다."""

    def test_exact_type05_replacement_preserves_inputs_and_lineage(self) -> None:
        """05의 9개만 교체하고 최초 72개·입력 순서·부모 구현을 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 교체 범위·계보·입력 보존이 다른 경우.
        Side Effects:
            없음. 실제 파일 접근은 실패하도록 차단한다.
        """
        base, corrected = generation_fixture(), generation_fixture(replacement=True)
        previous = deepcopy((base, corrected))
        with patch("builtins.open", side_effect=AssertionError("파일 접근 금지")), patch.object(Path, "open", side_effect=AssertionError("파일 접근 금지")):
            result = select_fixture(base, corrected)
        self.assertEqual((base, corrected), previous)
        self.assertEqual(len(result["batches"]), 81)
        self.assertEqual(sum(row["lineage"]["replaced"] for row in result["batches"]), 9)
        self.assertIsNone(result["implementation_sha256"])
        self.assertEqual(result["generation_seconds"], 100)
        for old, row in zip(base["batches"], result["batches"]):
            replaced = row["type"].startswith("05_")
            self.assertEqual(row["lineage"]["original_batch_directory"], old["directory"])
            self.assertEqual(row["generation_implementation_sha256"], ("c" if replaced else "b") * 64)
            self.assertEqual(row["directory"], old["directory"].replace("/full/", "/corrected-05/") if replaced else old["directory"])

    def test_wrong_type_or_missing_month_cannot_replace(self) -> None:
        """05 외 유형 또는 누락 월을 가진 교체 실행은 거부한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 허용 범위 밖 교체가 성공한 경우.
        Side Effects:
            합성 사전만 수정한다.
        """
        base, corrected = generation_fixture(), generation_fixture(replacement=True)
        corrected["batches"][0]["type"] = "06_test"
        with self.assertRaises(ValueError):
            select_fixture(base, corrected)
        corrected = generation_fixture(replacement=True)
        corrected["batches"].pop()
        with self.assertRaises(ValueError):
            select_fixture(base, corrected)

    def test_explicit_multiple_types_replace_exactly_eighteen_batches(self) -> None:
        """05·07을 명시한 경우에만 두 유형의 18개를 교체한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 미명시 유형을 교체하거나 18개 이외를 선택한 경우.
        Side Effects:
            없음. 합성 목록만 사용한다.
        """
        base = generation_fixture()
        corrected = generation_fixture(replacement=True, replacement_types=(5, 7))
        result = select_fixture(base, corrected, replace_types=("05", "07"))
        self.assertEqual(result["retained_batch_count"], 63)
        self.assertEqual(result["replacement_batch_count"], 18)
        self.assertEqual({row["type"][:2] for row in result["batches"] if row["lineage"]["replaced"]}, {"05", "07"})
        with self.assertRaises(ValueError):
            select_fixture(base, corrected)
        with self.assertRaises(ValueError):
            select_fixture(base, corrected, replace_types=("05", "05"))

    def test_inventory_sources_order_and_reference_must_match(self) -> None:
        """목록 해시·조직 입력 순서·기준 마스터의 차이를 조용히 수용하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 동일 원본 계약의 차이가 허용된 경우.
        Side Effects:
            합성 목록만 변경한다.
        """
        base = generation_fixture()
        corrected = generation_fixture(replacement=True)
        corrected["inventory_sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            select_fixture(base, corrected)
        corrected = generation_fixture(replacement=True)
        corrected["batches"][0]["organization_files"].reverse()
        with self.assertRaises(ValueError):
            select_fixture(base, corrected)
        corrected = generation_fixture(replacement=True)
        corrected["batches"][0]["master"] = "/reference/changed.xlsx"
        with self.assertRaises(ValueError):
            select_fixture(base, corrected)

    def test_duplicate_source_batch_and_non_source_generation_fail(self) -> None:
        """원본·배치 중복과 기준 마스터를 읽은 생성 실행을 거부한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 중복 또는 원본 전용 계약 위반을 허용한 경우.
        Side Effects:
            합성 사전만 변경한다.
        """
        base = generation_fixture()
        corrected = generation_fixture(replacement=True)
        corrected["batches"][0]["organization_files"][1] = corrected["batches"][0]["organization_files"][0]
        with self.assertRaises(ValueError):
            select_fixture(base, corrected)
        corrected = generation_fixture(replacement=True)
        corrected["batches"][1] = deepcopy(corrected["batches"][0])
        with self.assertRaises(ValueError):
            select_fixture(base, corrected)
        corrected = generation_fixture(replacement=True)
        corrected["reference_masters_read"] = 1
        with self.assertRaises(ValueError):
            select_fixture(base, corrected)

    def test_final_index_points_to_selected_artifacts_without_claiming_export(self) -> None:
        """색인은 선택한 실제 폴더 경로와 교체 여부를 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 최종 경로·원래 경로·XLSX 상태가 다른 경우.
        Side Effects:
            없음. XLSX 파일을 확인하거나 열지 않는다.
        """
        result = select_fixture(generation_fixture(), generation_fixture(replacement=True))
        rows = selection.final_index_rows(result)
        self.assertEqual(len(rows), 81)
        self.assertEqual(sum(row["replaced"] for row in rows), 9)
        for row in rows:
            self.assertEqual(row["master_json"], row["selected_batch_directory"] + "/master.json")
            self.assertEqual(row["master_xlsx"], row["selected_batch_directory"] + "/master.xlsx")
            self.assertEqual(row["xlsx_export_status"], "not_checked_by_selection")

    def test_dry_compose_reads_only_manifests_and_saved_detail_hashes(self) -> None:
        """실제 조립 경로가 원본 Excel·정답을 읽거나 기본 실행에서 쓰지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 허용되지 않은 읽기나 저장이 발생한 경우.
        Side Effects:
            파일·해시 경계를 메모리 대역으로 바꿨다가 복원한다.
        """
        manifests = {"/artifacts/run/full/generation.json": json.dumps(generation_fixture()),
                     "/artifacts/run/corrected-05/generation.json": json.dumps(generation_fixture(replacement=True))}
        with patch.object(selection, "_checked_path", side_effect=lambda path, allowed, **kwargs: path), \
                patch.object(selection, "_sha256", return_value="d" * 64) as hashed, \
                patch.object(Path, "read_text", autospec=True, side_effect=lambda path, **kwargs: manifests[str(path)]) as read, \
                patch.object(Path, "exists", return_value=False), \
                patch.object(Path, "open", side_effect=AssertionError("실제 파일 접근 금지")), \
                patch.object(Path, "mkdir", side_effect=AssertionError("디렉터리 생성 금지")):
            result = selection.compose_generation(Path("/artifacts/run/full"), Path("/artifacts/run/corrected-05"), Path("/artifacts/run/final"))
        self.assertFalse(result["written"])
        self.assertEqual(read.call_count, 2)
        hashed_paths = [str(call.args[0]) for call in hashed.call_args_list]
        self.assertEqual(sum(path.endswith("details.jsonl.gz") for path in hashed_paths), 81)
        self.assertTrue(all(path.endswith(("generation.json", "details.jsonl.gz", "reconstruction_selection.py")) for path in hashed_paths))


if __name__ == "__main__":
    unittest.main()
