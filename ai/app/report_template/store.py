"""틀·분류표를 JSON 파일로 저장·조회. 저장 위치는 설정(template_dir, 기본 AI_CACHE_DIR/templates)."""
from __future__ import annotations

import json
import os
import re
import secrets
import tempfile
from pathlib import Path

from ..config import get_settings
from .model import Mapping, Template, now_iso

ID_RE = re.compile(r"^[A-Za-z0-9_\-]{1,64}$")


class NotFound(KeyError):
    pass


def _root() -> Path:
    s = get_settings()
    return Path(s.template_dir) if s.template_dir else Path(s.ai_cache_dir) / "templates"


def _dir(kind: str) -> Path:
    d = _root() / kind
    d.mkdir(parents=True, exist_ok=True)
    return d


def _write(path: Path, obj: dict) -> None:
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


# ---------- 틀 ----------
def save_template(t: Template) -> Template:
    """id 가 없으면 새로 만들고, 있으면 덮어쓴다(created_at 유지)."""
    d = _dir("templates")
    if t.id is None:
        t = t.model_copy(update={"id": "t" + secrets.token_hex(6)})
    path = d / f"{t.id}.json"
    created = t.created_at
    if path.exists():
        created = created or json.loads(path.read_text(encoding="utf-8")).get("created_at")
    t = t.model_copy(update={"created_at": created or now_iso(), "updated_at": now_iso()})
    _write(path, t.model_dump(mode="json"))
    return t


def load_template(tid: str) -> Template:
    if not ID_RE.match(tid or ""):
        raise NotFound(tid)
    path = _dir("templates") / f"{tid}.json"
    if not path.exists():
        raise NotFound(tid)
    return Template.model_validate(json.loads(path.read_text(encoding="utf-8")))


def list_templates() -> list[dict]:
    out = []
    for p in sorted(_dir("templates").glob("*.json")):
        try:
            d = json.loads(p.read_text(encoding="utf-8"))
            out.append({"id": d["id"], "name": d["name"], "blocks": len(d.get("blocks", [])), "updated_at": d.get("updated_at"),
                        "mapping_version": (d.get("settings") or {}).get("mapping_version")})
        except (ValueError, KeyError):
            continue                                     # 깨진 파일은 목록에서만 뺀다
    out.sort(key=lambda x: x.get("updated_at") or "", reverse=True)
    return out


def delete_template(tid: str) -> None:
    if not ID_RE.match(tid or ""):
        raise NotFound(tid)
    path = _dir("templates") / f"{tid}.json"
    if not path.exists():
        raise NotFound(tid)
    path.unlink()


# ---------- 분류표(저장할 때마다 새 버전) ----------
def _versions() -> list[int]:
    return sorted(int(p.stem) for p in _dir("mappings").glob("*.json") if p.stem.isdigit())


def save_mapping(m: Mapping) -> Mapping:
    v = (_versions() or [0])[-1] + 1
    m = m.model_copy(update={"version": v, "created_at": now_iso()})
    _write(_dir("mappings") / f"{v}.json", m.model_dump(mode="json"))
    return m


def load_mapping(version: int) -> Mapping:
    path = _dir("mappings") / f"{int(version)}.json"
    if not path.exists():
        raise NotFound(version)
    return Mapping.model_validate(json.loads(path.read_text(encoding="utf-8")))


def latest_mapping() -> Mapping | None:
    v = _versions()
    return load_mapping(v[-1]) if v else None


def list_mappings() -> list[dict]:
    out = []
    for v in _versions():
        m = load_mapping(v)
        out.append({"version": v, "created_at": m.created_at, "note": m.note, "assignments": len(m.assignments)})
    return out
