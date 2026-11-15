# 규칙 기반 파서와 AI 구조 파서 비교

운영용 규칙 기반 파서와 독립 비교군인 AI 구조 파서를 동일한 입력·검증·평가 기준으로 실행한다. OCR/VLM이나 자동 운영 전환은 이번 구현에 포함하지 않는다. 진입점은 저장소 루트의 `python3 -B parser/compare.py`다. Python 3.11 이상과 표준 라이브러리를 사용하며 별도 설치는 필요 없다.

## 실행 경계

- 두 방법은 각각 `extract_workbook → validate_extraction → 해석 → validate_provenance`를 실행한다. 같은 추출 객체나 규칙 기반의 해석 결과를 공유하지 않는다.
- 규칙 경로는 기존 `map_workbook`, AI 경로는 기존 `infer_structure`를 그대로 호출한다. 구조 해석의 수식 비율 생략·블록·repair·review 정책을 벤치마크에서 몰래 수정하지 않는다.
- 원본 파일만 엔진에 전달한다. manifest의 조직·기간·정답 금액을 metadata 또는 프롬프트에 주입하지 않는다.
- 기본 입력은 manifest에 명시된 조직 파일이다. 마스터는 기본 제외하며 `--include-master`로 별도 입력·별도 집계에 포함할 수 있다. 조직과 마스터의 캐시도 분리한다.
- 기본 실행은 콘솔과 메모리만 사용한다. 기존 AI 디스크 응답 캐시와 구조 템플릿은 읽지도 쓰지도 않는다. Data는 항상 읽기 전용이다.
- `--allow-api`는 실제 외부 요청을 실행하는 선택이다. API 키는 환경 변수 또는 기존 `parser_ai/.env`에서 읽지만 출력에 포함하지 않는다.
- 파일별 처리 전후 및 엔진·반복 간 원본 해시를 확인한다. 불일치는 유효한 비교 완료로 표시하지 않는다.

## 명령

아래의 `/path/to/Data/dummy`는 기본 17개 조직과 마스터 및 `manifest_baseline.json`이 있는 실제 경로로 바꾼다. 현재 지원하는 manifest 버전은 `baseline_strict_native_input_only_v1`이다.

```bash
# API와 결과 저장 없이 실제 시트 분기·블록 수를 확인한다.
python3 -B parser/compare.py plan /path/to/Data/dummy --include-master

# API 없이 규칙 파서 1개를 실제 실행하고 평가한다.
python3 -B parser/compare.py run /path/to/Data/dummy \
  --engines rule --max-files 1

# 두 파서의 실제 비교. 이 명령을 실행하면 유료 AI 요청이 발생할 수 있다.
python3 -B parser/compare.py run /path/to/Data/dummy \
  --allow-api --max-files 1 --max-calls 20 --review off

# 같은 조건을 3회 반복하며 반복 시작마다 캐시를 비운다.
python3 -B parser/compare.py run /path/to/Data/dummy \
  --allow-api --repeats 3 --cache cold --max-calls 300
```

`--max-calls`는 파일당이 아니라 **실험 전체**의 캐시 제외 논리 호출 상한이다. HTTP 재시도는 별도이며 금액 상한이 아니다. 호출 상한에 걸린 파일도 실패·미처리 평가에 포함된다. 실제 과금은 모델·토큰·재시도에 따라 달라지므로 보고서는 비용을 임의 추정하지 않고 `cost_usd=null`로 남긴다. 응답 사용량이 없는 실패는 `usage_missing_calls`로 구분한다.

파일 저장을 명시적으로 원하는 경우에만 `run`에 `--write-output`을 추가한다. `parser/output/comparisons/<실행 ID>/comparison.json`과 `summary.csv`를 새로 만들며 기존 결과는 덮어쓰지 않는다. `--output-dir`을 지정해도 같은 허용 디렉터리 아래여야 한다. 잘못된 경로와 기존 경로는 API 호출 전에 검사한다. 셸 리다이렉션도 파일 생성이므로 저장을 의도한 경우에만 사용한다.

## 동일 조건과 캐시

기본 모델은 기존 AI 클라이언트의 설정을 따른다. 기본 추론 강도 high, review on, HTTP timeout 240초, 출력 토큰 상한 32768을 사용한다. 실제 값은 보고서에 남는다. `--model`, `--effort`, `--review`, `--timeout`, `--max-output-tokens`, `--max-exception-calls`로 조건을 명시한다.

- `cold`: 각 반복 시작 때 응답·구조 템플릿 캐시를 비운다. 같은 반복 안의 다음 조직 파일은 앞 파일의 결과를 재사용할 수 있다.
- `warm`: 반복 간 캐시를 유지한다. **첫 번째 반복은 빈 캐시**에서 시작한다. 이후에도 실제 캐시 적중 여부는 이벤트로 확인한다.
- 서로 다른 명령 실행 사이에는 메모리 캐시가 유지되지 않는다. 방법·모델·review 설정을 변경한 실험끼리 캐시를 섞지 않는다.
- 두 엔진은 동시에 실행하지 않는다. 파일과 반복에 따라 실행 순서를 교대한다. OS 파일 캐시는 강제로 비우지 않으므로 엄밀한 저장장치 cold-cache 실험은 아니다.

`plan`은 캐시 재사용과 실패가 없을 때의 작업량 계산이다. 실제 API 호출·모델 성공률·처리 시간 측정이 아니다. review 호출은 작업 수에 반영하지만 repair·재시도·연결 실패 후 재분할은 예측하지 않는다.

## 독립 평가 계약

### 기본 manifest 금액 합계 평가

manifest는 셀별 조직·항목·기간 정답이 아니라 매출·비용의 월별 및 연간 합계를 제공한다. 따라서 `financial_aggregate_evaluation`은 **금액 합계만** 평가하며 의미 정확도를 주장하지 않는다.

기본 더미 생성 계약에 따른 평가 전용 위치는 다음과 같다. 이 위치 정보는 엔진에 전달하지 않는다.

| 영역 | 시트 | 첫 상세 행 | 연간 합계 열 번호 | 월별 열 |
|---|---|---:|---|---|
| 매출 | 매출_세부_IFRS15後 | 5 | 8, 22, 36, 50 | 해당 연간 열 + 1~12 |
| 비용 | OpEx_세부_IFRS15後 | 6 | 17, 31, 45, 59, 73 | 해당 연간 열 + 1~12 |

기간은 prior, plan, outlook, next_plan이며 비용에는 next_projection이 추가된다. 파일마다 117개 합계 그룹이 있다. 각 그룹의 행 수는 manifest의 records를 사용한다. 원본의 상단 SUBTOTAL은 평가에 쓰지 않으므로 마스터 뒤쪽 상세 행도 분모에 포함된다. 정답 금액은 생성 계약의 천원에서 원으로 1000배 변환한다.

필수 셀 누락·중복·미확정·비금액 결과가 하나라도 있으면 해당 그룹은 incomplete다. 전부 들어오고 Decimal 합계가 일치해야 matching이다. 부분 매핑이어도 확정된 금액 자체는 합계 평가에 사용할 수 있으나 mapping 상태는 따로 기록한다. 의미가 틀린데 우연히 합계가 같은 경우는 이 평가만으로 탐지할 수 없다. 양식 변경 데이터에는 이 고정 위치 정답 계약을 재사용하지 않는다.

### 선택적 독립 셀 정답

`--truth /path/to/independent-truth.json`으로 JSON 배열 또는 `{"records": [...]}`를 제공하면 셀별 의미·금액 정확도도 계산한다. 없는 경우 정확도는 0이나 100% 대신 `null / not_scored`다. 예시는 파일 생성 명령이 아니라 계약 설명이다.

```json
[
  {
    "source_file": "example.xlsx",
    "sheet": "매출_세부_IFRS15後",
    "cell": "I5",
    "organization_code": "F10",
    "year": 2025,
    "month": 1,
    "aggregation": "month",
    "scenario": "actual",
    "normalized_value": "1000",
    "item_name": "독립 검토한 항목명",
    "source_unit": "천원"
  }
]
```

source_file은 파일 기본명이다. item_code, item_name, source_unit은 선택 필드이며 제공하면 비교한다. 필수 의미 필드는 조직 코드·연도·월·집계 구분·시나리오다. 출처는 파일명+시트+셀로 연결한다. 금액은 유한 Decimal로 비교하고 누락과 중복도 정답 분모에 포함한다. 전체 정답 판정에는 mapped와 confirmed가 필요하다. 미해결 의미에 올바른 금액만 있으면 value_exact로만 별도 보고한다. 정답 범위 밖 출력은 extra로 보고하며 자동으로 오류라고 단정하지 않는다.

정답은 독립적으로 검토해야 한다. 어느 한 파서의 결과를 그대로 정답으로 복사하면 해당 파서에 유리한 평가가 된다. 파일 일부에만 정답이 있을 때는 해당 파일·셀만 평가하며 셀 정답 수를 함께 표시한다.

## 보고서 해석

- `completed`: 추출·해석·출처 검증 단계의 연산 완료. 의미 정답 여부와는 다르다.
- `validation_passed`와 `validation.*.passed`: 원본 추출·출처 검사의 결과. 알려진 메모 불일치를 숨기지 않는다.
- `timings.total_seconds`: 원본 해시, 추출, 독립 원본 검사, 해석, 출처 검사 포함. 독립 정답 채점과 보고서 직렬화는 제외한다. 파일 저장을 하지 않는 두 엔진을 같은 조건에서 비교한다.
- `summary`: 방법·반복·조직/마스터 구분별 합계 시간, 파일별 중앙값·최대값, 금액 합계 일치율, 선택적 셀 정확도, 실제 호출 수와 관측 토큰.
- `source_consistent_across_trials`: 같은 파일의 엔진·반복 간 원본 동일성. false인 실험으로 성능 우열을 발표하지 않는다.
- `inference`: 현재 파일의 이벤트와 누적값 차이. API 요청 수와 HTTP 재전송 수를 구분한다. 원래 클라이언트에서 응답 파싱 실패 이벤트가 빠지는 경우도 사용량 미확인 호출로 기록한다.
- `conditions.implementation_sha256`: 두 파서와 평가기의 Python 소스 해시. 모델·추론 강도·review·캐시 조건과 함께 재현 조건으로 보관한다.

현재 기본 데이터의 `예산코드!A178 COMMENT_MISMATCH`는 검증 결과에 남기고 해석은 계속한다. CLI는 검증 실패가 있으면 결과를 출력하되 종료 코드 2를 반환한다. 현재 AI의 수식 비율 기반 OpEx 생략도 그대로 기록하고, 해당 필수 금액은 평가 분모에서 제거하지 않는다. 두 문제의 수정 전후를 비교하려면 별도 실험으로 구분한다.

비교 환경 연결 확인에서 실제 `01_F10_사업부1_재무제표.xlsx`와 `18_전체_마스터.xlsx`의 규칙 파서는 각각 비용 65개 그룹만 일치했다. 매출 헤더에 `사업부코드`가 없어서 기존 `mapping._layout`의 매출 조건을 통과하지 못하고, 매출 값이 단위 없는 일반 숫자로 남았다. 따라서 매출 52개 그룹은 금액으로 확정되지 않은 결과로 집계한다. 평가 전용 원본 금액 합산에서는 두 파일의 117개 그룹 모두 manifest와 일치하여 정답 범위 문제와 구분했다. 이것은 두 파일의 연결 확인 결과이며 전체 데이터 또는 AI와의 성능 비교 결과는 아니다. 비교 환경은 이 규칙을 수정하지 않는다.

## 검증

```bash
PYTHONPATH=parser/src:parser_ai/src python3 -B -m unittest discover \
  -s parser/tests -p 'test_benchmark_*.py' -v
```

합성 OOXML, 독립 정답, 대체 API 응답으로 실제 추출·두 해석 경로·검증·채점을 연결한다. 누락·중복·금액 오류·잘못된 의미·AI 부분 실패·캐시 재사용·정답 격리·원본 변경·출력 경로 보호를 검사한다. 실제 API의 정확도·속도 측정이나 전체 서비스 통합 검증을 대신하지 않는다.

## 함수 연결

`compare.py → cli.main → load_dataset/load_truth → plan_comparison 또는 run_comparison → run_rule_adapter/run_ai_adapter → score_manifest/score_records → summarize_trials` 순서다. 기본값은 콘솔 출력이며 save_report는 명시적 저장 옵션에서만 호출한다.

## 월별 검증의 오프라인 구조 계획 경로

`monthly` 하위 명령은 기존 `parser/compare.py`, 두 어댑터, 원본 추출·검증, 규칙 매핑, AI 구조 검증·적용을 재사용한다. 기본 더미 manifest의 합계 계약을 월별 자료에 임의 재사용하지 않고, 원본 요청 수집과 독립 마스터 상세 평가를 별도로 수행한다. 원본은 저장소 밖 `../Data/dummy/월별검증_2026`에서 읽고, 승인된 산출물은 저장소 안 `parser/output/comparisons/<새 실행 ID>/`에만 저장한다. 기존 실행 폴더와 `Data/`는 덮어쓰지 않는다.

실제 목록에는 `01`~`09`의 9개 유형이 있다. 유형별 2026년 1~9월, 월별 조직 17개와 마스터 1개가 있으며, 유형당 조직 153개·마스터 9개다. 실행 유형은 명령의 `--types`와 생성된 `inventory.json`으로 확인한다. 8개 유형이라는 요청만으로 파일 전체가 17개라고 가정하지 않는다.

### 수집·계획 작성·재생의 경계

1. `inventory_dataset`은 실제 경로와 파일 수를 확정한다. 원본 수집기에는 조직 파일 경로만 전달한다.
2. `collect_initial_requests`는 기존 AI 엔진의 `structure_context`, 시트 수식 비율, 행 블록 분할 함수를 사용한다. 지시문·JSON 스키마·원본 맥락·설정을 `requests/<request_hash>.json`으로 저장한다. 마스터·독립 정답·룰 결과·점수는 이 요청에 없다.
3. Codex 세션은 내보낸 요청만으로 기존 `PLAN_RESPONSE`에 맞는 구조 계획을 작성한다. `plans.json`의 계약은 `{request_hash: {"plan": ...}}`이다. 구조 계획에 금액 정답이나 실행 코드를 넣지 않는다. `codex-session-plan`은 요청 재현용 식별자이며 실제 API 모델 호출 이름이 아니다.
4. 계획을 고정한 뒤 `OfflineReplayClient.ask`가 정확히 같은 요청 해시의 응답만 반환한다. `engine.infer → validate_plan → apply_plans`의 기존 경로가 근거 포인터·스키마·영역을 검증하고, 기존 Python 로직이 현재 조직 원본 셀을 읽어 금액을 정규화한다.
5. 정답 변환·채점은 별도 평가기에서 수행한다. 채점 후 계획을 수정한 실행은 새 실험이며 첫 점수를 덮어쓰지 않는다.

해시는 기존 `ResponsesClient.ask`의 `PROMPT_VERSION + body` SHA256과 같은 방식이다. 시트명이나 표 모양만 비슷한 요청은 같은 요청으로 취급하지 않는다. `template_cache={}`는 파일마다 새로 만들어 이전 조직의 유사 구조가 계획 작성 누락을 가리지 못하게 한다. 계획 응답은 저장된 정확한 요청 해시로 재사용한다.

초기 수집은 응답 이전 요청을 모두 대상으로 하므로, 앞 블록 실패로 실제 재생에서 도달하지 못한 뒤 블록 요청도 포함할 수 있다. repair·review·exception은 응답에 의존하는 요청이며 초기 수집에 포함되지 않는다. 실제 재생의 요청 이벤트·실패와 초기 수집 목록을 구분해서 해석해야 한다.

### 고정 실행 조건과 실패 처리

- `review=False`, `max_exception_calls=0`, `allow_api=False`를 사용한다. 독립 모델 review와 예외 추가 해석은 수행하지 않는다.
- 기존 수식 비율 기준 `FORMULA_SKIP_RATIO=0.3`과 큰 시트 분할 기준은 유지한다. 생략 시트는 생략 이유·비율을 남기고 정답 분모에서 제거하지 않는다.
- 요청 해시가 없으면 `ModelError`로 기록한다. API 호출, 룰 결과 복사, 자동 계획 생성으로 대체하지 않는다. 기존 검증이 repair를 요구하면 그 정확한 repair 요청 계획도 별도로 있어야 한다.
- 오프라인 클라이언트에는 HTTP 전송 함수가 없다. 실행 범위의 `NetworkBlocker`도 HTTP와 소켓 연결을 송신 전에 차단하고 시도 건수를 기록한다.
- `inference.api_calls`와 `http_post_attempts`는 외부 요청만 뜻한다. `offline_requests`, `offline_plan_loads`, `offline_plan_missing`은 별도 재생 통계다. 미확정 레코드와 원본 출처는 기존 어댑터 결과에 보존한다.

### 저장과 시간 해석

`monthly export`에는 `--write-output`과 새 `--output-dir`이 필요하다. 요청 파일 외에 `inventory.json`, `request_catalog.json`, `source_capture.json`, `capture_execution.json`을 저장한다. 각 원본의 요청 해시 목록과 수집 전후 원본 해시, 실제 실행 명령, 구현 해시를 보관한다.

컨텍스트 수집, Codex 세션의 구조 계획 작성, 고정 계획 재생, 독립 채점을 구분한다. 재생 시간은 로컬 파서의 추출·검증·해석·원본 적용 시간이며 실제 API 방식의 전체 처리 시간이 아니다. 구조 계획 작성 시간과 API 토큰·API 지연을 관측하지 않았다면 수치로 추정하지 않는다. 병렬 실행의 벽시계 시간과 파일별 처리 시간 합도 서로 다르다.

오프라인 연결 확인에서는 실제 기존 `ResponsesClient` 요청 해시와의 일치, 기존 AI 검증·적용 경로 성공, 누락 계획 실패, 네트워크 차단, 외부 호출 계수 0을 메모리 자료로 확인한다. 전체 자료의 인식 성능은 별도로 저장된 실제 실행 결과로 판단한다.

### 독립 마스터 상세의 채점 계약

`monthly_truth.build_master_truth`는 생산 파서의 의미 매핑을 사용하지 않는다. 확인된 월별 원본의 상세 양식에 고정한 열 위치로 마스터를 읽고, 담당자 표기와 설명 ID의 조직 코드를 교차 확인한다. 조직 이관 수신 행은 ID의 `-IN-F코드` 접미사를 현재 수신 조직으로 읽고, 앞쪽 F코드는 원조직 이력으로 보존한다. 이 구분은 마스터의 조직명·담당자·이관 설명 및 독립 `manifest_08.json`의 수신 조직 정보에서 확인했다. 계정은 마스터의 해당 코드 셀에서 읽으며, outlook의 실적·추정 경계는 마스터 `답안지_IFRS15後!AL6:AW6`에서 확인한다. 상세 시트 제목의 `천원`이 확인된 경우에만 평가기 자체의 Decimal 변환으로 1000배 한다. 단위·조직·계정·기간 근거가 없거나 충돌하면 문제 내역을 남긴다.

비교 범위는 prior·plan·outlook·next_plan·next_projection의 월별 상세 셀이다. 연간 합계·소계·답안지·코드표는 레코드 F1 범위에 포함하지 않는다. 원본에 실제 값이 없는 빈 셀은 정답 금액으로 만들지 않으며 `master_blank`로 남긴다. 숫자 0은 정상 정답이다. 따라서 양식상 가능한 칸 수와 실제 금액 정답 수는 다를 수 있다.

각 유형·마감월에서 조직 17개 출력 전체를 이어 붙인 뒤 `조직코드 + 계정코드 + 연도 + 월 + 실적/계획/추정 + 상세 ID`로 대조한다. 유형·마감월·매출/비용도 평가 키에 포함하여 서로 다른 제출본이나 영역을 섞지 않는다. 상세 ID는 원본 설명 셀의 명시적인 ID를 사용한다. 수정번호는 출처와 오류 내역에 보존하며 자동 최신본 선택에 사용하지 않는다. 같은 키가 여러 개면 금액까지 정확한 항목부터 일대일로 연결하고 남은 출력은 오탐이다. 중복을 삭제하거나 합쳐 점수를 계산하지 않는다.

예측 투영은 파서가 반환한 조직·계정·기간·금액만 사용한다. 같은 조직 원본은 상세 ID와 월별 대상 셀 범위 확인에만 사용하며 파서가 놓친 의미나 금액을 채워주지 않는다. 금액 `confirmed`, 종류 `amount`, 유한 Decimal을 만족해야 확정 출력 수에 들어간다. 금액이 확정되어도 의미 키가 미확정이면 정답 일치로 인정하지 않는다.

- 일치: 키와 정규화 금액이 모두 같은 일대일 레코드.
- 누락: 정답에만 있는 레코드. 시트 생략·미확정 금액의 정답도 Recall 분모에 남는다.
- 금액 불일치: 같은 키의 서로 다른 확정 금액. 상세표에는 1건, F1에는 FP 1건과 FN 1건.
- 오탐: 연결되는 정답이 없는 확정 출력. 잉여 중복도 각각 1건이다.
- 미확정: 확정 출력 분모에는 넣지 않지만 원본·의미·상태·사유를 상세 결과에 보존한다.

Precision은 `일치 / 확정 출력`, Recall과 인식률은 `일치 / 전체 정답`, F1은 `2 × 일치 / (확정 출력 + 전체 정답)`이다. 전체 점수는 채점 가능한 유형의 건수를 합친 마이크로 지표로 계산한다. 상세 대조 후 매출·비용의 월별 합계를 별도로 확인하며, 합계가 맞는다는 이유로 잘못된 상세 키를 정답으로 인정하지 않는다. 조직별 상세가 없는 자료는 레코드 F1이 미측정이며 그 이유를 명시해야 한다.

다음 명령은 저장소 루트에서 실행한다. `--types`는 실제 승인된 검증 범위로 지정하고, `<새 실행 ID>`는 아직 없는 폴더 이름으로 바꾼다. 두 명령 사이에 저장된 조직 요청만으로 `plans.json`을 작성·고정한다.

```bash
python3 -B parser/compare.py monthly export ../Data/dummy/월별검증_2026 \
  --types 01,02,03,04,05,06,07,08 \
  --output-dir parser/output/comparisons/<새 실행 ID> --workers 6 --write-output

python3 -B parser/compare.py monthly run ../Data/dummy/월별검증_2026 \
  --output-dir parser/output/comparisons/<새 실행 ID> --workers 6 --write-output
```

재생은 export 때 고정한 `inventory.json`의 범위를 사용한다. `results/`는 처음 한 번만 생성하며 두 번째 실행은 기존 점수를 덮어쓰지 않는다. 실행 조건·명령·계획/구현 해시, 조직별 실행 내역, 마스터 정답, 두 파서 상세 출력·오류, 월별 점수와 유형별 집계는 실제 결과 폴더에서 확인한다.

## 조직 원본으로 기술 마스터를 생성한 뒤 평가하기

`master generate`는 조직 원본을 새로 파싱하고 상세를 조립하는 별도 실행이다. 최초 비교 결과와 고정 LLM 계획을 수정하지 않는다. 원본 자료에 맞춘 생산 룰 파서 변경도 최초 비교 점수에 소급 적용하지 않으며, 새 구현 해시와 새 실행 폴더를 사용한다.

생성과 평가는 다음 경계로 분리된다.

1. 생성 명령은 최초 `inventory.json`의 조직 파일 목록만 파서에 전달한다. 조직 목록의 중복과 마스터 경로 포함을 생성 전에 거부한다. 기존 마스터는 나중 평가를 위한 경로만 보관한다.
2. 기존 추출·원본 검증·`map_workbook`·출처 검증을 실행한다. 조직·계정·연월·시나리오·단위·ID는 같은 조직 원본의 근거로만 확인한다. 미확정값은 정답으로 채우지 않는다.
3. `project_source_details`는 월별 상세를 평탄화한다. 원본에 식별되는 상세행의 빈 금액 슬롯도 `missing`으로 보존하며 0으로 바꾸지 않는다. 값이 있으나 생산 파서가 처리하지 못한 셀은 미확정으로 남긴다.
4. `assemble_master`는 조직 제출본·상세 수정본을 선택하고 상세를 보존한 채 조직별·전사 월별 원화 합계를 별도로 만든다. 전체가 빈 선택 `next_projection`은 `missing_optional`; 일부 입력이 있거나 필수 영역의 빈 값은 미확정이다.
5. 생성 상세·합계·선택/제외 근거·원본 검증·멱등성 확인 결과를 새 폴더에 저장하고 해시를 고정한다. 이 JSON이 실제 생성 마스터이며, 이후 별도 `master evaluate` 명령의 입력이 된다. `master_workbook.mjs`는 같은 생성 JSON만 읽어 Excel을 작성하고 내보낸 파일을 다시 열어 전수 값·타입·빈칸을 검사한다.
6. 평가 명령만 기존 마스터를 읽고 생성 상세와 건별 대조한다. 조직별·전사 합계도 확인하며 생성 결과를 평가값으로 덮어쓰지 않는다.

`parse_submission`의 제출 소유자와 각 상세행의 조직코드는 별도로 보존한다. 행 조직코드 집합이 유일하면 기존 소유자를 유지하고, 비어 있지 않은 코드가 둘 이상일 때만 `_source_declared_owner`를 호출한다. 확인된 재무표의 데이터 시작 전·첫 값 열 왼쪽에서 `▣ 조직명` 제목과 별도 동일 이름 셀이 일치하고, 같은 원본 조직 코드표에서 그 이름의 코드가 유일해야 소유자를 보완한다. 제목만 있거나 선언·코드표가 불명확 또는 충돌하면 미확정이며 파일명·상세 ID·다수결은 사용하지 않는다. `organization_identity_source=source_declared_owner`와 상태·근거 셀·사유를 제출본 및 `trial.submission_owner`에 기록하고, 다른 조직에 속한 상세행의 코드·값과 후속 조립 진단은 그대로 보존한다.

원본의 금액 문자열은 명시 KRW 단위와 정확한 서식 근거가 있을 때만 로케일을 보완한다. 회계 여백 `_문자`·`*문자`를 제거한 양수 서식이 정확히 `#,##0` 또는 점 뒤 0이 하나 이상인 `#,##0.0…`이고 문자열에 3자리 쉼표 그룹이 있으면, 점 이하 소수를 포함해 기존 `normalize_number(locale="ko_KR")`로 처리한다. 표시 자릿수로 반올림하지 않아 원문 정밀도와 단위 변환을 유지한다. 뒤쪽 배율 쉼표·조건·백분율·날짜·숫자 리터럴과 모호한 소수 구분자에는 이 보완을 적용하지 않는다. 실제 숫자 셀과 호출자가 명시한 locale도 기존 경로를 유지한다.

필수 순서는 **조직 원본 파싱·마스터 JSON 생성 완료 → 기존 마스터를 읽는 평가**다. Excel 내보내기는 생성 JSON 뒤에 수행하는 독립 단계이며, 평가의 선행조건은 아니다. 이번 실행은 각 월 JSON 완료 → 해당 Excel 독립 내보내기, 최종 선택한 JSON 전체 완료 → 기존 마스터 평가의 순서로 진행했다. 따라서 마지막 Excel 검증과 평가는 동시에 진행될 수 있으며 Excel 작성기에 평가 정답이나 기존 마스터를 전달하지 않는다.

전체 생성 작업이 진행 중이어도 특정 유형·기준월의 `details.jsonl.gz`와 `master.json` 저장이 완료되면 그 월의 Excel을 병렬로 내보낼 수 있다. Excel 작성기는 아직 생성 중인 다른 월을 읽지 않는다. 기존 마스터와의 평가는 전체 생성 명령이 완료하여 `generation.json`이 고정된 후 별도 명령으로 시작한다.

### 제출 교체와 원본 수정본 선택

기존 [업무 규칙](../../../docs/domain-rules.md)의 공식 마스터는 모든 조직의 승인본이 준비된 뒤 생성하며, 승인된 제출 변경 시 새 버전으로 갱신하고 이전 마스터는 보관한다. 이번 조직 원본 목록에는 승인 상태·공식 제출 회차 근거가 없으므로 이를 만들지 않는다. 사용자가 요청한 재구성·검증용 기술 마스터를 별도 실행 폴더에 생성하고 `business_master_issued=False`, `approval_status=not_asserted`를 명시한다. 원본 행의 수정번호와 공식 제출본 회차도 구분한다.

`replace_organization`은 해당 조직의 저장 상태 전체를 새 제출본 한 개로 교체한다. 같은 입력을 다시 적용해도 상세가 추가되지 않으며 다른 조직 상태는 그대로 유지한다. 생성 명령은 대표 한 조직을 같은 제출본으로 교체한 후 전체 조직을 재조립해 `content_sha256`이 동일한지 확인하고 `idempotent_rerun`에 기록한다. 검사 범위와 대표 원본은 `idempotency_scope`, `replacement_checked_source`로 남긴다. 조직 격리·반복 교체·구조 변경 사례는 메모리 단위 테스트에서도 검증한다.

한 조직에 제출본이 하나면 그 원본을 선택할 수 있다. 여러 제출본은 `version_verified` 또는 `version_evidence`로 확인된 비교 가능한 버전에서 유일한 최고 버전이 있어야 한다. 버전 미확인·동률 충돌은 미확정으로 남긴다. 파일명·상세행 수정번호를 제출 버전으로 사용하지 않는다.

상세 수정은 원본 설명의 ID·수정번호를 근거로 판단한다. 같은 상세 ID의 구개정은 `superseded_detail_revision`으로 제외 근거와 원본 기록을 보존한다. 최고 수정번호의 원본행이 여러 개면 모든 월의 조직·계정·기간·구분·금액·상태가 동일한 경우에만 `exact_duplicate_delivery`로 한 번 반영한다. 선택 상세의 `source_occurrences`와 `duplicate_delivery_count`에 모든 원본 출처·횟수를 보존한다. 한 월이라도 서로 다르면 해당 ID의 행 선택을 `detail_revision_conflict`로 남기며 임의 후보를 고르지 않는다.

정규금액이 미확정이면 원문값과 수식 정보까지 비교한다. 서로 다른 원문이나 캐시 없는 수식이 모두 `normalized_value=null`이라는 이유로 같은 전달로 판정하지 않는다. 빈 슬롯을 추가할 때도 실제 항목·코드·거래 설명 근거가 있는 행만 보존하며, Excel 오류 자리표시만 있는 행을 새 재무 상세로 만들지 않는다.

이는 조직 원본의 반복 전달과 명시 수정 정보를 처리하기 위한 기술 조립 정책이다. [도메인 규칙](../../../docs/domain-rules.md)의 공식 master는 조직 승인본과 백엔드 버전 정책을 따른다. 이 로컬 작업은 승인·공식 발행을 추정하지 않고 `business_master_issued=False`를 기록한다. 승인 메타데이터가 없다는 이유로 사용자가 요청한 기술 조립 자체를 중단하지 않는다.

### 실행과 산출물

Excel 내보내기는 Codex 기본 런타임의 `@oai/artifact-tool` 패키지와 Node.js를 사용한다. 현재 작성기는 `$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules`에서 패키지를 찾으므로 일반 Node.js 설치만으로는 실행할 수 없다. 이 런타임과 생성된 `node_modules` 연결은 저장소에 포함하지 않는다.

```bash
python3 -B parser/compare.py master generate \
  --inventory parser/output/comparisons/<최초 실행 ID>/inventory.json \
  --output-dir parser/output/comparisons/<새 생성 실행 ID> \
  --workers 6 --write-output

# 완료된 generation.json 전체를 최대 두 작업자로 내보낸다.
node parser/src/excel_parser/export_masters.mjs \
  parser/output/comparisons/<새 생성 실행 ID> --workers 2

python3 -B parser/compare.py master evaluate \
  --output-dir parser/output/comparisons/<새 생성 실행 ID> --write-output
```

`generate`의 `--types 01,02`와 `--months 2026-01`은 목록에서 실행 범위를 좁히는 선택이다. 생략하면 목록 전체를 사용한다. 각 유형/기준월 폴더에 선택 상세 `details.jsonl.gz`, 조립 전 파서 상세 `source_records.jsonl.gz`, 선택에서 제외된 상세 `excluded_records.jsonl.gz`, 합계·문제·선택·상태를 가진 `master.json`, 파일별 파서 검증 `execution.json`을 저장한다. 실행 루트의 `generation.json`에는 생성 명령·구현·입력 목록 해시·생성 상세 해시·기존 마스터 읽기 0건을 기록한다.

이번 전체 실행에서는 생성과 내보내기를 겹쳐 진행하기 위해 별도 프로세스에서 다음 명령을 사용했다. `--watch`는 한 월의 JSON 저장 완료를 확인한 뒤에만 작성기를 호출하며, 최종 `generation.json`이 완성되면 목록 전체의 상세 해시와 내보내기 검증 결과를 다시 대조한다. 작업자는 최대 2개로 제한한다.

```bash
"$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node" \
  parser/src/excel_parser/export_masters.mjs \
  parser/output/comparisons/monthly-20261113-rule-master-v2/full --watch --workers 2
```

`export-logs/<attempt>/`에 월별 실제 명령·stdout/stderr·종료 코드와 실패 사유를 남긴다. `export-summary.json`에는 전체 범위, 작성/검증 후 재사용/실패 수, 월별 검증 근거, 생성 manifest·작성기 해시를 저장하며 시도별 요약과 직전 요약도 보존한다. 재실행은 입력 JSON·XLSX SHA256, 상세·합계 수, 네 시트 저장 파일 전수 재읽기 검사까지 일치할 때만 기존 파일을 건너뛴다. 누락·불완전 산출물은 `export-recovery/<attempt>/`로 보존한 뒤 새로 작성하며 입력 JSON을 수정하지 않는다. 단일 월과 미리보기는 `node parser/src/excel_parser/master_workbook.mjs <유형/기준월 폴더> --preview`로 실행할 수 있다.

Excel 변환은 생성 JSON의 값을 변경하지 않는다. 상세의 전체 월별 값과 상태를 조직·계정·ID·연도·구분·원본행별로 보존하고 12개월 가로 표로 제공한다. `master.xlsx`에는 전사 합계, 조직별 합계, 매출 상세, 비용 상세의 4개 시트가 있다. 원본 파일·시트·행·월별 셀 주소와 값 상태가 상세 행에 남는다. 합계는 생성 JSON 상세에서 BigInt 계수로 독립 계산한 값·건수와 모두 일치해야 한다. Excel 숫자로는 15자리 유효숫자 및 왕복 일치를 확인한 값만 저장하며, 정밀도 조건을 넘으면 조용히 반올림하지 않고 중단한다. 빈 셀과 실제 숫자 0을 구별한다.

`workbook_verification.json`은 입력 JSON 해시, 상세·합계 확인 수, 메모리 시트 전수 확인, 내보낸 Excel을 재열어 전수 확인한 결과, Excel SHA256을 기록한다. `--preview`를 선택하면 시트별 머리 범위 PNG를 추가로 저장한다. 이 단계는 기존 기준 마스터 Excel을 읽지 않으며 수식으로 금액을 만들어 채우지 않는다.

`evaluate`는 새 `evaluation/`에 오류 상세, 조직별/전사 합계 대조, 독립 정답 근거, 유형·기준월 요약과 `validation.json`, `validation.csv`를 저장한다. 이미 채점한 실행을 다시 덮어쓰지 않는다. 정답에 없는 실제 0원 출력은 엄격 F1에서 오탐으로 유지한다. 별도로 그 수와 설명 가능한 차이를 표시하더라도 `strict_detail_and_total_match`와 구분해야 한다.

평가기는 기존 마스터를 읽기 전에 생성 상세의 해시를 확인하고, `included_in_totals=True`인 상세로 다시 계산한 합계가 저장된 `master.json` 합계와 정확히 같은지 검사한다. 독립 마스터와의 합계 대조에도 실제 합계 포함 상세만 사용한다. 금액은 존재하지만 조직 의미 등이 미확정이라 합계에서 제외된 값을, 평가기에서 다시 합산하여 생성 마스터와 다른 합계를 비교하지 않는다.

생성 연산 완료(`generation_complete`), 생성 자료의 완전성(`status`), 상세·합계를 대조했는지, 엄격히 모두 일치했는지는 서로 다른 상태다. `partial` 자료도 누락·미확정 사유와 함께 저장·평가하며 완전 일치로 보고하지 않는다. 원본 해시와 재실행 멱등성, 전체 입력 파일의 선택 여부도 결과에 함께 기록한다.

### 별도 수정 실행과 최종 81개 선택

원본 근거로 파서를 수정해 일부 유형을 다시 생성할 때 최초 `full`을 덮어쓰지 않는다. 수정 대상만 같은 입력 목록으로 `corrected-05-07` 등의 새 폴더에 생성한 뒤, 독립 선택 명령으로 최종 참조 목록을 만든다. 선택 자체는 생성 manifest와 저장 상세의 SHA만 읽고 조직 Excel·기준 마스터·평가 결과는 읽지 않는다.

```bash
PYTHONPATH=parser/src python3 -B -m excel_parser.benchmark.reconstruction_selection \
  --base parser/output/comparisons/<실행 ID>/full \
  --replacement parser/output/comparisons/<실행 ID>/corrected-05-07 \
  --output parser/output/comparisons/<실행 ID>/final \
  --replace-types 05,07 --write-output
```

`--replace-types`의 기본은 `05`다. `05,07`이면 최초 81개 중 나머지 63개와 수정 유형의 18개를 선택한다. 각 명시 유형은 최초와 정확히 같은 9개월을 가져야 하며 inventory SHA, 조직 원본 경로 목록과 순서, 조직 수, 평가 전용 기준 마스터 경로가 일치해야 한다. 누락 월·미명시 유형·중복·다른 입력은 거부한다.

새 `final/generation.json`의 배치 디렉터리는 실제 최초 또는 수정 생성물 폴더를 가리킨다. `finalmaster_index.csv`는 최종 JSON·XLSX 경로, 최초 배치 경로, 교체 여부와 생성 구현 SHA를 보존한다. 선택 명령은 XLSX를 만들거나 작성 완료를 주장하지 않는다. Excel 검증은 각 원 실행의 `workbook_verification.json`과 내보내기 요약에서 확인한다.

배치별 `lineage`는 원 실행 manifest 경로·SHA, 생성 구현 SHA·시각, 원래/선택 디렉터리·상세 SHA를 남긴다. 혼합 결과의 최상위 단일 구현 SHA는 `null`로 두고 `implementation_profile=per_batch_lineage`로 표시한다. 생성 시간 합에는 선택에서 제외된 최초 수정 대상도 들어 있으므로 범위를 명시하고 선택 명령의 시간은 따로 기록한다. 이후 `master evaluate --output-dir <실행 ID>/final --write-output`은 이 고정 선택 목록만 새 평가 폴더에 대조한다. 최초 점수와 최초 생성·평가 폴더는 보존한다.

### 합계 차이를 원본 행까지 추적하기

`benchmark.followup_reconciliation`은 이미 생성·평가가 끝난 결과의 한 기간을 조사한다. 저장 `totals.json`의 전사 차이와 `included_in_totals=True`인 extra를 출처로 연결하며, 제외 미확정 금액은 별도 잠재 영향으로 남긴다. 제외 금액을 이미 발생한 합계 차이에 다시 더하지 않는다. 수치 잔차 0도 extra의 업무 유효성을 뜻하지 않으므로 원인이 미설명인 extra 금액을 별도로 표시한다.

```bash
PYTHONPATH=parser/src:parser_ai/src python3 -B -m excel_parser.benchmark.followup_reconciliation \
  --baseline parser/output/comparisons/monthly-20261113-rule-master-v2/final \
  --output parser/output/comparisons/monthly-20261113-rule-master-v3/pilot-reconciliation-v2-type05-2026-01 \
  --type 05 --closing-month 2026-01 --year 2025 --month 1 --scenario actual --write-output
```

추가 원본 확인은 최초 목록에 있는 조직 파일만 읽고 생성 당시 SHA와 읽기 전후 SHA를 확인한다. 원본 행의 조직·계정과 실제 집계 수식을 조사하며 ID·정답 후보로 의미를 보충하지 않는다. 매출의 관리분류는 실제 원본 SUMIFS가 상세 B열을 어떤 라벨 셀로 집계하는지를 근거로 확인한다. 소분류를 예상 관리분류로 임의 치환하지 않는다. 기준 마스터는 이 분석에서 직접 읽지 않으며 생산 코드·v2 상세·점수·원본도 변경하지 않는다.

새 분석 폴더의 `reconciliation.json`에는 기간별 금액 연결·원본 셀·헤더·단위·SUMIFS 라벨 근거·입력 보존 해시를 저장한다. `included_extras.jsonl.gz`와 `excluded_unresolved.jsonl.gz`는 차액 포함 여부를 분리한 상세이며, `report.md`는 두 전사 영역의 숫자와 해석을 설명한다.

### 동일 구현으로 전체 재파싱한 결과의 회귀 검증

`benchmark.regression join`은 같은 새 구현으로 실제 재파싱한 05의 9개월과 나머지 두 36개월 그룹을 결합한다. 저장 execution의 원본 해시가 v2와 같고, 세 그룹의 구현 SHA·inventory SHA가 같으며 합집합이 중복 없는 정확한 81개여야 한다. 기존 결과를 선택적으로 재사용하는 v2 선택 명령과 구분한다. 원본·기준 마스터 Excel을 열지 않고 생성 산출물만 읽는다.

```bash
PYTHONPATH=parser/src python3 -B -m excel_parser.benchmark.regression join \
  --groups parser/output/comparisons/<v3>/group-05 \
           parser/output/comparisons/<v3>/group-01-04 \
           parser/output/comparisons/<v3>/group-06-09 \
  --baseline parser/output/comparisons/<v2>/final \
  --output parser/output/comparisons/<v3>/final --write-output

# 새 final의 master evaluate가 완료된 뒤 실행한다.
PYTHONPATH=parser/src python3 -B -m excel_parser.benchmark.regression compare \
  --baseline parser/output/comparisons/<v2>/final \
  --current parser/output/comparisons/<v3>/final \
  --output parser/output/comparisons/<v3>/regression --write-output
```

`join`은 새 `generation.json`과 `finalmaster_index.csv`를 만들고 실제 그룹 폴더를 참조한다. `compare`는 같은 원본 셀의 의미·금액·상태, 오류 업무키·값·출처, 조직/전사 합계의 실제 값을 다중집합으로 비교한다. 건수가 같아도 다른 셀이 바뀌었으면 차이로 남기며 중복 수량을 지우지 않는다. 05를 제외한 72개 배치의 실제 재파싱 결과와 평가가 모두 동일해야 회귀 통과다. 새 검사 필드가 추가됐다는 이유만으로 변화로 세지 않지만 핵심 의미·금액·상태 변화는 보존한다.

각 그룹에서 이미 실제 평가가 끝났다면 독립 `regression_evaluation`으로 최종 평가를 합성할 수 있다. 최종 81개 목록과 그룹 생성·평가의 키, 구현·inventory·상세 SHA, 원본 목록·순서, 디렉터리·계보가 같아야 한다. 월별 `errors.jsonl.gz`, `totals.json`, `truth_metadata.json`, `summary.json`, `by_domain.json`의 405개 파일을 바이트 그대로 복사하고 SHA를 재확인한다. 점수는 다시 계산하지 않는다.

```bash
PYTHONPATH=parser/src python3 -B -m excel_parser.benchmark.regression_evaluation \
  --groups parser/output/comparisons/<v3>/type05 \
           parser/output/comparisons/<v3>/regression-a \
           parser/output/comparisons/<v3>/regression-b \
  --current parser/output/comparisons/<v3>/final \
  --output parser/output/comparisons/<v3>/final/evaluation --write-output
```

이 명령은 `validation.json`, `validation.csv`, `evaluation_provenance.json`에 실제 그룹 평가 명령·81개 평가 이벤트, 입력/사본 해시, 합성 명령·후처리 코드 SHA를 기록한다. 합성 중 원본·기준 마스터 Excel 읽기는 0건이며 81개 실제 평가는 앞서 완료된 9+36+36 그룹의 이벤트다. 동일 평가를 다시 실행한 것처럼 보고하지 않는다. 기존 그룹 평가와 이미 존재하는 final/evaluation은 덮어쓰지 않는다.

`comparison.json`, `before_after.csv`, `before_after_monthly.csv`, `source_semantic_changes.jsonl.gz`, `evaluation_changes.jsonl.gz`에 실제 before/after와 차이를 저장한다. 전체 Precision/Recall/F1은 레코드 건수를 합산한 마이크로 점수다. 비영 금액 FP에는 extra와 금액 불일치의 비영 출력이 모두 들어간다. `unresolved_required`는 평가의 선택 상세 미확정이며, 제외 상세를 포함한 총 필수 미확정은 별도 생성 감사에서 확인한다. 업무 무효 금액을 원값·확정값 그대로 보존하면서 합계에서 제외했다면 엄격 FP가 남아 있어도 합계 차이는 감소할 수 있다. 기존 v2 점수나 새 평가 정의를 이 때문에 바꾸지 않는다.

### 저장 결과만으로 전 기간 합계 차액을 연결하기

전 기간 사후 분석은 생성 manifest·상세·제외 상세·원본 파서 결과·선택 제출 메타데이터와 저장 평가만 읽는다. 원본 Excel과 기준 마스터 Excel을 다시 읽지 않는다. before/after의 입력 목록·조직 원본 SHA를 확인하고 같은 원본 파일·시트·셀로 상세와 오류를 연결한다. 식별 ID나 정답 후보로 조직·계정을 보완하지 않는다. 저장 입력의 분석 전후 SHA를 대조하며 별도 새 폴더에만 결과를 쓴다.

```bash
PYTHONPATH=parser/src python3 -B -m excel_parser.benchmark.followup_saved_report \
  --baseline parser/output/comparisons/<v2>/final \
  --current parser/output/comparisons/<v3>/final \
  --output parser/output/comparisons/<v3>/final-type05-bridge \
  --type 05 --write-output
```

이 모듈은 `src/excel_parser/benchmark/`에서 관리한다. 명령의 실제 입력 경로로 비교 실행 루트를 결정하며 `__file__`의 위치를 출력 루트로 사용하지 않는다. `comparisons` 아래 서로 다른 실행 루트의 완료 자료만 허용하고 출력은 현재 실행 루트의 새 경로로 제한한다. 기본 실행은 읽기 전용이며 `--write-output`을 명시해야 저장한다. 이전 산출물 폴더에서 실행했던 분석 코드의 원문·SHA·결과 기록은 `postprocessing_source_migration.json`에 보존한다.

차액은 `included_in_totals=True`인 오류 출력의 가산과 해당 정답 차감으로 연결한다. extra는 포함 출력만 가산하고, missing은 정답을 차감하며, amount_mismatch는 포함 출력에서 정답을 차감한다. 금액이 확정되어 strict FP에 남더라도 합계에서 제외된 업무 무효 출력의 기여는 0원이다. 전사·조직의 합계 그룹마다 포함 상세 합계를 저장 합계와 대조한 후 `출력−정답−오류 기여=0`을 검증한다. 제외된 알려진 금액과 금액 미상 슬롯은 별도 잠재 영향이며 이미 발생한 차액에 다시 더하지 않는다.

원본상 선택적 `next_projection` 빈칸은 해당 domain/period_group 전체에 원값·정규금액·수식이 없는지 확인하여 세며, `semantic_complete`와 독립된 관측 수로도 남긴다. 이 수와 조립기의 `assembly_status=missing_optional`은 다르다. 업무 키가 invalid라 `unresolved`로 남은 선택적 빈칸을 새 필수 금액 누락으로 세지 않는다. 조직 식별 때문에 상세 선택에서 제외된 빈칸도 별도 표기한다. 업무·조직 식별 미확정, 필수 금액 빈칸, 수식 cache 문제, 실제 0원은 각각 보존한다.

최종 81개 목록에 대한 05의 9개월 실제 사후 분석은 [measured_report.md](../../output/comparisons/monthly-20261113-rule-master-v3/final-type05-bridge/measured_report.md)와 같은 폴더의 `summary.json`에 기록했다. 정식 모듈 CLI로 새로 실행했으며 `executed_command.json`에 실제 명령·종료 0·코드 SHA·원본/기준 Excel 읽기 0건·입력 보호 해시를 보존했다. 앞선 `type05-nine-months-before-after-bridge` 실행의 감사·월별 점수, 압축 상세 54개의 JSONL 내용과 CSV 3개가 같음을 저장 자료끼리 재확인했다. 이전 결과는 덮어쓰지 않았다.

`remaining_difference_groups.csv`는 제출월·대상 연월·구분별 전사/조직 연결, `source_contributions.csv`는 원본별 포함 오류 기여, `excluded_source_potential.csv`는 원본별 제외 금액과 미상 슬롯이다. 각 월의 압축 상세에는 오류 원문과 원본 파일·시트·셀·키·정답 좌표가 남는다. 사후 분석 코드 SHA는 실제 생성 파서 구현 SHA와 구분한다.

수치 잔차 0은 기준 마스터의 생략 정책을 확인했다는 뜻이 아니다. 예를 들어 유효한 원본 출력 2,538개의 `필수 빈칸이 있는 원본행의 다른 기간` 분류는 저장 자료에서 관측한 출처 패턴이며, 기준 마스터가 행을 생략한 이유를 확정하지 않는다. 원본행 조직이 제출 소유자와 다른 864개도 원본에서 확정된 행 조직을 보존한다. 이 3,402개를 정답에 맞추어 삭제하지 않으며, 필수 식별·금액 미확정 12,582개 슬롯과 함께 05의 `partial` 상태를 유지한다.

### v4의 명시적 원본 행 발행 정책

v3 사후 관측과 별개로 v4에서는 원본 생성 계약을 정적으로 확인한 뒤 `publication_policy="source-contract-v4"`를 명시한 조립에만 정책을 적용한다. 근거는 [publication_policy_evidence.json](../../output/comparisons/monthly-20261113-rule-master-v4/evidence/publication_policy_evidence.json)에 원본 생성 코드의 경로·행·해시와 조직 Excel 표본 근거로 보존했다. 생성 코드를 실행하거나 정답 객체를 입력으로 사용하지 않는다. 기존 평가기·키·정답 분모를 바꾸지 않고 새 생성물을 평가한다.

- 업무 키가 원본의 명시 식별자 또는 같은 원본 등록·집계 계약을 위반하면 행 전체를 발행 상세에서 제외한다. 상태 문자열만 invalid이거나 계약 근거가 없으면 제외하지 않고 미확정으로 남긴다.
- 비용 행 조직이 원본에서 확인한 선언 소유자의 범위와 다르면 행 전체를 제외한다. 행 조직·소유자 코드는 모두 보존하며 정상 이관 수신 조직이나 비용의 실제 0을 제거하지 않는다.
- 필수 actual 빈칸 계약은 당해 outlook의 **1월 actual**에만 한정한다. 생성 코드의 명시 사례가 actual[0]이므로 다른 actual 월이나 prior/plan/next_plan 전체로 일반화하지 않는다. 수식 cache missing/error와 실제 빈칸도 구분한다.
- 비활성 매출은 prior 12개월·plan 12개월·next_plan 12개월·마감 이하 outlook actual에 None/0 아닌 값이 없는 전체 행이다. 미래 outlook estimate와 next_projection은 활성 판정에서 제외한다. 확인에 필요한 슬롯이 없거나 금액/의미가 미확정이면 비활성으로 단정하지 않는다. 활성 매출의 일부 0과 비용의 0은 그대로 발행한다.

정책은 기존 상세 수정본·동일 재전송 선택 뒤에 적용한다. 최신 무효행을 제외한 뒤 구수정본으로 되돌리지 않는다. 제외 행의 모든 월 슬롯·원문·정규금액·0·빈칸·수식·출처는 `excluded_records.jsonl.gz`에 보존하고 `decision_id`로 `master.json`의 행별 원본 근거에 연결한다. 선택 제출의 원본 파서 결과도 별도로 유지한다. 발행 상세 JSON과 Excel 상세는 이 선택의 같은 데이터이며, 제외값을 합계나 실제 최종 상세에 다시 넣지 않는다.

원본 품질과 발행 상태를 분리한다. `generation_complete=True`는 계산 종료, 기존 `status`/`source_quality.status`는 원본 오류를 포함한 상태, `publication_status`는 발행 상세의 완전성과 정책상 제외 여부다. 기존 조직 충돌은 코드·이름을 정정하지 않고 원본 오류로 보존한다. 조직 conflict·조직명/셀 provenance 및 기타 식별이 확인된 계약상 제외만 발행 범위에서 분리하며, 일반 ID/조직 미확정·기대조직누락·수정본 충돌은 발행 partial로 남긴다. 따라서 `publication_status=complete_with_exclusions`이어도 원본 오류가 해결됐거나 strict 점수가 100%라고 미리 표시하지 않는다. 실제 상세·합계 일치는 생성과 Excel 저장이 끝난 뒤 기존 평가로 확인한다.

### v4 저장 Excel 직접 평가

`master_workbook.mjs`의 네 시트 A4에는 발행 상태·원본 품질·조립 상태와 기대/반영 조직 수가 별도 표시된다. 상세 Z열은 월별 금액 확정·업무키 확정·value_kind·기존 상태·합계 포함·mapping 상태/이유를 보존한다. 합계표는 기존 합산 상세 수와 월별 상세 수 JSON을 함께 저장한다. 실제 값·빈칸·0·상태·헤더·A3/A4는 메모리 작성 후와 저장한 XLSX 재열기 후 모두 대조한다.

생성 JSON이 완료된 각 월부터 독립적으로 Excel을 내보낼 수 있다. **모든 선택 JSON과 해당 Excel 내보내기가 완료된 뒤** 아래 별도 명령으로 실제 저장 Excel을 평가한다. 기존 기준 마스터는 이 명령의 전체 완료 사전 확인 후 월별 평가에서만 읽는다.

```bash
PYTHONPATH=parser/src python3 -B -m excel_parser.benchmark.excel_evaluation \
  --run parser/output/comparisons/monthly-20261113-rule-master-v4/final \
  --output parser/output/comparisons/monthly-20261113-rule-master-v4/final/evaluation \
  --write-output
```

`excel_evaluation`은 생성 목록에서 파일 경로·기대 범위만 확인한다. 생성 상세/마스터 JSON이나 내보내기 검증 JSON의 값·상태로 Excel을 보충하지 않는다. 마지막 검증 JSON은 완료 표식 존재 여부만 확인한다. 실제 Excel의 A3/A4·헤더·키·숫자·빈칸·상태를 기존 OOXML 추출기와 `normalize_number`로 읽는다. 실제 상세를 변경하지 않은 `score_monthly_records`에 전달하므로 같은 키의 금액 불일치는 FP+FN, 키 미확정 숫자는 FP, 누락·시트 생략은 정답 Recall 분모에 남는다. 기존 `_aggregate_checks`의 키·금액·건수 기준도 유지한다.

실제 전사/조직 합계표는 별도로 읽어 실제 상세의 합계·월별 건수 및 독립 정답 합계와 대조한다. `strict_detail_and_total_match`는 기존 상세·합계 기준이고 최종 Excel 완료는 `strict_excel_detail_and_total_match`를 사용한다. 후자는 실제 합계표와 실제 상세의 금액·월별 건수, 실제 합계표와 기준 금액, 구조 정상, 실제 기대/반영 조직 수까지 요구한다. 원본 품질 partial을 정답 불일치로 바꾸지는 않는다. 0원은 확정 숫자로 유지하며 실제 비용 0 건수, 빈칸/미확정, 0 오탐, 비영 FP를 분리 보고한다.

새 평가 폴더에는 `validation.json/csv`, `provenance.json`, 월별 `summary.json`, `errors.jsonl.gz`, `totals.json`, `by_domain.json`, `truth_metadata.json`, `excel_records.jsonl.gz`, `actual_totals.jsonl.gz`, `excel_total_checks.jsonl.gz`, `excel_issues.json`, `excel_contexts.json`이 생성된다. 오류 주 위치는 생성 XLSX의 시트·셀이고, 보존된 조직 원본 위치는 별도 필드다. 이전 v3 Excel을 읽는 `--legacy-compatible`은 명시한 경우에만 기존 Z의 상태를 해석하며 월별 합계 건수는 미측정으로 남긴다. v4 기본 실행에는 이 호환 옵션을 사용하지 않는다.

### v4 생성 정책 전달과 저장 결과의 최종 발행 비교

`master generate --publication-policy source-contract-v4`는 최초 조립과 멱등성 확인용 재조립에 같은 정책을 전달한다. 옵션이 없으면 v3 기본 동작을 유지한다. 평가 명령에는 이 옵션을 적용하지 않으며 잘못 전달하면 거부한다. 원본 파서의 `source_records.jsonl.gz`는 발행 정책 전 상태를 그대로 보존한다. 입력 경로는 파싱 입구에서 절대 경로로 통일하여 제출 소유자 근거와 실제 행 출처의 대응을 유지한다.

여러 생성 그룹을 합칠 때는 구현·입력 해시뿐 아니라 발행 정책도 모두 같아야 한다. `regression join`은 v3 기본과 v4 발행 정책이 섞인 그룹을 거부하고 최종 generation.json에 정책 값을 보존한다. 원본 선택·ID·금액이나 기존 평가기의 키·분모를 이 과정에서 바꾸지 않는다.

발행 비교 명령은 현재 실행의 `evaluation/`에서 **실제 Excel 평가 결과**를 읽는다. 따라서 이 비교를 사용할 최종 실행에서는 앞 절의 actual Excel 평가 `--output`을 `.../final/evaluation`으로 지정한다. 이미 존재하는 평가를 덮어쓰지 않는다.

```bash
PYTHONPATH=parser/src python3 -B -m excel_parser.benchmark.publication_report \
  --baseline parser/output/comparisons/monthly-20261113-rule-master-v3/final \
  --current parser/output/comparisons/monthly-20261113-rule-master-v4/final \
  --output parser/output/comparisons/monthly-20261113-rule-master-v4/publication-report \
  --write-output
```

이 후처리는 저장 결과만 읽고 원본·기준 Excel을 열거나 재채점하지 않는다. 두 실행의 정확한 81개 목록·조직 입력·기준 경로 대응, 중복 없는 완료 평가, 현재의 `prediction_source=saved_excel`과 외부 JSON 값 보충 0을 확인한다. 원본 파서 결과는 압축 해제 바이트 SHA로 대조하며 달라졌으면 발행 정책만 바뀐 것처럼 보고하지 않는다. 모든 읽은 JSON/GZ의 실제 경로와 분석 전후 SHA를 보호하고 기존 결과는 변경하지 않는다.

`comparison.json`, `before_after.csv`, `before_after_monthly.csv`는 건수 기반 마이크로 지표와 실제 Excel 완료 수를 보존한다. 성공 기준은 `strict_excel_detail_and_total_match` 81개이며, 하나라도 남으면 CLI 종료코드는 2다. 상세 계산 합계의 차이는 `remaining_total_differences.json`, 실제 Excel 합계표의 금액·월별 건수 차이는 `remaining_actual_excel_total_checks.json`에 따로 남긴다. 상세 F1이 100%인 경우에도 실제 합계표만 틀린 문제를 누락하지 않는다.

`publication_exclusions.csv`는 제외 사유·영역·0/비영/빈칸/수식 상태별 슬롯 수와 전체 원문 감사 파일·행 정책 근거 파일을 연결한다. `source_corrections_needed.jsonl.gz`는 조직 충돌·명시 1월 actual 빈칸 등 실제 정정이 필요한 원본 슬롯의 값·셀·키를 그대로 보존한다. 일반 상세 ID 누락을 조직 오류로 바꾸어 설명하지 않으며, 선택적 추정 빈칸이나 제외 행의 다른 정상 월을 새 필수 금액 누락으로 세지 않는다. 최종 Excel의 엄격 일치와 원본 품질 오류가 해결되지 않았다는 사실을 각각 보고한다.
