"""AI 호출 기록(ai/output/ai_call_log.json). 토큰 사용량과 결과 종류만 누적한다. 키·입력 원문은 기록하지 않는다."""
from __future__ import annotations

import json
import threading
from datetime import datetime
from pathlib import Path

_LOCK = threading.Lock()


class CallLog:
    def __init__(self, output_dir: Path):
        self.path = Path(output_dir) / "ai_call_log.json"

    def load(self) -> dict:
        if not self.path.exists():
            return {"calls": []}
        data = json.loads(self.path.read_text(encoding="utf-8"))
        if "calls" not in data:  # 예전 형식(상태별 1건) 보존
            data = {"calls": [], "legacy_entries": data}
        return data

    def calls_today(self, today: str | None = None) -> int:
        """오늘 실제로 OpenAI 를 부른 횟수(성공·실패 포함, 캐시 적중·건너뜀 제외)."""
        today = today or datetime.now().strftime("%Y-%m-%d")
        return sum(1 for c in self.load()["calls"] if c.get("date") == today and c.get("outcome") in ("success", "error"))

    def append(self, entry: dict) -> None:
        with _LOCK:
            data = self.load()
            now = datetime.now()
            data["calls"].append({"at": now.strftime("%Y-%m-%d %H:%M:%S"), "date": now.strftime("%Y-%m-%d"), **entry})
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
