"""PDF → 보고서 블록 구조 JSON. 백엔드 PDF 작업과 개발 데모가 함께 사용한다. 글자·표 위치는 코드로 추출하고, 숫자 값은 뽑지 않는다(칸이 숫자라는 표시만)."""
from .extract import extract_structure

__all__ = ["extract_structure"]
