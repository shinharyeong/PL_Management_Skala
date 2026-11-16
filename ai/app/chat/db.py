"""챗봇 조회용 SQLite 연결. 조회는 항상 읽기 전용(mode=ro + PRAGMA query_only=ON), 적재는 별도 쓰기 연결로만 한다."""
from __future__ import annotations

import sqlite3
from pathlib import Path

from ..config import Settings, get_settings
from . import schema


def db_path(s: Settings | None = None) -> Path:
    s = s or get_settings()
    return s.chat_db_path or (s.ai_cache_dir / "chat" / "chat.db")


def files_dir(s: Settings | None = None) -> Path:
    s = s or get_settings()
    return s.ai_cache_dir / "chat" / "files"


def open_writer(s: Settings | None = None) -> sqlite3.Connection:
    """적재 전용 쓰기 연결(조회 경로에서는 쓰지 않는다). 구조가 없으면 만든다."""
    p = db_path(s)
    p.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(p)
    con.execute("PRAGMA journal_mode=WAL")
    con.executescript(schema.DDL)
    return con


def open_readonly(s: Settings | None = None) -> sqlite3.Connection:
    """조회용 읽기 전용 연결. DB 파일이 없으면 FileNotFoundError."""
    p = db_path(s)
    if not p.exists():
        raise FileNotFoundError(f"챗봇 조회 DB 가 없습니다({p.name}). 먼저 업로드 실행 결과나 엑셀을 적재하세요.")
    con = sqlite3.connect(f"file:{p}?mode=ro", uri=True)
    con.execute("PRAGMA query_only=ON")
    con.row_factory = sqlite3.Row
    return con


def versions(con: sqlite3.Connection) -> list[dict]:
    rows = con.execute("SELECT version, kind, label, year, month, loaded_at, source, row_count, is_latest FROM dim_version ORDER BY year, month, "
                       "CASE kind WHEN 'close' THEN 0 WHEN 'provisional' THEN 1 ELSE 2 END, loaded_at").fetchall()
    return [dict(r) for r in rows]


def latest_version(con: sqlite3.Connection) -> str | None:
    r = con.execute("SELECT version FROM dim_version WHERE is_latest=1 ORDER BY loaded_at DESC LIMIT 1").fetchone()
    return r["version"] if r else None


def mark_latest(con: sqlite3.Connection) -> None:
    """가장 최신 시점(연·월 큰 것, 같으면 확정 master > 잠정 > 월마감, 그다음 적재 시각)의 버전에만 is_latest=1."""
    con.execute("UPDATE dim_version SET is_latest=0")
    con.execute("UPDATE dim_version SET is_latest=1 WHERE version=(SELECT version FROM dim_version ORDER BY year DESC, month DESC, "
                "CASE kind WHEN 'master' THEN 0 WHEN 'provisional' THEN 1 ELSE 2 END, loaded_at DESC LIMIT 1)")


def previous_version(con: sqlite3.Connection, version: str) -> str | None:
    """이전 시점의 버전: 현재 버전보다 연월이 앞선 것 중 가장 최근(없으면 None). 같은 연월의 잠정·월마감은 '이전'으로 보지 않는다."""
    vs = versions(con)
    cur = next((v for v in vs if v["version"] == version), None)
    if not cur:
        return None
    earlier = [v for v in vs if (v["year"], v["month"]) < (cur["year"], cur["month"])]
    return earlier[-1]["version"] if earlier else None
