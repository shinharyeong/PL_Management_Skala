"""테스트 전용 DB 자료 구성. 운영 AI 앱에는 쓰기 연결·DDL을 포함하지 않는다."""
from __future__ import annotations

import psycopg

from app.chat.db import Conn, DbUnavailable, _row_factory, _xlate, schema_name
from app.chat.db import open_readonly, previous_version, latest_version, versions
from app.config import Settings, get_settings

DDL = """
CREATE TABLE IF NOT EXISTS dim_version(
  version TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT, year INTEGER, month INTEGER, loaded_at TEXT, source TEXT, row_count INTEGER, is_latest INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS dim_org(org_code TEXT PRIMARY KEY, org_name TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS fact(
  version TEXT NOT NULL, org_code TEXT NOT NULL, org_name TEXT, kind TEXT NOT NULL,
  cat1 TEXT, cat2 TEXT, item TEXT, detail TEXT, team TEXT, manager TEXT, src TEXT,
  year INTEGER NOT NULL, month INTEGER NOT NULL, scenario TEXT NOT NULL, amount_won NUMERIC(30,6) NOT NULL);
CREATE TABLE IF NOT EXISTS gap(
  version TEXT NOT NULL, gap_id TEXT, gap_type TEXT, org_code TEXT, org_name TEXT, item TEXT, year INTEGER, month INTEGER, scenario TEXT, basis TEXT,
  previous_won NUMERIC(30,6), current_won NUMERIC(30,6), diff_won NUMERIC(30,6), rate_percent TEXT, status TEXT, explanation_status TEXT);
CREATE TABLE IF NOT EXISTS explanation(
  version TEXT NOT NULL, explanation_id TEXT, gap_key TEXT, gap_id TEXT, org_code TEXT, org_name TEXT, kind TEXT, item TEXT, year INTEGER, month INTEGER, scenario TEXT,
  link_type TEXT, status TEXT, verdict TEXT, rounds INTEGER, answer TEXT, src TEXT);
CREATE OR REPLACE VIEW v_op AS
  SELECT version, org_code, org_name, year, month, scenario,
         SUM(CASE WHEN kind='매출' THEN amount_won ELSE 0 END) AS revenue_won,
         SUM(CASE WHEN kind='영업비용' THEN amount_won ELSE 0 END) AS cost_won,
         SUM(CASE WHEN kind='매출' THEN amount_won ELSE -amount_won END) AS op_won
  FROM fact GROUP BY version, org_code, org_name, year, month, scenario;
"""

INDEXES = """
CREATE INDEX IF NOT EXISTS ix_fact_main ON fact(version, org_code, kind, year, month, scenario);
CREATE INDEX IF NOT EXISTS ix_fact_item ON fact(version, kind, year, month, scenario, cat1, cat2);
CREATE INDEX IF NOT EXISTS ix_gap_v ON gap(version, org_code, year, month);
CREATE INDEX IF NOT EXISTS ix_expl_v ON explanation(version, org_code, year, month);
"""

class TestConn(Conn):
    """테스트 자료 적재에만 쓰는 연결. 운영 모듈에는 쓰기 메서드를 노출하지 않는다."""

    def executemany(self, sql: str, seq):
        """합성 테스트 행을 일괄 적재한다.

        Args:
            sql: 테스트 SQL.
            seq: 바인딩 인자 목록.
        Returns:
            실행한 커서.
        Raises:
            psycopg.Error: 실행 실패.
        Side Effects:
            테스트 DB 값을 변경한다.
        """
        rows = list(seq)
        cur = self.raw.cursor()
        if rows:
            cur.executemany(_xlate(sql, rows[0]), rows)
        return cur

    def executescript(self, script: str) -> None:
        """테스트 스키마 SQL을 실행한다.

        Args:
            script: 세미콜론으로 나눈 테스트 SQL.
        Returns:
            없음.
        Raises:
            psycopg.Error: 실행 실패.
        Side Effects:
            테스트 DB 구조를 변경하고 커밋한다.
        """
        for statement in (part.strip() for part in script.split(";")):
            if statement:
                self.raw.execute(statement)
        self.raw.commit()

    def __enter__(self):
        """테스트 트랜잭션 문맥의 연결을 반환한다.

        Args:
            없음.
        Returns:
            현재 연결.
        Raises:
            없음.
        Side Effects:
            없음.
        """
        return self

    def __exit__(self, error_type, error, traceback):
        """테스트 트랜잭션을 커밋하거나 롤백한다.

        Args:
            error_type: 발생한 예외 타입 또는 None.
            error: 발생한 예외 또는 None.
            traceback: 예외 추적 정보 또는 None.
        Returns:
            예외를 전파하는 False.
        Raises:
            psycopg.Error: 커밋·롤백 실패.
        Side Effects:
            테스트 DB 트랜잭션 상태를 변경한다.
        """
        (self.raw.rollback if error_type else self.raw.commit)()
        return False


def open_writer(s: Settings | None = None, schema_override: str | None = None) -> TestConn:
    """별도로 승인된 DB 테스트에서만 자료용 스키마를 만든다.

    Args:
        s: 테스트 DB 설정.
        schema_override: 테스트마다 고유한 스키마 이름.
    Returns:
        테스트 자료 적재용 연결.
    Raises:
        ValueError: 스키마 이름이 유효하지 않은 경우.
        DbUnavailable: 테스트 DB 주소가 없는 경우.
        psycopg.Error: DB 접속 또는 스키마 생성 실패.
    Side Effects:
        지정한 테스트 DB에 스키마·테이블을 만든다. 운영 앱은 이 모듈을 가져오지 않는다.
    """
    from dataclasses import replace
    s = s or get_settings()
    scoped = replace(s, chat_schema=schema_override) if schema_override else s
    sc = schema_name(scoped)
    if not sc.startswith("t_"):
        raise ValueError("테스트 스키마(t_)에만 적재할 수 있습니다.")
    if not s.chat_database_url:
        raise DbUnavailable("테스트 PostgreSQL 주소가 없습니다.")
    with psycopg.connect(s.chat_database_url, autocommit=True) as boot:
        boot.execute(f'CREATE SCHEMA IF NOT EXISTS "{sc}"')
    raw = psycopg.connect(s.chat_database_url, options=f"-c search_path={sc}", row_factory=_row_factory)
    con = TestConn(raw)
    con.executescript(DDL)
    return con


def upsert_orgs(con: Conn, pairs) -> None:
    """테스트 조직 목록을 적재한다.

    Args:
        con: 테스트 쓰기 연결.
        pairs: 조직 코드와 이름 쌍.
    Returns:
        없음.
    Raises:
        psycopg.Error: SQL 실행 실패.
    Side Effects:
        테스트 DB 조직 테이블을 변경한다.
    """
    con.executemany("INSERT INTO dim_org(org_code, org_name) VALUES(?,?) ON CONFLICT (org_code) DO UPDATE SET org_name = EXCLUDED.org_name", list(pairs))


def mark_latest(con: Conn) -> None:
    """합성 테스트 자료의 기본 버전을 표시한다.

    Args:
        con: 테스트 쓰기 연결.
    Returns:
        없음.
    Raises:
        psycopg.Error: SQL 실행 실패.
    Side Effects:
        테스트 DB의 dim_version만 변경한다.
    """
    con.execute("UPDATE dim_version SET is_latest=0")
    con.execute("UPDATE dim_version SET is_latest=1 WHERE version=(SELECT version FROM dim_version ORDER BY CASE kind WHEN 'master' THEN 0 WHEN 'provisional' THEN 1 ELSE 2 END, year DESC, month DESC, loaded_at DESC LIMIT 1)")


def drop_schema(settings: Settings | None = None, schema_override: str | None = None) -> None:
    """테스트에서 만든 스키마를 정리한다.

    Args:
        settings: 테스트 DB 설정.
        schema_override: 정리할 테스트 스키마 이름.
    Returns:
        없음.
    Raises:
        ValueError: 스키마 이름이 유효하지 않거나 테스트 접두사가 없는 경우.
        psycopg.Error: DB 작업 실패.
    Side Effects:
        t_ 접두사로 제한한 테스트 스키마를 삭제한다.
    """
    from dataclasses import replace
    s = settings or get_settings()
    sc = schema_name(replace(s, chat_schema=schema_override) if schema_override else s)
    if not sc.startswith("t_"):
        raise ValueError("테스트 스키마(t_)만 지울 수 있습니다.")
    if s.chat_database_url:
        with psycopg.connect(s.chat_database_url, autocommit=True) as con:
            con.execute(f'DROP SCHEMA IF EXISTS "{sc}" CASCADE')
