"""PDF → 블록 구조 JSON(데모). 글자·표 위치는 코드로 추출하고, 숫자 값은 뽑지 않는다(칸이 숫자라는 표시만)."""
from .extract import extract_structure

__all__ = ["extract_structure"]
