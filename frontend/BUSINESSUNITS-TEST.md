> 2026-11-24 갱신: 실행 백엔드·파서는 `/private/tmp/pl-backend-latest-20261124`의 `c7e8a8c`입니다. 기존 DB/저장소/18183 포트를 유지합니다. 아래 최초 적재 상태·숫자는 초기화 전 이력입니다. DB는 이후 사용자가 다시 요청·제출하며 변경합니다.
> 홈 YEAR는 최신 서버 기준 1~기준월 실적 + 남은 달 추정의 연간 전망입니다. 프론트 `.xlsb` 선택을 허용했습니다. 기존 manifest 한정 scope adapter는 유지합니다. 원본 파서는 최신 경로를 사용합니다.
> 업그레이드 전 백업: `backups/pl_business_units_v2_before_upgrade_20261124.dump`. 마이그레이션 `20261124.1400` 및 financial_line 이름 컬럼 4개 적용 확인. 프론트 33 통과, 백엔드 28 통과/41 생략, 파서 287 통과/3 생략, adapter 4 통과. 실제 경영기획팀/사업부1 인증·조회 API 정상 확인.

> 현재 상태 (2026-11-24 재테스트 초기화): 요청·제출본·소명·알림·연간/월간 master는 0건입니다. 계정 20개, 조직 19개와 기본 설정은 유지했습니다. 아래 적재/승인 결과는 초기화 이전 검증 기록입니다. 다시 테스트하려면 요청 발송부터 시작합니다. 원본 엑셀과 저장소 실제 파일은 삭제하지 않았습니다. 초기화 전 전체 백업: `backups/pl_business_units_v2_before_reset_20261124.dump`.

# BusinessUnits v2 화면 검증 데이터

2026-11-24. 사용자가 Downloads에 받은 `BusinessUnits_01_17_Gap_Explanation_v2` 자료로 실제 로컬 화면을 검증한다. 경계값 기준은 사용자 결정에 따라 `>=`이다.

## 현재 연결

- 프론트: http://127.0.0.1:14175/
- 백엔드: http://127.0.0.1:18183/
- DB: 별도 로컬 PostgreSQL `pl_business_units_v2` (127.0.0.1:15439).
- 파일 저장소: `/private/tmp/pl-businessunits-v2-storage`.
- 서버 로그: `/private/tmp/pl-businessunits-v2-backend.log`.
- 경영기획 계정: `kim.plan@sk.com`.
- 조직 계정: `f10.test@sk.com` 등 각 파일 조직코드를 소문자로 쓴 뒤 `.test@sk.com`. 사업부15는 `f66.test@sk.com`이다. 모두 이 검증 DB 전용 계정이다.
- 이전 합성 DB `pl_monthly_qa`와 원본 57개 Excel은 보존했다. DB·원본·로그·인증정보는 Git에 추가하지 않는다.

## 준비된 상태

실제 업로드·파싱·검증·제출·소명 수락·제출본 승인 API로 다음을 만들었다.

1. 17개 조직의 2026년 연간 계획 승인·확정.
2. 17개 조직의 10월 기준본 승인 및 10월 마스터.
3. 17개 조직의 11월 최초 제출 승인 및 11월 마스터.
4. 사업부15는 최초본 승인 후 제공된 최종 파일을 다시 제출했다. 최신 제출본 `2026-11-02`는 PENDING으로 남겼고, 홈은 이전 승인본이 들어간 마스터를 유지한다. 재제출본을 승인하기 전까지 홈 금액이 자동으로 바뀌지 않는다.

선정·사업부 탭·소명 요청 테스트는 **월간 시트 관리 → 사업부15 → Gap 계산하러 가기**에서 할 수 있다. 승인 완료된 조직의 계산 결과는 읽기 전용이다.

추가 재제출·소명은 자동화할 때 검증용이라고 명시했고, 원본 시나리오의 사유를 사용했다. 임의 사업 원인을 생성하지 않았다. 오류·대조군 파일은 정상 제출 묶음에 넣지 않았다.

## 요청 범위 선택 어댑터

원본 Excel에는 2025~2027년의 여러 구분이 공존한다. 현재 백엔드 `FinancialIngestionService`는 요청 연도 외 열을 `PERIOD_OUT_OF_RANGE`로 거절한다. 첫 원본 연간 파일은 이 이유로 DRAFT 검증 실패 상태로 남아 있다.

이를 숨기거나 승인 상태를 SQL로 강제하지 않았다. 별도 검증 서버에만 `scripts/dataset-scope-python.py`를 `PARSER_PYTHON`으로 지정했다.

- 변경 없는 실제 Python 파서를 실행한다.
- 이 자료의 manifest와 SHA-256이 일치하는 입력에만 적용한다.
- 원본 바이트와 sourceSha256, 금액 문자열, 셀 주소, 진단은 유지한다.
- 연간 제출은 요청 연도 PLAN, 월간 제출은 해당 연도 PLAN·마감월까지 ACTUAL·이후 ESTIMATE를 submissionData에서 선택한다.
- 생산 백엔드·파서 소스나 Excel을 수정한 것이 아니다. 일반 업로드의 완전한 호환성을 검증한 결과로 간주하지 않는다.

프론트에서 같은 데이터셋 원본 파일을 다시 업로드해도 이 검증 서버에서는 같은 선택을 적용한다. manifest에 없는 파일에는 적용하지 않는다. 서버 재시작 시 이 설정을 빼면 원본의 기간 오류가 다시 나타난다.

## 검증

- 원본 57개 파일의 manifest SHA-256 보존을 확인했다.
- 월·연간 홈의 매출·영업비용·영업이익이 예상 금액과 승인된 원본 합계에 Decimal 단위로 일치했다. 사업부15·17은 최종 파일과 구분해 최초 승인 파일 금액을 사용했다.
- 홈·사업부 표·워터폴·영업이익 변동 응답 모두 CONFIRMED이며 사업부 표에 17개 조직이 있다.
- 현재 백엔드 YEAR는 1~11월 누계 ACTUAL이다. 화면에 누계 실적으로 표시하며 미래 ESTIMATE가 합산된 연간 전망이라고 부르지 않는다.
- 프론트 단위·회귀 테스트 29개 통과. 요청 범위 선택 어댑터는 별도의 Python 테스트 4개로 정밀 금액·빈값·원본·진단 보존을 검사했다. `>=` 경계값과 금액 정밀도를 포함한다.

다시 읽기 검증만 할 때:

```bash
python3 -B scripts/load-business-unit-dataset.py \
  --source <데이터셋 절대 경로> \
  --repository /private/tmp/pl-management-frontend-20261124 \
  --local-auth-log /private/tmp/pl-businessunits-v2-backend.log --verify-only
```

`--apply`는 검증 DB에 업무 이력을 생성한다. 이미 만들어 둔 데이터로 화면만 확인할 때 다시 실행할 필요가 없다.

브라우저에서 홈 KPI(매출 537.5482억원, 비용 509.6882억원, 영업이익 27.86억원), 월간 조직 17곳, 사업부15 회신·계산기 진입·조건 적용·계층 결과 1건을 확인했다.

## 현재 서버 시작 설정

백엔드 시작 환경은 `DB_URL=jdbc:postgresql://127.0.0.1:15439/pl_business_units_v2`, `DB_USERNAME=pl_qa`, `SPRING_PROFILES_ACTIVE=local`, `SERVER_PORT=18183`, `SERVER_ADDRESS=127.0.0.1`, `PARSER_SOURCE_DIRECTORY=/private/tmp/pl-management-frontend-20261124/parser/src`, `PARSER_PYTHON=<frontend 절대 경로>/scripts/dataset-scope-python.py`, `BU_DATASET_SCOPE_ROOT=<원본 자료 폴더>`다. 저장소는 `--app.storage.local-root=/private/tmp/pl-businessunits-v2-storage`로 지정했다.

프론트:

```bash
API_PROXY_TARGET=http://127.0.0.1:18183 npm run dev -- --host 127.0.0.1 --port 14175 --strictPort
```

### 로그인 화면에서 인증코드 확인

로컬 개발 화면에서 **인증 메일 받기**를 누르면 아래 **로컬 검증 환경**에 발급 코드가 표시됩니다. 재전송하면 새 코드가 표시되고 만료 시 숨깁니다.

Vite의 `.env.development.local`에 `LOCAL_AUTH_LOG=/private/tmp/pl-businessunits-v2-backend.log`가 설정되어 있습니다. 서버 로그 위치를 바꿨다면 이 값도 바꾸고 개발 서버를 재시작합니다. `API_PROXY_TARGET`도 로컬 백엔드를 가리켜야 합니다. 이 기능은 Vite 개발 서버에서만 동작하며 운영 빌드에는 코드 조회 경로가 없습니다.
