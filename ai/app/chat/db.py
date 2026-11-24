"""챗봇 조회용 PostgreSQL 연결. 조회는 항상 읽기 전용(default_transaction_read_only=on), 적재는 별도 쓰기 연결로만 한다.

접속 주소는 AI_DATABASE_URL, 테이블은 AI_DB_SCHEMA(기본 ai) 스키마에 둔다. 연결마다 search_path 를 그 스키마로 고정해 다른 스키마의 테이블이 보이지 않게 한다.
기존 코드가 SQLite 모양(con.execute(sql, 파라미터), 행을 이름·번호로 읽기, with con: 으로 트랜잭션)으로 짜여 있어서
얇은 어댑터(Conn)가 같은 모양을 유지한다: '?' 와 ':이름' 자리표시를 psycopg 형식으로 바꿔 주고, 행(Row)은 이름·번호 둘 다로 읽히며, 숫자(numeric)는 int/float 로 돌려준다.
"""
from __future__ import annotations

import re
from decimal import Decimal
from pathlib import Path

import psycopg

from ..config import Settings, get_settings
from . import schema

TABLES = ("fact", "gap", "explanation", "dim_version", "dim_org")        # 조회 DB 의 실제 테이블(v_op 는 뷰)
_SCHEMA_OK = re.compile(r"^[a-z_][a-z0-9_]{0,62}$")


class DbUnavailable(FileNotFoundError):
    """조회 DB 에 연결할 수 없거나 아직 적재된 데이터가 없다. 호출하는 쪽은 FileNotFoundError 로 같이 처리한다('DB 없음' 안내)."""


def schema_name(s: Settings | None = None) -> str:
    n = (s or get_settings()).chat_schema
    if not _SCHEMA_OK.match(n or ""):
        raise ValueError("AI_DB_SCHEMA 는 소문자·숫자·밑줄만 쓸 수 있습니다")
    return n


def files_dir(s: Settings | None = None) -> Path:
    s = s or get_settings()
    return s.ai_cache_dir / "chat" / "files"


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


def _num(v):
    if isinstance(v, Decimal):                       # SUM(bigint) 은 numeric 으로 온다: 정수면 int, 아니면 float 로(SQLite 와 같은 모양)
        return int(v) if v == v.to_integral_value() else float(v)
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
    """psycopg 연결을 SQLite 처럼 쓰게 해 주는 어댑터. with con: 은 트랜잭션(성공 시 commit, 예외 시 rollback)이고 연결을 닫지 않는다."""

    def __init__(self, raw: psycopg.Connection):
        self.raw = raw

    def execute(self, sql: str, params=None):
        return self.raw.execute(_xlate(sql, params), params)

    def executemany(self, sql: str, seq):
        seq = list(seq)
        cur = self.raw.cursor()
        if seq:
            cur.executemany(_xlate(sql, seq[0]), seq)
        return cur

    def executescript(self, script: str) -> None:
        for stmt in (x.strip() for x in script.split(";")):
            if stmt:
                self.raw.execute(stmt)
        self.raw.commit()                            # SQLite 의 executescript 처럼 끝나면 확정한다

    def commit(self) -> None:
        self.raw.commit()

    def rollback(self) -> None:
        self.raw.rollback()

    def close(self) -> None:
        self.raw.close()

    def __enter__(self):
        return self

    def __exit__(self, et, ev, tb):
        (self.raw.rollback if et else self.raw.commit)()
        return False


def _connect(s: Settings, *, readonly: bool, schema_override: str | None = None) -> Conn:
    if not s.chat_database_url:
        raise DbUnavailable("조회 DB 주소가 없습니다. ai/.env 의 AI_DATABASE_URL 에 PostgreSQL 접속 주소를 적으세요.")
    sc = schema_override or schema_name(s)
    opts = f"-c search_path={sc}" + (" -c default_transaction_read_only=on" if readonly else "")
    try:
        raw = psycopg.connect(s.chat_database_url, options=opts, autocommit=readonly, row_factory=_row_factory, connect_timeout=5)
    except psycopg.OperationalError as e:
        raise DbUnavailable(f"조회 DB(PostgreSQL)에 연결하지 못했습니다: {type(e).__name__}") from e
    return Conn(raw)


def open_writer(s: Settings | None = None, schema_override: str | None = None) -> Conn:
    """적재 전용 쓰기 연결(조회 경로에서는 쓰지 않는다). 스키마·테이블이 없으면 만든다."""
    s = s or get_settings()
    sc = schema_override or schema_name(s)
    if not _SCHEMA_OK.match(sc):
        raise ValueError("스키마 이름이 올바르지 않습니다")
    boot = psycopg.connect(s.chat_database_url, autocommit=True, connect_timeout=5) if s.chat_database_url else None
    if boot is None:
        raise DbUnavailable("조회 DB 주소가 없습니다. ai/.env 의 AI_DATABASE_URL 에 PostgreSQL 접속 주소를 적으세요.")
    try:
        boot.execute(f'CREATE SCHEMA IF NOT EXISTS "{sc}"')
    except psycopg.OperationalError as e:
        raise DbUnavailable(f"조회 DB(PostgreSQL)에 연결하지 못했습니다: {type(e).__name__}") from e
    finally:
        boot.close()
    con = _connect(s, readonly=False, schema_override=sc)
    con.executescript(schema.DDL)
    con.commit()
    return con


def open_readonly(s: Settings | None = None) -> Conn:
    """조회용 읽기 전용 연결. 연결이 안 되거나 적재된 테이블이 없으면 DbUnavailable(FileNotFoundError)."""
    s = s or get_settings()
    con = _connect(s, readonly=True)
    if con.execute("SELECT to_regclass('dim_version') IS NOT NULL AS ok").fetchone()["ok"] is not True:
        con.close()
        raise DbUnavailable(f"챗봇 조회 DB 에 데이터가 없습니다(스키마 {schema_name(s)}). 먼저 업로드 실행 결과나 엑셀을 적재하세요.")
    return con


def upsert_org(con: Conn, code: str, name: str) -> None:
    con.execute("INSERT INTO dim_org(org_code, org_name) VALUES(?,?) ON CONFLICT (org_code) DO UPDATE SET org_name = EXCLUDED.org_name", (code, name))


def upsert_orgs(con: Conn, pairs) -> None:
    con.executemany("INSERT INTO dim_org(org_code, org_name) VALUES(?,?) ON CONFLICT (org_code) DO UPDATE SET org_name = EXCLUDED.org_name", list(pairs))


def versions(con: Conn) -> list[dict]:
    rows = con.execute("SELECT version, kind, label, year, month, loaded_at, source, row_count, is_latest FROM dim_version ORDER BY year, month, "
                       "CASE kind WHEN 'close' THEN 0 WHEN 'provisional' THEN 1 ELSE 2 END, loaded_at").fetchall()
    return [dict(r) for r in rows]


def latest_version(con: Conn) -> str | None:
    r = con.execute("SELECT version FROM dim_version WHERE is_latest=1 ORDER BY loaded_at DESC LIMIT 1").fetchone()
    return r["version"] if r else None


def mark_latest(con: Conn) -> None:
    """가장 최신 시점(연·월 큰 것, 같으면 확정 master > 잠정 > 월마감, 그다음 적재 시각)의 버전에만 is_latest=1."""
    con.execute("UPDATE dim_version SET is_latest=0")
    con.execute("UPDATE dim_version SET is_latest=1 WHERE version=(SELECT version FROM dim_version ORDER BY year DESC, month DESC, "
                "CASE kind WHEN 'master' THEN 0 WHEN 'provisional' THEN 1 ELSE 2 END, loaded_at DESC LIMIT 1)")


def previous_version(con: Conn, version: str) -> str | None:
    """이전 시점의 버전: 현재 버전보다 연월이 앞선 것 중 가장 최근(없으면 None). 같은 연월의 잠정·월마감은 '이전'으로 보지 않는다."""
    vs = versions(con)
    cur = next((v for v in vs if v["version"] == version), None)
    if not cur:
        return None
    earlier = [v for v in vs if (v["year"], v["month"]) < (cur["year"], cur["month"])]
    return earlier[-1]["version"] if earlier else None


def clear_all(settings: Settings | None = None) -> None:
    """조회 DB 를 비운다. 챗봇은 마지막에 적재·선택한 실행의 데이터만 본다(다른 실행의 조직·버전이 섞이지 않게)."""
    con = open_writer(settings)
    try:
        with con:
            for t in TABLES:
                con.execute(f"DELETE FROM {t}")
    finally:
        con.close()


def drop_schema(settings: Settings | None = None, schema_override: str | None = None) -> None:
    """스키마째 지운다(테스트 정리·데이터 묶음 다시 만들기)."""
    s = settings or get_settings()
    sc = schema_override or schema_name(s)
    if not _SCHEMA_OK.match(sc):
        raise ValueError("스키마 이름이 올바르지 않습니다")
    if not s.chat_database_url:
        return
    raw = psycopg.connect(s.chat_database_url, autocommit=True, connect_timeout=5)
    try:
        raw.execute(f'DROP SCHEMA IF EXISTS "{sc}" CASCADE')
    finally:
        raw.close()
