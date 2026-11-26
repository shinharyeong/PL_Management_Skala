"""호출 한도 만료를 파일·네트워크·DB 없이 검증한다."""
from __future__ import annotations

from datetime import datetime, timezone
import unittest
from unittest.mock import patch
from zoneinfo import ZoneInfo

from app import config


class DailyCallLimitTests(unittest.TestCase):
    """한국 날짜의 만료 경계와 기존 설정 호환성을 확인한다."""

    def read_settings(self, values: dict[str, str], instant: datetime) -> config.Settings:
        """메모리 설정과 고정된 한국 시각으로 공개 설정 진입점을 호출한다.

        Args: values: 합성 환경 설정. instant: 시간대가 포함된 합성 현재 시각.
        Returns: 실제 get_settings가 구성한 설정.
        Raises: ValueError: 잘못된 합성 환경 설정. AssertionError: 한국 시간 미사용.
        Side Effects: 호출 중 설정 읽기와 시계만 메모리에서 대체한다.
        """
        def read(name: str, default: str | None = None) -> tuple[str | None, str]:
            """환경 파일 대신 합성 설정의 값 또는 기본값을 반환한다.

            Args: name: 설정 이름. default: 미설정 기본값.
            Returns: 값과 합성 출처. Raises: 없음. Side Effects: 없음.
            """
            return values.get(name, default), "synthetic"

        with patch.object(config, "_read", side_effect=read), patch.object(config, "datetime") as clock:
            clock.now.return_value = instant.astimezone(ZoneInfo("Asia/Seoul"))
            settings = config.get_settings()
            if values.get("AI_DAILY_CALL_LIMIT_EXPIRES_ON"):
                clock.now.assert_called_once_with(ZoneInfo("Asia/Seoul"))
            else:
                clock.now.assert_not_called()
            return settings

    def test_expiration_day_is_inclusive_in_korea(self) -> None:
        """한국 만료일의 마지막 순간까지 임시 한도를 사용한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 조기 만료.
        Side Effects: 합성 설정과 시계만 사용한다.
        """
        values = {"AI_DAILY_CALL_LIMIT": "60", "AI_DAILY_CALL_LIMIT_EXPIRES_ON": "2026-11-26"}
        result = self.read_settings(values, datetime(2026, 11, 26, 14, 59, 59, tzinfo=timezone.utc))
        self.assertEqual(result.ai_daily_call_limit, 60)

    def test_next_korean_day_restores_default_on_next_request(self) -> None:
        """같은 설정도 한국 자정을 넘긴 다음 요청에서 기본 한도로 복구한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 만료 경계 또는 캐시 오류.
        Side Effects: 합성 설정과 시계만 사용한다.
        """
        values = {"AI_DAILY_CALL_LIMIT": "60", "AI_DAILY_CALL_LIMIT_EXPIRES_ON": "2026-11-26"}
        before = self.read_settings(values, datetime(2026, 11, 26, 14, 59, 59, tzinfo=timezone.utc))
        after = self.read_settings(values, datetime(2026, 11, 26, 15, tzinfo=timezone.utc))
        self.assertEqual((before.ai_daily_call_limit, after.ai_daily_call_limit), (60, 30))

    def test_explicit_recovery_limit_is_applied_after_expiration(self) -> None:
        """만료 후 복구 한도를 명시하면 해당 값을 적용한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 명시적 복구값 무시.
        Side Effects: 합성 설정과 시계만 사용한다.
        """
        values = {"AI_DAILY_CALL_LIMIT": "60", "AI_DAILY_CALL_LIMIT_EXPIRES_ON": "2026-11-26",
                  "AI_DAILY_CALL_LIMIT_AFTER_EXPIRY": "12"}
        result = self.read_settings(values, datetime(2026, 11, 27, tzinfo=timezone.utc))
        self.assertEqual(result.ai_daily_call_limit, 12)

    def test_without_expiration_keeps_existing_limits(self) -> None:
        """만료 설정이 없으면 기존 명시 한도와 기본 한도를 유지한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 기존 설정 동작 변경.
        Side Effects: 합성 설정과 시계만 사용한다.
        """
        instant = datetime(2027, 1, 1, tzinfo=timezone.utc)
        self.assertEqual(self.read_settings({"AI_DAILY_CALL_LIMIT": "60"}, instant).ai_daily_call_limit, 60)
        self.assertEqual(self.read_settings({}, instant).ai_daily_call_limit, 30)

    def test_invalid_expiration_is_rejected(self) -> None:
        """잘못된 만료일을 무시해 임시 한도가 무기한 유지되지 않도록 거부한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 잘못된 날짜 수락.
        Side Effects: 합성 설정과 시계만 사용한다.
        """
        with self.assertRaises(ValueError):
            self.read_settings({"AI_DAILY_CALL_LIMIT": "60", "AI_DAILY_CALL_LIMIT_EXPIRES_ON": "invalid"},
                               datetime(2026, 11, 26, tzinfo=timezone.utc))
