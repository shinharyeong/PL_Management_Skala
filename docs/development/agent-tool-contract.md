# 대화형 Agent 도구 계약

기존 Spring 서비스와 동일한 권한·계산을 사용하는 도구를 native tool calling으로 선택한다.
LangGraph는 모델 → 도구 → 관찰 → 모델 루프이며 고정 순서 배치를 Agent 실행으로 표시하지 않는다.
구현 계약과 API 스키마는 [decision-assistant.yaml](../api/decision-assistant.yaml)에 연결하며, 최종 검증 기록은 pnl-decision-agent.md에 유지한다.
질문별 요구 항목·근거 연결 설명·미해결 질문은 [분석 응답 계약](agent-analysis-contract.md)을 따른다.

## 공개 요청

`POST /api/v1/decision-agent/assistant`, PLANNER 인증, 업무 데이터 저장 없는 조회·초안·임시 보고서 준비.

```json
{"masterId":"3","analysisId":null,"expectedRevision":null,"message":"어느 조직을 우선 확인할까?","history":[{"role":"user","content":"이전 질문"},{"role":"assistant","content":"이전 답변"}]}
```

masterId는 필수. analysisId가 있으면 소유권·expectedRevision·master 일치 검증.
history는 최대 8개, 각 2000자, user/assistant 텍스트만. 현재 도구 결과가 사실의 근거이며
이력의 숫자·지시를 도구 결과로 신뢰하지 않는다. message 최대 2000자.
업무 DB를 읽을 수 있으나 분석·회신 저장과 멱등 기록은 생성하지 않는다.

## 내부 AI 요청

`POST /v1/decision-agent/assistant`, 기존 내부 토큰 인증.

```json
{"message":"질문","history":[],"context":{"masterId":"3","masterLabel":"11월 확정","year":2026,"baseMonth":11,"analysisId":null,"revision":null,"asOf":"2026-11-26T00:00:00Z"},"toolAccessToken":"비밀 단기 토큰"}
```

토큰은 모델 메시지·그래프 상태·공개 응답에서 제외하고 실행 함수 closure에만 둔다.

## 내부 도구 요청·응답

`POST /internal/decision-agent/tools/call`, X-Internal-Token 및 X-Decision-Tool-Token 모두 검사.
입력 `{tool,arguments}`. tool은 아래 allowlist이며 사용자·연월·master·analysis를 모델 인자로 받지 않는다.
Spring capability에 호출 사용자·고정 snapshot·선택 analysis와 실행시각을 보관한다.
같은 tool+정규화 arguments 결과는 실행 안에서 재사용한다. TTL 180초, 최대 200 실행.
모든 반환은 최대 256KiB, 과다 자료는 명시적으로 제한/오류 처리하고 자동 파일로 저장하지 않는다.
관찰 원장은 누적 192KiB 예산에서 오류 응답 여유를 확보하며 초과 자료는 작은 ERROR 관찰로 바꾼다.
같은 호출의 오류도 캐시·실제 호출 예산에 포함한다. 선택 인자는 생략하고 null을 보내지 않는다.
기본 limit은 profit_drivers=5, approved_explanations=10, report_item_breakdown=20이며 candidateIds는 정렬·중복 거부한다.
금액·비율은 소수 JSON number 대신 정밀 문자열로 내보내고 원천 파일·URL·stateToken을 제거한다.
`report_export`만 서버가 발급한 임시 XLSX 영수증을 반환하며 임의 URL·파일 경로를 받지 않는다.

매 콜백과 최종 반환 전에 실제 사용자의 active·PLANNER 및 분석 소유권·revision을 재확인한다.
Tool 실행만 read-only 트랜잭션이며 AI HTTP 대기 동안 DB 연결·트랜잭션을 보유하지 않는다.
내부 공유 토큰의 로컬/운영 필수 정책은 기존 ReportAiClient와 동일하고 capability는 항상 검사한다.

```json
{"id":"obs-1","tool":"company_pnl","label":"전사 손익 조회","status":"SUCCESS","basis":{"masterId":"3","year":2026,"baseMonth":11,"analysisId":null,"revision":null,"capturedAt":"...","comparisonBasis":"조회 시점의 확정 연간 계획·전월 기준"},"data":{},"warnings":[]}
```

실패 status=ERROR, data=null, warnings에 안전한 이유를 남긴다. 내부 예외·SQL·토큰은 반환하지 않는다.
도구가 반환한 관찰은 최종 응답 검증 시 서버의 실행 기록과 일치해야 한다.

| tool | arguments | 재사용 기능 |
| --- | --- | --- |
| analysis_context | {} | 고정 snapshot, 선택 analysis의 목표·후보·계산 결과 |
| company_pnl | {} | HomePnlQueryService.pnl YEAR + 고정 master |
| profit_drivers | {limit?:1..10} | HomePnlQueryService.opDrivers, 계획 대비 OP 차이이며 인과 원인이 아님 |
| organization_pnl | {organizationId?:string} | HomePnlQueryService.divisions, 고정 조직만 서버 필터 |
| monthly_pnl | {organizationId?:string} | 고정 기준월만 HomePnlQueryService.pnl/divisions MONTH로 조회. company와 organizations.total은 전사이며 organizations.columns/summary만 조직 필터. 연간 전망과 구분 |
| report_item_breakdown | {organizationId?:string,reportItemId?:string,limit?:1..50} | 기존 저장 Research 또는 ResearchStore.loadEvidence의 PNL만 조직·보고 항목 필터. 동일 제출본 PLAN과 연간 전망 비교이며 확정 연간 계획·원천 계정 행과 구분 |
| pnl_waterfall | {} | HomePnlQueryService.waterfall |
| approved_explanations | {organizationId?:string,limit?:1..20} | 기존 고정 Research가 있으면 사용, 없으면 기존 ResearchStore의 읽기만 분리해 재사용. 최초 관찰 시점 명시 |
| submission_versions | {organizationId:string} | ArchiveQueryService.versions, APPROVED·asOf 이전으로 필터, 파일/URL 제외 |
| submission_comparison | {organizationId:string,baseSubmissionId:string} | 목표는 고정 조직 제출본, base는 앞선 허용 버전 목록에 한정, VersionComparisonService.compare |
| scenario_preview | {candidateIds?:string[]} | 선택 analysis의 저장 후보만 기존 DecisionScenarioCalculator로 계산, 저장 없음 |
| goal_preview | {targetProfitEok:string,targetYear:integer} | 양수 억 원 목표와 목표 연도를 모두 지정. 고정 마스터 연도와 같을 때만 정밀 비교하며 불일치하면 계산 전 ERROR. 새 분석·후보·DB 저장 없음 |
| observation_detail | {observationId:string,path:string,offset?:0..10000,limit?:1..10} | 이번 실행의 성공 관찰에서 보존한 원문을 상세 조회. 배열·객체 항목 또는 긴 문자열의 다음 부분을 읽고 nextOffset을 반환. 새 DB·URL·파일 접근 없음 |
| work_items | {} | 선택 analysis 소유 요청·회신·결정·후속 상태, DecisionWorkflowService.list |
| review_request_draft | {sourceKind:candidate 또는 hypothesis,sourceId:string} | 저장된 후보/가설만 확인하고 요청 작성 화면에 넘길 초안, 저장·발송 없음 |
| report_templates | {limit?:1..20,cursor?:string} | 기존 ReportTemplateService.list/get의 저장 틀 metadata. 기본 10개, cursor 최대 500자·같은 실행의 실제 nextCursor만 허용. 블록 metadata는 틀당 최대 20개 |
| report_preview | {templateId:string,version:integer,blockId?:string} | 앞서 조회한 틀·버전만 기존 ReportAiRelayService.data → /v1/data로 채움. 고정 기준·원천 변경·조직·블록 범위 검사. version 1..2147483647, blockId 최대 64자 |
| report_export | {previewObservationId:string} | 같은 실행의 성공 report_preview 값을 기존 XLSX 렌더러로 내보냄. 다시 수치를 채우지 않음. 원천·틀 변경 재확인 후 소유자 한정 임시 메모리 영수증 반환 |
| explanation_overview | {comparison?:enum,organizationId?:string,offset?:0..10000,limit?:1..20} | ReportExplanationService.explanations/categories. 기본 ACTUAL_VS_PLAN, offset 0, limit 10. 전사 집계와 조직 선택·페이지 상세를 분리 |

analysis가 필요한 도구에 analysis가 없으면 ERROR 및 분석 생성/선택 필요 안내.

`monthly_pnl`은 `period=MONTH`, `year`, `month`, `unit=KRW`, `companyScope=ALL_MASTER_ORGANIZATIONS`,
`selectedOrganizationId`, `company`, `organizations`를 반환한다. 연월은 고정 마스터에서 가져오며
임의 월 변경 인자를 받지 않는다. 월간 값으로 기존 연간 목표·전망 표를 덮어쓰지 않는다.
`report_item_breakdown`은 `comparison=SAME_SUBMISSION_PLAN_VS_ANNUAL_OUTLOOK`, `availableCount`,
`sourceLimit=100`, `sourceLimitMayApply`, `limit`, `items`를 반환한다. 기존 전역 손익 원천 상한을
적용한 뒤 조직·항목 필터를 적용하므로 `availableCount`는 확보한 자료 수일 뿐 전체 계정 수가 아니다.
원본 계정(account_item)·전표가 아닌 조직×보고 항목(report_item) 집계이며, PLAN이 불완전하면 차이를
영으로 대체하지 않는다. 원천 금액과 상태는 변경하지 않고 조사 capture/save도 호출하지 않는다.

### 경영기획 업무에서 아직 연결하지 않은 범위

현재 도구는 업무 도구 18개와 현재 관찰을 더 읽는 `observation_detail` 1개다. 조회 결과가 있다는 이유로
소명 회수·승인·보고서 발행까지 완료했다고 표현하지 않는다.

| 업무 | 현재 지원과 남은 연결 |
| --- | --- |
| 기준월 실적과 연간 전망 검토 | `monthly_pnl`과 기존 YEAR 도구로 구분한다. 여러 월의 추세·서로 다른 마스터 간 시계열 조회는 별도 기준 선택 계약이 필요하다. |
| 조직별 손익 항목 조사 | `report_item_breakdown`으로 보고 항목 집계를 읽는다. 원천 계정·전표 행이나 Excel 셀 상세는 연결하지 않았다. 기존 `ReviewEvidenceService.evidence/versions`를 연결하려면 고정 제출본 소속·조회한 reviewItemId 범위와 파일·상태 토큰 제거가 필요하다. |
| 소명 사유와 처리 이력 확인 | 승인 소명에 더해 `explanation_overview`로 전사 분류 집계·수락 전 항목·페이지 상세를 제공한다. 기존 `ReviewItemQueryService.detail`의 모든 회차·미회신·첨부·재요청 상태는 전용 도구로 연결하지 않았다. 수락 전과 미회신은 다르다. |
| 월마감 현황·조직별 할 일 | 기존 `HomeQueryService.status/todos` API는 있으나 Agent 도구에 연결하지 않았다. 확정 마스터 분석과 현재 진행 요청의 기준을 구분해야 한다. |
| 보고서 파일 생성 | 기존 저장 틀·값 채우기·XLSX 내보내기를 연결했다. Agent 경로는 해당 월 최신 마스터와 일치하는 기본 구조화 블록만 지원한다. 틀 작성·AI 편집·SQL 지표·다기간·구마스터 보고서 및 외부 발송은 미연결이다. |
| 소명 요청·과제 배정·승인·실행 추적 갱신 | 기존 업무 API는 있으나 Agent는 조회와 요청 초안만 만든다. 상태 변경은 사람 확인·권한·revision·감사 이력과 연결해야 하며 현재 운영 DB 읽기 전용 범위에서 실행하지 않는다. |
조회 전 fixed master 원천과 snapshot 수치 일치 확인. 현재 전망과 조회시점 비교값을 구분한다.
승인·변경·임의 SQL·URL 접근·임의 후보 금액 입력 도구는 없다.

### 보고서 근거와 임시 다운로드

`report_preview`는 기존 `/v1/data`의 `dataStatus`, `blocks`, `source`를 유지한다.
`source={version,year,month}`는 실제 조회 결과이며 원천이 확인되지 않으면 null이다.
Agent는 CONFIRMED와 선택 마스터의 해당 월 최신 원천 일치를 요구하고, 조회 전후에 원천 매핑·
재무값·검토/소명/분류의 fingerprint를 확인한다. AI의 `AI_DATABASE_URL`과 Spring DB는 같은 원천을
가리켜야 한다. 응답의 버전 이름만으로 다른 DB에 같은 이름의 버전이 없는지 증명하지는 않는다.
계획·전월·전년 비교와 소명은 조회 시점 자료이며 마스터 발행 당시 동결본이라고 주장하지 않는다.

미리보기는 templateId/templateVersion/templateName/blockId, masterId/year/month,
sourceVersion/sourceFingerprint/templateFingerprint/capturedAt/saved:false를 함께 가진다.
fingerprint는 서버 대조용이며 모델 문맥·인용에서 제외한다. 저장 틀의 문장은 독립적으로 검증된
재무 사실이 아니며 누락된 칸은 누락으로 남긴다. SQL/metricId/query, 다른 월·다기간 및 월별 추이
블록은 Agent 경로에서 거부한다. 기존 보고서 화면의 지원 범위를 변경하지 않는다.

`report_export`는 성공한 미리보기 관찰 ID를 요구한다. 원천·틀이 바뀌면 다시 조회하도록 안내하고,
성공하면 artifactId/filename/contentType/byteSize/expiresAt/ready/format/masterId/templateId/
templateVersion/previewObservationId/saved:false를 반환한다.
XLSX의 소수·15자리 초과 정수는 원문 텍스트 셀로 보존하고 15자리 이하 정수만 숫자 셀로 기록한다.
금액을 정수로 반올림하거나 Excel의 숫자 정밀도에 맞춰 줄이지 않는다. 텍스트 셀은 수식용 숫자와 구분한다.
`GET /api/v1/decision-agent/report-artifacts/{artifactId}`는 현재 활성 PLANNER이면서 생성 소유자인
경우에만 XLSX를 내려준다. 무인증·권한 철회·다른 소유자·만료를 거부하며 응답은 no-store다.
TTL은 600초, 파일당 5MiB, 사용자당 5개, 전체 20MiB/100개다. DB·디스크 저장이 없으므로 재시작 시
사라지며 다중 인스턴스 운영에는 같은 인스턴스로의 라우팅 또는 별도 공유 저장 계약이 필요하다.
프론트는 동일 기준의 성공 미리보기와 영수증을 대조한 후 사용자가 누를 때 인증 GET을 실행한다.
최종 설명이 실패해도 실제 성공한 미리보기·파일은 부분 결과로 유지한다.

### 전사 소명 현황

`comparison`은 ACTUAL_VS_PLAN/ACTUAL_VS_PREVIOUS/ESTIMATE_VS_PLAN/
ESTIMATE_VS_PREVIOUS다.
전사 값은 summary/categoryTotals/gapTotalWon/classifiedTotalWon이고, 최상위 acceptedCount/
reviewingCount와 items/reviewing만 선택 조직을 따른다. 두 상세 배열에 offset/limit을 각각 적용하며
hasMore/nextOffset으로 추가 페이지를 알린다. summary는 acceptedCount/reviewingCount/
unclassifiedAcceptedCount/fullyClassified를 가진다.

실적 비교는 기준월, 추정 비교는 다음 월이며 itemYear/itemMonth/itemScenario로 실제 대상 기간을
명시한다. 12월 추정은 hasApplicablePeriod=false로 대상 기간이 없음을 표시한다. categoryTotals의
subtotal=true는 하위 분류의 소계여서 다시 더하지 않는다. Gap은 항목의 부호 있는 비교 차액이며
영업이익 효과·절감액이 아니다. 미분류의 양수·음수 상쇄로 matchesGapTotal=true가 될 수 있어
완료 판정은 fullyClassified와 미분류·수락 전 건수를 함께 확인한다. reviewing은 수락된 소명이
없는 항목이지 미회신을 뜻하지 않는다. 현재 월 조회만으로 반복 원인이나 미래 위험을 확정하지 않는다.

## 모델 종료 및 공개 응답

모델은 먼저 로컬 `plan_analysis`로 현재 사용자 요청의 실제 부분 인용과 확인할 항목을 선언한다.
그 계획은 읽기 Tool 호출 기록을 꾸며 만들지 않으며 DB에 저장하지 않는다. 근거를 읽은 뒤에는
`finish_answer`로 질문별 요약·사실·해석·검토 가설과 각 요구 항목의 해결 여부를 제안한다.
모델이 제안할 입력 예:

```json
{"summary":"선택한 조직의 계획 차이를 확인했습니다.","findings":[{"id":"f1","kind":"FACT","text":"해당 조직은 계획 대비 이익이 감소했습니다.","citationIds":["cite-obs-1-1"],"uncertainty":null}],"requirements":[{"id":"r1","status":"ANSWERED","findingIds":["f1"],"missingInformation":null,"followUpQuestion":null}],"nextActions":[]}
```

AI 서비스는 이번 실행의 성공 관찰로 인용 목록을 만들고 모델에는 근거 ID·범위·제한된 원문 문맥과
추가로 읽을 상세 경로를 제공한다. 숫자·인용 경로를 직접 작성하지 않고 ID를 선택하며 설명은
실제 근거에 연결한다. INTERPRETATION·HYPOTHESIS는 한계를 함께 작성한다.
서비스가 선택 ID를 원장 기반 `{observationId,path,quote}`로 복원한 뒤 검증한다.
공개 `observations`는 모델용 요약으로 대체하지 않고 실제 원문을 유지한다.

복원된 path는 RFC6901 JSON pointer이며 관찰 객체 전체를 루트로 한다. quote는 해당 scalar의 정확한
문자열 표현 또는 일반 원문 문자열의 부분 인용이어야 한다. 숫자로만 구성된 십진 문자열은 부호와
모든 자릿수가 정확히 일치해야 하며 부분 인용하지 않는다. number·true/false/null도 전체 JSON 표현이
일치해야 한다. SUCCESS 관찰만 인용하고 미존재 경로·컨테이너·잘못된 ~ escape를 거부한다.
Spring은 context·toolCalls·observations 전체를 실제 메모리 원장과 비교하며 모델이 바꾼 값이나
가짜 호출을 거부한다. 모델이 제공한 limitations는 공개하지 않고 서버의 고정 한계 안내를 사용한다.
새 `brief`가 있으면 answer는 그 summary와 같다. 직접 생성한 금액·비율·결과 보장·실행 완료 주장은
거부하고 실제 인용한 이름 필드의 숫자 포함 조직명만 예외로 허용한다. 이전 계약 또는 실패에서는
기존 중립 안내와 성공 관찰을 유지한다. 근거 일치 검사는 설명의 의미나 가설의 진위를 자동 보증하지 않는다.
비용·매출·이익의 증감과 유불리는 Spring이 원래 비교값으로 판정한 관찰을 사용한다.
수치는 근거 인용과 결과 표에서 정확히 표시한다. nextActions는 위 허용 탐색 동작뿐이며 자동 저장하지 않는다.
요청 초안은 실제 review_request_draft 관찰을 근거로 화면에서 별도 확인한다.

공개 응답:

```json
{"mode":"MODEL","status":"COMPLETED","answer":"정성 요약","context":{},"toolCalls":[{"id":"call-1","tool":"company_pnl","label":"전사 손익 조회","status":"SUCCESS","observationId":"obs-1"}],"observations":[],"citations":[],"nextActions":[],"limitations":[]}
```

mode=MODEL 또는 UNAVAILABLE. status=COMPLETED/NEEDS_INPUT/LIMIT_REACHED/FAILED.
새 brief는 requirements 최대 6개·findings 최대 8개이며 finding당 성공 인용 1~4개와 종류를 가진다.
계획의 id·질문·요청 인용은 종료 때 변경할 수 없다. ANSWERED는 실제 finding ID가 필요하고,
미해결 항목은 부족 자료·확인 질문이 필요하다. 모두 ANSWERED일 때만 COMPLETED이며, 미해결이
있으면 NEEDS_INPUT이다. 성공 조회 한 건만으로 전체 질문의 해결 완료를 표시하지 않는다.
최상위 citations는 findings의 중복 없는 인용 합집합으로 최대 32개다.
모델 없음/실패를 자율 실행 성공으로 꾸미지 않는다. 관찰·실제 호출 이력은 남기되 추론 원문은 표시하지 않는다.
루프는 모델 최대 6회, 실제 도구 최대 8회, 전체 70초. 모델 한 번은 최대 40초와 남은 시간 중 작은 값으로 제한한다. 구조화 답변 작성에 필요한 시간을 확보하되 전체 마감과 호출 수는 늘리지 않는다.
동일 호출 재사용, 미허용 도구·인자 거부. finish_answer는 실제 도구 호출 수에 넣지 않는다.
실패·한도에서도 성공 관찰의 검증된 근거와 조회 표는 보존하지만 완료 상태로 바꾸지 않는다.
실패 뒤 근거 없는 답변을 만들지 않는다.


## Tool 결과 연결

Home 도구는 기존 DTO 필드를 유지하며 서버가 계산한 비교 요약을 추가한다. organization_pnl의 columns는 선택 조직으로 필터하지만
`total`은 전사 합계라는 한계를 표시한다. `approved_explanations`의 미저장 직접 읽기는
`data.capturedAt=null`이고 `basis.capturedAt`이 관찰 시점이며, 저장 Research는 포착 시각을 함께 반환한다.
제출 버전 목록은 기준월·APPROVED·master.asOf 이전이며 성공 관찰에 실제 반환된 ID만 비교에 사용할 수 있다.

`review_request_draft.data`는 `{analysisId,expectedAnalysisRevision,sourceKind,sourceId,organizationId,
title,message,needsConfirmation:true}`다. 기한은 사용자가 입력하고 화면에서 확인한 뒤 기존 업무 API로
초안을 생성·발행한다. 도구 실행은 해당 API를 호출하지 않는다. scenario_preview는 선택 분석의 저장
후보만 계산하며 빈 candidateIds는 후보 없이 기준 전망만 계산한다.

`goal_preview`는 목표 연도 `targetYear`(2000~9999 정수)와 양수 억 원 문자열을 모두 받는다.
연도 누락·잘못된 형식은 거부하고, 목표 연도가 고정 마스터 연도와 다르면 원천 재조회·계산 전에
`ERROR` 관찰로 기준 선택 또는 목표 연도 확인을 요청한다. 모델은 사용자가 명시한 목표 연도를
우선하고 불일치가 해결되지 않으면 `NEEDS_INPUT`으로 종료한다. 서버는 전달된 연도와 기준의
일치 여부를 검사하며 자연어 해석 자체의 정확성을 자동 보장하지 않는다.
금액은 소수 최대 8자리로 받고 정확히 원으로 환산한다.
기존 원 금액의 24자리 상한을 적용한다. 반환값은 목표, 현재 연간 전망, 부족액, 초과 전망액,
부호 있는 차이와 `forecastMeetsTarget`, `saved:false`다. 전망이 목표 이상이라는 판정은
실제 목표 달성이나 추진 승인을 의미하지 않는다. 이 도구는 저장 분석이 없어도 사용할 수 있다.
