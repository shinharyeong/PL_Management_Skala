# 보고 자료 작성 계약

보고 작성은 `/api/v1/report-authoring` 아래에서 제공한다. 모든 경로는 기존 Bearer 인증을 통과한 `PLANNER` 전용이다. 제출·소명 원문·공식 마스터를 수정하지 않으며 보고 자료의 정의·계산 결과·분류만 별도 저장한다. V10이 추가하는 업무 테이블은 `report_metric`, `report_template`, `report_template_version`, `report_reason_version`, `report_material`, `report_material_version`, `report_authoring_proposal`이다. `report_authoring_guard`는 사유 버전 동시 저장 직렬화용 기술 테이블이다.

## 명령과 조회

DB를 변경하는 POST 명령은 `Idempotency-Key`가 필수다. 현재 사용자·역할을 DB에서 재확인하고 명령 결과와 업무 변경을 같은 트랜잭션에 저장한다. AI 제안·SQL 조회·MCP는 저장 명령이 아니며 멱등키를 요구하지 않는다.

| 경로 | 입력·반환 |
| --- | --- |
| `GET /catalog` | 기본·사용자 지표 SQL, 기간 규칙, CSS 글꼴 목록, 알려진 요청 기간, AI 연결 설정 여부 |
| `GET /templates`, `GET /materials` | `{items}`. 자료 목록은 현재 버전의 기준 연·월 포함 |
| `GET /templates/{id}?version=` | 현재 또는 지정 틀 버전과 전체 버전 목록 |
| `POST /templates` | `{name,blocks}` → 첫 틀 버전 |
| `POST /templates/{id}/versions` | `{expectedRevision,name,blocks}` → 불변 새 틀 버전 |
| `POST /materials` | `{name,baseYear,baseMonth,templateId?,expectedTemplateRevision?,blocks?}` → 첫 자료 버전. 틀을 사용하면 확인한 회차 필수이며 최신 틀 회차가 바뀌면 거부. `results=[]`; 계산은 미리보기에서 실행 |
| `GET /materials/{id}` | 현재 정의·저장 결과·버전 목록 |
| `GET /materials/{id}/versions/{revision}` | 과거 정의와 당시 결과. 최신 금융값으로 재계산하지 않음 |
| `POST /materials/{id}/preview` | `{expectedRevision,name?,baseYear?,baseMonth?,blocks?}` → `{proposalId,expiresAt,blocks,results,...}`. 현재 자료는 변경하지 않고 본인용 30분 제안 저장 |
| `POST /materials/{id}/apply` | `{expectedRevision,proposalId}` → 미리보기 그대로 새 버전. 만료·타인·다른 자료 제안 거부 |
| `POST /materials/{id}/undo` | `{expectedRevision}` → 직전 버전의 정의·결과를 새 버전으로 복원. 기존 이력 삭제 없음 |
| `GET /materials/{id}/file?version=` | 자료 하나의 고정 버전 HTML. 표·BAR/PIE·블록 폭/글꼴·사유·출처 포함. PDF/PPTX를 생성했다고 간주하지 않음 |
| `GET /reason-types?version=` | `{version,items,versions}`. 최초 미등록 버전은 0 |
| `POST /reason-types` | `{expectedVersion,items:[{id,name,parentId?,active?,keywords?}]}` 전체 계층의 새 불변 버전. 순환·없는 부모·중복 ID 거부 |
| `POST /metrics` | `{name,sql,baseMetricCode?}` → `CUSTOM:{id}`. SQL의 단일 원지표 필터와 원지표 일치 필수; 등록된 SQL은 불변 |
| `POST /query` | `{baseYear,baseMonth,block}` 또는 `blocks` → 저장 없는 실제 보고뷰 결과 |
| `POST /mcp` | 인증된 HTTP JSON-RPC `initialize`, `tools/list`, `tools/call(query_report_view)`만 제공. 도구는 `/query`와 동일한 읽기 경계 |
| `POST /ai/sql` | `{question,block?,baseYear?,baseMonth?}` → 검증할 SQL 초안. 자동 실행·사전등록 없음 |
| `POST /ai/edit` | `{instruction,blocks}` → 확인할 편집 구성. 자동 적용 없음 |
| `POST /ai/pdf` | multipart `file`, `maskNumbers=true/false` → 미확정 `{blocks,pages,maskApplied,warnings}` |

## 블록과 기간

블록은 1~30개이며 필수 `id,title`과 다음 속성을 가진다.

틀 저장·새 틀 버전에서는 자료별 `reasonMappings`를 빈 배열로 정규화한다. 다음 달 재사용할 때 과거 소명 ID가 새 자료의 분류로 이어지지 않으며, 이전 분류는 전월 자료의 미확정 추천으로만 제공한다.

- `type`: `TEXT/TABLE/CHART/GAP/LIST`
- `metricCode`: `REVENUE/COST/OP/CUSTOM:{id}`. 사용자 지표도 확인된 원 지표 하나에 속한다.
- `scenario`: `PLAN/ACTUAL/ESTIMATE`
- `periodRule`: `CURRENT_MONTH/PREVIOUS_MONTH/NEXT_MONTH/YTD/FULL_YEAR/OFFSET/FIXED/ROLLING`
- `OFFSET`은 `offsetMonths`, `FIXED`는 `fixedYear/fixedMonth`, `ROLLING`은 기준월까지 `windowMonths`(1~24)를 사용한다.
- `comparisonBasis`: `PLAN/PREVIOUS/YEAR_AGO`. `PREVIOUS`는 **전월 요청의 마지막 승인본에 담긴 동일 대상 월 ESTIMATE**다. 이전 달 금액 비교와 혼동하지 않는다.
- `organizationIds`: 빈 배열은 해당 연간 요청의 전사 필수 조직. 선택 조직이 존재해도 해당 기간 대상에 없으면 누락으로 표시한다.
- `chartKind`: `BAR/PIE`. 음수 또는 합계 0의 원형 차트는 만들지 않고 원금액 표를 유지한다.
- `sql`: 비워두면 지표사전 SQL. `text`는 사용자 본문이며 AI가 금액을 생성하는 통로로 사용하지 않는다.
- `style`: 글꼴·크기·폭·색상. 값은 HTML에서 이스케이프하고 실행 가능한 코드를 허용하지 않는다.
- `sourcePage/sourceBounds`: PDF 초안의 페이지(1부터)와 `[x,y,width,height]` 0~1 상대 좌표. 사용자 확인 전에는 자동 확정하지 않는다.
- `reasonMappings`: `{explanationId,reasonTypeId,amount?}`. amount 미지정은 해당 보고 기준의 검증된 Gap 기여액이다.

공식 ACTUAL 마스터와 확정 PLAN 참조가 있으면 그 불변 출처를 사용한다. 없으면 같은 요청의 검증된 APPROVED/PENDING 제출본을 잠정 출처로 명시한다. 직전 추정 비교는 APPROVED만 허용한다. ESTIMATE는 공식 ACTUAL 마스터에 편입하지 않는다. 현재 또는 비교 출처가 잠정이면 결과는 `PROVISIONAL`이다. 조직·기간이 누락되면 `NO_DATA`, 전체 합계 null, 결과 rows는 빈 배열이며 부분합을 전사 확정값으로 내보내지 않는다.

## SQL과 MCP 경계

사용자 SQL은 원본 DB에 전달하지 않는다. 인증된 기간·조직의 금융값으로 만든 메모리 `report_view`에서 제한 문법을 실제 평가한다.

```sql
SELECT year, month, organization_name, SUM(amount) AS amount
FROM report_view
WHERE metric_code = 'REVENUE'
GROUP BY year, month, organization_name
```

열은 `year,month,scenario,organization_id,organization_name,report_item_id,report_item_name,metric_code,amount`다. `SUM/AVG/MIN/MAX(amount) AS 별칭`, 차원 `=` 및 `AND`, `GROUP BY`, 조회열 하나의 `ORDER BY`, `LIMIT 1~1000`을 지원한다. JOIN·서브쿼리·원본 테이블·임의 함수·세미콜론·주석·쓰기 문은 거부한다. 서로 다른 지표를 무조건 합산하지 않도록 `metric_code` 필터를 필수로 한다. `valueColumn`이 차트 금액의 실제 조회 별칭이다.

SQL WHERE가 선택한 원천행과 동일한 업무키로 비교·소명 범위를 제한한다. SQL 지표와 결과 합계는 같은 집계 연산을 사용한다. 개별 소명 금액 합계로 대사할 수 있는 것은 SUM 지표이며 AVG/MIN/MAX·혼합 지표는 `reasonAllocationAvailable=false`와 경고를 반환한다. 혼합 집계의 대표 total은 null이다.

MCP는 보고뷰 읽기 도구를 제공하지만 운영 DB의 별도 읽기 계정으로 임의 SQL을 실행하는 구현은 아니다. 현재 API와 동일한 DB 연결에서 허용된 쿼리만 실행하고 read-only 트랜잭션을 사용한다. 운영 별도 읽기 계정·연결풀 분리는 배포 설정 작업으로 남는다.

## 사유 분류와 대사

선택 출처·기간의 현재 ACCEPTED 회차만 사용하며 `EXCLUDED`는 제외한다. OP에서는 동일 조직·월에 상세 수용 소명이 있으면 매출 증가를 +, 비용 증가를 −로 기여시키고 OP 소명을 중복 사용하지 않는다. 상세 소명이 없으면 OP 소명 자체를 사용한다. 자료 비교 기준으로 재계산한 `gapAmount`와 제출 검토 당시 `reviewGapAmount`를 구분하고 다르면 경고한다.

활성 말단 사유유형만 직접 분류할 수 있다. 상위는 하위 합으로 계산하고 `aggregate=true`를 표시한다. 분류 합계와 전체 Gap 차이는 `unclassifiedTotal`로 남긴다. 빠진 사유를 AI로 지어내거나 자동 배분하지 않는다. 분류결과와 사유버전은 보고자료 버전에만 보존하며 `review_explanation`을 갱신하지 않는다.

전월 저장 자료의 동일 조직·보고항목 분류와 사용자가 설정한 정확한 키워드 규칙을 미확정 후보로 제공한다. `reasonSuggestion={reasonTypeId,source,materialId?,revision?}`, `suggestedReasonTypeId`가 그 후보다. 후보만으로 `reasonMappings`를 자동 저장하지 않는다. 원본 계정과 보고 항목은 같다고 가정하지 않는다.

## AI 연결과 PDF 보호

백엔드 `REPORT_AI_URL`, 백엔드/AI 서비스 공통 `REPORT_AI_TOKEN`, AI 서비스의 기존 `OPENAI_API_KEY/OPENAI_MODEL` 설정을 사용한다. 기존 로컬 compose에는 AI 서비스가 없으므로 별도 AI 프로세스 주소를 연결해야 한다. URL·토큰만 있는 catalog의 `aiConfigured`는 전송 설정 여부이며 실제 모델 사용 가능성은 제안 응답에서 확인한다. 키값을 응답·로그로 반환하지 않는다.

새 Python 경로는 기존 배치·SQLite 챗봇·파일 캐시를 호출하지 않는다. 외부 AI에는 사용자 질문·보고 구성 또는 사용자가 분석 요청한 PDF만 전달한다. 보고뷰 금융값·소명 원문·Data 파일을 자동 전달하지 않는다. Responses API의 `store=false`, 제한 출력·시간초과, 내부 공유 토큰을 사용한다. 모델의 숫자 본문 변경 제안은 거부하고, SQL/블록 초안은 Java 경계에서 다시 검증한다.

AI 편집은 입력과 동일한 블록 ID 집합을 유지한다. 원본 또는 제안의 `text`에 Unicode 숫자 문자가 있으면 본문 전체가 원문과 같아야 한다. 숫자의 추가·삭제뿐 아니라 부호·괄호 음수·퍼센트·단위·순서·주변 문구 변경도 거부한다. 숫자 본문을 수정하려면 사용자가 직접 편집한다. 같은 ID 집합의 블록 순서·서식·기간 변경과 숫자 문자가 없는 본문 편집은 허용한다. 이 검사는 `text` 필드의 문자 보존 규칙이며, 한글로 풀어 쓴 수량·제목·다른 필드의 숫자 의미 전체를 검증하는 기능은 아니다. 잘못된 블록 구조·ID 변경·블록 누락·중복은 수동 작업 안내로 반환한다.

`maskNumbers=true`는 원 PDF를 복사하지 않고 추출된 비숫자 텍스트 위치만 새 메모리 PDF에 재구성한다. Unicode 숫자 문자가 있는 토큰은 사각형으로 대체하고 이미지·벡터·숨은 원문·원본 메타데이터는 전송하지 않는다. 한글로 풀어 쓴 수량·날짜 등 의미 분석에 의한 익명화를 보장하는 기능은 아니다. 따라서 원본 차트 모양과 남은 문구는 사람이 확인해야 한다. 텍스트 없는 스캔 페이지, 암호, 30쪽 초과, 마스킹 실패는 외부 전송을 거부하고 직접 작성 경로를 안내한다. `false`는 사용자의 명시 선택으로 원 PDF 분석을 요청한다. 16MiB 제한이다. PyMuPDF 의존성은 manifest에 추가했고 사용자가 허용한 `backend/build/ai-validation`의 검증 가상환경에만 설치했다.

AI 미설정·실패는 `configured=false`와 경고로 반환한다. 빈 성공 자료를 대신 만들거나 자동 저장·적용하지 않는다. 수동 블록·SQL·사유 편집과 버전 저장은 AI 없이 동작한다.

## 함수와 호출 관계

| 소스 | 주요 함수와 호출 |
| --- | --- |
| `ReportAuthoringController` | catalog/list/get → Service 조회. create/templateVersion/preview/apply/undo/saveReasons/metric → `command` → `CommandExecutor` → Service. file → `ReportMaterialExport.write`. ai/pdf → `ReportAiClient.propose` → 결과 재검증. mcp → Service.query |
| `ReportAuthoringService` | create/templateVersion/preview/apply/undo 버전 전이, validateBlocks 구조·SQL 검사, reasons/saveReasons 계층·이력, saveMetric 불변 SQL사전, query 저장 없는 보고뷰, insertMaterial 불변 버전 저장 |
| `ReportFactService` | evaluate → evaluateBlock → periods/organizationIds/facts → SELECT 원금액, `ReportQueryEngine` → 동일 범위 집계. explanations/itemAmount → 기준 대사. suggestReasons → 과거 자료·키워드 후보 |
| `ReportQueryEngine` | execute 문법·집계·정렬·행한도, filters/selectedFacts 같은 SQL범위, aggregation 동일 집계연산 |
| `ReportAiClient` | configured 설정 존재, propose 내부 서비스 호출/실패 경로, unavailable 수동 작성 안내 |
| `ReportMaterialExport` | write → table/chart/escape. 저장 결과만 메모리 HTML로 직렬화 |
| `ReportAuthoringJson` | JSON 변환·입력 범위·공개 오류 유틸리티 |
| `ai/app/api/report_authoring.py` | propose_sql/edit/pdf → _authorize → _generate. 편집은 _validate_edit_blocks로 동일 ID 집합과 숫자 본문 원문을 확인하고 PDF는 _masked_pdf로 원문 전송 범위를 제한. 외부 AI·로컬 파일 저장 분리 |
| `_validate_edit_blocks(originals: list[dict[str, Any]], proposals: object) -> str \| None` | 편집 전 블록과 모델 제안 → 위반 안내 또는 None. 숫자 문자가 포함된 text 전체를 비교하고 본문 외 서식·기간·순서는 제한하지 않음. 잘못된 응답은 예외 대신 안내로 반환하며 입력·파일·DB를 변경하지 않음 |

각 함수의 입력·반환·예외·부작용은 소스의 한국어 명세를 따른다. 테스트는 합성 H2·메모리 숫자·모의 AI를 사용하며 실제 Data/외부 API를 사용하지 않는다. 실행 결과는 실제 수행된 검증만 별도로 기록한다.

2026-11-18 신규 Python 통합 테스트 6개가 통과했다. FastAPI TestClient와 실제 PyMuPDF 2.1을 사용해 합성 PDF의 숫자·메타데이터 제거, 스캔 미전송, 명시 비마스킹, 내부 인증·키 미설정, 본문 숫자 변경 거부, 미확정 SQL·잘못된 JSON 처리를 검사했다. Responses 클라이언트는 전부 모의이며 실제 OpenAI 호출·사용자 데이터 전송은 하지 않았다. pytest cacheprovider·플러그인 자동 로드·Python 바이트코드를 끄고 캐시·가상환경·임시는 승인된 build 하위에 한정했다. PyMuPDF SWIG 관련 deprecation 경고 5개가 있었고 실패는 없었다.
