"""외부 패키지·파일·실제 DB 없이 실행하는 AI 저장 경계 및 보고서 조회 회귀 검증.

실행: python3 -B ai/tests/test_stateless_runtime.py
data_select의 실제 함수 본문을 AST로 읽어 메모리 SQLite에 실행한다.
PostgreSQL 연결·FastAPI·LangGraph 통합 검증을 대신하지 않는다.
"""
from __future__ import annotations

import ast
import json
from types import SimpleNamespace
import importlib.util
import sqlite3
import unittest
from decimal import Decimal, ROUND_HALF_UP, getcontext
from pathlib import Path
from unittest.mock import Mock, patch

AI_ROOT = Path(__file__).resolve().parents[1]


def load_module(name: str, relative_path: str):
    """표준 라이브러리만 사용하는 실제 소스 모듈을 로드한다.

    Args:
        name: 테스트 내 모듈 이름.
        relative_path: AI 디렉터리 아래 소스 경로.
    Returns:
        실행된 모듈 객체.
    Raises:
        ImportError: 모듈 로드 실패.
    Side Effects:
        소스만 읽는다. -B 실행 시 바이트코드 파일을 만들지 않는다.
    """
    spec = importlib.util.spec_from_file_location(name, AI_ROOT / relative_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CACHE = load_module("response_cache_test", "app/services/response_cache.py")
USAGE = load_module("call_log_test", "app/services/call_log.py")
NUMERIC = load_module("numeric_test", "app/services/numeric.py")


class StatelessRuntimeTests(unittest.TestCase):
    """응답 캐시와 호출 계수기의 파일 없는 동작을 검증한다."""

    def setUp(self) -> None:
        """각 테스트의 메모리 상태를 초기화한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            없음.
        Side Effects:
            프로세스 메모리 캐시·사용량만 비운다.
        """
        CACHE.clear()
        USAGE.CallLog.clear()

    def test_cache_copies_values_expires_and_never_writes(self) -> None:
        """응답 사본 격리·만료와 파일 접근 금지를 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 캐시 동작이 계약과 다른 경우.
        Side Effects:
            메모리 캐시만 변경한다.
        """
        with patch("builtins.open", side_effect=AssertionError("파일 접근 금지")), \
             patch.object(Path, "mkdir", side_effect=AssertionError("폴더 생성 금지")), \
             patch.object(CACHE, "monotonic", return_value=10) as timer:
            value = {"items": ["원문"]}
            CACHE.put("/not/a/file.json", value)
            value["items"].append("외부 변경")
            found = CACHE.get("/not/a/file.json")
            self.assertEqual(found, {"items": ["원문"]})
            found["items"].clear()
            self.assertEqual(CACHE.get("/not/a/file.json"), {"items": ["원문"]})
            timer.return_value = 10 + CACHE.TTL_SECONDS
            self.assertIsNone(CACHE.get("/not/a/file.json"))

    def test_cache_evicts_least_recently_used_and_accepts_lists(self) -> None:
        """조회 순서에 따른 최대 개수 제한과 요약 목록 저장을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 제한 또는 목록 응답 지원이 잘못된 경우.
        Side Effects:
            메모리 캐시만 변경한다.
        """
        with patch.object(CACHE, "MAX_ENTRIES", 2):
            CACHE.put("a", ["요약"])
            CACHE.put("b", {})
            self.assertEqual(CACHE.get("a"), ["요약"])
            CACHE.put("c", {})
            self.assertIsNone(CACHE.get("b"))
            self.assertEqual(len(CACHE._entries), 2)

    def test_usage_filters_content_and_counts_beyond_recent_history(self) -> None:
        """민감 본문 제외·실제 호출 집계·최근 이력 상한을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 로그 필드 또는 호출 집계가 잘못된 경우.
        Side Effects:
            메모리 계수기를 갱신하며 실제 로그 출력은 모의 객체가 받는다.
        """
        log = USAGE.CallLog("/not/a/log/directory")
        with patch("builtins.open", side_effect=AssertionError("파일 접근 금지")), \
             patch.object(Path, "mkdir", side_effect=AssertionError("폴더 생성 금지")), \
             patch.object(USAGE._logger, "info") as output:
            for _ in range(1002):
                log.append({"task": "test", "outcome": "success", "input_tokens": 1,
                            "question": "비밀 질문", "api_key": "비밀 키"})
            log.append({"outcome": "error"})
            log.append({"outcome": "cache_hit"})
            log.append({"outcome": "skipped_daily_limit"})
            self.assertEqual(log.calls_today(), 1003)
            self.assertEqual(len(log.load()["calls"]), 1000)
            self.assertNotIn("비밀", str(output.call_args_list))
            self.assertNotIn("api_key", str(log.load()))
            self.assertEqual(USAGE.CallLog("other").calls_today(), 0)

    def test_decimal_amount_survives_db_adapter_and_won_format(self) -> None:
        """numeric(30,6) 값이 float·Decimal 기본 정밀도로 반올림되지 않음을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 원 단위 소수 또는 큰 금액이 달라진 경우.
        Side Effects:
            실제 소스 함수만 메모리에 로드하고 Decimal 값을 변환한다.
        """
        scope = {"Decimal": Decimal}
        for path, name in [("app/chat/db.py", "_num"), ("app/pdf_structure/data_select.py", "_won")]:
            tree = ast.parse((AI_ROOT / path).read_text())
            node = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == name)
            exec(compile(ast.Module(body=[node], type_ignores=[]), path, "exec"), scope)
        for text in ["9007199254740993.123456", "999999999999999999999999.123456", "0.000001"]:
            value = scope["_num"](Decimal(text))
            self.assertIsInstance(value, Decimal)
            self.assertEqual(scope["_won"](value), text)
        self.assertEqual(scope["_won"](scope["_num"](Decimal("123.000000"))), "123")
        self.assertIsNone(scope["_won"](None))

    def test_json_boundary_and_arithmetic_keep_decimal_precision(self) -> None:
        """SQL·NL 질의의 큰 정수와 소수 JSON 값 및 30자리 계산을 검증한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 문자열 보존·계산·문맥 복원이 잘못된 경우.
        Side Effects:
            메모리 JSON 문자열과 임시 Decimal 문맥만 사용한다.
        """
        value = Decimal("999999999999999999999999.123456")
        original_precision = getcontext().prec
        with NUMERIC.amount_precision():
            difference = value - Decimal("0.000001")
            self.assertEqual(str(difference), "999999999999999999999999.123455")
        self.assertEqual(getcontext().prec, original_precision)
        rows = [[Decimal("9007199254740993.123456"), 9007199254740993, 7]]
        payload = NUMERIC.exact_json_value({"rows": rows})
        self.assertEqual(json.loads(json.dumps(payload))["rows"], [["9007199254740993.123456", "9007199254740993", 7]])
        source = ast.parse((AI_ROOT / "app/chat/service.py").read_text())
        node = next(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name == "_jsonable")
        scope = {"Decimal": Decimal, "exact_json_value": NUMERIC.exact_json_value}
        exec(compile(ast.Module(body=[node], type_ignores=[]), "chat_json_test", "exec"), scope)
        self.assertEqual(scope["_jsonable"](9007199254740993), "9007199254740993")
        self.assertEqual(scope["_jsonable"](Decimal("1.123456")), "1.123456")

    def test_numbercard_includes_exact_won_for_backend_export(self) -> None:
        """숫자 카드가 반올림 표시와 별도로 정확한 원 단위 값을 제공함을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 내보내기 금액 또는 누락 상태가 잘못된 경우.
        Side Effects:
            실제 소스 함수와 합성 금액만 메모리에서 사용한다.
        """
        value = Decimal("9007199254740993.123456")
        source = ast.parse((AI_ROOT / "app/pdf_structure/data_select.py").read_text())
        selected = [node for node in source.body if isinstance(node, ast.FunctionDef)
                    and node.name in {"_numbercards", "_metric_in", "eok", "_won"}]
        scope = {"Decimal": Decimal, "ROUND_HALF_UP": ROUND_HALF_UP, "METRIC_COL": {"매출": "revenue_won"},
                 "UNIT": SimpleNamespace(get=lambda: "억원"), "SCOPE_ORG": SimpleNamespace(get=lambda: None),
                 "UNIT_DIV": {"억원": Decimal(10) ** 8}, "COMPARE_WORDS": [],
                 "_bases": lambda *args: {"현재": value}}
        exec(compile(ast.Module(body=selected, type_ignores=[]), "card_export_test", "exec"), scope)
        cards, missing = scope["_numbercards"](None, {}, {"year": 2030, "month": 6}, [{"label": "매출"}, {"label": "미등록"}], ["매출"])
        self.assertEqual(cards[0]["won"], "9007199254740993.123456")
        self.assertIsNone(cards[1]["won"])
        self.assertTrue(missing)

    def test_current_backend_and_demo_routes_survive_legacy_cleanup(self) -> None:
        """기존 계약과 실제 데모 호출 경로를 유지하며 폐기된 추측 경로가 없는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 현재 계약 경로 누락 또는 폐기 경로 잔존.
        Side Effects:
            라우터 소스를 읽어 AST로만 검사한다. 앱을 시작하거나 파일을 만들지 않는다.
        """
        paths = {}
        for name in ("contract", "pdf_structure"):
            tree = ast.parse((AI_ROOT / f"app/api/{name}.py").read_text())
            paths[name] = {decorator.args[0].value for node in tree.body
                           if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                           for decorator in node.decorator_list
                           if isinstance(decorator, ast.Call) and isinstance(decorator.func, ast.Attribute)
                           and isinstance(decorator.func.value, ast.Name) and decorator.func.value.id == "router"
                           and decorator.args and isinstance(decorator.args[0], ast.Constant)}
        self.assertEqual(paths["contract"], {"/nl-query", "/data", "/sql-check", "/edit", "/pdf-jobs"})
        self.assertTrue({"/pdf-structure", "/report-data/months", "/report-data/resolve", "/report-data/gaps",
                         "/report-data/fill", "/report-data/sentences", "/report-data/cause-summary", "/report-data/chat"} <= paths["pdf_structure"])
        self.assertFalse({"/report-data/guess", "/report-data/guess-block"} & paths["pdf_structure"])
        tree = ast.parse((AI_ROOT / "app/main.py").read_text())
        routers = {ast.unparse(node.args[0]) for node in ast.walk(tree)
                   if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                   and node.func.attr == "include_router"}
        self.assertIn("contract.router", routers)
        self.assertIn("pdf_structure.router", routers)
        self.assertNotIn("report_authoring.router", routers)

    def test_schema_contract_import_has_no_schema_creation_constants(self) -> None:
        """운영 스키마 계약을 독립 로드하고 DDL 참조 제거를 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 계약 해시 또는 DDL 분리가 잘못된 경우.
        Side Effects:
            운영 소스만 읽는다.
        """
        contract = load_module("report_schema_test", "app/chat/schema.py")
        self.assertEqual(len(contract.SCHEMA_HASH), 16)
        self.assertFalse(hasattr(contract, "DDL"))
        self.assertIn("ITEM_GAP", contract.SCHEMA_DOC)
        tree = ast.parse((AI_ROOT / "app/chat/db.py").read_text())
        names = {node.name for node in ast.walk(tree) if isinstance(node, ast.FunctionDef)}
        self.assertFalse(names & {"open_writer", "drop_schema", "clear_all", "executemany", "executescript"})


class BorrowedConnection:
    """함수의 close가 메모리 테스트 DB를 종료하지 않게 하는 얇은 래퍼."""

    def __init__(self, raw: sqlite3.Connection) -> None:
        """공유할 메모리 연결을 보관한다.

        Args:
            raw: 파일이 없는 SQLite 연결.
        Returns:
            없음.
        Raises:
            없음.
        Side Effects:
            참조만 보관한다.
        """
        self.raw = raw

    def execute(self, sql: str, params=()):
        """운영 함수의 SQL을 메모리 DB에서 실행한다.

        Args:
            sql: 운영 조회 SQL.
            params: 바인딩 값.
        Returns:
            SQLite 커서.
        Raises:
            sqlite3.Error: 조회 실패.
        Side Effects:
            파일 없는 메모리 DB만 조회한다.
        """
        return self.raw.execute(sql, params)

    def close(self) -> None:
        """빌린 연결의 종료를 테스트 종료 시점까지 미룬다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            없음.
        Side Effects:
            없음.
        """


class ReportViewSemanticsTests(unittest.TestCase):
    """운영 조회 함수의 ITEM_GAP·소명·기준 월 동작을 검증한다."""

    def setUp(self) -> None:
        """보고서 뷰와 같은 열을 메모리 DB에 준비한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            sqlite3.Error: 메모리 자료 구성 실패.
        Side Effects:
            메모리 DB만 만들고 소스 함수 본문을 실행 환경에 로드한다.
        """
        self.raw = sqlite3.connect(":memory:")
        self.raw.row_factory = sqlite3.Row
        fixtures = ast.parse((AI_ROOT / "tests/db_support.py").read_text())
        ddl = next(ast.literal_eval(node.value) for node in fixtures.body
                   if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "DDL" for t in node.targets))
        self.raw.executescript(ddl.replace("CREATE OR REPLACE VIEW", "CREATE VIEW"))
        source = ast.parse((AI_ROOT / "app/pdf_structure/data_select.py").read_text())
        functions = {"months", "gaps", "q_explanation_count", "_unlinked", "_best_version", "_tbl", "_accepted_causes"}
        selected = [ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)]
        selected.extend(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name in functions)
        self.scope = {"amount_precision": NUMERIC.amount_precision, "_con": lambda: BorrowedConnection(self.raw), "STATE_LABEL": {"master": "확정", "provisional": "잠정"},
                      "DERIVED": {"영업이익": "v_op"}, "BASIS_LABEL": {"PLAN": "계획 대비", "PREVIOUS": "직전 추정 대비"}}
        exec(compile(ast.fix_missing_locations(ast.Module(body=selected, type_ignores=[])), "data_select_test", "exec"), self.scope)
        self.raw.executemany("INSERT INTO dim_version VALUES(?,?,?,?,?,?,?,?,?)", [
            ("m", "master", "확정", 2030, 6, "t", "backend", 0, 1),
            ("p", "provisional", "잠정", 2030, 7, "t", "backend", 0, 0)])
        self.raw.execute("INSERT INTO dim_org VALUES('A', '조직가')")

    def tearDown(self) -> None:
        """메모리 테스트 DB를 닫는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            없음.
        Side Effects:
            메모리 연결을 해제한다.
        """
        self.raw.close()

    def add_gap(self, identity: str, basis: str, *, scenario: str = "ACTUAL", month: int = 6,
                status: str = "수락", gap_type: str = "ITEM_GAP") -> None:
        """백엔드 형식의 Gap 비교 행을 준비한다.

        Args:
            identity: RI 접두사를 가진 검토 항목 키.
            basis: PLAN 또는 PREVIOUS.
            scenario: ACTUAL 또는 ESTIMATE.
            month: 비교 월.
            status: 현재 소명 상태.
            gap_type: 필터 검증용 Gap 유형.
        Returns:
            없음.
        Raises:
            sqlite3.Error: 자료 적재 실패.
        Side Effects:
            메모리 DB의 합성 자료만 변경한다.
        """
        self.raw.execute("INSERT INTO gap VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                         ("m", f"{identity}-{basis}", gap_type, "A", "조직가", "광고비", 2030, month,
                          scenario, basis, 100, 130, 30, "30", "ACCEPTED", status))

    def add_explanation(self, identity: str, round_number: int, status: str, answer: str | None) -> None:
        """하나의 비교만 gap_id로 가리키는 소명 이력 행을 준비한다.

        Args:
            identity: 소명이 연결된 검토 항목 키.
            round_number: 소명 회차.
            status: 회차의 수락·검토중 상태.
            answer: 수락 원문 또는 None.
        Returns:
            없음.
        Raises:
            sqlite3.Error: 자료 적재 실패.
        Side Effects:
            메모리 DB의 합성 자료만 변경한다.
        """
        self.raw.execute("INSERT INTO explanation VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                         ("m", f"EX-{identity}-{round_number}", identity, f"{identity}-PLAN", "A", "조직가",
                          "영업비용", "광고비", 2030, 6, "ACTUAL", "GAP", status, "ACCEPTED", round_number, answer, None))

    def test_default_month_uses_backend_latest_flag(self) -> None:
        """AI 자체 순위 대신 backend is_latest를 따르는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 기본 월 선택이 잘못된 경우.
        Side Effects:
            메모리 DB의 기본 월 플래그만 변경한다.
        """
        self.assertEqual(self.scope["months"]()["default"], {"year": 2030, "month": 6})
        self.raw.execute("UPDATE dim_version SET is_latest=CASE WHEN version='p' THEN 1 ELSE 0 END")
        self.assertEqual(self.scope["months"]()["default"], {"year": 2030, "month": 7})

    def test_item_gap_connects_both_comparisons_and_filters_period(self) -> None:
        """ITEM_GAP만 조회하고 양쪽 비교에 같은 수락 소명을 연결한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 유형·기간·소명 연결이 잘못된 경우.
        Side Effects:
            메모리 테스트 자료만 변경한다.
        """
        self.add_gap("RI-1", "PLAN")
        self.add_gap("RI-1", "PREVIOUS")
        self.add_gap("RI-2", "PLAN", scenario="ESTIMATE", month=7, status="없음")
        self.add_gap("RI-3", "PLAN", month=5)
        self.add_gap("RI-4", "PLAN", gap_type="OP_GAP")
        self.add_explanation("RI-1", 1, "검토중", None)
        self.add_explanation("RI-1", 2, "수락", "광고 일정 변경")
        groups = self.scope["gaps"](2030, 6)["groups"]
        self.assertEqual(len(groups), 3)
        self.assertEqual([g["cards"][0]["summary"] for g in groups[:2]], ["광고 일정 변경"] * 2)
        self.assertEqual(groups[-1]["month"], 7)
        unlinked = {row["gap_id"] for row in self.scope["_unlinked"](BorrowedConnection(self.raw), "m")}
        self.assertNotIn("RI-1-PREVIOUS", unlinked)

    def test_direct_gap_endpoint_keeps_large_decimal_total(self) -> None:
        """상위 보고서 문맥 없이 Gap을 직접 조회해도 합계 소수를 보존함을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: numeric(30,6) 합계가 기본 Decimal 정밀도로 반올림된 경우.
        Side Effects:
            DB 응답을 모의 객체로 대체하며 실제 연결·파일을 만들지 않는다.
        """
        connection = Mock()
        row = {"org_code": "A", "org_name": "조직가", "item": "광고비", "previous_won": 0,
               "current_won": 0, "status": "NEEDS_REVIEW", "explanation_status": "없음"}
        connection.execute.side_effect = [
            Mock(fetchall=lambda: [{"scenario": "ACTUAL", "basis": "PLAN", "year": 2030, "month": 6}]),
            Mock(fetchall=lambda: [{**row, "gap_id": "RI-1-PLAN", "diff_won": Decimal("999999999999999999999999.123456")},
                                   {**row, "gap_id": "RI-2-PLAN", "diff_won": Decimal("0.000001")}]),
            Mock(fetchall=lambda: []), Mock(fetchall=lambda: [])]
        self.scope["months"] = lambda: {"db_ready": True, "months": [{"year": 2030, "month": 6, "version": "m", "state": "확정"}]}
        self.scope["_con"] = lambda: connection
        result = self.scope["gaps"](2030, 6)
        self.assertEqual(NUMERIC.exact_json_value(result)["groups"][0]["total_won"], "999999999999999999999999.123457")

    def test_report_causes_do_not_mix_next_estimate_into_current_actual(self) -> None:
        """실적 원인 표에 다음 월 추정 원문이 섞이지 않는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 기간·구분이 다른 원문이 섞이거나 중복된 경우.
        Side Effects:
            메모리 합성 카드만 사용한다.
        """
        groups = {"groups": [
            {"year": 2030, "month": 6, "scenario": "ACTUAL", "cards": [{"org_code": "A", "summary": "현재 실적 사유"}]},
            {"year": 2030, "month": 6, "scenario": "ACTUAL", "cards": [{"org_code": "A", "summary": "현재 실적 사유"}]},
            {"year": 2030, "month": 7, "scenario": "ESTIMATE", "cards": [{"org_code": "A", "summary": "다음 추정 사유"}]},
            {"year": 2030, "month": 5, "scenario": "ACTUAL", "cards": [{"org_code": "A", "summary": "이전 실적 사유"}]}]}
        self.assertEqual(self.scope["_accepted_causes"](groups, 2030, 6), {"A": "현재 실적 사유"})
        self.assertEqual(self.scope["_accepted_causes"](groups, 2030, 5), {"A": "이전 실적 사유"})

    def test_latest_round_prevents_old_acceptance_and_duplicate_counts(self) -> None:
        """이전 수락 이력이 최신 검토중 상태를 덮지 않고 회차를 중복 집계하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 현재 상태 또는 소명 건수가 잘못된 경우.
        Side Effects:
            메모리 테스트 자료만 변경한다.
        """
        self.add_gap("RI-1", "PLAN", status="검토중")
        self.add_gap("RI-1", "PREVIOUS", status="검토중")
        self.add_explanation("RI-1", 1, "수락", "이전 답변")
        self.add_explanation("RI-1", 2, "검토중", None)
        groups = self.scope["gaps"](2030, 6)["groups"]
        self.assertTrue(all(not group["cards"][0]["explained"] for group in groups))
        counts = self.scope["q_explanation_count"]({"year": 2030, "month": 6})
        self.assertIn("모두 1건", counts["text"])
        self.assertIn("검토중 1건", counts["text"])
        self.assertIn("소명이 연결된 항목은 2건", counts["text"])


if __name__ == "__main__":
    unittest.main()
