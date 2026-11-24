"""챗봇 조회용 PostgreSQL 연결. 조회는 항상 읽기 전용(default_transaction_read_only=on)이며 스키마·값 변경 기능은 제공하지 않는다.

접속 주소는 AI_DATABASE_URL, 테이블은 AI_DB_SCHEMA(기본 report) 스키마에 둔다. 연결마다 search_path 를 그 스키마로 고정해 다른 스키마의 테이블이 보이지 않게 한다.
기존 코드가 SQLite 모양(con.execute(sql, 파라미터), 행을 이름·번호로 읽기)으로 짜여 있어서
얇은 어댑터(Conn)가 같은 모양을 유지한다: '?' 와 ':이름' 자리표시를 psycopg 형식으로 바꿔 주고, 행(Row)은 이름·번호 둘 다로 읽히며, 숫자(numeric)는 정수면 int, 소수면 Decimal로 유지한다.
"""
from __future__ import annotations

import re
from decimal import Decimal

import psycopg

from ..config import Settings, get_settings

_SCHEMA_OK = re.compile(r"^[a-z_][a-z0-9_]{0,62}$")


class DbUnavailable(FileNotFoundError):
    """조회 DB 에 연결할 수 없거나 아직 적재된 데이터가 없다. 호출하는 쪽은 FileNotFoundError 로 같이 처리한다('DB 없음' 안내)."""


def schema_name(s: Settings | None = None) -> str:
    n = (s or get_settings()).chat_schema
    if not _SCHEMA_OK.match(n or ""):
        raise ValueError("AI_DB_SCHEMA 는 소문자·숫자·밑줄만 쓸 수 있습니다")
    return n


# ---------------- 행·자리표시 변환 ----------------
class Row(tuple):
    """sqlite3.Row 처럼 이름(r["x"])·번호(r[0])로 읽히고 dict(r)·tuple(r) 이 되는 행."""

    def __new__(cls, cols, vals):
        o = super().__new__(cls, vals)
        o._cols = tuple(cols)
        return o

    def keys(self):
        return list(self._cols)

    def __getitem__(self, k):
        return super().__getitem__(self._cols.index(k) if isinstance(k, str) else k)


def _num(v: object) -> object:
    """PostgreSQL numeric 값을 정수 또는 정확한 Decimal로 유지한다.

    Args:
        v: DB에서 읽은 스칼라 값.
    Returns:
        정수 numeric은 int, 소수 numeric은 Decimal, 다른 값은 원래 값.
    Raises:
        없음.
    Side Effects:
        없음. float 변환이나 반올림을 하지 않는다.
    """
    if isinstance(v, Decimal):
        return int(v) if v == v.to_integral_value() else v
    return v


def _row_factory(cursor):
    cols = [d.name for d in (cursor.description or [])]
    return lambda vals: Row(cols, [_num(v) for v in vals])


_LIT = re.compile(r"('(?:[^']|'')*')")


def _xlate(sql: str, params) -> str:
    """'?'(순서)·':이름'(이름) 자리표시를 psycopg 형식으로 바꾼다. 문자열 리터럴 안은 건드리지 않는다. 자리표시가 쓰일 때는 남은 '%' 를 '%%' 로 이스케이프한다."""
    if params is None:
        return sql
    named = isinstance(params, dict)
    out = []
    for i, part in enumerate(_LIT.split(sql)):
        if i % 2:                                    # 문자열 리터럴
            out.append(part.replace("%", "%%"))
            continue
        part = part.replace("%", "%%")
        part = re.sub(r"(?<![:\w]):([A-Za-z_]\w*)", r"%(\1)s", part) if named else part.replace("?", "%s")
        out.append(part)
    return "".join(out)


class Conn:
    """읽기 전용 psycopg 연결에 조회·종료 인터페이스만 제공하는 어댑터."""

    def __init__(self, raw: psycopg.Connection):
        self.raw = raw

    def execute(self, sql: str, params=None):
        return self.raw.execute(_xlate(sql, params), params)

    def close(self) -> None:
        self.raw.close()


def _connect(s: Settings) -> Conn:
    """백엔드 보고서 스키마에 읽기 전용으로 연결한다.

    Args:
        s: 접속 주소와 보고서 스키마 설정.
    Returns:
        읽기 전용 Conn 어댑터.
    Raises:
        DbUnavailable: 주소 누락 또는 접속 실패.
        ValueError: 유효하지 않은 스키마 이름.
    Side Effects:
        PostgreSQL 연결을 연다. DDL·쓰기 연결은 제공하지 않는다.
    """
    if not s.chat_database_url:
        raise DbUnavailable("조회 DB 주소가 없습니다. ai/.env 의 AI_DATABASE_URL 에 PostgreSQL 접속 주소를 적으세요.")
    sc = schema_name(s)
    opts = f"-c search_path={sc} -c default_transaction_read_only=on"
    try:
        raw = psycopg.connect(s.chat_database_url, options=opts, autocommit=True, row_factory=_row_factory, connect_timeout=5)
    except psycopg.OperationalError as e:
        raise DbUnavailable(f"조회 DB(PostgreSQL)에 연결하지 못했습니다: {type(e).__name__}") from e
    return Conn(raw)


def open_readonly(s: Settings | None = None) -> Conn:
    """보고서용 뷰가 있는 읽기 전용 연결을 연다.

    Args:
        s: 요청별 설정. 없으면 공통 설정.
    Returns:
        보고서 조회용 Conn.
    Raises:
        DbUnavailable: 접속 실패 또는 보고서 뷰가 없는 경우.
        ValueError: 유효하지 않은 스키마 이름.
    Side Effects:
        DB 연결과 뷰 존재 조회를 수행한다. 스키마·테이블은 만들지 않는다.
    """
    s = s or get_settings()
    con = _connect(s)
    if con.execute("SELECT to_regclass('dim_version') IS NOT NULL AS ok").fetchone()["ok"] is not True:
        con.close()
        raise DbUnavailable(f"챗봇 조회 DB 에 데이터가 없습니다(스키마 {schema_name(s)}). 백엔드 보고서용 뷰의 마이그레이션과 읽기 권한을 확인하세요.")
    return con


def versions(con: Conn) -> list[dict]:
    # 연간 확정 계획(annual-YYYY, month 없음)은 월 단위 기준 시점이 아니라 기준 선택·이전 시점 계산에서 뺀다(조회는 version 으로 가능)
    rows = con.execute("SELECT version, kind, label, year, month, loaded_at, source, row_count, is_latest FROM dim_version WHERE month IS NOT NULL ORDER BY year, month, "
                       "CASE kind WHEN 'close' THEN 0 WHEN 'provisional' THEN 1 ELSE 2 END, loaded_at").fetchall()
    return [dict(r) for r in rows]


def latest_version(con: Conn) -> str | None:
    r = con.execute("SELECT version FROM dim_version WHERE is_latest=1 ORDER BY loaded_at DESC LIMIT 1").fetchone()
    return r["version"] if r else None


def previous_version(con: Conn, version: str) -> str | None:
    """이전 시점의 버전: 현재 버전보다 연월이 앞선 것 중 가장 최근(없으면 None). 같은 연월의 잠정·월마감은 '이전'으로 보지 않는다."""
    vs = versions(con)
    cur = next((v for v in vs if v["version"] == version), None)
    if not cur:
        return None
    earlier = [v for v in vs if (v["year"], v["month"]) < (cur["year"], cur["month"])]
    return earlier[-1]["version"] if earlier else None
