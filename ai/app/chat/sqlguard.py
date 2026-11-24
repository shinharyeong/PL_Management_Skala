"""SQL 검사기와 안전 실행(PostgreSQL).

검사(문자열): SELECT/WITH 한 문장만, 세미콜론·주석·변경 키워드 금지, 허용 테이블만, 허용 함수만, 시스템 카탈로그(pg_*·information_schema) 금지,
fact/v_op/gap/explanation 을 쓰면 version = :version 조건 필수.
실행: 읽기 전용 연결(default_transaction_read_only=on)에서 statement_timeout 으로 시간을 제한하고, search_path 가 조회 스키마로 고정돼 다른 스키마의 테이블은 보이지 않는다.
SQLite 때의 authorizer 는 DB 가 해 주던 일이라 PostgreSQL 에는 없다. 그 대신 위 문자열 검사(테이블·함수 허용 목록)와 읽기 전용 연결을 겹쳐 쓴다.
운영에서는 조회 전용 DB 계정(허용 테이블 SELECT 권한만)을 AI_DATABASE_URL 에 쓰는 것을 권한다(README 참고).
"""
from __future__ import annotations

import re
import time
from dataclasses import dataclass, field

import psycopg

from . import schema

FORBIDDEN_WORDS = ("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "COPY", "GRANT", "REVOKE", "SET", "RESET", "DO", "CALL", "EXECUTE", "LOCK",
                   "LISTEN", "NOTIFY", "INTO", "VACUUM", "ANALYZE", "REINDEX", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "TRIGGER", "ATTACH", "DETACH", "PRAGMA")
ALLOWED_FUNCTIONS = {"SUM", "COUNT", "AVG", "MIN", "MAX", "ROUND", "ABS", "COALESCE", "NULLIF", "SUBSTR", "SUBSTRING", "LOWER", "UPPER", "LENGTH", "TRIM", "LTRIM", "RTRIM",
                     "STRING_AGG", "ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD", "CAST", "GREATEST", "LEAST", "FLOOR", "CEIL", "CEILING"}
# 함수 호출처럼 보이지만 SQL 문법인 단어(뒤에 '(' 가 오는 것)와 형 이름(CAST(x AS numeric(10,2)))
SQL_WORDS = {"IN", "AS", "EXISTS", "OVER", "FILTER", "VALUES", "ON", "AND", "OR", "NOT", "FROM", "JOIN", "WHERE", "SELECT", "USING", "BY", "PARTITION", "ORDER", "WITH", "UNION",
             "ALL", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END", "LIKE", "ILIKE", "BETWEEN", "ANY", "SOME", "INTERSECT", "EXCEPT", "GROUP", "HAVING", "LIMIT", "OFFSET",
             "NUMERIC", "DECIMAL", "VARCHAR", "CHAR", "TEXT", "INTEGER", "BIGINT", "FLOAT", "DOUBLE", "ROWS", "RANGE", "LATERAL", "WITHIN", "IS", "NULL"}
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
    if "$$" in bare or re.search(r"\$\w*\$", bare):
        raise SqlRejected("FORBIDDEN_KEYWORD", "달러 인용 문자열($$)은 쓸 수 없습니다.")
    if not re.match(r"^(SELECT|WITH)\b", bare, re.I):
        raise SqlRejected("NOT_SELECT", "SELECT 또는 WITH 로 시작하는 한 문장만 허용됩니다.")
    for w in FORBIDDEN_WORDS:
        if re.search(rf"\b{w}\b", bare, re.I):
            raise SqlRejected("FORBIDDEN_KEYWORD", f"허용되지 않는 키워드 {w} 가 있습니다.")
    if re.search(r"\bRECURSIVE\b", bare, re.I):
        raise SqlRejected("RECURSIVE", "재귀 쿼리는 허용되지 않습니다.")
    if re.search(r"\bpg_\w+|\binformation_schema\b|\bsqlite_\w+", bare, re.I):
        raise SqlRejected("TABLE_NOT_ALLOWED", "시스템 테이블·카탈로그는 읽을 수 없습니다.")
    refs = {t.lower() for t in re.findall(r"\b(?:FROM|JOIN)\s+([A-Za-z_][A-Za-z0-9_]*)", bare, re.I)}
    ctes = {t.lower() for t in re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\s+AS\s*\(", bare, re.I)}
    bad = sorted(refs - ctes - set(schema.ALLOWED_TABLES))
    if bad:
        raise SqlRejected("TABLE_NOT_ALLOWED", f"허용되지 않은 테이블 {', '.join(bad)}. 사용 가능: {', '.join(schema.ALLOWED_TABLES)}.")
    if re.search(r"\b(?:FROM|JOIN)\s+[A-Za-z_]\w*\s*\.", bare, re.I) or re.search(r"\b(?:FROM|JOIN)\s+\(?\s*(?!(?:SELECT|WITH|VALUES)\b)\w+\s*\(", bare, re.I):
        raise SqlRejected("TABLE_NOT_ALLOWED", "스키마를 붙인 이름이나 함수 결과를 테이블처럼 읽을 수 없습니다. 테이블 이름만 쓰세요.")
    badf = sorted({f.upper() for f in re.findall(r"\b([A-Za-z_]\w*)\s*\(", bare)} - ALLOWED_FUNCTIONS - SQL_WORDS - {c.upper() for c in ctes})
    if badf:
        raise SqlRejected("FUNCTION_NOT_ALLOWED", f"허용되지 않은 함수 {', '.join(badf)}. 사용 가능: {', '.join(sorted(ALLOWED_FUNCTIONS))}.")
    if refs & set(GUARDED):
        if not re.search(r"\bversion\s*=\s*:version\b", bare, re.I):
            raise SqlRejected("NO_VERSION_FILTER", "fact/v_op/gap/explanation 을 쓰려면 WHERE 에 version = :version 조건이 필요합니다.")
        if re.search(r"\bversion\s*(=|!=|<>|like|in)\s*'", bare, re.I) or re.search(r"\bversion\s+(?:not\s+)?in\b", bare, re.I):
            raise SqlRejected("VERSION_LITERAL", "version 은 문자열로 직접 비교하지 말고 :version(또는 :version_prev)만 쓰세요.")
    return s


@dataclass
class Result:
    columns: list[str]
    rows: list[tuple]
    row_count: int
    truncated: bool
    seconds: float
    notes: list[str] = field(default_factory=list)


def execute(con, sql: str, params: dict, *, row_limit: int = 1000, timeout_s: float = 20.0) -> Result:
    """검사를 통과한 SQL 을 읽기 전용 연결에서 실행한다. 시간 상한을 넘으면 중단하고 SqlRejected(TIMEOUT)."""
    sql = validate(sql)
    start = time.perf_counter()
    con.raw.execute(f"SET statement_timeout = {max(1, int(timeout_s * 1000))}")
    try:
        cur = con.execute(sql, params)
        rows = cur.fetchmany(row_limit + 1)
        cols = [d.name for d in (cur.description or [])]
    except psycopg.errors.QueryCanceled as e:
        raise SqlRejected("TIMEOUT", f"실행 시간이 {timeout_s:.0f}초를 넘어 중단했습니다. 더 좁은 조건이나 집계를 쓰세요.") from e
    except (psycopg.errors.InsufficientPrivilege, psycopg.errors.ReadOnlySqlTransaction) as e:
        raise SqlRejected("NOT_AUTHORIZED", "허용되지 않은 테이블·함수를 쓰고 있습니다.") from e
    except psycopg.Error as e:
        raise SqlRejected("SQL_ERROR", f"SQL 오류: {str(e).splitlines()[0] if str(e) else type(e).__name__}") from e
    finally:
        try:
            con.raw.execute("SET statement_timeout = 0")
        except psycopg.Error:
            pass
    truncated = len(rows) > row_limit
    rows = [tuple(r) for r in rows[:row_limit]]
    return Result(cols, rows, len(rows), truncated, round(time.perf_counter() - start, 3), ["행 상한으로 잘림"] if truncated else [])
