# 근거 기반 대화 분석 계약

이번 확장은 저장 없는 대화와 기존 읽기 전용 데이터 범위를 유지한다. 수치는 기존 Spring 계산·조회 결과를 사용하고, 모델은 질문의 요구 항목과 근거에 연결한 설명을 작성한다. 이 문서는 구현·검증과 함께 갱신한다.

## 공유 응답 계약

기존 assistant 응답에 `brief`를 추가한다. 이전 응답과의 호환을 위해 프론트·Spring은 필드가 없는 응답도 처리하지만, 새 AI 실행은 항상 아래 구조 또는 null을 반환한다. 모델 장애 전 성공 관찰은 기존처럼 보존한다.

```json
{
  "summary": "숫자를 직접 생성하지 않는 질문별 요약",
  "requirements": [
    {"id":"r1","requestQuote":"사용자 질문의 실제 부분 인용","question":"확인할 항목","status":"ANSWERED","findingIds":["f1"],"missingInformation":null,"followUpQuestion":null}
  ],
  "findings": [
    {"id":"f1","kind":"FACT","text":"근거와 연결한 설명","citations":[{"observationId":"obs-1","path":"/data/name","quote":"원문 값"}],"uncertainty":null}
  ]
}
```

- requirements 최대 6개, findings 최대 8개. kind는 FACT / INTERPRETATION / HYPOTHESIS, status는 ANSWERED / NEEDS_INPUT / UNSUPPORTED.
- summary 최대 1000자, finding text 최대 700자, uncertainty 최대 400자. requirement question·missingInformation·followUpQuestion 최대 400자, requestQuote 최대 500자.
- 각 finding은 해당 실행의 SUCCESS 관찰에 연결된 citations 1~4개가 필요하다. INTERPRETATION·HYPOTHESIS는 uncertainty가 필요하다. 가설은 개선 효과나 원인의 확정이 아니다.
- ANSWERED 요구 항목은 실제 findingIds 1개 이상, 미해결 요구 항목은 missingInformation·followUpQuestion을 가져야 한다. plan의 id·requestQuote·question은 종료 때 변경하지 않는다.
- summary·설명에 근거 밖 금액·비율·효과를 생성하지 않는다. 수치는 연결된 원문 인용에서 정밀 표시한다. 존재하는 숫자 포함 조직명은 실제 인용한 이름 필드와 일치할 때만 허용한다.
- 현재 질문에 대한 모든 요구 항목이 ANSWERED일 때만 COMPLETED. 미해결 항목이 있으면 NEEDS_INPUT. 실패·호출 한도는 기존 상태를 유지한다. 이 검증은 형식·근거 연결 검증이며 해석의 진위를 자동 보증하지 않는다.

## 모델의 계획·조회·종료

첫 모델 선택은 로컬 `plan_analysis`로 requirements의 id·requestQuote·question을 선언한다. requestQuote는 현재 사용자 message의 부분 문자열이어야 한다. 이후 읽기 도구와 상세 읽기를 선택하고, 종료 제안이 요구 항목·근거 계약을 만족하지 못하면 검증 오류를 돌려보내 수정·추가 조회할 수 있게 한다.

native finish는 `summary`, `findings`(citations 대신 citationIds), `requirements`(id·status·findingIds·missingInformation·followUpQuestion), `nextActions`를 받는다. AI 서비스가 citationIds를 실제 원문으로 복원한다. 기존 최상위 answer는 brief.summary이며 citations는 finding 인용의 중복 없는 합집합(최대 32개)이다.

계획·종료 함수는 `strict: true`를 사용한다. 모든 객체 속성을 필수로 지정하고 선택 문자열은 nullable로 유지한다. native 정성 문장은 숫자·퍼센트 기호를 생성하지 않는 패턴을 적용하며 숫자 포함 조직명과 실제 금액은 근거 카드로 표시한다. 응답 검증은 이전 계약과 호환되는 정확한 숫자 포함 이름 인용을 계속 허용한다. strict는 형식 계약을 강화하며 해석의 진실성·금액·실제 인용 여부 검증을 대체하지 않는다. [OpenAI function calling 명세](https://developers.openai.com/api/docs/guides/function-calling)의 strict 요구사항을 따른다.

## 추가 상세 조회 도구

`observation_detail` 인자는 `{observationId, path, offset?:0, limit?:5}`다. 현재 실행에서 성공한 관찰의 `/data` 아래 JSON pointer만 읽고 임의 DB·URL·파일에는 접근하지 않는다. offset은 0~10000, limit은 1~10이다. 배열은 limit개, 문자열은 limit×400자(최대 4000자), 객체는 limit개 속성만 반환한다. 응답 `data`는 `sourceObservationId`, `path`, `offset`, `nextOffset`(없으면 null), `total`, `value`를 가진다. nextOffset은 동일 타입의 다음 시작 위치다. scalar는 offset 0에서만 반환한다. 재귀적인 detail 원천은 허용하지 않는다.

모델용 관찰은 실제 값·구조를 제한된 문맥으로 제공하고, 축약 여부와 상세 조회할 경로를 알린다. 인용 카탈로그만으로 원문이 존재하지 않는다고 판단하지 않는다. 대화 중 개선 가설은 읽은 소명·손익으로 검토 가설을 작성하며 저장형 discover를 자동 실행하거나 후보 금액을 생성하지 않는다.

상세 결과는 `/data/value` 또는 그 하위 실제 값만 업무 근거로 인정한다. 원천 ID·offset·total 등 메타데이터만으로 사실·요구 충족을 주장할 수 없다. 상세 페이지도 모델 문맥에서 잘렸으면 실제 표시한 길이를 기준으로 같은 최초 원천의 다음 구간을 안내하고, 작은 페이지로 재조회한다. 서버 페이지 끝으로 바로 건너뛰지 않는다.

## 화면

대화와 오른쪽 단일 보고서에 같은 brief를 표시한다. 보고서는 질문별 요약, 확인된 사실·해석·검토 가설, 미해결 항목과 확인 질문, 각 문장의 실제 출처를 연결한다. 정밀 목표·조직 표와 현재 범위 검증은 유지한다. 기준 변경·새 요청 중 이전 brief는 숨기며 오류 상태에서 성공 근거만 부분 결과로 표시한다.
