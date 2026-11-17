"""데이터 묶음(조회 DB 파일) 관리: sets/ 아래에 묶음마다 SQLite 파일을 두고, 선택하면 활성 DB(chat.db)로 복사한다.

묶음 이름·조직 수·월은 파일 안의 내용(dim_org, dim_version)에서 읽는다(이름을 코드에 박지 않는다).
"""
from __future__ import annotations

import dataclasses
import shutil
import sqlite3
from pathlib import Path

from ..config import Settings, get_settings
from . import db


def sets_dir(s: Settings | None = None) -> Path:
    return db.db_path(s).parent / "sets"


def _active_marker(s: Settings | None = None) -> Path:
    return sets_dir(s) / "active.txt"


def list_sets(s: Settings | None = None) -> dict:
    s = s or get_settings()
    d = sets_dir(s)
    active = _active_marker(s).read_text(encoding="utf-8").strip() if _active_marker(s).exists() else None
    out = []
    for p in sorted(d.glob("*.db")) if d.exists() else []:
        try:
            con = sqlite3.connect(f"file:{p}?mode=ro", uri=True)
            orgs = con.execute("SELECT COUNT(*) FROM dim_org").fetchone()[0]
            vs = con.execute("SELECT version, year, month FROM dim_version ORDER BY year, month").fetchall()
            con.close()
        except sqlite3.Error:
            continue
        out.append({"name": p.stem, "orgs": orgs, "versions": len(vs), "latest": f"{vs[-1][1]}-{vs[-1][2]:02d}" if vs else None, "label": f"{orgs}개 조직", "active": p.stem == active})
    return {"sets": out, "active": active}


def select(name: str, s: Settings | None = None) -> dict:
    s = s or get_settings()
    src = sets_dir(s) / f"{Path(name).name}.db"
    if not src.exists():
        raise FileNotFoundError(f"데이터 묶음 '{name}' 이 없습니다.")
    dst = db.db_path(s)
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(".tmp")
    shutil.copy2(src, tmp)
    for ext in ("-wal", "-shm"):
        dst.with_name(dst.name + ext).unlink(missing_ok=True)
    tmp.replace(dst)
    _active_marker(s).write_text(src.stem, encoding="utf-8")
    return list_sets(s)


def save_current_as(name: str, s: Settings | None = None) -> Path:
    """지금 활성 DB 를 묶음 파일로 저장(백업)."""
    s = s or get_settings()
    dst = sets_dir(s) / f"{Path(name).name}.db"
    dst.parent.mkdir(parents=True, exist_ok=True)
    src = sqlite3.connect(db.db_path(s))
    out = sqlite3.connect(dst)
    src.backup(out)
    out.close()
    src.close()
    return dst


def build(name: str, root: Path, s: Settings | None = None) -> dict:
    """폴더(데이터 묶음)를 읽어 sets/<name>.db 로 만든다. 활성 DB 는 건드리지 않는다."""
    from . import loader_set
    s = s or get_settings()
    dst = sets_dir(s) / f"{Path(name).name}.db"
    dst.parent.mkdir(parents=True, exist_ok=True)
    for ext in ("", "-wal", "-shm"):
        dst.with_name(dst.name + ext).unlink(missing_ok=True)
    s2 = dataclasses.replace(s, chat_db_path=dst)
    r = loader_set.load_dataset(root, s2)
    r["set"] = dst.stem
    return r
