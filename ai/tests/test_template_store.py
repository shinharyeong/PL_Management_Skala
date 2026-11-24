"""AI 보고서 틀(A 단계): 스키마 검증, 저장·목록·불러오기, 분류표 버전, 불러오기로 시작, 개발 전용 잠금, 날짜 규칙 해석. OpenAI 는 쓰지 않는다."""
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.main import app
from app.report_template import periods, store
from app.report_template.model import Mapping, Period, Template

client = TestClient(app)


@pytest.fixture(autouse=True)
def dev_on(monkeypatch):
    monkeypatch.setenv("AI_TEMPLATE_DEV", "1")


def sample(**kw):
    d = {"name": "샘플 틀", "source": "manual", "blocks": [
        {"id": "b1", "kind": "title", "text": "월간 보고"},
        {"id": "b2", "kind": "table", "metric": "operating_profit", "scope": {"type": "all"}, "period": {"rule": "base"},
         "compare": {"kind": "plan"}, "on_missing": "show_empty", "footnote_ids": ["b4"]},
        {"id": "b3", "kind": "chart", "metric": "revenue", "scope": {"type": "org", "org": "ORG-X"}, "period": {"rule": "recent_n", "n": 6}, "on_missing": "drop"},
        {"id": "b4", "kind": "footnote", "text": "※ 단위는 원본 단위"}]}
    d.update(kw)
    return d


def test_dev_flag_off_gives_404(monkeypatch):
    monkeypatch.setenv("AI_TEMPLATE_DEV", "0")
    assert client.get("/v1/templates").status_code == 404
    assert client.post("/v1/templates", json=sample()).status_code == 404


def test_save_list_load_delete_roundtrip():
    r = client.post("/v1/templates", json=sample())
    assert r.status_code == 200
    saved = r.json()
    tid = saved["id"]
    assert tid and saved["created_at"] and saved["updated_at"] and saved["schema_version"] == 1
    lst = client.get("/v1/templates").json()["templates"]
    assert [x["id"] for x in lst] == [tid] and lst[0]["blocks"] == 4
    got = client.get(f"/v1/templates/{tid}").json()
    assert got["blocks"] == saved["blocks"] and got["settings"]["base_month"]["mode"] == "latest_master"
    # 같은 id 로 저장하면 덮어쓰고 created_at 은 유지
    got["name"] = "바꾼 이름"
    again = client.post("/v1/templates", json=got).json()
    assert again["id"] == tid and again["name"] == "바꾼 이름" and again["created_at"] == saved["created_at"]
    assert len(client.get("/v1/templates").json()["templates"]) == 1
    assert client.delete(f"/v1/templates/{tid}").status_code == 200
    assert client.get(f"/v1/templates/{tid}").status_code == 404


def test_path_traversal_ids_are_rejected():
    for bad in ("..", "a b", "x.json"):
        assert client.get(f"/v1/templates/{bad}").status_code in (404, 422)
    with pytest.raises(store.NotFound):
        store.load_template("../x")


@pytest.mark.parametrize("patch,msg", [
    ({"blocks": [{"id": "a", "kind": "title", "text": "t"}, {"id": "a", "kind": "title", "text": "u"}]}, "겹칩니다"),
    ({"blocks": [{"id": "a", "kind": "table"}]}, "metric"),
    ({"blocks": [{"id": "a", "kind": "title"}]}, "text"),
    ({"blocks": [{"id": "a", "kind": "title", "text": "t", "footnote_ids": ["zz"]}]}, "각주"),
    ({"blocks": [{"id": "a", "kind": "title", "text": "t"}, {"id": "b", "kind": "title", "text": "u", "footnote_ids": ["a"]}]}, "footnote"),
    ({"blocks": [{"id": "a", "kind": "table", "metric": "m", "period": {"rule": "fixed"}}]}, "fixed"),
    ({"blocks": [{"id": "a", "kind": "table", "metric": "m", "period": {"rule": "recent_n"}}]}, "recent_n"),
    ({"blocks": [{"id": "a", "kind": "table", "metric": "m", "scope": {"type": "org"}}]}, "조직"),
    ({"blocks": [{"id": "a", "kind": "table", "metric": "m", "on_missing": "boom"}]}, ""),
    ({"blocks": [{"id": "a", "kind": "title", "text": "t", "unknown_key": 1}]}, ""),
    ({"schema_version": 99}, "스키마"),
])
def test_invalid_templates_are_rejected(patch, msg):
    r = client.post("/v1/templates", json=sample(**patch))
    assert r.status_code == 422
    assert msg in r.text


def test_relative_period_resolution_has_no_absolute_dates():
    base = (2030, 2)                                   # 어떤 연·월이어도 같은 규칙
    assert periods.resolve(Period(rule="base"), base) == [(2030, 2)]
    assert periods.resolve(Period(rule="base_offset", offset=-1), base) == [(2030, 1)]
    assert periods.resolve(Period(rule="base_offset", offset=-2), base) == [(2029, 12)]       # 연도 넘김
    assert periods.resolve(Period(rule="base_offset", offset=11), base) == [(2031, 1)]
    assert periods.resolve(Period(rule="fixed", year=2031, month=7), base) == [(2031, 7)]
    assert periods.resolve(Period(rule="recent_n", n=4), base) == [(2029, 11), (2029, 12), (2030, 1), (2030, 2)]
    r = client.post("/v1/templates/resolve-period", json={"base_year": 2033, "base_month": 12, "period": {"rule": "base_offset", "offset": 1}})
    assert r.json()["months"] == [{"year": 2034, "month": 1}]
    with pytest.raises(ValidationError):
        Period(rule="base_offset", offset=0)


def test_mapping_versions_and_assignment_rules():
    m1 = client.post("/v1/templates/mappings", json={"assignments": {"k1": 1, "k2": 3}, "note": "처음"}).json()
    m2 = client.post("/v1/templates/mappings", json={"assignments": {"k1": 4}}).json()
    assert (m1["version"], m2["version"]) == (1, 2)
    assert [c["id"] for c in m1["classes"]] == [1, 2, 3, 4, 5]
    assert client.get("/v1/templates/mappings/1").json()["assignments"] == {"k1": 1, "k2": 3}     # 옛 버전은 그대로
    assert [x["version"] for x in client.get("/v1/templates/mappings").json()["mappings"]] == [1, 2]
    assert client.get("/v1/templates/mappings/9").status_code == 404
    for bad in (2, 5, 0):                                # 2 는 3+4 자동, 5 는 미지정 자동 — 직접 지정 불가
        assert client.post("/v1/templates/mappings", json={"assignments": {"k": bad}}).status_code == 422
    with pytest.raises(ValidationError):
        Mapping(classes=[{"id": 1, "name": "a"}], assignments={})


def test_start_from_saved_template_skips_extraction():
    mv = client.post("/v1/templates/mappings", json={"assignments": {"k1": 1}}).json()["version"]
    t = sample()
    t["settings"] = {"mapping_version": mv}
    tid = client.post("/v1/templates", json=t).json()["id"]
    r = client.post(f"/v1/templates/{tid}/start").json()
    assert r["next_step"] == "select_data" and r["skipped_steps"] == ["extract_pdf", "confirm_structure"]
    assert r["template"]["id"] == tid and r["mapping"]["version"] == mv and r["mapping_missing"] is False
    # 분류표 버전을 기록한 틀: 파일이 사라지면 알려 준다
    (store._root() / "mappings" / f"{mv}.json").unlink()
    assert client.post(f"/v1/templates/{tid}/start").json()["mapping_missing"] is True
    assert client.post("/v1/templates/nope/start").status_code == 404


def test_template_json_is_plain_and_reloadable_from_disk():
    saved = store.save_template(Template.model_validate(sample()))
    text = (store._root() / "templates" / f"{saved.id}.json").read_text(encoding="utf-8")
    assert "월간 보고" in text                           # 한글이 이스케이프 없이 저장
    assert store.load_template(saved.id) == saved


def test_new_code_has_no_hardcoded_year_org_or_folder():
    bad = re.compile(r"(?<![0-9])(2025|2026|2027)(?![0-9])|(?<![A-Za-z0-9])F\d{2,3}(?![A-Za-z0-9])|사업부\s?\d|master-\d|close-\d")
    root = Path(__file__).resolve().parents[1] / "app"
    files = list((root / "report_template").glob("*.py")) + [root / "api" / "templates.py"]
    hits = [f"{f.name}:{n}" for f in files for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1) if bad.search(line)]
    assert not hits, hits
