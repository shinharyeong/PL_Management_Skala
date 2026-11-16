"""SQL 검사기와 안전 실행.

검사(문자열): SELECT/WITH 한 문장만, 세미콜론·주석·변경 키워드 금지, fact/v_op/gap/explanation 을 쓰면 version = :version 조건 필수.
실행(sqlite3 authorizer): 허용 테이블 읽기·허용 함수만 통과, 재귀 쿼리·PRAGMA·ATTACH 등은 모두 거부.
한도: 행 상한과 시간 상한(progress handler 로 중단). 연결은 읽기 전용이어야 한다.
"""
from __future__ import annotations

import re
import sqlite3
import time
from dataclasses import dataclass, field

from . import schema

FORBIDDEN_WORDS = ("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "ATTACH", "DETACH", "PRAGMA", "REPLACE", "VACUUM", "REINDEX", "TRUNCATE",
                   "LOAD_EXTENSION", "ANALYZE", "SAVEPOINT", "BEGIN", "COMMIT", "ROLLBACK", "TRIGGER")
ALLOWED_FUNCTIONS = {"SUM", "COUNT", "AVG", "MIN", "MAX", "TOTAL", "ROUND", "ABS", "COALESCE", "IFNULL", "NULLIF", "SUBSTR", "SUBSTRING", "LOWER", "UPPER", "LENGTH", "TRIM",
                     "LTRIM", "RTRIM", "GROUP_CONCAT", "ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD", "IIF", "MAX", "MIN", "CAST", "PRINTF"}
GUARDED = ("fact", "v_op", "gap", "explanation")      # 이 중 하나라도 쓰면 version 조건이 있어야 한다


class SqlRejected(ValueError):
    """검사나 실행에서 막힌 SQL. code 는 기계가 읽는 사유, 메시지는 AI 가 고치도록 돌려주는 설명."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code, self.message = code, message


def _strip_literals(sql: str) -> str:
    """문자열 리터럴 안을 비워서(자리표시) 키워드·세미콜론 검사가 리터럴에 속지 않게 한다."""
    return re.sub(r"'(?:[^']|'')*'", "''", sql)


def validate(sql: str | None) -> str:
    """통과하면 정리된 SQL(앞뒤 공백 제거)을 돌려주고, 아니면 SqlRejected."""
    if not sql or not isinstance(sql, str) or not sql.strip():
        raise SqlRejected("EMPTY", "SQL 이 비어 있습니다.")
    s = sql.strip()
    bare = _strip_literals(s)
    if ";" in bare:
        raise SqlRejected("MULTI_OR_SEMICOLON", "세미콜론은 쓸 수 없습니다. SELECT 한 문장만 쓰세요.")
    if "--" in bare or "/*" in bare or "*/" in bare:
        raise SqlRejected("COMMENT", "주석은 쓸 수 없습니다.")
    if not re.match(r"^(SELECT|WITH)\b", bare, re.I):
        raise SqlRejected("NOT_SELECT", "SELECT 또는 WITH 로 시작하는 한 문장만 허용됩니다.")
    for w in FORBIDDEN_WORDS:
        if re.search(rf"\b{w}\b", bare, re.I):
            raise SqlRejected("FORBIDDEN_KEYWORD", f"허용되지 않는 키워드 {w} 가 있습니다.")
    if re.search(r"\bRECURSIVE\b", bare, re.I):
        raise SqlRejected("RECURSIVE", "재귀 쿼리는 허용되지 않습니다.")
    refs = {t.lower() for t in re.findall(r"\b(?:FROM|JOIN)\s+([A-Za-z_][A-Za-z0-9_]*)", bare, re.I)}
    ctes = {t.lower() for t in re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\s+AS\s*\(", bare, re.I)}
    bad = sorted(refs - ctes - set(schema.ALLOWED_TABLES))
    if bad:
        raise SqlRejected("TABLE_NOT_ALLOWED", f"허용되지 않은 테이블 {', '.join(bad)}. 사용 가능: {', '.join(schema.ALLOWED_TABLES)}.")
    if re.search(r"\bsqlite_\w+", bare, re.I):
        raise SqlRejected("TABLE_NOT_ALLOWED", "시스템 테이블은 읽을 수 없습니다.")
    if refs & set(GUARDED):
        if not re.search(r"\bversion\s*=\s*:version\b", bare, re.I):
            raise SqlRejected("NO_VERSION_FILTER", "fact/v_op/gap/explanation 을 쓰려면 WHERE 에 version = :version 조건이 필요합니다.")
        if re.search(r"\bversion\s*(=|!=|<>|like|in)\s*'", bare, re.I) or re.search(r"\bversion\s+(?:not\s+)?in\b", bare, re.I):
            raise SqlRejected("VERSION_LITERAL", "version 은 문자열로 직접 비교하지 말고 :version(또는 :version_prev)만 쓰세요.")
    return s


def _authorizer(allowed_tables=frozenset(schema.ALLOWED_TABLES)):
    deny, ok = sqlite3.SQLITE_DENY, sqlite3.SQLITE_OK

    def cb(action, arg1, arg2, dbname, source):
        if action == sqlite3.SQLITE_SELECT:
            return ok
        if action == sqlite3.SQLITE_READ:
            return ok if (arg1 or "").lower() in allowed_tables or (arg1 or "") == "" else deny
        if action == sqlite3.SQLITE_FUNCTION:
            return ok if (arg2 or "").upper() in ALLOWED_FUNCTIONS else deny
        return deny      # 재귀·PRAGMA·ATTACH·쓰기 등 나머지는 모두 거부
    return cb


@dataclass
class Result:
    columns: list[str]
    rows: list[tuple]
    row_count: int
    truncated: bool
    seconds: float
    notes: list[str] = field(default_factory=list)


def execute(con: sqlite3.Connection, sql: str, params: dict, *, row_limit: int = 1000, timeout_s: float = 20.0) -> Result:
    """검사를 통과한 SQL 을 읽기 전용 연결에서 실행한다. 시간 상한을 넘으면 중단하고 SqlRejected(TIMEOUT)."""
    sql = validate(sql)
    start = time.perf_counter()

    def progress():
        return 1 if time.perf_counter() - start > timeout_s else 0       # 1 을 돌려주면 실행 중단
    con.set_authorizer(_authorizer())
    con.set_progress_handler(progress, 10000)
    try:
        cur = con.execute(sql, params)
        rows = cur.fetchmany(row_limit + 1)
        cols = [d[0] for d in (cur.description or [])]
    except sqlite3.OperationalError as e:
        msg = str(e)
        if "interrupted" in msg:
            raise SqlRejected("TIMEOUT", f"실행 시간이 {timeout_s:.0f}초를 넘어 중단했습니다. 더 좁은 조건이나 집계를 쓰세요.") from e
        if "not authorized" in msg:
            raise SqlRejected("NOT_AUTHORIZED", "허용되지 않은 테이블·함수를 쓰고 있습니다.") from e
        raise SqlRejected("SQL_ERROR", f"SQL 오류: {msg}") from e
    except sqlite3.Error as e:
        raise SqlRejected("SQL_ERROR", f"SQL 오류: {e}") from e
    finally:
        con.set_progress_handler(None, 0)
        con.set_authorizer(None)
    truncated = len(rows) > row_limit
    rows = [tuple(r) for r in rows[:row_limit]]
    return Result(cols, rows, len(rows), truncated, round(time.perf_counter() - start, 3), ["행 상한으로 잘림"] if truncated else [])
