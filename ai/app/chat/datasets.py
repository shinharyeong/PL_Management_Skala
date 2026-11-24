"""데이터 묶음(조회 DB 스냅샷) 관리: 묶음마다 PostgreSQL 스키마(<활성 스키마>_set_<이름 해시>)를 하나 두고, 선택하면 활성 스키마로 복사한다.

묶음 이름·조직 수·월은 스키마 안의 내용(dim_org, dim_version)에서 읽는다(이름을 코드에 박지 않는다). 이름→스키마 대응과 활성 묶음은 활성 스키마의 dataset_set / dataset_state 에 둔다.
"""
from __future__ import annotations

import dataclasses
import hashlib
from pathlib import Path

from ..config import Settings, get_settings
from . import db, schema

_REG_DDL = """
CREATE TABLE IF NOT EXISTS dataset_set(name TEXT PRIMARY KEY, schema_name TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS dataset_state(key TEXT PRIMARY KEY, val TEXT);
"""


def _set_schema(name: str, s: Settings) -> str:
    return f"{db.schema_name(s)}_set_{hashlib.sha1(Path(name).name.encode('utf-8')).hexdigest()[:10]}"[:63]


def _reg(s: Settings) -> db.Conn:
    con = db.open_writer(s)                               # 활성 스키마가 없으면 만든다
    con.executescript(_REG_DDL)
    return con


def list_sets(s: Settings | None = None) -> dict:
    s = s or get_settings()
    try:
        con = _reg(s)
    except db.DbUnavailable:
        return {"sets": [], "active": None}
    try:
        a = con.execute("SELECT val FROM dataset_state WHERE key='active'").fetchone()
        active = a["val"] if a else None
        out = []
        for r in con.execute("SELECT name, schema_name FROM dataset_set ORDER BY name").fetchall():
            try:
                orgs = con.execute(f'SELECT COUNT(*) FROM "{r["schema_name"]}".dim_org').fetchone()[0]
                vs = con.execute(f'SELECT version, year, month FROM "{r["schema_name"]}".dim_version ORDER BY year, month').fetchall()
            except Exception:  # noqa: BLE001  (스키마가 지워졌으면 목록에서 뺀다)
                con.rollback()
                continue
            out.append({"name": r["name"], "orgs": orgs, "versions": len(vs), "latest": f"{vs[-1][1]}-{vs[-1][2]:02d}" if vs else None, "label": f"{orgs}개 조직", "active": r["name"] == active})
        return {"sets": out, "active": active}
    finally:
        con.close()


def _copy(con: db.Conn, src: str, dst: str) -> None:
    """src 스키마의 조회 테이블 내용을 dst 스키마로 통째로 복사한다(dst 의 기존 내용은 지운다)."""
    for t in db.TABLES:
        con.execute(f'DELETE FROM "{dst}".{t}')
        con.execute(f'INSERT INTO "{dst}".{t} SELECT * FROM "{src}".{t}')


def select(name: str, s: Settings | None = None) -> dict:
    s = s or get_settings()
    con = _reg(s)
    try:
        r = con.execute("SELECT schema_name FROM dataset_set WHERE name=?", (Path(name).name,)).fetchone()
        if not r:
            raise FileNotFoundError(f"데이터 묶음 '{name}' 이 없습니다.")
        with con:
            _copy(con, r["schema_name"], db.schema_name(s))
            con.execute("INSERT INTO dataset_state(key, val) VALUES('active', ?) ON CONFLICT (key) DO UPDATE SET val = EXCLUDED.val", (Path(name).name,))
    finally:
        con.close()
    return list_sets(s)


def _register(name: str, sc: str, s: Settings) -> None:
    con = _reg(s)
    try:
        with con:
            con.execute("INSERT INTO dataset_set(name, schema_name) VALUES(?,?) ON CONFLICT (name) DO UPDATE SET schema_name = EXCLUDED.schema_name", (name, sc))
    finally:
        con.close()


def save_current_as(name: str, s: Settings | None = None) -> str:
    """지금 활성 스키마의 내용을 묶음으로 저장(백업). 반환: 묶음 스키마 이름."""
    s = s or get_settings()
    name = Path(name).name
    sc = _set_schema(name, s)
    con = db.open_writer(s, schema_override=sc)
    try:
        with con:
            _copy(con, db.schema_name(s), sc)
    finally:
        con.close()
    _register(name, sc, s)
    return sc


def build(name: str, root: Path, s: Settings | None = None) -> dict:
    """폴더(데이터 묶음)를 읽어 묶음 스키마로 만든다. 활성 스키마는 건드리지 않는다."""
    from . import loader_set
    s = s or get_settings()
    name = Path(name).name
    sc = _set_schema(name, s)
    db.drop_schema(s, schema_override=sc)
    s2 = dataclasses.replace(s, chat_schema=sc)
    r = loader_set.load_dataset(root, s2)
    _register(name, sc, s)
    r["set"] = name
    return r
