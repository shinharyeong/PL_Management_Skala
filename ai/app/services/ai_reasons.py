"""AI 를 못 썼거나 문장이 바뀐 이유를 사람이 읽는 말로 바꾼다(보고서 막대·챗봇 공용).
이유 코드: NO_API_KEY / DAILY_LIMIT_EXCEEDED / AI_NOT_USED / AI_CALL_FAILED:<예외 이름> / MISSING_IN_AI_OUTPUT / 문장 검증 실패 코드(NUMBER_NOT_IN_FACTS 등)."""
from __future__ import annotations

ERROR_KO = {"APITimeoutError": "시간 초과", "TimeoutError": "시간 초과", "APIConnectionError": "연결 실패", "ConnectError": "연결 실패", "RateLimitError": "요청 한도 초과",
            "AuthenticationError": "키 인증 실패", "PermissionDeniedError": "권한 없음", "BadRequestError": "잘못된 요청", "InternalServerError": "OpenAI 서버 오류",
            "APIStatusError": "OpenAI 응답 오류", "JSONDecodeError": "응답 형식 오류", "ValueError": "응답 형식 오류", "KeyError": "응답 형식 오류"}
CODE_KO = {"NO_API_KEY": "API 키가 없음", "DAILY_LIMIT_EXCEEDED": "하루 호출 상한을 넘음", "AI_NOT_USED": "AI 사용을 켜지 않음", "MISSING_IN_AI_OUTPUT": "AI 응답에 해당 항목이 없음",
           "FILL_MIN_3_SENTENCES": "문장 수를 채우려고 기본문구 사용"}
VALIDATION = {"NUMBER_NOT_IN_FACTS", "NUMBER_NOT_IN_RESULT", "FORBIDDEN_WORD", "UNKNOWN_FACT_ID", "NO_FACT_IDS", "EMPTY_SENTENCE", "UNDISTRIBUTED_NOT_STATED",
              "CAUSE_WORD_NOT_IN_SOURCE", "IMPACT_WITHOUT_SOURCE_CUE", "EMPTY", "MISSING_FIELD"}


def call_failed(error_type: str) -> str:
    """예외 이름('APITimeoutError HTTP 429' 꼴 포함)을 'AI_CALL_FAILED:<이름>' 코드로."""
    return "AI_CALL_FAILED:" + (error_type or "Error")


def ko(r: str | None) -> str | None:
    if not r:
        return None
    if r.startswith("AI_CALL_FAILED:"):
        t = r.split(":", 1)[1].strip()
        base = t.split(" ")[0]
        return "호출 실패(" + ERROR_KO.get(base, base) + (f" · {' '.join(t.split(' ')[1:])}" if " " in t else "") + ")"
    if r in CODE_KO:
        return CODE_KO[r]
    head = r.split(":")[0].split(";")[0]
    if head in VALIDATION:
        return "문장 검증 실패로 기본문구로 교체(" + head + ")"
    if r in ERROR_KO or r.endswith("Error"):          # 코드 앞부분(AI_CALL_FAILED:)이 빠진 예외 이름이 들어와도 실제 사유로 보이게
        return "호출 실패(" + ERROR_KO.get(r.split(" ")[0], r) + ")"
    return r
