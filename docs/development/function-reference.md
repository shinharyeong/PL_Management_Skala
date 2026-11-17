# 함수 명세와 호출 관계

함수별 목적·입출력·예외·부작용은 소스의 한국어 명세와 아래 영역별 표를 함께 관리한다. 구현을 변경할 때 해당 영역의 표와 실제 호출 관계도 갱신한다.

| 영역 | 함수 명세·호출 관계 |
| --- | --- |
| Vue 화면·공통 상태·조회·명령 | [프런트엔드 구현 안내](frontend-guide.md) |
| Java API·업무 규칙·저장·인증·검증 | [백엔드 함수 명세](backend-function-reference.md) |
| Python 원본 읽기·파싱·매핑·검증·적재 계약 | [파서 함수 명세](../../parser/docs/development/function-reference.md) |
| 로컬 검증 스크립트·환경 | [실행 환경과 검증 기록](local-environment.md) |

화면과 API의 실제 연결 범위는 [PRD·와이어프레임 대응표](prd-wireframe-alignment.md), 공개 HTTP 입력·응답은 [API 계약](api-contract.md)을 따른다. 문서에 연결됐다고 표시한 기능과 실제 브라우저에서 실행 확인한 기능은 구별한다.
