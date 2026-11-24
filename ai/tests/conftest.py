"""테스트 공통 설정. 실제 OpenAI 호출·실제 ai/.env·실제 출력 폴더를 쓰지 않는다."""
import json
import os
import shutil
import socket
import subprocess
import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.chat.db import DbUnavailable
from app.services import openai_client

FAKE_KEY = "test-key-not-a-real-key"

FACTS = [
    {"id": "F1", "kind": "매출", "text": "2026년 11월 전사 매출 실적은 557.7억원이며, 계획 대비 +2.3억원(+0.4%)입니다."},
    {"id": "F2", "kind": "영업이익", "text": "2026년 11월 전사 영업이익 실적은 178.3억원이며, 계획 177.0억원 대비 +1.3억원(+0.7%)입니다."},
    {"id": "F3", "kind": "소명", "text": "사업부12의 기타외주용역비 12월 추정 변동은 +0.8억원이며, 수락된 소명 원문은 \"추정을 75백만원 높였습니다.\"입니다."},
    {"id": "F4", "kind": "주의", "text": "사업부20의 11월 변동에서 증가분 80백만원의 원인별 금액은 미분해 상태이므로 원인 금액을 나누어 쓰지 않는다."},
]
GOOD = [
    {"text": "2026년 11월 전사 매출은 557.7억원으로 계획 대비 +2.3억원(+0.4%)입니다.", "fact_ids": ["F1"]},
    {"text": "영업이익은 178.3억원으로 계획 177.0억원 대비 +1.3억원(+0.7%)입니다.", "fact_ids": ["F2"]},
    {"text": "사업부12의 기타외주용역비 12월 추정은 소명에 따라 75백만원 높아졌습니다.", "fact_ids": ["F3"]},
    {"text": "사업부20의 80백만원 증가분은 원인별 금액이 미분해 상태입니다.", "fact_ids": ["F4"]},
]


class FakeClient:
    """OpenAI 클라이언트 모의. 호출 횟수를 센다."""

    def __init__(self, sentences=None, usage=(120, 60), exc=None):
        self.calls, self.kwargs = 0, []
        self._content = json.dumps({"sentences": sentences if sentences is not None else GOOD}, ensure_ascii=False)
        self._usage, self._exc = usage, exc
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        self.calls += 1
        self.kwargs.append(kw)
        if self._exc:
            raise self._exc
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=self._content))],
                               usage=SimpleNamespace(prompt_tokens=self._usage[0], completion_tokens=self._usage[1]))


# ---------------- PostgreSQL(챗봇 조회 DB) ----------------
# AI_TEST_DATABASE_URL 이 있으면 그 서버를 쓰고, 없으면 PATH·Homebrew 의 PostgreSQL(initdb/pg_ctl)로 임시 서버를 띄운다. 둘 다 없으면 DB 가 필요한 테스트는 건너뛴다.
# 테스트마다 고유한 스키마(t_xxxx)를 만들고 끝나면 지워서 서로 섞이지 않는다.
def _pg_bin():
    for d in [None, *sorted(Path("/opt/homebrew/opt").glob("postgresql@*/bin"), reverse=True), *sorted(Path("/usr/lib/postgresql").glob("*/bin"), reverse=True)]:
        initdb = shutil.which("initdb", path=str(d) if d else None)
        if initdb:
            return Path(initdb).parent
    return None


@pytest.fixture(scope="session")
def pg_url(tmp_path_factory):
    url = os.environ.get("AI_TEST_DATABASE_URL")
    if url:
        yield url
        return
    b = _pg_bin()
    if b is None:
        yield None
        return
    d = tmp_path_factory.mktemp("pgdata")
    with socket.socket() as sk:
        sk.bind(("127.0.0.1", 0))
        port = sk.getsockname()[1]
    run = lambda *a: subprocess.run([str(b / a[0]), *a[1:]], capture_output=True, text=True, check=True)
    try:
        run("initdb", "-D", str(d), "-U", "aitest", "--auth=trust", "-E", "UTF8", "--no-locale")
        run("pg_ctl", "-D", str(d), "-o", f"-p {port} -c listen_addresses=127.0.0.1 -c fsync=off", "-l", str(d / "log"), "-w", "start")
        run("createdb", "-h", "127.0.0.1", "-p", str(port), "-U", "aitest", "aitest")
    except Exception:  # noqa: BLE001
        yield None
        return
    yield f"postgresql://aitest@127.0.0.1:{port}/aitest"
    subprocess.run([str(b / "pg_ctl"), "-D", str(d), "-m", "immediate", "stop"], capture_output=True)


@pytest.hookimpl(wrapper=True)
def pytest_runtest_call(item):
    try:
        return (yield)
    except DbUnavailable as e:
        if "연결하지 못했" in str(e) or "주소가 없" in str(e):
            pytest.skip("PostgreSQL 이 없어 건너뜀(AI_TEST_DATABASE_URL 또는 로컬 PostgreSQL 필요)")
        raise


@pytest.fixture(autouse=True)
def isolated(tmp_path, monkeypatch, pg_url):
    for k in ("OPENAI_API_KEY", "OPENAI_MODEL", "AI_TEMPERATURE", "AI_MAX_OUTPUT_TOKENS", "AI_TIMEOUT_SECONDS", "AI_MAX_RETRIES", "AI_DAILY_CALL_LIMIT"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("AI_ENV_FILE", str(tmp_path / "no.env"))          # 실제 ai/.env 를 읽지 않음
    monkeypatch.setenv("OUTPUT_DIR", str(tmp_path / "output"))
    monkeypatch.setenv("AI_CACHE_DIR", str(tmp_path / "cache"))
    monkeypatch.setenv("OPENAI_API_KEY", FAKE_KEY)
    schema = "t_" + uuid.uuid4().hex[:10]
    monkeypatch.setenv("AI_DB_SCHEMA", schema)
    if pg_url:
        monkeypatch.setenv("AI_DATABASE_URL", pg_url)

    def forbidden(settings):
        raise AssertionError("실제 OpenAI 클라이언트 생성 금지 — 테스트에서 모의 클라이언트를 지정하세요")
    monkeypatch.setattr(openai_client, "make_client", forbidden)
    yield tmp_path
    if pg_url:                                                              # 이 테스트가 만든 스키마(데이터 묶음 스키마 포함)를 모두 지운다
        import psycopg
        with psycopg.connect(pg_url, autocommit=True) as c:
            for (n,) in c.execute("SELECT nspname FROM pg_namespace WHERE nspname LIKE %s", (schema + "%",)).fetchall():
                c.execute(f'DROP SCHEMA IF EXISTS "{n}" CASCADE')


@pytest.fixture
def fake(monkeypatch):
    def install(**kw):
        c = FakeClient(**kw)
        monkeypatch.setattr(openai_client, "make_client", lambda settings: c)
        return c
    return install


@pytest.fixture
def facts():
    return [dict(f) for f in FACTS]
