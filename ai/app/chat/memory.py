"""대화 기억(테스트용 최소 버전): 서버 메모리에만 보관한다(최근 6턴, 2시간, 서버를 다시 켜면 사라짐). 파일·DB 에 저장하지 않는다.

저장하는 것: 해석된 조건(조직, 연, 월, 버전, 지표, 구분), 되묻기 대기 상태(종류만), 직전 SQL, 결과의 열 이름과 행 수.
저장하지 않는 것: 질문 원문, 결과 값 전체.
"""
from __future__ import annotations

import threading
import time
from collections import deque
from dataclasses import dataclass, field

MAX_TURNS = 6
TTL_SECONDS = 2 * 60 * 60


@dataclass
class State:
    conditions: dict = field(default_factory=dict)      # {orgs, org_names, year, months, version, metric, scenario}
    pending: str | None = None                          # 되묻기 종류(org / compare / ai)
    last_sql: str | None = None
    last_params: dict = field(default_factory=dict)
    last_columns: list = field(default_factory=list)
    last_rows: int = 0
    turns: deque = field(default_factory=lambda: deque(maxlen=MAX_TURNS))   # 최근 6턴의 조건 스냅샷
    stamp: str = ""                                     # 조회 DB 적재 상태(바뀌면 대화를 초기화)
    updated: float = field(default_factory=time.time)


_LOCK = threading.Lock()
_STORE: dict[str, State] = {}


def _prune(now: float) -> None:
    for k in [k for k, v in _STORE.items() if now - v.updated > TTL_SECONDS]:
        del _STORE[k]


def get(cid: str | None, stamp: str, *, on: bool) -> State | None:
    """기억이 켜져 있고 대화 ID 가 있으면 상태를 돌려준다(없으면 새로 만든다). DB 적재 상태(stamp)가 바뀌었으면 비운다."""
    if not on or not cid:
        return None
    now = time.time()
    with _LOCK:
        _prune(now)
        st = _STORE.get(cid)
        if st is None or st.stamp != stamp:
            st = State(stamp=stamp)
            _STORE[cid] = st
        st.updated = now
        return st


def peek(cid: str | None) -> State | None:
    with _LOCK:
        return _STORE.get(cid) if cid else None


def reset(cid: str | None) -> None:
    if cid:
        with _LOCK:
            _STORE.pop(cid, None)


def clear_keys(cid: str | None, keys: list[str]) -> State | None:
    """조건 칩 해제: 지정한 조건(org, year, month, version, metric, scenario)을 비운다."""
    st = peek(cid)
    if st is None:
        return None
    with _LOCK:
        for k in keys:
            if k == "org":
                st.conditions.pop("orgs", None); st.conditions.pop("org_names", None)
            elif k == "month":
                st.conditions.pop("months", None)
            else:
                st.conditions.pop(k, None)
        st.last_sql, st.last_params = None, {}
        st.updated = time.time()
    return st


def chips(st: State | None, version_label: str | None = None) -> list[dict]:
    """화면에 보여 줄 이어 쓰는 조건(칩)."""
    if st is None:
        return []
    c, out = st.conditions, []
    if c.get("org_names"):
        out.append({"key": "org", "label": " · ".join(c["org_names"])})
    if c.get("year"):
        out.append({"key": "year", "label": f"{c['year']}년"})
    if c.get("months"):
        out.append({"key": "month", "label": "·".join(f"{m}월" for m in c["months"])})
    if c.get("version_explicit") and version_label:
        out.append({"key": "version", "label": version_label})
    if c.get("metric"):
        out.append({"key": "metric", "label": c["metric"]})
    if c.get("scenario") and c["scenario"] != "ACTUAL":
        out.append({"key": "scenario", "label": {"ESTIMATE": "추정", "PLAN": "계획"}.get(c["scenario"], c["scenario"])})
    return out


def forget_all() -> None:      # 테스트용
    with _LOCK:
        _STORE.clear()
