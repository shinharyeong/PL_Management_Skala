"""DB 금액 계산의 정밀도와 JSON 숫자 경계를 보존한다."""
from __future__ import annotations

from contextlib import contextmanager
from decimal import Decimal, localcontext
from typing import Any, Iterator


@contextmanager
def amount_precision() -> Iterator[None]:
    """numeric(30,6)의 합계·차이를 충분한 정밀도의 요청 문맥에서 계산한다.

    Args:
        없음.
    Returns:
        with 문 또는 데코레이터로 사용할 문맥 관리자.
    Raises:
        내부 계산의 예외를 그대로 전달한다.
    Side Effects:
        현재 실행 문맥의 Decimal 정밀도를 최소 50자리로 높였다가 복구한다.
        전역 설정·파일·DB는 변경하지 않는다.
    """
    with localcontext() as context:
        context.prec = max(context.prec, 50)
        yield


def exact_json_value(value: Any) -> Any:
    """소수 금액과 JavaScript 안전정수 범위를 넘는 정수를 문자열로 보존한다.

    Args:
        value: JSON 응답에 넣을 값·목록·사전.
    Returns:
        Decimal은 고정소수 문자열, 큰 정수는 십진 문자열로 바꾼 사본.
        그 밖의 JSON 값은 동일한 값으로 유지한다.
    Raises:
        없음. 입력은 순환 참조가 없는 JSON 형태여야 한다.
    Side Effects:
        없음. 입력을 변경하거나 파일을 쓰지 않는다.
    """
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, int) and not isinstance(value, bool) and abs(value) > 2**53 - 1:
        return str(value)
    if isinstance(value, dict):
        return {key: exact_json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [exact_json_value(item) for item in value]
    return value
