"""일회용 이전 도구: 예전 SQLite 조회 DB(chat.db 와 sets/*.db)를 PostgreSQL 스키마로 복사한다. 서비스 코드에서 import 하지 않는다.

사용: AI_DATABASE_URL=postgresql://... python tools/sqlite_to_postgres.py [--cache ai/.cache/chat] [--schema ai]
  - <cache>/chat.db   → 활성 스키마(AI_DB_SCHEMA, 기본 ai)
  - <cache>/sets/*.db → 데이터 묶음(스키마 <활성>_set_<해시>), <cache>/sets/active.txt 가 있으면 활성 묶음 표시도 옮긴다
복사 전에 대상 스키마의 기존 내용을 지운다. 원본 SQLite 파일은 읽기만 한다.
"""
from __future__ import annotations

import argparse
import dataclasses
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.chat import datasets, db  # noqa: E402
from app.config import get_settings  # noqa: E402


def copy_file(src: Path, s, schema: str) -> dict:
    lite = sqlite3.connect(f"file:{src}?mode=ro", uri=True)
    con = db.open_writer(s, schema_override=schema)
    counts = {}
    try:
        with con:
            for t in reversed(db.TABLES):
                con.execute(f'DELETE FROM "{schema}".{t}')
            for t in ("dim_version", "dim_org", "fact", "gap", "explanation"):
                cols = [r[1] for r in lite.execute(f"PRAGMA table_info({t})")]
                ph = ",".join("?" * len(cols))
                n = 0
                cur = lite.execute(f"SELECT {','.join(cols)} FROM {t}")
                while True:
                    rows = cur.fetchmany(20000)
                    if not rows:
                        break
                    con.executemany(f'INSERT INTO "{schema}".{t}({",".join(cols)}) VALUES({ph})', rows)
                    n += len(rows)
                counts[t] = n
        con.executescript("ANALYZE;")
    finally:
        con.close()
        lite.close()
    return counts


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--cache", type=Path, default=get_settings().ai_cache_dir / "chat")
    ap.add_argument("--schema", default=None, help="활성 스키마 이름(기본: AI_DB_SCHEMA)")
    a = ap.parse_args()
    s = get_settings()
    if a.schema:
        s = dataclasses.replace(s, chat_schema=a.schema)
    if not s.chat_database_url:
        sys.exit("AI_DATABASE_URL 이 없습니다.")
    main_db = a.cache / "chat.db"
    if main_db.exists():
        print("활성:", db.schema_name(s), copy_file(main_db, s, db.schema_name(s)))
    sets = a.cache / "sets"
    for p in sorted(sets.glob("*.db")) if sets.exists() else []:
        sc = datasets._set_schema(p.stem, s)
        print("묶음:", p.stem, sc, copy_file(p, s, sc))
        datasets._register(p.stem, sc, s)
    marker = sets / "active.txt"
    if marker.exists():
        con = datasets._reg(s)
        try:
            with con:
                con.execute("INSERT INTO dataset_state(key, val) VALUES('active', ?) ON CONFLICT (key) DO UPDATE SET val = EXCLUDED.val", (marker.read_text(encoding="utf-8").strip(),))
        finally:
            con.close()
    print("완료. 원본 SQLite 파일은 그대로 두었습니다.")


if __name__ == "__main__":
    main()
