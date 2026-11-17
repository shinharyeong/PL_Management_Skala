# 현재 구현 함수 명세서

코드 docstring을 기준으로 실제 함수·메서드의 명세를 정리합니다. 공개·비공개 모두 포함하며, 각 함수 변경 시 이 문서도 같은 변경에서 갱신합니다. 자동 생성되는 dataclass 생성자는 클래스의 Attributes와 생성 규칙 설명을 따릅니다.

## 전체 사용 흐름

```text
CLI main() → pipeline.run_parser()
  ├── paths.ProjectPaths.load() / collect_inputs()
  ├── extraction.extract_workbook() → ooxml 읽기 보조 함수
  ├── mapping.map_workbook() → normalization.normalize_number()
  ├── validation.validate_extraction() / validate_provenance()
  ├── validation.compare_master()
  └── output_dir를 명시한 경우에만 JSON/JSONL 저장
```

외부 Python 코드는 extract_workbook과 map_workbook을 순서대로 호출해 메모리에서 처리할 수 있습니다. 결과 파일 생성은 pipeline에서 분리합니다. Python 실행에는 -B 또는 PYTHONDONTWRITEBYTECODE=1을 사용합니다. 정확한 실행·입출력 형식은 [README](../../README.md)와 [출력 계약](output-contract.md)을 따릅니다.

서비스 내부 프로세스 호출은 `python3 -B -m excel_parser.service_bridge --filename <파일명>`을 사용합니다. xlsx 원본 바이트를 stdin으로 전달하며 `service_bridge.main → parse_bytes → _validate_package → extraction.extract_workbook_bytes → _extract_workbook_source → mapping.map_workbook → (_review_diagnostics, _preview_record, submission_adapter.build_submission_data) → _response_chunks` 순서입니다. 표준 추출·매핑 결과는 그대로 유지하고 전체 후보의 미리보기와 제출용 원본 근거를 별도 계약으로 제공합니다. 원본 저장·업무 요청·승인·전체 제출 검증은 호출자인 백엔드의 책임이며, 이 경로는 파일·로그·임시 폴더를 만들지 않습니다.

## 함수별 명세

## submission_adapter.py

정의: [submission_adapter.py](../../src/excel_parser/submission_adapter.py). `service_bridge.parse_bytes`가 같은 원본의 `extract_workbook_bytes`와 `map_workbook` 결과를 전달합니다. 모든 함수는 메모리만 사용하며 파일·DB·원본 입력을 변경하지 않습니다. 내부 계약 손상은 KeyError 등으로 전파되어 브리지의 고정 실패 응답으로 바뀝니다. 지원하지 않는 업무 구조는 추정하지 않고 미확정으로 남깁니다. 상세 입출력은 [출력 계약](output-contract.md#서비스-제출-검증-어댑터)에 있습니다.

| 함수 | 호출 관계·입출력·제약 |
| --- | --- |
| `build_submission_data(workbook: dict, mapped: dict) -> dict` | parse_bytes → 아래 색인/보고/행/원본 셀 함수. adapterVersion·SHA·owner·sheets·reportItems·lines·controls·sourceCells를 구성. 압축 빈 구간은 시트·행별 열 범위로 색인하며 개별 빈 셀 객체로 확장하지 않음. 요청 연도 선택·승인·중복 선택 없음 |
| `_column_name(number: int) -> str` | 양수 원본 열 번호 → 주소 문자. 추출 계약 범위 전제 |
| `_refs(references: list[dict \| None]) -> list[dict]` | provenance/source → 고유 sheet/cell. 경로·내부 원본 ID 생략 |
| `_remember(context: dict, references: list[dict \| None]) -> list[dict]` | `_refs` 호출 및 응답에 필요한 원본 주소 집합 갱신 |
| `_problem(context: dict, code: str, message: str, source: dict \| None = None, **scope: object) -> None` | 고정 제출 검증 진단·적용 범위와 정확한 근거 셀 기록 |
| `_source_cell(context: dict, sheet: str, address: str) -> dict` | 원본·표준 후보 → API camelCase SourceCell. 원본이 없는 확인된 월 슬롯도 EMPTY로 구분. 수식 재계산 없음 |
| `_sumifs_contract(expression: str) -> tuple[list[str], Decimal] \| None` | 단일 SUMIFS와 단일 양수 표시 배율만 구문 검사. 추가 함수·연산은 None |
| `_report_criterion(context: dict, expression: str, sheet: str, row: int, domain: str) -> tuple[str, list[dict]]` | 직접 셀/리터럴 또는 같은 비용 보고행 E:E → 기준값·근거. 임의 Excel 평가 없음 |
| `_period_key(period: dict) -> tuple` | 원본 연도·월·시나리오·집계 단위의 일치 검사 키 |
| `_report_binding(context: dict, sheet: str, cell: dict, period: dict) -> dict \| None` | 조직/분류 두 조건·월/단위·말단명이 모두 일치하는 직접 SUMIFS → 분류/보고 키. 계정 추정 없음 |
| `_additive_references(expression: str, column: str) -> list[str] \| None` | 같은 열의 SUM/양수 더하기를 주소 목록으로 한정해 전개. 범위 최대1,001행, 미지원 연산은 None |
| `_report_expression(cell: dict \| None) -> str \| None` | 원문 또는 범위가 확인된 같은 행 공유 합계/차감의 상대 열 주소만 읽음. 원본 follower의 formula.text는 변경하지 않음 |
| `_total_leaves(context: dict, sheet: str, address: str, leaf_cells: dict, visited: set[str]) -> list[str] \| None` | 총계 참조 → 확인된 말단 키. 순환·32단계 초과·파일당200,000회 방문·말단 수 초과·미지원 경로 차단, 중복 보존 |
| `_control_value(context: dict, sheet: str, cell: dict \| None, address: str, period: dict, **identity: str) -> dict` | 검증된 표시 단위와 원본 캐시 → 원 단위 대조값. 부재/미확정은 null. 캐시를 계산 정답으로 표현하지 않음 |
| `_reports(context: dict) -> tuple[list[dict], dict, list[dict]]` | `_report_binding`, `_total_leaves`, `_control_value` → 보고 말단·분류별 키집합·월별 대조값. 총계 말단의 중복·누락, OP 부호 확인 |
| `_line_value(context: dict, sheet: str, row: int, column: int, period: dict) -> dict` | 알려진 월 열 → 원본 슬롯. 실제0/저장빈칸/XML부재/수식오류·캐시누락 구분. `blank_ranges`의 해당 행·열 포함 여부로 압축된 저장 빈 셀도 STORED_BLANK로 보존 |
| `_lines(context: dict, classifications: dict) -> list[dict]` | `_row_context`, `validate_business_row`, `_line_value` → 활성 행별 차원·근거·전체기간 월값. 미사용만 제외하고 업무 오류·중복/개정을 보존 |

검증 정의: [test_submission_adapter.py](../../tests/test_submission_adapter.py). 모든 fixture는 `memory_workbook`, `append_sheet`를 사용하며 파일을 만들지 않습니다.

| 함수·메서드 | 책임 |
| --- | --- |
| `_column(number: int) -> str`, `_cell(workbook: dict, sheet: str, address: str) -> dict` | 독립 fixture 주소 생성과 메모리 셀 조회. 없는 셀은 StopIteration |
| `submission_workbook() -> dict` | 동일 계정의 서로 다른 매출 분류·비용 E:E·12개월·조직/계정표·보고 캐시를 독립 상수로 합성 |
| `test_formula_mapping_controls_and_input_are_preserved()` | 분류별 보고 키, 원 환산/OP, 원본·표준 후보의 깊은 동등성 |
| `test_month_slots_distinguish_zero_blank_missing_and_formula_error()` | 실제0·저장빈칸·XML부재·캐시오류와 원본 조회 근거 |
| `test_compressed_blank_spans_preserve_stored_cell_presence()` | 압축 빈 구간의 양 끝·구간 사이 틈·다른 행을 구분하고 실제0과 입력 불변을 유지 |
| `test_missing_header_and_unknown_report_are_not_filled()` | 없는 월 헤더·미등록 보고 분류를 추정하지 않음 |
| `test_shared_totals_and_unsupported_arithmetic_are_guarded()` | 공유 합계의 원문 보존·추가 연산 차단·OP 부호 검사 |
| `test_inactive_rows_stay_out_but_active_lookup_error_is_preserved()` | 미사용 행만 제외하고 실제 조직 오류·0 유지 |
| `test_bridge_adds_separate_adapter_and_keeps_preview_records()` | 1.3 분리 계약과 모든 기존 후보 value/formula/raw_value/data_type 불변 |
| `test_report_reference_budget_keeps_details_and_blocks_controls()` | 참조 작업량 초과 시 상세 보존·미확정 대조값·정확한 제한 진단 |

실행: `env -u EXCEL_PARSER_TEST_DATA PYTHONPATH=src:tests python3 -B -m unittest test_extraction test_mapping test_mapping_monthly test_business_validation test_service_bridge test_submission_adapter`. 테스트·원본 전수 대조 결과는 실제 실행 후 별도 기록하며, 임시 fixture·로그·결과 파일을 만들지 않습니다.

2026-11-16 실행 결과는 총67개 중65개 통과·외부 자료 의존2개 건너뜀입니다. 새 어댑터 회귀7개가 포함됩니다. 사용자 지정 사업부 원본17개를 메모리에서 읽은 전수 대조 결과는 다음과 같습니다. 전체 마스터와 하위 월별 자료는 이 범위에 포함하지 않았습니다.

- 모든 파일이 `SUCCEEDED`, 후보19,376개·미사용345행이며 제출 어댑터의 활성59행(매출35·비용24)·보고 말단150개·전체기간 월슬롯3,120개·대조값7,344개·원본 조회셀11,498개를 제공합니다. 원본 파일 SHA-256은 읽기 전후 모두 같았습니다.
- 2026 PLAN 상세12,036개는 각각 원본 숫자×1,000(천원→원)과 정확히 같고 17개 파일의 매출/비용 월합계408개가 기존 manifest와 정확히 같습니다. 전체기간의 대조값124,848개는 모두 AVAILABLE입니다.
- PLAN 말단/총계/OP 대조값31,212개 가운데31,186개는 상세 합계와 정확히 같고26개는 Excel 저장 캐시에서 최대0.000001원의 차이를 보였습니다. 0.00001원을 넘는 차이는0개입니다. 파서는 차이와 원문을 보정하지 않습니다. 전체기간 캐시82개(PLAN26개)는 후행0을 포함한 십진 scale이6보다 크지만, 후행0 제거 후 유효소수 자릿수가6을 넘는 값은0개입니다. 서비스의 허용 정책·경고·원본 차이 보존은 백엔드 구현을 따릅니다.
- 응답은28,845,244~28,886,218바이트로 기존64MiB 상한 안입니다. 첫 파일은 파싱2.73초·직렬화 포함3.37초·Mac 단독 프로세스 최대RSS 약488MB였으며,17개 순차 처리/직렬화/대조는 약63.55초입니다. 이 값은 관측값이며 OS 메모리 한도 보장이 아닙니다.
- 마지막 참조 작업량 보강 후 첫 파일의 실제 참조 방문8,976회가 상한200,000회 안이며 모든7,344개 대조값이 계속 AVAILABLE임을 확인했습니다. 브리지 JSON·회귀 결과는 stdout/메모리만 사용했고 샘플·보고서·로그·캐시·변환 파일을 만들지 않았습니다.

## __main__.py

### main

정의: [__main__.py](../../src/excel_parser/__main__.py)

서명: `main() -> int`

정적 호출 관계: 외부 호출 또는 동적 호출 가능. 전체 실행 흐름과 호출 코드를 함께 확인합니다..

```text
CLI 조회 또는 파싱·검증을 실행하고 종료 상태를 반환한다.

Args:
    없음. sys.argv에서 --root와 paths/files/parse 명령을 읽는다.
    parse의 inputs는 파일·폴더 목록이며 --write-output은 결과 저장 요청이다.

Returns:
    정상 완료 또는 의미 미확정이 있는 원본 추출 완료는 0.
    parse의 파일 처리·독립 검증 실패는 1. 프로세스 종료는 호출자가 처리한다.

Raises:
    SystemExit: 도움말은 0, 인자 오류는 2, 포착한 OSError/ValueError는 1로
        종료한다. 도움말·인자 오류는 설정 파일을 읽기 전에 처리한다.
    RuntimeError: 경로 해석 등에서 발생하면 이 함수가 포착하지 않고 전파한다.

Side Effects:
    조회는 메타데이터만, parse는 원본 Excel 내용을 읽는다.
    --write-output이 있을 때만 output 하위에 새 실행 결과를 생성한다.
    Data 내부와 기존 출력에는 쓰지 않는다. 콘솔 출력이 있다.
    Python 바이트코드 캐시는 호출 시 -B 또는 환경변수로 별도 차단해야 한다.
```

## extraction.py

### file_fingerprint

정의: [extraction.py](../../src/excel_parser/extraction.py)

서명: `file_fingerprint(path: Path) -> str`

정적 호출 관계: `extraction.extract_workbook`, `pipeline.run_parser`.

```text
원본 바이트의 SHA-256 식별자를 계산한다.

Args:
    path: 읽을 파일 경로.
Returns:
    SHA-256 16진수 문자열.
Raises:
    OSError: 파일 읽기에 실패한 경우.
Side Effects:
    파일을 청크 단위로 읽기만 한다.
```

### _cell_value

정의: [extraction.py](../../src/excel_parser/extraction.py)

서명: `_cell_value(node: ET.Element, shared: list[str], number_format: str | None) -> tuple[object, str, str | None]`

정적 호출 관계: `extraction._extract_sheet`.

```text
셀 저장 타입에 따라 원본 값을 손실 없이 읽는다.

Args:
    node: 원본 c 요소.
    shared: 공유 문자열 표.
    number_format: 숫자 표시 형식.
Returns:
    해석한 값, 자료형, v 요소 원문 튜플. 숫자는 문자열로 유지한다.
Raises:
    ValueError: 공유 문자열 인덱스가 유효하지 않은 경우.
Side Effects:
    없음. 수식을 계산하거나 표시 서식으로 부호를 바꾸지 않는다.
```

### _formula

정의: [extraction.py](../../src/excel_parser/extraction.py)

서명: `_formula(node: ET.Element, value: object, kind: str, shared: dict) -> dict | None`

정적 호출 관계: `extraction._extract_sheet`.

```text
수식과 저장 캐시 상태를 분리하여 보존한다.

Args:
    node: 원본 셀 요소.
    value: 저장 캐시의 해석 값.
    kind: 캐시 자료형.
    shared: 같은 시트의 공유 수식 원본 표.
Returns:
    수식 원문·속성·캐시 상태 또는 수식이 없으면 None.
Raises:
    없음.
Side Effects:
    없음. 공유 수식의 상대 참조를 임의 전개하거나 재계산하지 않는다.
```

### _blank_span

정의: [extraction.py](../../src/excel_parser/extraction.py)

서명: `_blank_span(spans: list[dict], node: ET.Element, row: int, column: int) -> None`

정적 호출 관계: `extraction._extract_sheet`.

```text
동일 스타일의 연속 빈 XML 셀을 구조 구간으로 압축한다.

Args:
    spans: 시트의 빈 셀 구간 목록.
    node: 내용이 없는 원본 c 요소.
    row: 1부터 시작하는 행 번호.
    column: 1부터 시작하는 열 번호.
Returns:
    없음. 전달받은 목록에 구간을 추가하거나 마지막 구간을 확장한다.
Raises:
    없음.
Side Effects:
    메모리 목록만 변경한다. 빈 셀에 값은 채우지 않는다.
```

### _extract_sheet

정의: [extraction.py](../../src/excel_parser/extraction.py)

서명: `_extract_sheet(archive: ZipFile, info: dict, file: dict, shared: list[str], styles: dict) -> dict`

정적 호출 관계: `extraction.extract_workbook`.

```text
한 시트의 내용 셀·숨김·병합·서식·메모와 빈 구조를 읽는다.

Args:
    archive: 읽기 전용 ZIP 패키지.
    info: name, sheet_id, state, part를 포함한 시트 정보.
    file: 원본 파일 식별 정보.
    shared: 공유 문자열 표.
    styles: 읽어 둔 셀 스타일 표.
Returns:
    셀 목록과 구조 정보를 포함한 시트 사전.
Raises:
    ValueError: 셀 주소·공유 문자열 참조가 유효하지 않은 경우.
    ET.ParseError: 시트 XML이 손상된 경우.
Side Effects:
    시트와 메모 XML을 읽기만 한다.
```

### extract_workbook

정의: [extraction.py](../../src/excel_parser/extraction.py)

서명: `extract_workbook(path: Path, root: Path | None=None) -> dict`

정적 호출 관계: `pipeline.run_parser` 등 경로 호출자 → `file_fingerprint`, `_extract_workbook_source`.

```text
한 xlsx/xlsm 파일의 모든 시트를 읽기 전용으로 추출한다.

Args:
    path: 원본 OOXML 파일 경로.
    root: 출처 경로를 상대 표현할 프로젝트 루트. 없으면 절대 경로를 사용한다.
Returns:
    file, sheets, styles, package_metadata, diagnostics를 가진 추출 결과.
Raises:
    ValueError: 지원하지 않는 형식이나 잘못된 관계·셀 구조인 경우.
    OSError: 원본 파일을 읽을 수 없는 경우.
    BadZipFile: ZIP 패키지가 손상된 경우.
    ET.ParseError: XML 파트가 손상된 경우.
Side Effects:
    원본을 읽기만 한다. Excel 실행·재계산·파일 생성은 하지 않는다.
```

### extract_workbook_bytes / _extract_workbook_source

정의: [extraction.py](../../src/excel_parser/extraction.py). 경로 API의 OOXML 읽기 본문을 공통 함수로 분리했으며 기존 경로의 식별·해시·상대 경로 규칙은 유지합니다.

| 함수 서명 | 입력·반환·호출 관계 | 예외·부작용·한계 |
| --- | --- | --- |
| `extract_workbook_bytes(content: bytes, filename: str) -> dict` | 브리지의 바이트 → 기존 file/sheets/styles/package_metadata/diagnostics. SHA-256은 원본 바이트, 표시 path는 파일명 | 잘못된 파일명은 ValueError; ZIP/XML/관계 오류 전파. BytesIO만 읽으며 파일을 생성하지 않음. 비신뢰 서비스 입력은 브리지의 자원·XML 검사를 먼저 거쳐야 함 |
| `_extract_workbook_source(source: Path \| BytesIO, file: dict) -> dict` | 경로·메모리 API 공통 본문. ZipFile과 기존 OOXML 보조 함수 호출 | 경로 입력의 읽기 오류와 ZIP/XML/셀·관계 오류 전파. 읽기만 수행하고 수식 재계산·외부 관계 접속 없음 |

## mapping.py

### _text

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_text(cell: dict | None) -> str`

정적 호출 관계: `mapping._header_at`, `mapping._period`, `mapping._layout`, `mapping._organization_registry`, `mapping._workbook_organization`, `mapping._unit`, `mapping._statement_items`, `mapping._row_context`.

```text
셀의 저장값을 검색용 문자열로 반환한다.

Args:
    cell: 추출 셀 또는 None. 수식은 이미 추출된 캐시값을 사용한다.
Returns:
    값이 없으면 빈 문자열, 그 외에는 저장값의 문자열 표현.
Raises:
    없음. 추출 계약을 만족하는 입력을 전제로 한다.
Side Effects:
    없음. 입력을 변경하거나 수식을 계산하지 않는다.
```

### _ref

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_ref(cell: dict | None) -> dict | None`

정적 호출 관계: `mapping._period`, `mapping._organization_registry`, `mapping._workbook_organization`, `mapping._unit`, `mapping._statement_items`, `mapping._row_context`, `mapping.map_workbook`.

```text
해석 근거 셀과 원본 레코드의 연결 정보를 만든다.

Args:
    cell: 근거 셀 또는 None.
Returns:
    시트·셀·원본 레코드 참조 또는 None.
Raises:
    없음. 추출 계약을 만족하는 입력을 전제로 한다.
Side Effects:
    없음.
```

### _index

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_index(sheet: dict) -> dict`

정적 호출 관계: `mapping.map_workbook`.

```text
행·열별 셀과 주소별 셀의 메모리 색인을 만든다.

Args:
    sheet: extraction의 시트 구조.
Returns:
    rows와 addresses 색인을 포함하는 사전.
Raises:
    KeyError: 필수 행·열·주소가 누락된 잘못된 추출 자료.
    ValueError: 병합 범위 주소가 잘못된 경우.
Side Effects:
    없음. 원본 셀 사전은 읽기 참조로만 사용한다.
```

### _header_at

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_header_at(index: dict, row: int, column: int) -> dict | None`

정적 호출 관계: `mapping._layout`.

```text
병합 헤더를 읽을 때만 기준 셀과 상속 위치를 연결한다.

Args:
    index: 시트 색인과 병합 범위 목록.
    row: 조회할 1부터 시작하는 헤더 행.
    column: 조회할 1부터 시작하는 헤더 열.
Returns:
    직접 셀 또는 상속 근거가 붙은 기준 셀 사본. 없으면 None.
Raises:
    없음. 추출 계약의 유효한 병합 범위를 전제로 한다.
Side Effects:
    없음. 원본 빈 셀에 금액을 복제하지 않고 헤더 조회에만 사용한다.
```

### _scenario

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_scenario(label: str) -> str`

정적 호출 관계: `mapping._period`, `mapping._layout`.

```text
명시된 한국어 값 구분을 계약의 시나리오로 해석한다.

Args:
    label: 원본 헤더 문자열.
Returns:
    actual, plan, estimate, mixed, unknown 중 하나.
Raises:
    없음.
Side Effects:
    없음. 월이나 파일 이름으로 시나리오를 추측하지 않는다.
```

### _year

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_year(label: str) -> int | None`

정적 호출 관계: `mapping._period`, `mapping._layout`.

```text
명시된 연도 또는 이 템플릿의 작은따옴표 연도를 읽는다.

Args:
    label: 2027년 또는 '27년 같은 원본 연도 헤더.
Returns:
    4자리 연도. 명시 연도가 없으면 None.
Raises:
    없음.
Side Effects:
    없음.
```

### _period

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_period(cells: list[dict], *, annual: bool=False) -> dict`

정적 호출 관계: `mapping._layout`, `mapping.map_workbook`.

```text
근거 헤더에서 대상 연월과 기간 집계 방식을 해석한다.

Args:
    cells: 같은 값 열을 설명하는 원본 헤더 셀 목록.
    annual: 단일 복합 헤더의 연간 집계를 명시하는 템플릿 규칙.
Returns:
    기간·시나리오·원본 헤더·근거 셀 사전.
Raises:
    없음. 추출 계약을 만족하는 입력을 전제로 한다.
Side Effects:
    없음. 제출 월과 대상 월을 결합하지 않는다.
```

### _layout

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_layout(sheet: dict, index: dict) -> dict`

정적 호출 관계: `mapping.map_workbook`.

```text
파일명이 아닌 실제 헤더 조합으로 알려진 재무 표를 식별한다.

Args:
    sheet: 시트 구조.
    index: _index 결과.
Returns:
    표 종류, 헤더 행, 값 열별 기간 및 헤더명별 열 색인.
Raises:
    없음. 추출 계약을 만족하는 입력을 전제로 한다.
Side Effects:
    없음. 빈 셀에 원본 값이나 금액을 복제하지 않는다.
```

### _periods_with_cross_evidence

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_periods_with_cross_evidence(layouts: list[dict]) -> None`

정적 호출 관계: `mapping.map_workbook`.

```text
같은 워크북의 명시 월별 시나리오로 실적 연도군의 의미를 보완한다.

Args:
    layouts: 표 레이아웃 목록. 시트별 기간 사전이 갱신된다.
Returns:
    없음.
Raises:
    없음. 내부 생성 레이아웃을 전제로 한다.
Side Effects:
    전달된 레이아웃의 기간만 갱신한다. 원본 workbook은 변경하지 않는다.
```

### _organization_registry

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_organization_registry(indices: list[dict]) -> dict[str, dict]`

정적 호출 관계: `mapping.map_workbook`.

```text
워크북 내 사업부 코드표에서 코드와 이름의 확인 근거를 읽는다.

Args:
    indices: 각 시트의 메모리 색인.
Returns:
    사업부 코드별 이름·근거. 충돌 이름은 확정하지 않는다.
Raises:
    없음. 추출 계약을 만족하는 입력을 전제로 한다.
Side Effects:
    없음. 다른 워크북이나 마스터 파일에 접근하지 않는다.
```

### _workbook_organization

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_workbook_organization(layouts: list[dict], indices: list[dict], registry: dict) -> dict`

정적 호출 관계: `mapping.map_workbook`.

```text
상세 행의 조직 식별자를 모아 단일 조직과 여러 조직 범위를 구분한다.

Args:
    layouts: 시트별 레이아웃.
    indices: 같은 순서의 시트별 색인.
    registry: 같은 워크북의 조직 코드표.
Returns:
    단일 코드·원래 이름 또는 multi_organization 범위와 원본 근거.
Raises:
    없음. 내부 생성 자료를 전제로 한다.
Side Effects:
    없음. 파일 이름의 조직을 확정값으로 사용하지 않는다.
```

### _metric

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_metric(label: str) -> str | None`

정적 호출 관계: `mapping._statement_items`, `mapping._row_context`, `mapping.map_workbook`.

```text
실제 항목명에서 GAP 관련 지표를 식별한다.

Args:
    label: 표의 항목명. 파일명이나 임의 추정값은 전달하지 않는다.
Returns:
    OP, CONTINGENCY, OP_EX_CONTINGENCY 또는 None.
Raises:
    없음.
Side Effects:
    없음. 지표 값을 계산하거나 조정하지 않는다.
```

### _unit

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_unit(index: dict) -> tuple[str | None, list[dict]]`

정적 호출 관계: `mapping.map_workbook`.

```text
제목 또는 단위 설명에 명시된 기본 금액 단위를 찾는다.

Args:
    index: 현재 시트 색인.
Returns:
    원·천원·백만원·억원 중 하나와 근거 셀 목록. 없으면 None.
Raises:
    없음.
Side Effects:
    없음. 표시 형식의 쉼표를 저장값의 단위로 추측하지 않는다.
```

### _submission

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_submission(metadata: dict) -> dict`

정적 호출 관계: `mapping.map_workbook`.

```text
사용자가 전달한 제출 정보만 보존하고 명확한 버전 표기를 분리한다.

Args:
    metadata: submission_month, submission_version 등의 선택 메타데이터.
Returns:
    버전 원문, 월별 회차, 개정번호, 제출 월과 확인 상태.
Raises:
    ValueError: 버전 원문이 문자열이 아니거나 제출 월 형식이 잘못된 경우.
Side Effects:
    없음. 버전을 발급하거나 승인 여부를 판정하지 않는다.
```

### _statement_items

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_statement_items(index: dict, layout: dict) -> dict[int, dict]`

정적 호출 관계: `mapping.map_workbook`.

```text
답안지의 들여쓰기 열을 계층 항목 경로로 보존한다.

Args:
    index: 답안지 셀 색인.
    layout: financial_statement 레이아웃.
Returns:
    행별 항목명, 계층 경로와 원본 근거.
Raises:
    없음. 내부 생성 자료를 전제로 한다.
Side Effects:
    없음. 원본 빈 셀에는 값을 쓰지 않는다.
```

### _row_context

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_row_context(cell: dict, index: dict, layout: dict, common_org: dict, registry: dict, statement_items: dict, inactive_template: bool | None = None) -> dict`

정적 호출 관계: `mapping.map_workbook`.

```text
값 셀에 대응하는 조직·항목·역할을 실제 헤더에서 가져온다.

Args:
    cell: 값 또는 수식의 원본 셀.
    index: 현재 시트 색인.
    layout: 확인된 표 레이아웃.
    common_org: 워크북 상세 행에서 확인한 조직 범위.
    registry: 같은 워크북의 조직 코드표.
    statement_items: 답안지의 행별 항목.
    inactive_template: 호출자가 행별로 캐시한 미사용 판정. None이면 직접 확인한다.
Returns:
    organization, item, dimensions, role, financial을 담은 사전.
Raises:
    없음. 내부 생성 자료를 전제로 한다.
Side Effects:
    없음. 원본이나 공통 조직 사전을 변경하지 않는다.
```

### _gap_relationships

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `_gap_relationships(records: list[dict]) -> list[dict]`

정적 호출 관계: `mapping.map_workbook`.

```text
동일 조직·기간에서 GAP 지표의 원본과 명시 제외값을 연결한다.

Args:
    records: 현재 워크북의 표준 레코드.
Returns:
    값 계산 없이 원본 지표 참조와 관계 확인 상태를 담은 목록.
Raises:
    없음. 내부 생성 표준 레코드를 전제로 한다.
Side Effects:
    없음. 영업이익에 Contingency를 더하거나 빼지 않는다.
```

### map_workbook

정의: [mapping.py](../../src/excel_parser/mapping.py)

서명: `map_workbook(workbook: dict, metadata: dict | None=None) -> dict`

정적 호출 관계: `pipeline.run_parser`.

```text
추출 셀 전체 후보를 출처가 연결된 표준 레코드로 변환한다.

Args:
    workbook: extract_workbook의 원본 보존 결과. 입력은 변경하지 않는다.
    metadata: 선택 조직명·코드, 제출 월·버전. 조직 보완은
        organization_verified=True인 입력만 사용하며 파일명에서 추측하지 않는다.
Returns:
    records, diagnostics, summary, gap_relationships 사전. Decimal은 저장
    계층에서 문자열로 직렬화한다. 숫자·수식 후보는 미매핑이어도 남는다.
Raises:
    ValueError: 잘못된 제출 메타데이터. 정규화 계약 오류도 전파한다.
    KeyError: 추출 계약 필수 필드가 없는 입력.
Side Effects:
    없음. 파일 접근·수식 계산·DB 작업·결과 저장을 하지 않는다.
```

## normalization.py

### inspect_number_format

정의: [normalization.py](../../src/excel_parser/normalization.py)

서명: `inspect_number_format(number_format: str | None) -> dict`

정적 호출 관계: `mapping.map_workbook`, `normalization._base_result`.

```text
숫자 표시 형식의 구간·조건·색·표시 배율을 값 변경 없이 요약한다.

Args:
    number_format: 원본 Excel 표시 형식. 없으면 None.

Returns:
    원문, 구간별 메타데이터, 백분율·날짜 표시 여부를 담은 사전.

Raises:
    없음. 알 수 없는 형식은 원문을 보존하며 완전한 렌더링을 시도하지 않는다.

Side Effects:
    없음. 파일을 읽거나 쓰지 않는다.
```

### _base_result

정의: [normalization.py](../../src/excel_parser/normalization.py)

서명: `_base_result(value: object, value_kind: str | None, unit: str | None, number_format: str | None) -> dict`

정적 호출 관계: `normalization.normalize_number`.

```text
정규화 상태를 담을 독립된 결과 객체를 만든다.

Args:
    value: 원본 저장값 또는 문자열.
    value_kind: 호출자가 확인한 필드 종류.
    unit: 호출자가 확인한 원본 단위.
    number_format: 원본 표시 형식.

Returns:
    정규화값이 없는 초기 결과 사전.

Raises:
    없음.

Side Effects:
    없음. 입력 객체를 변경하지 않는다.
```

### _stop

정의: [normalization.py](../../src/excel_parser/normalization.py)

서명: `_stop(result: dict, status: str, code: str, message: str) -> dict`

정적 호출 관계: `normalization.normalize_number`.

```text
값을 확정하지 못한 이유와 상태를 결과에 기록한다.

Args:
    result: 현재 호출에서 생성한 결과 사전.
    status: missing, not_numeric, unresolved 중 하나.
    code: 기계 판별에 사용하는 규칙·진단 코드.
    message: 사람이 읽을 수 있는 미확정 사유.

Returns:
    상태와 진단이 추가된 같은 결과 객체.

Raises:
    없음.

Side Effects:
    해당 결과 사전만 변경하며 원본 입력이나 파일은 변경하지 않는다.
```

### _unit_info

정의: [normalization.py](../../src/excel_parser/normalization.py)

서명: `_unit_info(unit: str | None) -> tuple[str | None, Decimal, str | None] | None`

정적 호출 관계: `normalization.normalize_number`.

```text
등록된 단위의 통화·배율·값 종류를 찾는다.

Args:
    unit: 단위 문자열. None은 배율이 지정되지 않았음을 뜻한다.

Returns:
    통화, Decimal 배율, 값 종류의 튜플. 미등록 단위이면 None.

Raises:
    없음.

Side Effects:
    없음.
```

### _remove_affixes

정의: [normalization.py](../../src/excel_parser/normalization.py)

서명: `_remove_affixes(text: str) -> tuple[str, list[str], list[str], str | None]`

정적 호출 관계: `normalization.normalize_number`.

```text
숫자 바깥의 등록된 통화·단위 표기만 분리한다.

Args:
    text: 유니코드와 공백을 정규화한 전체 문자열.

Returns:
    숫자 부분, 단위 목록, 통화 기호 목록, 오류 코드의 튜플.

Raises:
    없음.

Side Effects:
    없음.
```

### _parse_decimal_token

정의: [normalization.py](../../src/excel_parser/normalization.py)

서명: `_parse_decimal_token(token: str, locale: str | None) -> Decimal | None`

정적 호출 관계: `normalization.normalize_number`.

```text
부호 없는 숫자 토큰을 로케일과 전체 구분자 패턴으로 해석한다.

Args:
    token: 부호와 단위가 제거된 숫자 문자열. 지수부의 부호는 허용한다.
    locale: 등록된 로케일 또는 None. None에서는 모호한 단일 구분자를 거부한다.

Returns:
    유효한 유한 Decimal. 구분자나 전체 형태가 모호하면 None.

Raises:
    없음. Decimal 변환 실패는 None으로 반환한다.

Side Effects:
    없음.
```

### normalize_number

정의: [normalization.py](../../src/excel_parser/normalization.py)

서명: `normalize_number(value: object, *, data_type: str, value_kind: str | None=None, unit: str | None=None, number_format: str | None=None, locale: str | None=None, symbol_rules: dict | None=None) -> dict`

정적 호출 관계: `mapping.map_workbook`.

```text
원본 자료형을 우선하여 숫자·문자열 부호·단위를 한 번만 정규화한다.

Args:
    value: 원본 저장값. 숫자 셀의 XML 숫자 문자열도 허용한다.
    data_type: number/string/boolean/error/date/blank 등 원본 자료형.
    value_kind: amount/ratio/quantity/number 또는 보호할 code/date/text 등.
    unit: 원본 제목·헤더 등에서 확인한 단위. 표시 배율과 별개이다.
    number_format: 원본 숫자 표시 형식. 저장 숫자의 부호를 바꾸지 않는다.
    locale: 문자열 숫자의 확인된 로케일. 미지정 모호 표기는 거부한다.
    symbol_rules: 확인된 기호별 부호 규칙. 값은 1/-1 또는 sign을 가진 사전.

Returns:
    원본·Decimal 정규화값·상태·단위·배율·규칙·진단을 담은 사전.
    confirmed만 정규화값을 가진다. JSON 직렬화 시 Decimal은 문자열로 쓴다.

Raises:
    없음. 지원하지 않는 입력·표기는 미확정 진단으로 반환한다.

Side Effects:
    없음. 파일·DB·콘솔을 사용하거나 원본 객체를 변경하지 않는다.
```

## ooxml.py

### local_name

정의: [ooxml.py](../../src/excel_parser/ooxml.py)

서명: `local_name(tag: str) -> str`

정적 호출 관계: `extraction._extract_sheet`, `extraction.extract_workbook`, `ooxml.xml_text`, `ooxml.read_comments`.

```text
네임스페이스를 제외한 XML 이름을 반환한다.

Args:
    tag: ElementTree 태그 또는 속성명.
Returns:
    마지막 닫는 중괄호 뒤의 이름.
Raises:
    없음. 문자열 입력을 전제로 한다.
Side Effects:
    없음.
```

### xml_text

정의: [ooxml.py](../../src/excel_parser/ooxml.py)

서명: `xml_text(element: ET.Element | None) -> str`

정적 호출 관계: `extraction._cell_value`, `extraction.extract_workbook`, `ooxml.read_comments`.

```text
리치 텍스트의 실제 표시 텍스트를 연결한다.

Args:
    element: 공유 문자열·inline 문자열·메모 요소 또는 None.
Returns:
    발음 안내 rPh를 제외한 t 요소의 연결 문자열.
Raises:
    없음.
Side Effects:
    없음. 전달받은 트리를 변경하지 않는다.
```

### cell_position

정의: [ooxml.py](../../src/excel_parser/ooxml.py)

서명: `cell_position(address: str) -> tuple[int, int, str]`

정적 호출 관계: `extraction._extract_sheet`, `ooxml.range_bounds`.

```text
A1 주소를 1부터 시작하는 행·열 번호로 해석한다.

Args:
    address: 달러 기호를 포함할 수 있는 셀 주소.
Returns:
    행 번호, 열 번호, 열 문자 튜플.
Raises:
    ValueError: 유효한 단일 셀 주소가 아닌 경우.
Side Effects:
    없음.
```

### range_bounds

정의: [ooxml.py](../../src/excel_parser/ooxml.py)

서명: `range_bounds(reference: str) -> tuple[int, int, int, int]`

정적 호출 관계: `extraction._extract_sheet`, `mapping._index`.

```text
직사각형 A1 범위의 경계를 반환한다.

Args:
    reference: 단일 주소 또는 시작:끝 주소.
Returns:
    시작 행·열, 끝 행·열 번호.
Raises:
    ValueError: 주소가 잘못된 경우.
Side Effects:
    없음.
```

### relationships

정의: [ooxml.py](../../src/excel_parser/ooxml.py)

서명: `relationships(archive: ZipFile, part: str) -> dict[str, dict]`

정적 호출 관계: `extraction._extract_sheet`, `extraction.extract_workbook`, `ooxml.read_comments`.

```text
패키지 내부 관계를 대상 경로와 함께 반환한다.

Args:
    archive: 읽기 전용 ZIP 패키지.
    part: 소유 XML 파트. 패키지 루트는 빈 문자열.
Returns:
    관계 ID를 키로 하는 속성과 해석된 part 경로.
Raises:
    ValueError: 내부 관계가 패키지 밖을 가리키는 경우.
    ET.ParseError: 관계 XML이 손상된 경우.
Side Effects:
    관계 XML만 읽는다. 외부 관계는 접속하지 않는다.
```

### read_styles

정의: [ooxml.py](../../src/excel_parser/ooxml.py)

서명: `read_styles(archive: ZipFile, part: str | None) -> dict`

정적 호출 관계: `extraction.extract_workbook`.

```text
셀 서식과 원본 스타일 XML을 보존한다.

Args:
    archive: 읽기 전용 ZIP 패키지.
    part: styles 파트 경로. 없으면 None.
Returns:
    custom_formats, cell_formats, 원본 XML을 담은 사전.
Raises:
    ET.ParseError: 스타일 XML이 손상된 경우.
Side Effects:
    스타일 XML만 읽으며 서식을 적용하거나 값을 변경하지 않는다.
```

### is_date_format

정의: [ooxml.py](../../src/excel_parser/ooxml.py)

서명: `is_date_format(number_format: str | None) -> bool`

정적 호출 관계: `extraction._cell_value`.

```text
명시적인 날짜·시간 서식을 보수적으로 탐지한다.

Args:
    number_format: Excel 표시 형식 또는 None.
Returns:
    리터럴 제거 뒤 날짜·시간 토큰이 확인되면 True.
Raises:
    없음.
Side Effects:
    없음. 일련번호를 날짜나 금액으로 환산하지 않는다.
```

### read_comments

정의: [ooxml.py](../../src/excel_parser/ooxml.py)

서명: `read_comments(archive: ZipFile, sheet_part: str) -> dict[str, list[dict]]`

정적 호출 관계: `extraction._extract_sheet`.

```text
시트에 연결된 일반·스레드 메모를 셀 주소별로 보존한다.

Args:
    archive: 읽기 전용 ZIP 패키지.
    sheet_part: 시트 XML 경로.
Returns:
    셀 주소별 텍스트·작성자·원본 XML 목록.
Raises:
    ET.ParseError: 메모 XML이 손상된 경우.
Side Effects:
    연결된 메모 파트를 읽는다. 외부 서비스에 접근하지 않는다.
```

## paths.py

### _paths_overlap

정의: [paths.py](../../src/excel_parser/paths.py)

서명: `_paths_overlap(first: Path, second: Path) -> bool`

정적 호출 관계: `paths.ProjectPaths.load`.

```text
두 경로가 같거나 서로를 포함하는지 문자열 기준으로 비교한다.

Args:
    first: 비교할 첫 번째 경로. 호출자가 해석을 마친 경로를 전달한다.
    second: 비교할 두 번째 경로. 첫 번째 경로와 같은 기준을 사용한다.

Returns:
    대소문자와 유니코드 조합 차이를 무시했을 때 겹치면 True.

Raises:
    없음. Path 타입의 정상 입력을 기준으로 한다.

Side Effects:
    없음. 파일시스템에 접근하지 않으며 심볼릭 링크도 직접 해석하지 않는다.
```

### _find_project_root

정의: [paths.py](../../src/excel_parser/paths.py)

서명: `_find_project_root() -> Path`

정적 호출 관계: `paths.ProjectPaths.load`.

```text
설정 파일과 패키지 정의가 함께 있는 프로젝트 루트를 찾는다.

Args:
    없음. 현재 작업 위치와 이 소스 파일의 위치를 탐색 기준으로 사용한다.

Returns:
    pyproject.toml과 config/paths.toml을 포함하는 루트의 절대 경로.

Raises:
    FileNotFoundError: 모든 후보에서 프로젝트 루트를 찾지 못한 경우.
    OSError: 현재 위치 확인이나 경로 해석에서 파일시스템 오류가 전파된 경우.
    RuntimeError: 실행 환경에 따라 링크 순환 등 경로 해석이 실패한 경우.

Side Effects:
    후보 경로의 파일 존재 여부를 확인한다. 내용 읽기와 파일 쓰기는 없다.
```

### ProjectPaths.load

정의: [paths.py](../../src/excel_parser/paths.py)

서명: `ProjectPaths.load(cls, root: str | Path | None=None) -> 'ProjectPaths'`

정적 호출 관계: `__main__.main`, `pipeline.run_parser`.

```text
설정 파일을 읽어 서로 겹치지 않는 프로젝트 내부 경로를 반환한다.

Args:
    root: 프로젝트 루트. None이면 자동 탐색한다. 상대 경로를 주면
        현재 작업 위치를 기준으로 해석하며 '~'는 사용자 홈으로 확장한다.

Returns:
    root, input_dir, output_dir, logs_dir를 담은 ProjectPaths 객체.
    설정된 폴더의 실제 존재 여부나 접근 권한은 보장하지 않는다.

Raises:
    FileNotFoundError: 프로젝트 루트나 경로 설정 파일을 찾지 못한 경우.
    tomllib.TOMLDecodeError: 설정 파일이 올바른 TOML 형식이 아닌 경우.
    ValueError: 필수 설정이 없거나 경로가 프로젝트 밖 또는 서로 겹친 경우.
    OSError: 설정 읽기나 경로 해석에서 파일시스템 오류가 전파된 경우.
    RuntimeError: 홈 경로나 심볼릭 링크를 실행 환경에서 해석할 수 없는 경우.

Side Effects:
    config/paths.toml을 읽고 경로 정보를 조회한다.
    원본 파일 내용을 읽거나 파일·디렉토리를 생성하지 않는다.
```

### ProjectPaths.list_input_files

정의: [paths.py](../../src/excel_parser/paths.py)

서명: `ProjectPaths.list_input_files(self, *, recursive: bool=False) -> list[Path]`

정적 호출 관계: 외부 호출 또는 동적 호출 가능. 전체 실행 흐름과 호출 코드를 함께 확인합니다..

```text
입력 디렉토리에서 대상 엑셀 파일의 경로를 정렬해 반환한다.

Args:
    recursive: True이면 하위 폴더도 탐색한다. 기본값 False는 직속 항목만
        확인한다. self.input_dir를 기준으로 파일 목록을 읽는다.

Returns:
    대소문자와 관계없이 .xlsx 또는 .xlsb인 파일의 경로 목록.
    '~$'로 시작하는 파일은 제외한다. 전체 경로의 casefold() 결과로
    정렬하고 같으면 원래 경로 문자열로 비교한다. 대상이 없으면 빈 목록.

Raises:
    FileNotFoundError: 입력 경로가 없거나 디렉토리로 확인되지 않는 경우.
    OSError: 디렉토리 탐색 중 파일시스템 접근 오류가 전파된 경우.

Side Effects:
    디렉토리 항목과 파일 정보를 조회한다. 파일 내용 읽기와 쓰기는 없다.
    파일을 가리키는 심볼릭 링크도 포함할 수 있으며, 반환 경로의 대상이
    입력 디렉토리 안에 있는지까지 검증하지는 않는다.
```

## pipeline.py

### json_value

정의: [pipeline.py](../../src/excel_parser/pipeline.py)

서명: `json_value(value: object) -> str`

정적 호출 관계: 외부 호출 또는 동적 호출 가능. 전체 실행 흐름과 호출 코드를 함께 확인합니다..

```text
Decimal을 정밀도 손실 없는 JSON 문자열 값으로 직렬화한다.

Args:
    value: json 모듈이 기본 처리할 수 없는 객체.
Returns:
    Decimal의 고정 소수점 문자열.
Raises:
    TypeError: 등록하지 않은 타입인 경우.
    ValueError: 유한하지 않은 Decimal인 경우.
Side Effects:
    없음.
```

### collect_inputs

정의: [pipeline.py](../../src/excel_parser/pipeline.py)

서명: `collect_inputs(inputs: list[Path], *, recursive: bool=False) -> list[Path]`

정적 호출 관계: `pipeline.run_parser`.

```text
명시한 파일·폴더에서 Excel 입력을 빠짐없이 수집한다.

Args:
    inputs: 단일·복수 파일 또는 디렉토리 경로.
    recursive: 디렉토리 하위도 탐색할지 여부.
Returns:
    중복 실제 경로를 제거한 정렬 목록. 미지원 Excel 형식도 진단용으로 포함한다.
Raises:
    FileNotFoundError: 입력이 없거나 파일·디렉토리가 아닌 경우.
    ValueError: 탐색 결과 Excel 파일이 없는 경우.
Side Effects:
    경로 정보만 조회한다. Excel '~$' 잠금 파일은 제외한다.
```

### _write_json

정의: [pipeline.py](../../src/excel_parser/pipeline.py)

서명: `_write_json(stream: TextIO, value: object) -> None`

정적 호출 관계: `pipeline.run_parser`.

```text
열린 결과 스트림에 JSON 레코드 한 줄을 기록한다.

Args:
    stream: 호출자가 명시적으로 연 출력 스트림.
    value: JSON으로 직렬화할 레코드.
Returns:
    없음.
Raises:
    TypeError: 계약 밖의 객체가 포함된 경우.
    OSError: 결과 기록 실패.
Side Effects:
    전달받은 스트림에 한 줄을 쓴다. 새 경로를 결정하거나 열지 않는다.
```

### _file_metadata

정의: [pipeline.py](../../src/excel_parser/pipeline.py)

서명: `_file_metadata(metadata: dict, file: dict) -> dict`

정적 호출 관계: `pipeline.run_parser`.

```text
실행 기본값과 파일별 명시 메타데이터를 결합한다.

Args:
    metadata: defaults와 files를 포함하는 실행 메타데이터.
    file: 파일명·출처 경로·식별자.
Returns:
    해당 파일에 적용할 평탄한 메타데이터 사전.
Raises:
    ValueError: 메타데이터 구조가 사전이 아닌 경우.
Side Effects:
    없음. 원본 파일과 입력 사전을 변경하지 않는다.
```

### _record_observations

정의: [pipeline.py](../../src/excel_parser/pipeline.py)

서명: `_record_observations(records: list[dict], observations: dict) -> None`

정적 호출 관계: `pipeline.run_parser`.

```text
실제로 적용한 정규화 규칙의 횟수와 출처 예시를 집계한다.

Args:
    records: 한 파일의 표준 레코드.
    observations: 누적할 규칙별 사전.
Returns:
    없음. observations를 메모리에서 갱신한다.
Raises:
    없음. 매핑 계약을 충족한 입력을 전제로 한다.
Side Effects:
    전달받은 누적 사전만 변경한다.
```

### run_parser

정의: [pipeline.py](../../src/excel_parser/pipeline.py)

서명: `run_parser(inputs: list[Path], *, root: Path, recursive: bool=False, output_dir: Path | None=None, metadata: dict | None=None) -> dict`

정적 호출 관계: `__main__.main`.

```text
입력 전체를 순차 처리하고 원본 보존·독립 검증 결과를 반환한다.

Args:
    inputs: 원본 파일 또는 디렉토리 목록.
    root: 프로젝트 루트.
    recursive: 하위 디렉토리 탐색 여부.
    output_dir: 명시적으로 요청된 새 결과 디렉토리. None이면 저장하지 않는다.
    metadata: defaults와 파일별 files 메타데이터. 누락 정보는 추정하지 않는다.
Returns:
    실행 요약. 파일별 실패·미확정·검증 통과 여부를 별도 필드로 담는다.
Raises:
    ValueError: 입력·메타데이터·출력 경로가 허용된 범위를 벗어난 경우.
    OSError: 원본 탐색 또는 결과 기록에 실패한 경우.
Side Effects:
    원본을 읽기만 한다. output_dir 지정 시에만 새로운 결과 파일을 생성한다.
    기존 결과를 덮어쓰지 않으며 Data 내부에는 쓰지 않는다.
```

## validation.py

### _index_sheets

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `_index_sheets(archive: ZipFile) -> list[dict[str, str]]`

정적 호출 관계: `validation.validate_extraction`, `validation._read_numeric_sources`, `validation.compare_master`.

```text
통합문서 관계를 직접 읽어 모든 시트의 이름과 XML 위치를 얻는다.

Args:
    archive: 읽기용으로 열린 OOXML ZIP.
Returns:
    문서 순서의 name, state, part 사전 목록.
Raises:
    KeyError: 필수 ZIP 부분이나 관계가 없는 경우.
    ET.ParseError: 원본 XML이 손상된 경우.
Side Effects:
    통합문서와 관계 XML만 읽는다. 파일을 생성하지 않는다.
```

### _read_strings

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `_read_strings(archive: ZipFile) -> list[str]`

정적 호출 관계: `validation.validate_extraction`, `validation._read_numeric_sources`.

```text
공유 문자열의 실제 텍스트를 원본 순서대로 읽는다.

Args:
    archive: 읽기용 OOXML ZIP.
Returns:
    공유 문자열 목록. 부분이 없으면 빈 목록.
Raises:
    ET.ParseError: 문자열 XML이 손상된 경우.
Side Effects:
    공유 문자열 XML을 메모리로 읽으며 쓰지 않는다.
```

### _decode_cell

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `_decode_cell(cell: ET.Element, strings: list[str]) -> dict[str, Any]`

정적 호출 관계: `validation._scan_sheet`.

```text
개별 원본 셀의 저장값·수식·공유 문자열을 독립 해석한다.

Args:
    cell: 원본 XML의 c 요소.
    strings: 원본 공유 문자열 목록.
Returns:
    비교에 필요한 원본 값과 수식·속성의 사전.
Raises:
    IndexError: 공유 문자열 번호가 유효하지 않은 경우.
    ValueError: 공유 문자열 번호를 정수로 읽을 수 없는 경우.
Side Effects:
    없음. 제공된 XML 요소만 조회한다.
```

### _scan_sheet

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `_scan_sheet(archive: ZipFile, part: str, strings: list[str]) -> dict[str, Any]`

정적 호출 관계: `validation.validate_extraction`, `validation._read_numeric_sources`.

```text
한 시트 XML을 순회해 주소·값·행열·병합의 독립 근거를 만든다.

Args:
    archive: 읽기용 OOXML ZIP.
    part: 검사할 시트 XML 부분 경로.
    strings: 공유 문자열 목록.
Returns:
    전체 XML 주소 집합, 값 셀, 행열 속성, 병합 범위와 일반·대화형 메모 목록.
Raises:
    KeyError: 지정된 ZIP 부분이 없는 경우.
    ET.ParseError: 원본 XML이 손상된 경우.
Side Effects:
    시트와 연결된 메모 XML을 읽으며 원본은 수정하지 않는다.
```

앞의 `_scan_sheet` 메모 검증은 [test_validation.py](../../tests/test_validation.py)의 `IndependentValidationTests.test_legacy_and_threaded_comments_are_verified_independently`로 회귀 검사한다. 메모리 ZIP에 일반 리치 텍스트 메모와 대화형 메모를 같은 셀 및 내용 없는 셀에 구성하고, 정상 보존 통과와 대화형 메모 삭제·변조 탐지를 확인한다. 인자·반환값은 없고 불일치 시 AssertionError가 발생하며, 파일·캐시 생성이나 외부 관계 조회는 없다.

### _column_label

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `_column_label(index: int) -> str`

정적 호출 관계: `validation._preserved_addresses`.

```text
1부터 시작하는 열 번호를 A1 표기의 열 이름으로 변환한다.

Args:
    index: 양의 정수 열 번호.
Returns:
    A, Z, AA 등의 열 이름.
Raises:
    ValueError: 열 번호가 1보다 작은 경우.
Side Effects:
    없음. 메모리에서만 처리한다.
```

### _preserved_addresses

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `_preserved_addresses(sheet: dict[str, Any]) -> set[str]`

정적 호출 관계: `validation.validate_extraction`.

```text
추출 셀과 압축 빈 구간에서 보존된 주소를 복원한다.

Args:
    sheet: 생산 파서의 한 시트 결과.
Returns:
    명시적으로 보존된 모든 셀 주소 집합.
Raises:
    KeyError: 필수 주소나 빈 구간 필드가 누락된 경우.
    ValueError: 잘못된 열 번호가 전달된 경우.
Side Effects:
    없음. 메모리에서 주소 집합만 만든다.
```

### _cell_issues

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `_cell_issues(expected: dict[str, Any], actual: dict[str, Any]) -> list[str]`

정적 호출 관계: `validation.validate_extraction`.

```text
원본 셀과 추출 셀의 값·수식·스타일 불일치를 분류한다.

Args:
    expected: 독립 XML 점검으로 읽은 셀.
    actual: 생산 파서에서 반환한 셀.
Returns:
    불일치한 필드명의 목록. 일치하면 빈 목록.
Raises:
    없음. 누락 필드는 불일치로 처리한다.
Side Effects:
    없음. 원본과 결과 사전을 변경하지 않는다.
```

### validate_extraction

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `validate_extraction(path: Path, workbook: dict[str, Any]) -> dict[str, Any]`

정적 호출 관계: `pipeline.run_parser`.

```text
원본 OOXML과 추출 결과의 전체 시트·셀·구조를 독립 비교한다.

Args:
    path: 읽기 전용 원본 xlsx 경로.
    workbook: extract_workbook이 반환한 통합문서 결과.
Returns:
    passed, 전체 개수, 시트별 개수 및 위치가 포함된 issues 사전.
Raises:
    OSError: 원본을 읽을 수 없는 경우.
    KeyError: 필수 OOXML 부분이 없는 경우.
    ET.ParseError: 원본 XML이 손상된 경우.
Side Effects:
    원본 ZIP을 다시 읽는다. 생산 파서의 reader나 결과값을 정답으로 쓰지
    않으며 파일을 수정·생성하지 않는다.
```

### _read_numeric_sources

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `_read_numeric_sources(path: Path, required: set[str]) -> dict[str, dict[str, Any]]`

정적 호출 관계: `validation.compare_master`.

```text
마스터 비교에 필요한 원본 시트를 생산 파서 없이 읽는다.

Args:
    path: 원본 xlsx 경로.
    required: 비교에 필요한 시트명 집합.
Returns:
    시트명별 주소·원본 셀 사전.
Raises:
    OSError: 원본을 읽을 수 없는 경우.
    ET.ParseError: 원본 XML이 손상된 경우.
Side Effects:
    지정 원본의 필요한 시트만 읽으며 산출물을 만들지 않는다.
```

### validate_provenance

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `validate_provenance(workbook: dict[str, Any], mapped: dict[str, Any]) -> dict[str, Any]`

정적 호출 관계: `pipeline.run_parser`.

```text
표준 레코드가 같은 원본의 숫자·수식과 정확히 연결되는지 확인한다.

Args:
    workbook: 독립 원본 대조를 수행한 추출 결과.
    mapped: map_workbook의 표준 레코드와 GAP 관계 결과.
Returns:
    passed, record_count, required_candidate_count, 참조 검사 수와 issues.
    숫자 및 모든 자료형의 수식은 필수 후보이며, 일반 날짜·불리언은
    필수 금액 후보로 간주하지 않는다.
Raises:
    KeyError: 원본 계약의 record_id 등 필수 필드가 없는 경우.
Side Effects:
    없음. 메모리에서 전체 후보와 출처·원문·보조 근거를 비교한다.
```

### compare_master

정의: [validation.py](../../src/excel_parser/validation.py)

서명: `compare_master(paths: list[Path]) -> dict[str, Any]`

정적 호출 관계: `pipeline.run_parser`.

```text
실제 마스터 수식과 조직 원본 합계를 제한된 비교 범위에서 대조한다.

Args:
    paths: 이번 입력의 원본 파일 목록. 파일명으로 조직을 확정하지 않는다.
Returns:
    status, comparison_count, 비교 근거와 issues를 포함한 사전.
    사업부별집계 시트가 없으면 not_applicable로 반환한다.
Raises:
    OSError: 원본 파일을 읽을 수 없는 경우.
    ET.ParseError: 원본 XML이 손상된 경우.
Side Effects:
    원본의 집계 수식·캐시와 조직별 상세 셀을 독립 조회한다. 공식 마스터를
    발행하거나 일반 수식 계산 엔진을 실행하지 않으며 원본을 변경하지 않는다.
```

## 규칙 파서 평가 환경

실행 조건·명령·정답 스키마는 [원본 대조와 마스터 검증 문서](parser-comparison.md)에 정의한다. 각 함수의 전체 Args/Returns/Raises/Side Effects 명세는 연결된 소스의 한국어 docstring에 있다.

| 모듈 | 함수 | 입력 → 출력 및 호출 관계 |
|---|---|---|
| [benchmark/cli.py](../../src/excel_parser/benchmark/cli.py) | `main`, `make_parser` | CLI → 입력 조사 또는 규칙 파서 실행. 기본 콘솔 출력과 명시적 저장을 분리한다. |
| 위와 같음 | `validate_output_path`, `save_report`, `json_value` | 출력 경로 사전 검사 → 명시적으로 선택한 새 JSON/CSV 저장. Decimal은 문자열로 보존한다. |
| [benchmark/runner.py](../../src/excel_parser/benchmark/runner.py) | `load_dataset`, `load_truth` | 기본 manifest와 선택 파일·독립 셀 정답 읽기. 기본 마스터 제외. |
| 위와 같음 | `plan_comparison(files)` | 원본 파일 → 시트·후보 개수 조사. 원본 읽기만 수행하며 결과 파일을 만들지 않는다. |
| 위와 같음 | `run_comparison(files, manifest, *, repeats=1, truth=None, progress=None)` | 동일 파일 → 규칙 어댑터 → 독립 평가 → 반복별 보고서. 정답을 파서에 전달하지 않는다. |
| 위와 같음 | `_compact_validation`, `_summarize_result`, `summarize_trials` | 대형 원본을 제외하고 검증·누락·금액·선택적 의미 점수를 집계한다. |
| 위와 같음 | `implementation_fingerprint` | 규칙 파서·평가기 Python 소스 → 재현용 SHA256. |
| [benchmark/adapters.py](../../src/excel_parser/benchmark/adapters.py) | `run_rule_adapter(path, *, metadata=None, root=None)` | 파일 → 새 추출·원본 검증·규칙 매핑·출처 검증. 단계별 시간·완료/검증 상태를 분리한다. |
| [benchmark/manifest.py](../../src/excel_parser/benchmark/manifest.py) | `score_manifest`, `_group_specs`, `_index_records`, `_amount`, `_column`, `_exact_sum` | 기본 더미 생성 계약과 예측 → 반올림 없는 Decimal 합계로 117개 금액 그룹의 완전성·일치 여부. 의미 정확도와 분리한다. |
| [benchmark/evaluation.py](../../src/excel_parser/benchmark/evaluation.py) | `score_records`, `_truth_index`, `_compare_record`, `_source_key`, `_decimal`, `_section` | 독립 셀 정답과 표준 레코드 → 누락·중복·의미·금액의 독립 비교. |

### 규칙 평가 테스트

아래 메모리 테스트는 실제 원본·파일 출력·네트워크를 사용하지 않는다. 각 테스트 메서드는 인자 없이 `None`을 반환하며 실패는 `AssertionError`로 드러난다. 입력 준비·검사 범위·모의 호출의 부작용은 소스의 함수 명세를 따른다.

| 정의 파일 | 함수·메서드 및 호출 관계 |
|---|---|
| [test_benchmark_adapters.py](../../tests/test_benchmark_adapters.py) | `_memory_io(package, *, fingerprints=None)`: 합성 OOXML과 원본 해시를 메모리 I/O로 제공한다. `BenchmarkAdapterTests`가 사용한다. |
| 같은 파일 `BenchmarkAdapterTests` | `test_rule_executes_real_extraction_mapping_and_validation_without_output`, `test_extraction_validation_failure_keeps_mapping_and_provenance_results`, `test_changed_source_hash_prevents_success`: 규칙 추출·매핑·출처검사, 검사 실패 보존, 원본 변경 차단을 검증한다. |
| [test_benchmark_runner.py](../../tests/test_benchmark_runner.py) | `manifest_fixture`, `adapter_fixture`: 독립 합계 정답과 규칙 어댑터 결과를 메모리에 준비한다. |
| 같은 파일 `BenchmarkRunnerTests` | `test_master_excluded_by_default_and_names_validated`, `test_truth_never_passes_to_engine_and_missing_is_scored`, `test_default_run_executes_rule_evaluation`: 파일 선택, 정답 격리·누락 채점, 기본 규칙 실행을 확인한다. |
| 같은 클래스 | `test_output_outside_allowed_root_is_rejected_without_writes`, `test_invalid_manifest_rejected_before_engine_calls`, `test_cross_trial_source_change_invalidates_comparison`, `test_bad_output_and_repetition_are_preflight_errors`, `test_removed_model_options_are_rejected_before_source_reads`: 출력 경계, 정답 계약, 반복 간 원본 변경, 잘못된 실행 조건과 삭제된 옵션의 선행 거부를 검사한다. |

### 월별 규칙 파서 실행과 평가

정의 파일: [benchmark/monthly.py](../../src/excel_parser/benchmark/monthly.py). 실제 실행 명령과 저장 범위는 [원본 대조 문서](parser-comparison.md)의 월별 검증 절을 따른다.

| 실제 서명 | 호출자 → 호출 대상, 입출력·예외·부작용 |
|---|---|
| `write_json(path: Path, value: object) -> None` | 월별 저장 단계 → 배타적 `open("x")`. Decimal을 보존한 새 JSON 저장. 기존 파일이면 `FileExistsError`; 저장 실패는 `OSError`. |
| `inventory_dataset(dataset: Path, types: list[str]) -> dict` | 월별 CLI → 실제 유형·기준월·조직 및 마스터 경로 목록. 파일명과 폴더만 읽으며 마스터 내용을 읽지 않는다. 유형 누락·마스터 비유일은 `ValueError`. |
| `_append_rows(path: Path, rows: list[dict]) -> None` | 월별 실행 저장 → 현재 실행의 gzip JSONL에 입력 순서대로 행 추가. 중복·미확정 레코드를 보존하며 기존 타 실행 파일에는 사용하지 않는다. 저장 실패는 `OSError`. |
| `_run_source(job: tuple[str, str, str]) -> dict` | 월별 작업 프로세스 → 조직 파일·유형·기준월 → `run_rule_adapter` → 평가용 상세 출력과 실행 내역. 조직 파일만 읽으며 입력 해시를 보존한다. |
| `summarize_months(rows: list[dict]) -> list[dict]` | 월별 점수 → 유형별·전체 마이크로 집계. 월별 비율 평균이 아닌 정답·확정 출력·일치 건수를 합산한다. 필수 필드 누락은 `KeyError`; 메모리 연산. |
| `write_summary(report: dict, output: Path) -> None` | 월별 실행 완료 → `summary.csv`, 한국어 `report.md` 새 저장. 실행 조건·해시·시간·산출물·명령을 기록한다. 기존 요약은 `FileExistsError`, 저장 실패는 `OSError`. |
| `run_monthly(dataset: Path, types: list[str], output: Path, repo: Path, workers: int) -> dict` | 월별 CLI → 실제 입력 목록 고정 → 규칙 파서 실행 → 독립 마스터 정답·레코드 채점 → 새 inventory·results·요약 저장. 기존 경로는 `FileExistsError` 또는 `ValueError`; 원본·마스터는 읽기만 한다. |

### 월별 독립 정답과 상세 대조

정답 함수는 생산 파서에 전달되지 않는다. 결과 투영은 파서가 출력한 조직·계정·기간·금액만 사용하고, 같은 원본의 설명 ID는 상세행 식별을 위해 추가한다.

| 정의 파일 및 실제 서명 | 호출자 → 호출 대상, 입출력·예외·부작용 |
|---|---|
| [monthly_truth.py](../../src/excel_parser/benchmark/monthly_truth.py) `column_letter(column: int) -> str` | 정답 출처 표시 → 양의 열 번호를 Excel 열 문자로 변환. 잘못된 번호는 `ValueError`; 메모리 연산. |
| 같은 파일 `decimal_amount(value: object) -> Decimal \| None` | 정답·평가기 → 유한 Decimal 또는 None. 빈 값과 0을 구별하고 쉼표·괄호 음수를 해석한다. 잘못된 숫자는 None; 메모리 연산. |
| 같은 파일 `snapshot_label(closing_month: str \| int) -> str` | 월별 변환 → 2026년 마감월 표준화. `2026-MM` 반환; 형식·범위 오류는 `ValueError`. |
| 같은 파일 `native_detail_index(workbook: dict) -> dict` | 정답 변환·예측 투영 → 확인된 상세 시트 고정 열과 원본 설명의 ID·수정번호 색인. 빈 결과 가능. 원본 메모리 조회만 수행한다. |
| 같은 파일 `_master_organization(row: dict, layout: dict, detail_id: str \| None) -> str \| None` | 정답 변환 → 마스터 담당자·설명 ID의 현재 조직 코드를 교차 확인. 이관 수신 상세의 `-IN-F코드`는 현재 수신 조직, ID 앞쪽 F코드는 원조직 이력으로 구분한다. 마스터 조직명·담당자·이관 설명과 독립 manifest의 수신 조직 정보에서 확인한 고정 규칙이다. 일반 행은 앞쪽 F코드를 사용하며 근거 충돌/부재 시 None; 조직 파서 결과를 읽지 않는다. |
| 같은 파일 `build_master_truth(master_path: Path, *, type_name: str, closing_month: str \| int) -> dict` | 월별 실행의 평가 단계 → 마스터 상세 원본·원본 단위·실적/추정 헤더 확인 → records, metadata, issues, totals. 마스터와 형제 파일명·해시만 읽는다. 읽기/마감월 오류는 `OSError`/`ValueError`. |
| [monthly_evaluation.py](../../src/excel_parser/benchmark/monthly_evaluation.py) `_code(value: object) -> str \| None` | 예측 투영 → 출력된 계정 코드의 표기만 통일. 새 코드 추론·원본 보완 없음. |
| 같은 파일 `_account(record: dict, domain: str) -> str \| None` | 예측 투영 → 파서의 item.code·코드 차원·매출 분류 경로에서 계정 추출. 원본·정답값으로 보완하지 않는다. |
| 같은 파일 `project_predictions(records: list[dict], workbook: dict, *, type_name: str, closing_month: str \| int) -> list[dict]` | 규칙 어댑터 결과 → 월별 상세 출력 평탄화. 원본은 ID·대상 셀 범위 판정에만 사용. 금액 확정과 의미 확정을 구별하고 미확정·중복도 보존. 잘못된 마감월은 `ValueError`. |
| 같은 파일 `record_key(record: dict) -> tuple` | 채점 → 유형·마감월·영역·조직·계정·연월·구분·상세 ID 키. 수정번호는 보존하지만 자동 최신본 선택에 사용하지 않는다. |
| 같은 파일 `_detail(status: str, truth: dict \| None, prediction: dict \| None, *, duplicate: bool = False) -> dict` | 채점 → 두 원본의 파일·시트·셀·키·값·수정번호·미확정 사유를 담은 오류 행. |
| 같은 파일 `_metrics(expected: int, confirmed: int, matched: int) -> dict` | 전체/매출·비용 집계 → TP·FP·FN, Precision·Recall·F1. 정답이 없으면 미측정. |
| 같은 파일 `_aggregate_checks(predictions: list[dict], truth: list[dict]) -> list[dict]` | 상세 대조 후 → 영역·기간·구분별 Decimal 합계 및 건수 검증. 상세 레코드를 합계로 대체하지 않는다. |
| 같은 파일 `score_monthly_records(predictions: list[dict], truth_records: list[dict]) -> dict` | 월별 실행 → 업무키 다중집합의 일대일 연결 → summary, details, by_domain, aggregates. 금액 불일치는 상세 1건이면서 FP·FN 각각 1건. 정답은 생략·미확정과 무관하게 분모 유지. |

월별 평가 함수는 마감월 검증 및 명시된 마스터 읽기 외에는 메모리 연산만 수행한다. 모든 오류 상세를 반환하며 상한으로 잘라내지 않는다. 기록의 빈 값을 임의의 0으로 바꾸거나 중복 행을 삭제·합산하지 않는다.

### 월별 비교의 보존 테스트

아래 테스트는 모두 메모리 자료와 일시적인 I/O 경계 대체만 사용한다. 실제 파일 생성·캐시·네트워크 연결이 없으며 검증 실패는 `AssertionError`로 드러난다. 각 테스트 메서드는 인자 없이 `None`을 반환한다. 상세 Args/Returns/Raises/Side Effects는 연결된 파일의 한국어 docstring을 따른다.

| 정의 파일 | 함수·메서드 및 호출 관계 |
|---|---|
| [test_benchmark_monthly.py](../../tests/test_benchmark_monthly.py) | `expected_record(**changes: object) -> dict`, `predicted_record(**changes: object) -> dict`, `memory_cell(row: int, column: int, value: object) -> dict`: 독립 정답·예측·추출 셀의 메모리 자료를 준비한다. |
| 같은 파일 `MonthlyEvaluationTests` | `test_exact_decimal_match_preserves_inputs`, `test_amount_mismatch_is_one_detail_and_one_fp_one_fn`, `test_duplicate_predictions_and_truth_keep_multiplicity`, `test_duplicate_truth_missing_copy_stays_in_recall`: Decimal 일치, 금액 오류의 FP/FN, 정답·출력 중복 보존을 검증한다. |
| 같은 클래스 | `test_zero_and_blank_are_distinct`, `test_missing_and_unresolved_keep_all_truth_in_recall`, `test_different_detail_ids_are_not_merged`, `test_closing_snapshots_are_evaluated_separately`: 빈 값/0, 미확정 분모, 상세 ID 및 마감월 분리를 검사한다. |
| 같은 클래스 | `test_master_blank_is_unmeasured_while_zero_is_truth`, `test_missing_location_points_to_master_evidence`, `test_master_transfer_uses_recipient_without_rewriting_donor`: 실제 정답 변환 경로의 빈칸/0, 누락 출처, 이관 수신 조직 근거를 검사한다. |
| 같은 파일 `MonthlyExecutionTests` | `test_source_worker_reads_only_organization_via_rule_adapter`, `test_monthly_run_keeps_independent_scoring_and_source_integrity`, `test_invalid_worker_count_does_not_create_output`: 규칙 어댑터 전용 호출, 독립 채점·입력 해시 보존, 잘못된 worker 수의 출력 생성 차단을 메모리에서 확인한다. |

## 조직 원본 매핑 보완과 기술 마스터 조립

매핑과 조립은 기존 마스터·정답 파일을 입력으로 받지 않는다. `organization_verified=True`는 호출자가 검증한 조직 메타데이터를 사용하기 위한 선택 표식이며, 미표시 호출은 경고와 함께 조직 보완에서 제외된다. 원본의 확정 조직과 충돌하는 메타데이터는 원본을 덮어쓰지 않는다.

| 정의 파일·실제 서명 | 입력·출력, 호출 관계와 한계 |
|---|---|
| [mapping.py](../../src/excel_parser/mapping.py) `_resolve_organization(code_cell: dict \| None, name_cell: dict \| None, registry: dict, fallback: dict \| None = None) -> dict` | `_row_context`, `_workbook_organization` → 같은 원본의 명시 코드·이름·코드표·공통 조직 근거로 resolved/conflict/ambiguous/unresolved 반환. 조직 수식 오류·캐시 누락은 fallback으로 채우지 않고 LOOKUP_ERROR, 오류 값은 VALUE_ERROR와 diagnostic_cell을 보존한다. 근거 사전을 변경하지 않고 파일을 읽지 않는다. |
| 같은 파일 `_template_formula(cell: dict) -> tuple[str, int] \| None` | 미사용 행 판별 → 명시 수식 또는 같은 열의 유효 공유 수식 범위와 상대 행 기준. 근거 누락·외부 참조·범위 불일치는 None. 원본 수식 전개·계산·변경 없음. |
| 같은 파일 `_inactive_expense_template_row(row_number: int, index: dict, layout: dict) -> bool` | `map_workbook`이 행마다 한 번 판정·캐시하고 `_row_context`에 전달. 확인된 조회 오류 수식과 12개월 연간 SUM만 남고 실제 입력·주석·월 금액/수식이 없는 비용 양식 행만 True. 실제 0·불명확한 근거는 활성 검증으로 유지. 파일 접근·후보 삭제 없음. |
| 같은 파일 `_normalization_locale(cell: dict, unit: str \| None, supplied_locale: str \| None) -> tuple[str \| None, bool]` | `map_workbook` → `inspect_number_format`으로 배율·조건·백분율·날짜를 검사하고 숫자 리터럴도 거부. 명시 KRW 단위, 회계 여백만 제거한 양수 서식 `#,##0` 또는 점 뒤 0이 하나 이상인 `#,##0.0…`, 실제 3자리 쉼표 그룹 문자열이 일치하면 locale과 확인 여부 반환. 문자열 소수 정밀도는 `normalize_number`에 그대로 전달하며 실제 숫자·기존 명시 locale은 유지한다. 파일·원본값 변경 없음. |

### 같은 원본의 업무필드 계약

정의 파일: [business_validation.py](../../src/excel_parser/business_validation.py). 금액 정규화와 별개의 메모리 검증이다. 원본·마스터·정답 파일을 읽거나 쓰지 않고, 조직·계정·ID 또는 정규금액을 보완하지 않는다. `map_workbook`은 계약을 한 번 만들고 각 원본행 결과를 캐시한다. 빈 금액 행을 재구성하는 호출자도 동일 행 API를 사용할 수 있다. 전체 Args/Returns/Raises/Side Effects는 각 함수의 한국어 docstring을 따른다.

| 실제 서명 | 호출·반환·예외·부작용 |
|---|---|
| `_text(cell: dict \| None) -> str`, `_ref(cell: dict \| None) -> dict \| None` | 계약·행 검사 → 유효 저장 문자열 또는 최소 원본 셀 참조. 오류·캐시 누락을 유효 문자열로 취급하지 않는다. 원문 불변. |
| `_unique_refs(refs: list[dict \| None]) -> list[dict]` | 필드·행 출처 → 최초 순서의 고유 셀 참조. 입력 목록 불변. |
| `_sumifs_arguments(formula: str) -> list[list[str]]` | 계약 색인 → 따옴표·괄호 경계가 닫힌 SUMIFS 인자. 수식 실행 없이 동적 인자를 원문으로 보존한다. |
| `_sheet_reference(expression: str, default_sheet: str) -> tuple[str, str] \| None` | 직접 인자 → 시트·주소. 외부 파일 참조는 None. |
| `_column_range(expression: str, default_sheet: str) -> tuple[str, int] \| None` | SUMIFS 조건 범위 → 같은 전체 열의 시트·열 번호. 부분 범위·계산식은 None이며 전체 행으로 확장하지 않는다. |
| `_criterion(expression: str, sheet: str, cells: dict) -> tuple[str, dict \| None]` | 직접 기준 셀 또는 명시 문자열 → 기준값과 셀 참조. 동적 식·와일드카드·오류는 빈값이며 계산하지 않는다. |
| `_finish_contract(contract: dict) -> None` | 내부 색인 → 허용 집합·범위의 SHA256. 내부 사전에 해시만 추가하며 원본 불변. 내부 직렬화 계약 위반은 `TypeError`. |
| `build_business_contracts(workbook: dict) -> dict` | 매핑·재구성 → 같은 원본 예산 코드표와 실제 SUMIFS 조건 열별 값 색인. 직접 읽지 못한 기준이 있으면 complete=False. 추출 계약 위반은 `KeyError`. |
| `_field_result(cell: dict \| None, contract: dict \| None, *, observed: str \| None = None) -> dict` | 행 검사 → 필드 상태·관측값·최소 출처·선택 계약 해시/범위. 계약 부재는 unverified. 내부 계약 위반은 `KeyError`. |
| `validate_business_row(row: dict[int, dict], layout: dict, contracts: dict) -> dict` | 매핑의 행 캐시 또는 재구성의 빈 행 → 상태·detail_id/account_code/management_category 검증·문제·출처. 명시 None/null/nan ID 및 미등록 값은 원문을 보존하고 invalid, 적용 가능한 계약 부재는 필드 unverified로 구분. 필수 구조 위반은 `KeyError`. |

전체 `status=valid`는 적용한 검사의 통과만 뜻한다. 미검증 필드가 동시에 존재할 수 있으며 원래 생산 파서의 의미 근거를 대신하지 않는다. 검사 실패를 금액 `confirmed=False`나 코드/ID 삭제로 표현하지 않는다. 해당 코드·기준값 일치 셀 또는 계약 헤더·범위·해시만 출력에 연결하고 전체 코드표나 기준값 집합은 반복하지 않는다.

정의 파일: [master.py](../../src/excel_parser/master.py). 모든 함수는 순수 메모리 연산이며 파일·정답·기존 마스터를 읽거나 쓰지 않는다. 공식 승인·버전 발급은 하지 않는다. 전체 Args/Returns/Raises/Side Effects는 소스 docstring을 따른다.

| 실제 서명 | 호출자 → 호출 대상, 반환·예외·한계 |
|---|---|
| `_decimal(value: object) -> Decimal \| None` | 조립 검증 → 이미 정규화된 유한 금액만 Decimal로 읽기. 빈 값·불리언·오류는 None. |
| `_decimal_text(value: Decimal) -> str` | 해시·상세·합계 → 값 손실 없는 표준 금액 문자열. 비유한 값은 `ValueError`. |
| `_json_default(value: object) -> str` | `_canonical` → Decimal 문자열 변환. 다른 자료형은 `TypeError`. |
| `_canonical(value: object) -> str`, `_fingerprint(value: object) -> str` | 제출·행·결과 → 정렬 JSON 및 SHA256. 지원하지 않는 자료는 `TypeError`/`ValueError`. |
| `_version(submission: dict, closing_month: str) -> tuple[int, int] \| None` | 제출 선택 → `version_verified` 또는 `version_evidence`가 있는 `YYYY-MM-NN`, `Vn.r`, 정수 개정번호 해석. 파일명·상세 수정번호를 제출 버전으로 쓰지 않는다. |
| `replace_organization(submissions: list[dict], replacement: dict) -> list[dict]` | 생성기의 재실행 확인 → 선택 조직의 이전 상태 전체를 새 상태 한 개로 교체. 다른 조직과 입력 객체를 보존한다. 코드·records 부재는 `ValueError`. |
| `_select_submissions(submissions: list[dict], closing_month: str) -> tuple[list[dict], list[dict], list[dict]]` | `assemble_master` → 조직별 단일 원본 또는 유일한 최고 확인 버전 선택. 정확히 같은 제출 재전송은 한 번 반영하고 건수 기록. 선택·제외 요약·문제 내역 반환. |
| `_business_payload(record: dict) -> dict` | 상세행 비교 → 조직·계정·ID·연월·구분·정규금액·의미/값/수식캐시 상태. 정규금액이 미확정이면 원문과 전체 수식 정보를 추가 비교하여 서로 다른 실패값을 동일 전달로 묶지 않는다. 업무필드 검증 상태·관측값도 비교하되 출처 문구는 업무 동일성에서 제외한다. |
| `_source_reference(record: dict) -> dict` | 중복·선택·제외 → 파일·시트·셀·원값·정규값·수정번호·수식 상태·업무필드 검증 근거 복사. |
| `_select_detail_rows(records: list[dict]) -> tuple[list[dict], list[dict], list[dict]]` | 조립 → 원본행 전체 월별 내용을 비교하여 최고 수정행 선택. 최고 수정번호가 같으면 전체 업무 내용이 동일해야 `exact_duplicate_delivery`. 출처·중복 수·구개정·충돌을 모두 보존. 잘못된 문자열 ID의 물리적 원본행은 각각 유지하며 수정본/중복으로 병합하지 않는다. |
| `_amount_eligibility(record: dict) -> tuple[Decimal \| None, str \| None]` | 선택 상세 → 업무필드 검증·의미·월·금액·빈칸·수식캐시 확인 후 합산 가능 금액 또는 미확정 사유 반환. 원본 업무 계약 실패는 `business_key_unresolved`이며 읽힌 금액을 삭제하지 않는다. 수식 재계산·빈칸 0 대체 없음. |
| `_exact_sum(values: list[Decimal]) -> Decimal` | 합계 → 지수에 맞춘 정수 계수 합산으로 Decimal 기본 정밀도의 반올림을 회피. 비유한 값은 `ValueError`. |
| `_totals(details: list[dict], *, by_organization: bool) -> list[dict]` | 선택 상세 → 조직 또는 전사 영역·계정·연월·구분 합계. 미확정 금액을 합산하지 않고 상세는 유지한다. |
| `assemble_master(submissions: list[dict], expected_organizations: list[str], closing_month: str, type_name: str, *, publication_policy: str \| None = None) -> dict` | 생성기 → 선택·검증·합계 → records/details, totals, issues, selected/excluded, 상태·내용 해시. 기본 None은 v3 동작을 보존한다. 명시 source-contract-v4는 수정본 선택 뒤 원본 행 발행 정책을 적용한다. 기대 조직은 수 확인에만 사용하며 누락 시 publication_status도 partial이다. 입력 오류는 `ValueError`/`TypeError`. |

`generation_complete=True`는 조립 함수의 연산이 끝났음을 뜻한다. `status=partial`은 선택·의미·금액·조직 수 문제를 가진 결과이고, `business_master_issued=False`는 공식 발행을 주장하지 않는다는 뜻이다. `records`와 `details`는 같은 선택 상세의 별칭이다. `totals.scope`는 organization/company를 구분한다. 의미가 확정되고 전체가 빈 `next_projection`만 `missing_optional`로 보존한다. 빈 금액이 조직·계정·기간의 미확정을 숨기지 않으며 수식 캐시 부재도 미확정 사유로 남긴다. 선택되지 않은 제출 목록까지 정렬하여 입력 순서만 달라져 내용 해시가 바뀌지 않게 한다.

### 생성과 사후 평가의 명령 경계

정의 파일: [benchmark/reconstruction.py](../../src/excel_parser/benchmark/reconstruction.py). `compare.py → cli.main → generate`와 `compare.py → cli.main → evaluate`는 분리된 명령이다. 생성 완료 자료가 저장된 후에만 평가기가 기존 마스터를 읽는다.

| 실제 서명 | 입출력·호출·예외·부작용 |
|---|---|
| `_group(period: dict, closing_month: str) -> str` | 파서의 연도·구분 → prior/plan/outlook/next_plan/next_projection 표시. 기간 의미를 보완하지 않는다. 기준월 형식 오류는 `ValueError`. |
| `_flat(record: dict, type_name: str, closing_month: str) -> dict` | 표준 레코드 → 출처·근거·의미·정규금액·상태·`business_validation`을 보존한 평탄 상세. 업무필드 invalid/unresolved이면 `semantic_complete=False`이며 숫자 금액의 confirmed와 원문 업무키는 그대로 유지한다. 계약 위반은 `KeyError`; 메모리 연산. |
| `_has_source_detail_identity(row: int, index: dict, layout: dict) -> bool` | 빈 월별 슬롯의 보존 대상 판단 → 원본 항목·계정·거래 설명이 실제 존재하는지 확인. Excel 오류 타입·오류 문자열·캐시 없는 수식만 있는 자리표시는 상세행으로 추가하지 않는다. `KeyError`는 내부 계약 위반. |
| `project_source_details(mapped: dict, workbook: dict, type_name: str, closing_month: str) -> list[dict]` | 같은 조직 원본의 파서 출력·메모리 → 월별 상세와 빈 슬롯. 빈 셀에 금액을 만들지 않으며 미처리된 실제 값도 미확정으로 보존한다. 원본행에 확정 금액 레코드가 전혀 없는 경우에도 `build_business_contracts → validate_business_row`로 동일한 업무 검증을 적용한다. |
| `_source_declared_owner(workbook: dict) -> dict` | `parse_submission` → `_index`, `_layout`, `_organization_registry`, `_ref`. 확인된 재무표의 데이터 시작 전·첫 값 열 왼쪽에서 `▣ 조직명` 제목과 별도 동일 이름 셀, 같은 원본 코드표의 유일 대응이 모두 일치해야 resolved. 상태·이름·코드·근거·사유 반환. 계약 위반은 `KeyError`; 메모리 연산이며 파일명·다수결·다른 파일을 사용하거나 행 조직을 수정하지 않는다. |
| `parse_submission(job: tuple[str, str, str]) -> dict` | 조직 경로·유형·기준월 → 기존 `run_rule_adapter` → 제출 상세·읽기 검증. 행 조직코드가 유일하면 기존 소유자를 유지하고, 둘 이상일 때만 `_source_declared_owner`로 보완. 근거 불명·충돌은 미확정. `organization_identity_source`, `source_declared_owner`, `trial.submission_owner`에 소유자 근거와 행 조직 목록을 보존하며 행 조직·값은 변경하지 않는다. 계약 위반은 `KeyError`; 지정 조직 원본만 읽는다. |
| `write_jsonl(path: Path, rows: list[dict]) -> None` | 생성·평가 저장 → 새 gzip JSONL 배타적 생성. 기존 파일은 `FileExistsError`, 저장 실패는 `OSError`. |
| `read_jsonl(path: Path) -> list[dict]` | 평가 → 생성 완료 gzip JSONL 읽기. 접근/JSON 오류는 `OSError`/`ValueError`. |
| `generate(inventory_path: Path, output: Path, *, types: list[str], months: list[str], workers: int) -> dict` | 최초 목록의 조직 원본만 새 파싱 → 조립 → 대표 한 조직을 같은 제출본으로 교체한 후 전체 재조립 해시 비교 → 새 실행 폴더 저장. 검사 범위·대표 원본을 기록하며 기존 마스터는 경로만 보관하고 열지 않는다. |
| `evaluate(output: Path) -> dict` | 저장 완료 상세 해시·`included_in_totals` 상세의 재계산 합계와 저장 합계의 엄격 일치 확인 → 평가 전용 기존 마스터 읽기 → 실제 합계 포함 상세만 합계 대조. 상세·오류·요약은 새 evaluation 폴더에 저장하고 생성본·최초 결과를 변경하지 않는다. |

### 생성 JSON의 Excel 내보내기

정의 파일: [master_workbook.mjs](../../src/excel_parser/master_workbook.mjs). CLI는 `node parser/src/excel_parser/master_workbook.mjs <유형/기준월 생성 폴더> [--preview]`이다. 생성된 `details.jsonl.gz`와 `master.json`만 데이터 입력으로 읽는다. 원본 조직 Excel·기존 기준 마스터·평가 정답은 읽지 않는다.

| 실제 함수 | 역할·반환·예외·부작용 |
|---|---|
| `compareEntries(left, right)` | Map 항목의 문자열 키 정렬. 메모리 연산. |
| `columnLabel(column)` | 양의 열 번호 → Excel 열 문자. 잘못된 번호는 Error. |
| `decimalParts(value)` | 유한 십진수 → BigInt 계수·지수·값 표식·유효숫자 수. 잘못된 숫자·과도한 지수는 Error. |
| `exactNumber(value, location)` | 15자리 이하 유효숫자와 숫자 왕복을 확인한 경우에만 typed number. 빈 값은 null. 정밀도 손실 가능 시 Error로 중단하며 JSON은 보존. |
| `addDecimal(state, value)`, `sumSignature(state)` | BigInt 계수로 반올림 없는 합계·값 표식 생성. 입력은 변경하지 않는다. |
| `fileHash(filename)` | 생성 입력·출력의 SHA256 스트리밍 조회. 해당 파일만 읽고 읽기 오류는 전파. |
| `readDetails(filename)` | 생성 JSONL → 원본행을 보존한 12개월 가로 상세·빈칸/0 통계·정확한 합계 색인. Z열에는 월별 confirmed/semantic_complete/value_kind/value/assembly/included와 mapping 상태·이유를 보존한다. 같은 원본행·월 중복, 확정 상태 부재, 기간·숫자 오류는 Error. |
| `prepareTotals(totals, sums)` | 생성 합계와 상세에서 독립 계산한 합계·건수 확인 → 전사/조직별 가로 표. 기존 연간 합산 건수와 월별 detail_count JSON을 함께 저장한다. 불일치·중복·숫자 정밀도 문제는 Error. |
| `loadArtifactTool(monthlyDir)` | 승인된 실행 폴더의 런타임 연결 → Artifact Tool 공개 API. 필요하면 실행 루트에 `node_modules` 심볼릭 링크 생성. 다른 대상 기존 링크나 런타임 부재는 Error. 패키지를 설치하지 않는다. |
| `addSheet(workbook, definition, context)` | 메모리 시트·표·서식·필터·틀 고정 작성 → 검증용 범위와 입력행. A4는 발행 상태, 원본 품질, 조립 상태, 기대/반영 조직 수를 분리 표기한다. 기존 빈칸·0 안내를 보존한다. 도구 오류 전파. |
| `verifyWritten(written)` | 모든 작성행의 값·타입·null, 헤더, A3 문맥, A4 상태를 다시 읽고 비교 → 검증 셀 수·헤더 수·문맥 확인. 불일치 시 Error. 메모리 문서와 내보낸 XLSX 재열기에 같은 함수를 적용한다. |
| `main()` | CLI 입력의 생성 JSON 해시·상세/합계 검증 → 4시트 Excel → 내보낸 파일 재열기·전수 값 비교 → `workbook_verification.json`. 선택 `--preview`는 시트별 PNG 생성. 기존 Excel·검증파일은 덮어쓰지 않는다. |

시트는 `전사월별합계`, `조직월별합계`, `매출상세`, `비용상세`다. 금액 단위는 원이며 실제 0은 숫자 0, 빈값은 빈 셀로 저장한다. 다른 시나리오에 속해 해당 행에 없는 월과 원본의 실제 빈칸은 오른쪽 월별 원본 셀·상태 정보로 구분한다. 검증 JSON은 상세·메타데이터 입력 해시, 합계 대조, 메모리·파일 재열기 값 검사, Excel 해시를 보존한다.

### 생성 마스터의 일괄 Excel 내보내기

정의 파일: [export_masters.mjs](../../src/excel_parser/export_masters.mjs). CLI는 `node parser/src/excel_parser/export_masters.mjs <생성 실행 폴더> [--workers 1|2] [--preview] [--watch]`이다. 기본값은 작업자 1개이며 완료된 `generation.json`이 필요하다. `--watch`는 저장이 끝난 월별 JSON부터 처리하고 최종 manifest가 나타나면 전체 범위를 다시 확인한다. 모든 함수의 Args/Returns/Raises/Side Effects는 소스의 한국어 JSDoc을 따른다.

| 실제 함수 | 호출 관계·반환·예외·부작용 |
|---|---|
| `fileHash(filename)`, `exists(filename)` | 파일 해시·존재 여부 조회. 파일을 변경하지 않으며 부재 이외의 접근 오류를 전파한다. |
| `parseArguments(argv)` | CLI → 생성 폴더·workers·preview·watch. 작업자 1 또는 2만 허용하며 잘못된 옵션은 Error. |
| `validateGeneration(generation)` | 완료 manifest의 작업 목록·소요 시간·원본 마스터 읽기 0·월폴더 유일성 확인. 계약 위반은 Error. |
| `discoverReady(directory)` | 생성 실행 폴더 → 완성된 월별 `master.json`, `details.jsonl.gz` 경로와 고정 상세 해시. 작성 중 JSON은 다음 순회로 미룬다. 원본 Excel에 접근하지 않는다. |
| `watchGeneration(context, manifestPath)` | 완료 월 탐색 → `exportBatch` → 최종 manifest의 상세 해시·전체 월 Excel 재검증. 최종 목록 밖에서 발견된 폴더도 결과로 남긴다. |
| 내부 `watchWorker()` | 발견 묶음의 공유 대기열 → 최대 2개 자식 작성기. 한 월을 중복 처리하지 않고 완료 목록·진행 로그를 갱신한다. |
| `readbackComplete(verification)` | 네 시트의 메모리 작성 검사와 저장 파일 전수 재읽기 행·셀 수가 같은지 확인. 메모리 연산. |
| `verifyExisting(directory, preview)` | 기존 Excel·검증 JSON → 입력/출력 SHA256, 입력 경로·불변성, 상세/합계 수, 네 시트 전수 재읽기 완료 확인. 모두 통과해야 재사용하며 실패 사유를 반환한다. |
| `preserveIncomplete(directory, recoveryDirectory)` | 불완전 Excel·검증 JSON·PNG·inspect만 별도 복구 폴더로 이동 보존. 입력 JSON·원본은 변경하지 않고 기존 복구본을 덮어쓰지 않는다. |
| `runWriter(writer, directory, preview, logPath, reason)` | `master_workbook.mjs` 자식 프로세스 → 실제 명령·stdout/stderr·종료 코드·시간을 새 월별 로그에 보존. |
| `exportBatch(batch, context)` | 생성 경계·상세 해시 확인 → 엄격 검증 통과 시 `skipped_verified`, 그 외 보존 후 작성·재검증 → `exported` 또는 `failed`. 실패도 사유·로그를 남기고 다음 월을 처리한다. |
| `saveSummary(directory, summary, attempt)` | 불변 시도별 `export-summary-<attempt>.json`와 최신 `export-summary.json` 저장. 직전 최신 요약은 이름을 바꿔 보존한다. |
| `main()`, 내부 `worker()` | CLI → 일괄 또는 watch 대기열 → 모든 결과·입력 manifest/작성기 해시·성공 여부 요약. 전체 성공 0, 실패/구현 변경 2. 승인된 로그·복구·내보내기 산출물만 작성한다. |

월별 입력·XLSX 해시와 전수 재읽기 검증 근거가 일치하는 경우에만 이미 작성한 파일을 건너뛴다. 단순히 `master.xlsx`가 존재한다는 이유로 완료로 판단하지 않는다. 재실행의 월별 로그는 `export-logs/<attempt>/`, 불완전 산출물은 `export-recovery/<attempt>/`에 보존하며 최초 점수·구조 계획·생성 상세 JSON은 변경하지 않는다.

### 기술 마스터의 메모리 검증

[test_master.py](../../tests/test_master.py)의 `detail(**changes: object) -> dict`, `submission(records: list[dict] \| None = None, **changes: object) -> dict`는 원본·정답 파일 없이 독립 예시를 만든다. `MasterAssemblyTests`의 메서드는 인자 없이 `None`을 반환하고 실패는 `AssertionError`로 표시한다.

| 메서드 | 검증 범위 |
|---|---|
| `test_idempotent_assembly_does_not_mutate_or_read_files`, `test_replace_updates_only_one_organization_without_append` | 동일 입력의 동일 결과·해시, 조직 전체 교체, 다른 조직 보존, 파일 접근 금지 |
| `test_highest_verified_submission_replaces_older_source`, `test_submission_version_tie_is_unresolved` | 확인된 최신 제출 선택과 동률 충돌 |
| `test_latest_identical_whole_row_delivery_preserves_all_sources`, `test_one_month_conflict_blocks_the_entire_tied_detail_row` | 전체 월별 내용으로만 동일 재전송 판단, 한 월 충돌도 행 전체 미확정 |
| `test_blank_zero_and_formula_cache_states_remain_distinct`, `test_entire_empty_optional_projection_is_preserved_without_error` | 실제 0·빈 값·수식 캐시 상태·선택 빈 추정 영역 분리 |
| `test_decimal_totals_keep_small_value_after_large_cancellation`, `test_annual_subtotal_and_empty_submissions_are_not_complete_detail` | 정확한 Decimal 합계, 연간·소계 제외, 빈 상세 성공 오표시 방지 |
| `test_optional_blank_never_hides_unresolved_semantics`, `test_unselected_submission_order_does_not_change_content_hash` | 선택 빈칸의 의미 오류 보존, 미확정 제출 목록 순서와 무관한 내용 해시 |
| `test_unresolved_raw_values_and_formulas_cannot_be_exact_duplicates` | 미확정 정규금액이 같아도 원문·수식이 다르면 수정행 충돌로 보존 |

### 원본 월별 매핑·투영의 메모리 검증

다음 테스트도 실제 원본·마스터·임시 파일에 접근하지 않는다. 각 메서드는 인자 없이 `None`을 반환하고 불일치 시 `AssertionError`를 발생시킨다. 기대값은 합성 원본에 명시한 값과 근거로 정하며 평가 마스터에서 가져오지 않는다.

`test_business_validation.py`의 `contract_workbook() -> dict`는 같은 원본 코드표·SUMIFS 근거가 있는 메모리 입력을 준비한다. 잘못된 합성 주소는 `ValueError`이며 파일 접근은 없다. `BusinessValidationTests`의 `test_registered_fields_preserve_workbook_and_confirmed_amount`, `test_unregistered_account_keeps_observed_code_and_amount`, `test_none_id_remains_observed_without_code_or_organization_inference`, `test_management_category_uses_sumifs_criterion_not_item_label`, `test_missing_contract_and_dynamic_criterion_do_not_invent_validity`, `test_all_blank_amount_row_uses_same_field_validation`, `test_partial_column_ranges_do_not_define_whole_column_contract`는 정상 계약·금액 보존·미등록 코드·None ID·실제 집계분류·계약 부재·동적 조건·빈 행·부분 범위의 경계를 확인한다.

| 정의 파일 | 함수·메서드와 검증 범위 |
|---|---|
| [test_mapping_monthly.py](../../tests/test_mapping_monthly.py) | `append_sheet(workbook: dict, name: str, rows: list[tuple]) -> None`, `monthly_workbook(registry_rows: list[tuple] \| None = None) -> dict`, `find_record(mapped: dict, sheet: str, cell: str) -> dict`: 합성 원본·코드표 준비와 결과 조회. |
| 같은 파일 `inactive_expense_workbook() -> dict` | 실제 0 입력과 조회식 10개·공유 연간 SUM·빈 12개월 양식 행의 독립 메모리 fixture. 원본이나 임시 파일에 접근하지 않는다. |
| 같은 파일 신규 회귀 4개 | `test_unused_expense_formulas_preserve_candidates_and_active_zero`, `test_any_real_input_or_month_formula_keeps_expense_row_active`, `test_unknown_or_broken_formula_does_not_prove_unused_template`, `test_missing_organization_formula_cache_never_uses_fallback`: 원본 후보 보존, 실제 입력·0·월수식·주석·공유식/기간 근거·조직 캐시 누락의 분리. |
| 같은 파일 테스트 클래스 | `test_name_only_details_resolve_unique_same_workbook_code`, `test_duplicate_names_and_persistent_code_conflicts_are_unresolved`, `test_metadata_requires_verified_marker_and_matching_name`, `test_explicit_code_conflict_keeps_source_and_marks_partial`: 같은 원본의 유일한 조직 대응·충돌·검증 메타데이터 선택. |
| 같은 클래스 | `test_actual_group_uses_month_evidence_and_preserves_values`, `test_conflicting_month_sources_keep_scenario_unknown`: 명시 월별 실적/추정 근거와 충돌 보존. |
| 같은 클래스 | `test_missing_formula_cache_and_absent_blank_are_not_zero`, `test_missing_row_organization_keeps_identified_detail_amount`, `test_ambiguous_text_without_integer_grouping_format_stays_unresolved`: 원본 값 보존, 빈칸/캐시/일반 모호 문자열 미확정. |
| 같은 클래스 | `test_accounting_grouping_preserves_source_precision`, `test_ambiguous_decimal_locale_is_not_inferred_from_padding`: 정수·점 소수 그룹 서식과 회계 여백의 제한적 허용, 원문 소수 정밀도·부호·단위 보존, 모호한 점·유럽식 구분자 미확정. |
| [test_reconstruction.py](../../tests/test_reconstruction.py) | `projected_cell(records: list[dict], sheet: str, address: str) -> dict`: 메모리 투영 상세에서 출처로 결과 조회. |
| 같은 파일 | `owner_workbook(declared_name: str = "합성조직", independent_name: str = "합성조직", *, foreign_row: bool = True) -> dict`: 상단 제목·독립 조직명·원본 코드표·행 조직을 독립 지정한 합성 입력. 잘못된 주소는 `ValueError`; 메모리 연산만 수행. |
| 같은 파일 `ReconstructionProjectionTests` | `test_conflicting_organization_prevents_semantic_completion`, `test_file_path_and_closing_month_do_not_fill_source_semantics`, `test_unprojected_source_number_is_not_silently_promoted`: 충돌·파일명·제출월·생산 누락으로 의미나 금액을 임의 보완하지 않음. |
| 같은 클래스 | `test_all_amounts_blank_retains_source_identified_monthly_row`, `test_zero_blank_and_missing_formula_cache_remain_distinct`: 전체 빈 금액 행 보존과 실제 0·캐시 상태 구분. |
| 같은 클래스 | `test_error_placeholders_do_not_create_missing_monthly_details`, `test_real_item_label_retains_blank_row_without_code_or_detail_id`: 오류 자리표시 제외와 실제 항목 근거가 있는 빈 행 보존. |
| 같은 클래스 | `test_source_owner_resolves_multiple_row_organizations_without_rewriting`, `test_mismatched_or_conflicting_owner_declarations_remain_unresolved`, `test_unique_row_owner_keeps_existing_path_without_title_fallback`, `test_owner_name_requires_unique_source_registry_code`: 복수 행 조직의 제출 소유자만 원본 선언으로 보완, 행 조직 보존, 이름·코드 충돌 거부, 기존 유일 행 조직 경로 유지. |

### 별도 재생성 결과의 명시적 선택

정의 파일: [benchmark/reconstruction_selection.py](../../src/excel_parser/benchmark/reconstruction_selection.py). 최초 9개 유형·9개월 목록을 보존하고 `--replace-types`로 명시한 유형의 각 9개월만 별도 재생성 결과로 선택한다. 기본은 `05`이며 `05,07`은 최초 63개와 수정 18개를 선택한다. `compare.py`를 변경하지 않는 독립 모듈 명령이다. 원본 Excel·기준 마스터·평가 결과는 읽지 않는다.

| 실제 서명 | 호출·반환·예외·부작용 |
|---|---|
| `_sha256(path: Path) -> str` | 생성 manifest·선택 상세·현재 선택 구현 → SHA256. 읽기 오류는 `OSError`; 지정 산출물만 읽는다. |
| `_checked_path(path: Path, allowed: Path, *, exists: bool) -> Path` | 비교 산출물 경계 → 실제 절대 경로. Data·범위 이탈·심볼릭 링크는 `ValueError`; 필요한 파일 부재는 `FileNotFoundError`. 파일 생성 없음. |
| `_index_batches(manifest: dict, label: str) -> dict[tuple[str, str], dict]` | 원본 전용 생성 계약·완료 시간·SHA·중복 없는 조직 목록·유형/기준월 확인 → 배치 색인. 오류는 `ValueError`; 메모리 연산. |
| `select_generation(base: dict, replacement: dict, *, base_manifest: str, replacement_manifest: str, base_manifest_sha256: str, replacement_manifest_sha256: str, replace_types: tuple[str, ...] = ("05",)) -> dict` | 최초 81개와 명시 유형 재생성 → 동일 inventory SHA·원본 경로 목록·기준 마스터 대응 검증 → 배치별 구현 SHA·원 실행 manifest SHA·원래/선택 디렉터리 계보. 기존 객체·결과 불변. |
| `final_index_rows(manifest: dict) -> list[dict]` | 선택 결과 → JSON·예상 XLSX 경로와 원래 배치·교체 여부. XLSX 작성 완료나 존재를 주장하지 않는다. |
| `compose_generation(base: Path, replacement: Path, output: Path, *, replace_types: tuple[str, ...] = ("05",), write_output: bool = False) -> dict` | 동일 실행 루트의 최초·수정 manifest 읽기 → 선택 상세 SHA 81개 확인 → 원 manifest 불변 확인. 저장 플래그일 때만 새 `generation.json`, `finalmaster_index.csv` 생성. 기존 결과는 `FileExistsError`. |
| `main(argv: list[str] \| None = None) -> int` | `--base`, `--replacement`, `--output`, `--replace-types`, `--write-output` → 검증·선택. 기본은 파일 저장 없음. |

선택 결과의 최상위 `implementation_sha256`는 `null`이고 `implementation_profile=per_batch_lineage`다. 배치마다 실제 생성 구현을 보존하므로 혼합 결과를 한 구현에서 생성한 것처럼 표시하지 않는다. `generation_seconds`는 교체되어 선택되지 않은 최초 배치까지 포함한 두 부모 실행의 시간 합이며 `generation_seconds_scope`가 이 한계를 밝힌다. 선택 작업의 시간과 구현 SHA는 별도 필드다. 생성물은 복사·이동하지 않고 실제 폴더를 참조한다.

[test_reconstruction_selection.py](../../tests/test_reconstruction_selection.py)의 `generation_fixture`, `select_fixture`와 `ReconstructionSelectionTests` 7개 메서드는 메모리로 05 단일/05·07 다중 선택, 81개 계보·입력 보존, 누락 월/유형/원본 순서/기준 마스터/중복 거부, CSV 경로, 기본 실행의 생성 manifest·상세 해시 외 읽기 및 쓰기 금지를 검사한다. 실제 원본·마스터·임시 파일·네트워크를 사용하지 않는다. 전체 함수·테스트 메서드의 Args/Returns/Raises/Side Effects는 소스 한국어 명세를 따른다.

### 한 기간 합계 차이의 원본 추적

정의 파일: [benchmark/followup_reconciliation.py](../../src/excel_parser/benchmark/followup_reconciliation.py). 저장 평가와 생성 상세를 읽는 사후 분석이며 생산 매핑·조립·채점을 바꾸지 않는다. 원본 확인은 고정 입력 목록의 조직 Excel만 허용하고 기준 마스터는 직접 읽지 않는다.

| 실제 서명 | 호출·반환·예외·부작용 |
|---|---|
| `read_rows(path: Path) -> Iterator[dict]` | 생성·평가 gzip JSONL → 전체 행 반복자. 읽기·JSON 오류를 전파하며 지정 산출물만 읽는다. |
| `location(record: dict) -> tuple`, `in_period(record: dict, year: int, month: int, scenario: str) -> bool` | 저장 출처·기간 필드 → 동일 출처·기간 검사. ID나 기준월로 의미를 보완하지 않는다. |
| `extra_category(record: dict, blank_rows: set) -> str` | 실제 포함 extra → 0, 원문 계정/ID, 제출 소유자와 행 조직 차이, 필수 빈칸 행, 추가 원본 확인 대상으로 구분. 정답의 제외 정책을 추정하지 않는다. |
| `reconcile_period(details: list[dict], errors: list[dict], excluded: list[dict], totals: list[dict], *, year: int, month: int, scenario: str) -> dict` | 저장 상세·오류·제외·합계 → 전사 영역별 차액, 합계 포함 extra 합, 수치 잔차, 별도 제외 금액, 원본 조사 목록. 출처 중복·합계 계약 위반은 `ValueError`. 메모리 연산. |
| `cell_evidence(cell: dict \| None) -> dict \| None` | 추출 원본 셀 → 값·유형·수식·서식·좌표 보존. 빈 값과 0을 구분한다. |
| `inspect_source_contract(path: Path, requests: list[dict], expected_sha256: str) -> dict` | 허용 조직 원본 해시 확인 → 기존 추출 → 원본 A~E·헤더·단위·금액·실제 SUMIFS의 B열 관리분류/D열 라벨 계약 확인 → 읽기 전후 불변 검사. ID 후보로 조직·계정을 보완하지 않는다. |
| `run_followup(baseline: Path, output: Path, *, type_code: str = "05", closing_month: str = "2026-01", year: int = 2025, month: int = 1, scenario: str = "actual", write_output: bool = False) -> dict` | 고정 생성/평가 읽기 → 금액 연결 → 허용 조직 원본의 추가 근거 확인. 저장 시 새 `reconciliation.json`, `included_extras.jsonl.gz`, `excluded_unresolved.jsonl.gz`, `report.md` 생성. 입력 해시 불변 확인. |
| `main(argv: list[str] \| None = None) -> int` | 독립 분석 CLI → 요약 출력. 쓰기는 `--write-output`일 때만 실행한다. |

`numeric_residual_won`은 출력-정답 차액에서 실제 합계 포함 extra를 뺀 수치 잔차다. `unexplained_extra_before_source_inspection_won`은 그 extra 중 저장 메타데이터만으로 업무 원인이 설명되지 않은 금액이다. `excluded_known_amount_separate_won`은 이미 생성 합계에서 빠진 미확정 상세의 알려진 금액이며 차액에 다시 가산하지 않는다. 원본 수식 계약으로 추가 확인한 금액과 남은 미설명 금액은 별도 필드로 기록한다.

[test_followup_reconciliation.py](../../tests/test_followup_reconciliation.py)의 `record`, `extra`는 합성 예시 생성 함수다. `FollowupReconciliationTests.test_excluded_amount_is_separate_from_actual_difference`, `test_numeric_residual_and_unexplained_extra_are_different`, `test_source_formula_contract_does_not_use_detail_id`는 제외 금액의 중복 가산 방지, 수치/업무 잔차 분리, 실제 수식 라벨 근거와 ID 비사용을 검증한다. 실제 파일·마스터·임시 산출물에 접근하지 않는다.

### 같은 새 구현의 전체 재파싱과 회귀 검증

정의 파일: [benchmark/regression.py](../../src/excel_parser/benchmark/regression.py). `join`은 같은 새 구현으로 재파싱한 비중복 9+36+36 그룹을 고정 v2와 동일한 81개 목록으로 합친다. `compare`는 실제 새 출력과 완료된 평가를 비교한다. 원본·기준 Excel은 읽지 않고 저장 manifest·execution·압축 상세·평가만 조회한다.

| 함수·서명 | 역할·반환·읽기/쓰기 경계 |
|---|---|
| `_allowed_root() -> Path` | comparisons 경로 계산. 자동 생성 없음. |
| `_read_json(path: Path) -> tuple[dict, str]` | 경계·읽기 전후 SHA 확인 → JSON 사전과 해시. |
| `_saved_index(manifest: dict, label: str) -> dict[tuple[str, str], dict]` | 단일 구현 및 v2 배치별 계보 계약 → 중복 없는 배치 색인. |
| `join_manifest_data(groups: list[dict], baseline: dict, *, group_paths: list[str], group_hashes: list[str], baseline_path: str, baseline_hash: str) -> dict` | 동일 구현·inventory·정확81·원본 경로/순서·기준 마스터 대응 확인 → evaluate 호환 단일 구현 final. 메모리 연산. |
| `_source_audit(directory: Path, batch: dict) -> dict[str, str]` | 저장 execution의 17개 조직 원본 해시·불변성·범위 확인. 원본 경로는 열지 않는다. |
| `_write_json(path: Path, value: dict) -> None`, `_write_csv(path: Path, rows: list[dict]) -> None` | 새 승인 결과만 배타적으로 작성. |
| `join_runs(groups: list[Path], baseline: Path, output: Path, *, write_output: bool = False) -> dict` | 저장 입력·상세 SHA와 v2/새 원본 해시 대조 → 새 final generation.json/finalmaster_index.csv. 기존 생성물 불변. |
| `_amount_signature(value: object) -> str \| None` | 유한 Decimal의 표현 차이만 제거. 빈 값과 0 유지, 잘못된 금액은 `ValueError`. |
| `semantic_payload(record: dict) -> dict` | 원본 셀의 업무 의미·기간·금액·원값·수식·상태 투영. 새 진단 필드 부재 자체는 회귀 차이로 세지 않는다. |
| `compare_source_records(before: list[dict], after: list[dict]) -> dict` | 파일·시트·셀별 의미 다중집합 → 일치·제거·추가와 양쪽 상세. 중복 수량 보존. |
| `_read_jsonl(path: Path) -> list[dict]` | 압축 상세 전수 읽기와 읽기 전후 SHA 확인. |
| `metric_snapshot(summary: dict, errors: list[dict]) -> dict` | 저장 건수·비율·합계 일치와 비영 금액 FP 계산. `nonzero_fp`는 extra 및 amount_mismatch의 비영 출력이다. |
| `compare_evaluation_rows(before: list[dict], after: list[dict], *, totals: bool = False) -> dict` | 오류 업무키·출처·금액 또는 합계 기간·값·건수 다중집합을 전수 대조. 진단 문구만 다른 경우는 금액 회귀로 세지 않는다. |
| `_aggregate_matrix(rows: list[dict]) -> list[dict]` | 월별 레코드 건수 → 유형/ALL before·after·delta와 마이크로 P/R/F1. 월별 비율 평균을 쓰지 않는다. |
| `compare_runs(baseline: Path, current: Path, output: Path, *, write_output: bool = False) -> dict` | 동일81·동일 원본·새 디렉터리 강제 → 의미/오류/합계 대조 → 비교 JSON·유형/월 CSV·변경 JSONL. 05 이외72개는 동일성을 요구한다. |
| `main(argv: list[str] \| None = None) -> int` | join/compare 독립 명령. 정상0, compare의 요구 회귀 실패2. 저장은 명시 플래그일 때만 수행한다. |

모든 함수의 상세 Args/Returns/Raises/Side Effects는 소스 한국어 명세를 따른다. 경계·계약·해시 불일치는 `ValueError`, 읽기·저장 실패는 `OSError`로 중단한다. `unresolved_required`는 저장 평가의 선택 상세 미확정 수이며 생성 단계에서 제외된 상세 미확정을 포함하지 않는다. 총 필수 미확정 감사와는 구분해야 한다. 금액이 확정되어 strict FP로 남은 업무 무효 상세도 합계에서는 제외될 수 있으므로 FP와 합계 차이 감소는 독립적으로 보고한다.

[test_regression.py](../../tests/test_regression.py)의 `regression_inputs`, `join_fixture`, `source_record`와 `RegressionTests` 9개 메서드는 메모리로 복합 v2와 새 동일 구현 결합, 중복/범위/원본/구현 불일치 거부, 셀/평가 다중집합, 빈값/0, 비영 금액 FP, 마이크로 집계, 합계 그룹·실제 금액 변화 검출을 검증한다. 실제 원본·마스터·임시 파일을 만들거나 읽지 않는다.

### 저장 결과 차액·미확정 후처리

정의 파일: [benchmark/followup_saved_report.py](../../src/excel_parser/benchmark/followup_saved_report.py). 완료된 두 실행의 저장 JSON/GZ만 읽는 사후 분석 모듈이다. 원본·기준 마스터 Excel, 생산 파서·채점 함수를 호출하지 않는다. 실제 생성 구현 SHA와 후처리 코드 SHA를 구분한다. 최초 산출물 폴더에서 실행한 임시 분석 코드는 `src`로 이동했으며 `postprocessing_source_migration.json`에 당시 코드 전문·SHA·기존 결과 경로를 보존했다.

| 함수 | 역할과 호출 관계 | 입출력·예외·부작용 |
| --- | --- | --- |
| `checked` | 읽기·쓰기 경로를 명시한 실행 산출물 루트로 제한 | Data·경계 밖·심볼릭 링크·부재는 거부; 경로 메타데이터만 조회 |
| `sha` | 보호할 저장 산출물 SHA256 계산 | 지정 파일만 읽으며 읽기 오류 전파 |
| `rows` | 압축 JSONL의 상세·오류 순회 | gzip/JSON 오류 전파; 파일 생성 없음 |
| `amount` | 저장 금액을 유한 Decimal로 해석 | None/빈 문자열과 실제 0 구분; 비유한 금액 거부 |
| `location` | 파일·시트·셀 출처 키 생성 | 원본을 열거나 ID로 의미 보완하지 않음 |
| `issue_category` | 조직·업무·계정·상세 식별·금액·수식 미확정 분류 | 저장 필드만 사용하고 없는 사유를 추측하지 않음 |
| `group_keys` | 전사·원래 조직의 기간 합계 키 생성 | 행 조직 None을 그대로 유지; 제출 소유자·파일명·ID로 보완 금지 |
| `extra_reason` | 포함 extra의 실제 0·원본 코드·원본행 관계 분류 | 동일 원본 셀의 현재 업무 검증을 진단에 사용; 기존 오류·점수 불변 |
| `load_batch` | 생성·평가 상세와 보호 해시 읽기 | 생성 상세 SHA 검증; Excel 경로는 문자열로만 취급 |
| `analyze_batch` | 포함 오류의 signed 기여와 별도 제외 잠재 영향 연결 | 포함 상세 합계와 저장 합계가 다르면 거부; 잔차·선택적 빈칸·실제 0·식별/금액 미확정을 분리 |
| `save_rows` | 새 압축 JSONL 배타 저장 | 기존 파일 덮어쓰기 금지; 지정 출력 1개 생성 |
| `run_saved_report` | 지정 유형의 before/after 읽기·연결·해시 재확인 | `baseline,current,output,type_prefix,write_output`; 기본 읽기 전용, True에서만 현재 실행 루트의 새 폴더에 저장 |
| `main` | 독립 모듈 CLI | `--baseline --current --output --type --write-output`; 정상 0, 잘못된 입력·범위·기존 출력은 오류 |

[test_followup_saved_report.py](../../tests/test_followup_saved_report.py)의 `saved_record`, `saved_error`, `saved_totals`, `saved_batch`는 독립 합성 입력 함수다. `FollowupSavedReportTests` 12개 메서드는 포함/제외 extra, 금액 불일치의 출력−정답, 정답 누락의 음수 기여, 미설명 숫자잔차 보존, 손상 저장합계 거부, 실제 0·선택적/필수 빈칸·업무 식별 오류의 구분, 조직 미확정의 비추론, 기본 읽기 전용·명시 저장 CLI·경계 밖 거부를 메모리로 검증한다. 전체 작성 함수의 한국어 Args/Returns/Raises/Side Effects는 소스에 명시했다.

### 완료 그룹 평가의 최종 합성

정의 파일: [benchmark/regression_evaluation.py](../../src/excel_parser/benchmark/regression_evaluation.py). 실제 완료된 9+36+36개의 그룹 평가를 최종 81개 생성 목록에 대응시키는 후처리다. 재파싱·재채점·조직/기준 Excel 읽기는 0이며 기존 월별 평가 5개 파일씩 총 405개를 바이트 동일 사본으로 보존한다.

| 함수 | 역할과 호출 관계 | 입출력·예외·부작용 |
| --- | --- | --- |
| `_evaluation_rows` | 평가 완료 행을 유형·기준월로 색인 | 중복·누락·계수·필수 검증 상태 오류 거부; 메모리 전용 |
| `combine_evaluation_data` | final81과 그룹 생성·평가의 정확한 비중복 합집합 검증 | 구현·inventory·상세 SHA·원본 목록·디렉터리·그룹 계보·생성 상태 대조; 기존 점수 행을 수정하지 않음 |
| `merge_evaluations` | 월별 파일 구성·summary 일치·상세/평가 해시 확인 후 사본 작성 | `groups,current,output,write_output`; 아직 없는 final/evaluation만 허용; 명시 저장 때만 사본·합성 validation·계보 생성 |
| `main` | 평가 합성 CLI | `--groups --current --output --write-output`; 정상 0, 경계·범위·기존 출력 오류 전파 |

[test_regression_evaluation.py](../../tests/test_regression_evaluation.py)의 `evaluation_fixture`와 `RegressionEvaluationTests` 6개 메서드는 메모리로 81개 점수 행·입력 보존, 계수 합성, 중복/누락/불완전 합집합, 다른 생성물·계보·상세 SHA, 미완료 평가, 기존 출력의 읽기 전 거부를 검증한다. 실제 원본·마스터·임시 산출물에 접근하지 않는다.

### v4 원본 계약에 따른 발행 상세 분리

정의 파일: [publication_policy.py](../../src/excel_parser/publication_policy.py). `assemble_master(..., publication_policy="source-contract-v4")`에서만 호출한다. 기본 v3 API·결과를 바꾸지 않으며 원본 생성 코드나 마스터를 실행·import·조회하지 않는다. 고정 정책의 정적 근거와 파서가 읽은 원본 값·상태·계약 provenance만 사용한다. 함수별 전체 한국어 Args/Returns/Raises/Side Effects는 소스에 명시했다.

| 함수 | 역할·입출력·호출 관계 | 주요 한계·부작용 |
| --- | --- | --- |
| `_number` | 이미 정규화된 유한 Decimal 또는 None 반환 | 빈값·0 구분; 원문 재해석·금액 보완 없음 |
| `_source` | 정책에 사용한 원본 셀·기간·금액·상태 복사 | 파일·수식 실행 없음 |
| `_blank` | 명시 원문 빈칸이고 정규금액·수식이 없는지 판정 | 수식 cache missing/error는 실제 빈칸이 아님 |
| `_invalid_business_evidence` | 필드 invalid·대응 issue·원본 provenance·등록 계약 근거 확인 | invalid 문자열만으로 제외 금지; ID는 명시 무효 토큰만 확인하고 조직·계정 추론에 사용하지 않음 |
| `_owner_evidence` | 선언 소유자와 확인된 비용 행 조직의 범위 위반 근거 | 소유자 선언과 행 조직 provenance 필요; 두 코드 모두 원문 보존 |
| `_inactive_revenue_evidence` | prior/plan/next_plan 전체 및 마감 이하 actual의 완전한 슬롯이 None/0인지 확인 | 비용·활성 매출의 개별 0 유지; 미래 estimate 제외; 기간 누락·금액/의미 미확정은 비활성 판정 보류 |
| `source_organization_rejection` | 기존 identity 제외 중 조직 conflict·조직명/셀 근거·기타 식별 정상인 원본 행 제외만 구분 | 단순 ID/조직 missing·unresolved·근거 부재는 일반 미확정으로 유지 |
| `partition_publication_records` | 수정본 선택 후 물리 원본행을 발행 records와 전월 슬롯 감사로 분리 | 정책 이름·기준월·원본행 계약 오류는 ValueError; 순수 메모리이며 입력·키·금액 변경 없음 |

`master.publication_policy`는 정책 버전·계약·제외 행/슬롯 건수와 근거 위치를 가진다. 제외된 레코드는 `publication_decision.decision_id`로 `issues[].publication_decision`의 행별 근거에 연결한다. 원문·정규금액·confirmed·0·빈칸·수식·개정·재전송 출처는 `excluded_records`에 보존한다. 원본 조직 충돌의 기존 제외는 `publication_rejection_evidence`만 덧붙이고 `detail_identity_unresolved` 사유와 코드 None을 바꾸지 않는다.

`publication_status`는 `complete`, `complete_with_exclusions`, `partial` 문자열이다. 발행 상세에 필수 미확정이 있거나 기대 조직 누락·제출/수정본 선택 미확정·근거 없는 일반 identity 제외가 있으면 partial이다. 이 상태는 정답 일치나 공식 승인 여부를 뜻하지 않는다. 기존 `status`와 `source_quality.status`는 원본 오류를 포함하므로 발행 상세가 완전해도 partial일 수 있다. `source_quality`는 정책상 거부 행 수, 원본 identity 미확정 슬롯 전체, 그중 계약상 조직 충돌 제외/일반 미확정, 명시 1월 actual 빈칸, 발행 잔여 미확정을 각각 보존하며 `original_values_corrected=False`를 명시한다.

[test_publication_policy.py](../../tests/test_publication_policy.py)의 `source_row`, `source_submission`, `mark_invalid`, `partition`은 합성 자료 함수다. 15개 테스트는 실제 0(활성 매출 47개·비용 30개 포함) 보존, 미래 추정 비활성 판정 제외, January만 필수로 한정, 다른 월·선택적 빈칸·수식 cache와 미완전 범위의 비추정, 무효 필드 근거·모든 슬롯 보존, owner 범위/정상 수신조직, v3 기본 호환, 조직 충돌 감사·기대조직누락·일반 identity 미확정, 수정본 선택 후 정책·멱등성을 메모리로 검증한다.

### 업무 검증과 조립·엄격 채점의 연결 검증

[test_business_integration.py](../../tests/test_business_integration.py)의 `BusinessIntegrationTests`는 독립 메모리 레코드만 사용한다. 모든 메서드는 인자 없이 `None`을 반환하며 실패 시 `AssertionError`를 발생시키고 파일·원본·정답에는 접근하지 않는다.

| 메서드 | 검증하는 연결 |
|---|---|
| `test_flat_keeps_amount_and_observed_key_when_business_invalid` | 업무키 실패 이후에도 원문 코드·ID·확정 0원을 그대로 보존하는 투영. |
| `test_invalid_business_key_excludes_totals_but_remains_fp_fn` | 같은 키·금액이어도 업무 미확정이면 합계와 TP에서 제외하고 엄격 FP/FN으로 남기는 평가 경계. |
| `test_invalid_literal_id_does_not_merge_physical_rows` | 같은 문자열 `None` ID의 서로 다른 물리 행을 중복이나 수정본으로 병합하지 않는 선택. |
| `test_source_verified_foreign_organization_is_not_rejected` | 원본에서 확인한 행 조직과 제출 소유자가 달라도 정상 상세를 합계에 반영하는 처리. |

### 저장 Excel의 실제 셀 엄격 평가

정의 파일: [benchmark/excel_evaluation.py](../../src/excel_parser/benchmark/excel_evaluation.py). 생성 완료 목록은 범위·경로 확인에만 사용한다. 생성 `details.jsonl.gz`, `master.json`, `workbook_verification.json`의 내용은 판독하지 않는다. 마지막 파일은 내보내기 종료를 확인하는 존재 검사만 한다. 실제 XLSX의 A3/A4·헤더·키·금액·월별 상태·합계 셀을 기존 추출기와 금액 정규화기로 읽어, 변경하지 않은 `score_monthly_records` 및 `_aggregate_checks`에 전달한다.

| 함수 | 입력 → 반환·경계 |
|---|---|
| `_allowed()` | 없음 → 승인된 comparisons 절대 경로. 생성 없음. |
| `_integer(value)` | 실제 셀 → 정확한 정수 또는 None. bool·빈칸·소수의 보충 없음. |
| `_object_pairs(pairs)` | JSON 키·값 → 중복 없는 사전. 중복은 ValueError. |
| `_json_map(value)` | 실제 Excel 월별 JSON 문자열 → 사전·유효 여부. 1–12 외 키·손상·중복은 실패로 남긴다. |
| `_index_sheet(sheet)` | 기존 추출기 셀 → 실제 행·열 색인. 중복 좌표는 ValueError. |
| `_context(rows)` | A3/A4 → 유형·마감월·단위·발행/품질/조립 상태·기대/반영 조직 수. 외부 문맥 보충 없음. |
| `_headers(rows, required)` | 실제 5행 → 유일한 헤더 색인 또는 None. |
| `_money(cell, unit)` | 실제 값·타입·서식·수식 캐시와 A3 단위 → 기존 normalize_number 결과. 빈칸을 0으로 만들지 않는다. |
| `_address(row, column)` | 실제 좌표 → 오류 위치 A1 문자열. |
| `_detail_records(sheet, rows, context, path, issues, legacy=...)` | 실제 가로 상세 → 월별 물리 레코드. 같은 업무키 행의 다중성을 유지하며 미확정 숫자는 FP 가능 출력으로 보존한다. issues만 갱신한다. |
| `_total_records(sheet, rows, context, path, issues, legacy=...)` | 실제 전사·조직 합계표 → 월별 금액·건수·위치. 중복 합계 행은 합산·제거하지 않는다. |
| `parse_generated_workbook(workbook, legacy_compatible=False)` | 메모리 추출 결과 → 실제 상세/합계·문제·문맥·숫자/0/빈칸 계수. 파일 접근 없음. |
| `read_generated_excel(path, legacy_compatible=False)` | 승인 경계의 생성 XLSX → 실제 판독 결과·SHA·불변성·읽기 시간. 파일 하나만 읽고 외부 JSON이나 기준을 읽지 않는다. |
| `_expected_totals(records, truth=False)` | 실제 상세 또는 독립 정답 → 전사/조직/계정 합계. 상세 점수 분모를 대체하지 않는다. |
| `compare_total_cells(actual, expected, basis=...)` | 실제 합계 셀과 독립 합계 → 물리 행 일대일 금액·월별 건수 결과. 중복·빈칸·누락을 보존한다. |
| `evaluate_excel_data(parsed, truth, batch)` | 실제 판독·독립 정답·기대 범위 → 변경하지 않은 엄격 점수·기존 합계 + 실제 합계표 대조. 추가 오류 출처는 생성 Excel 좌표와 보존된 조직 원본 좌표를 모두 담는다. 메모리만 사용한다. |
| `_preflight_run(run_dir, output_dir)` | 완료 generation과 새 출력 경로 → 목록·모든 생성 XLSX 경로. 모든 Excel 및 내보내기 완료 표식 확인 전 기준을 읽지 않는다. 기존 출력·누락·중복은 오류. |
| `evaluate_excel_run(run_dir, output_dir, write_output=False, legacy_compatible=False)` | 명시 승인 → 실제 Excel 읽기 후 평가 전용 기준 마스터 읽기·엄격 채점·신규 결과 저장. 생성 JSON의 값·키·상태는 읽거나 보충하지 않는다. |
| `main()` | CLI `--run --output --write-output [--legacy-compatible]` → 평가 실행 및 콘솔 요약. 기존 결과 덮어쓰기 없음. |

월별 `summary.json`은 기존 truth_count/confirmed_output_count/match_count/FP/FN/금액 불일치·합계 지표를 유지하고 `prediction_source="saved_excel"`을 기록한다. `strict_detail_and_total_match`는 기존 상세+합계 기준이며, 최종 Excel 완료는 여기에 실제 합계표 금액·월별 건수와 실측 구조·기대 조직 수까지 만족한 `strict_excel_detail_and_total_match`로 확인한다. `stored_totals_verified`는 실제 Excel 합계표와 실제 Excel 상세의 일치이고 `actual_sheet_totals_match_reference`는 실제 합계표와 독립 정답 합계 금액 일치다. 0원 오탐과 비영 FP는 따로 센다.

[test_excel_evaluation.py](../../tests/test_excel_evaluation.py)의 `workbook_fixture`, `fixture_cell`, `truth_fixture`, `score_fixture`는 파일 없는 독립 합성 자료만 만든다. `ExcelEvaluationTests` 15개 메서드는 실제 셀/키 변경, 금액 불일치 FP+FN, 비용 0과 빈칸, 시트 누락의 분모 유지, 물리 상세/합계 중복, 미확정 숫자의 FP·합계 제외, 명시 이전 양식 호환, 실제 합계·월별 건수 변경, 손상 JSON/수식 캐시, A4 조직 수·부분 제출, 조기 기준 읽기/쓰기 차단을 검증한다. 모든 함수·메서드의 Args/Returns/Raises/Side Effects는 한국어 소스 명세를 따른다.

### v4 생성 정책 전달 경계

| 함수/경로 | v4에서 추가된 명시적 계약 |
| --- | --- |
| `benchmark.cli.build_parser` / `main` | master generate의 `--publication-policy source-contract-v4`만 전달. 기본은 None이며 master evaluate에 정책 플래그를 주면 거부하여 채점 규칙 적용과 분리한다. |
| `benchmark.reconstruction.parse_submission` | 입력 파일을 실제 절대 경로로 통일한 뒤 파서·제출 메타데이터·행 출처에 사용한다. 상대/절대 경로 차이로 원본 선언 소유자 근거 연결이 빠지는 일을 방지한다. |
| `benchmark.reconstruction.generate` | 허용 정책을 확인하고 최초 조립과 교체 후 재조립 두 호출에 같은 publication_policy를 전달한다. 명시한 경우에만 generation.json에 정책을 기록하며 원본 source_records는 정책 전 상태 그대로 보존한다. |
| `benchmark.regression.join_manifest_data` | 모든 새 생성 그룹의 policy 값이 같아야 합성한다. None과 v4 혼합도 거부하며 최종 manifest에 명시 정책을 보존한다. 입력·구현·정확한 81개 합집합 검증은 그대로다. |

### v3 저장 평가와 v4 실제 Excel 평가의 발행 보고

정의 파일: [benchmark/publication_report.py](../../src/excel_parser/benchmark/publication_report.py). 현재 실행의 `evaluation/validation.json`은 실제 Excel 평가여야 한다. 원본·기준 Excel을 열거나 재채점하지 않고, 저장 원본 파서 결과의 압축 해제 바이트·제외 감사·실제 Excel 평가의 before/after만 읽는다.

| 함수 | 역할과 호출 관계 | 입출력·예외·부작용 |
| --- | --- | --- |
| `_lines` | 저장 압축 JSONL 스트리밍 | JSON 객체 반복자; 읽기·압축 오류 전파; 생성 없음 |
| `_uncompressed_hash` | source_records의 실제 저장 바이트 SHA | gzip 작성 시각과 내용 변경을 분리; 스트리밍만 수행 |
| `_artifact` | nested 저장 파일 경계와 읽기 전 SHA 보호 | comparisons 내부 JSON/GZ만 허용하고 Excel/심볼릭 링크/변경 거부; 보호 해시 사전만 갱신 |
| `_evaluation_index` | 정확한 완료 평가 행을 유형·월로 색인 | 중복/누락/범위/미완료/기준 보존 실패 거부; 현재는 saved_excel 및 JSON값보충0 요구 |
| `audit_exclusion` | 금액 종류·제외 사유·필요한 원본 정정 필드 분류 | 실제 0·선택적 빈칸·수식 캐시·조직/계정/ID/출처 미확정 구분; 명시 당해 1월 actual만 필수 금액 슬롯 |
| `create_publication_report` | 동일 81개 before/after와 source_records 불변·전량 제외 감사 연결 | 모든 읽은 산출물 SHA 재검사. 기본 읽기 전용, 명시 저장 때만 새 JSON/CSV/압축 감사 작성 |
| `main` | `--baseline --current --output --write-output` CLI | 실제 Excel strict 완료 81개면 0, 미완료가 남으면 2; 기존 결과 덮어쓰기 금지 |

`comparison.json`에는 레코드 마이크로 지표·실제 Excel strict 완료 수·원본 파서 불변·정정 필요 슬롯의 필드별 분류를 저장한다. `publication_exclusions.csv`는 원문 전체 감사 파일과 행별 decision 근거가 있는 master.json을 함께 연결한다. `source_corrections_needed.jsonl.gz`는 원문·금액·셀과 필요한 정정 항목을 보존한다. `remaining_total_differences.json`은 상세 계산 합계, `remaining_actual_excel_total_checks.json`은 실제 Excel 합계표의 금액·건수 문제다. 상세 F1이 100%라도 실제 합계표만 틀린 문제를 숨기지 않는다.

[test_publication_report.py](../../tests/test_publication_report.py)의 `excluded_record`, `evaluation_fixture`, `report_fixture`, `diagnostic_lines`는 독립 합성 입력 함수다. 10개 메모리 테스트는 0/빈칸/수식·정정 필드 구분, January 필수 범위, 평가 중복/미완료/실제 Excel 출처, 저장 경계·입력 변경, 압축 메타데이터와 실제 바이트 구분, 원본 파서 변경 시 점수 비교 전 거부, 상세 F1 100%인 실제 합계표 실패 및 CLI 엄격 완료 기준을 검사한다. 실제 원본·정답·임시 파일은 읽거나 만들지 않는다.

## service_bridge.py · 내부 메모리 프로세스 연결

정의: [service_bridge.py](../../src/excel_parser/service_bridge.py). 설치·사설 HTTP 서비스 없이 `PYTHONPATH`에 `parser/src`를 설정한 Python 3.11 이상에서 실행합니다. 호출자는 셸 문자열을 조합하지 않고 인자 배열로 파일명을 전달하며 `-B`, 실행 시간 제한, stdout 크기 상한을 적용합니다. 브리지의 stdout은 UTF-8 JSON 한 건과 마지막 줄바꿈이며, 파일 저장과 업무 권한 검사는 수행하지 않습니다.

| 함수 서명 | 호출 관계·입출력 | 예외·부작용·한계 |
| --- | --- | --- |
| `_check_filename(filename: str) -> None` | main/parse_bytes → 파일명 길이·구분자·제어 문자·xlsx 확장자 확인 | 고정 BridgeError 코드. 조직·버전 추론 및 파일 접근 없음 |
| `_reject_doctype(*arguments: object) -> None` | Expat DTD 이벤트 → UNSAFE_XML 거부 | UTF-8/UTF-16 구분 없이 선언 시점에 BridgeError. 외부 자원 조회 없음 |
| `_inspect_xml(document: bytes, totals: dict, limits: BridgeLimits) -> None` | 패키지 검사 → 전체 XML 자원과 워크시트 물리/내용 셀·주소·작업량 자료 누적 | 공유 문자열을 반복 확장하지 않음. 그림 앵커의 col·calcChain의 c는 워크시트 셀 규칙에서 제외하며 일반 XML 한도는 적용 |
| `_inspect_xml.start(name: str, attributes: dict) -> None` | Expat 시작 이벤트 → 전체 요소·속성 길이·시트 ID/이름 및 워크시트 셀·병합·열 범위 확인 | BridgeError/ValueError. 핵심 XML 관계의 비xml 대상 거부. 워크시트 물리 셀과 내용 여부를 분리 기록 |
| `_inspect_xml.end(name: str) -> None` | Expat 종료 이벤트 → 내용 셀·공유 문자열·행별 값/수식/참조 길이 기록 | 내용 없는 서식 셀은 행 문맥에서 제외. 내용 셀 상한·잘못된 공유 문자열 인덱스는 BridgeError |
| `_inspect_xml.characters(value: str) -> None` | Expat 분할 텍스트 이벤트 → 단일 값·수식·리치 문자열 합계 길이 확인 | 길이 초과는 BridgeError. 내용을 반복 생성하지 않고 길이·인덱스만 기록 |
| `_validate_text_budget(totals: dict, limits: BridgeLimits) -> None` | 공유 문자열 길이 연결 → 행 값/문자열×내용 셀 수 + 수식 1회분의 작업량 확인 | 없는 공유 문자열·작업량 초과는 BridgeError. 추출·매핑 전 거부 |
| `_validate_package(content: bytes, limits: BridgeLimits) -> None` | parse_bytes → ZIP·OOXML·XML 사전 검사 | 중복/상위/절대 엔트리, 암호화, 미지원 압축, 매크로 형식, DTD, 한도 위반 거부. 메모리 해제만 수행 |
| `_failure(code: str) -> dict` | 내부 오류 → 빈 records·0 카운트·고정 메시지의 FAILED 응답 | 원본 값·경로·예외 원문 없음 |
| `_preview_record(record: dict) -> dict` | 표준 매핑 후보 → 내부 미리보기 필드 | 모든 후보·금액·수식·업무 오류 근거 보존. 중복 행 문맥·내부 경로는 전달하지 않음. 입력은 변경하지 않고 중첩 읽기 값 참조를 공유 |
| `_public_diagnostic(item: dict) -> dict` | 추출·매핑 진단 → 원인별 고정 안내·확인된 원본 셀 | 조직 조회 오류·이름 충돌·코드 불일치·모호함을 구분. 직접 셀이 없으면 동일 시트의 provenance 셀 사용. 원문 값·경로·예외 메시지는 복제하지 않음 |
| `_business_diagnostic(record: dict, issue: dict) -> dict \| None` | `_review_diagnostics` → 계정·상세 ID·관리분류의 고정 원인·담당자 정정 및 전체 파일 재업로드 안내 | issue의 field/code/provenance와 dimensions 필드 출처가 일치하고 금액 셀과 같은 시트·행인 경우만 정확한 입력 셀 사용. 코드표·다른 행·위치 불명은 None. 관측값·금액·상태 변경 없음 |
| `_review_diagnostics(unresolved: list[dict]) -> list[dict]` | parse_bytes → 업무 오류는 code·sheet·cell별 한 번 표시하고 나머지 일반 안내 유지 | 금액·매핑의 별도 불확실성, 미지원 사유와 출처 부족은 기존 금액 셀 안내로 보존. 진단 수만 줄이며 후보·미확정 수·검토 상태는 변경하지 않음 |
| `parse_bytes(content: bytes, filename: str, limits: BridgeLimits \| None = None) -> dict` | 검사 → 기존 메모리 추출·매핑 → 전체 후보의 검토 상태 판단 → 미리보기 프로토콜 | 일반 예외는 고정 실패코드. Decimal 유지. 참고/미확정 후보를 제거하거나 성공으로 바꾸지 않음 |
| `_decimal_json(value: object) -> str` | JSONEncoder → 유한 Decimal의 지수 없는 문자열 | 비유한 값은 ValueError, 다른 미지원 타입은 TypeError |
| `_response_chunks(response: dict, limit: int) -> Iterator[str]` | main/문자열 호출 → 응답 전체의 형식·UTF-8 바이트를 먼저 검사한 뒤 같은 JSON 조각 반복자 반환 | 상한/비유한 값 검사는 반환 전에 완료. 반환 후 응답을 변경하지 않으며 CLI는 전체 문자열을 만들지 않음 |
| `_encode_response(response: dict, limit: int) -> str` | 문자열이 필요한 내부 호출 → 검증된 조각을 StringIO로 수집 | 응답 상한과 계약은 동일. 실제 CLI는 이 수집 경로를 사용하지 않음 |
| `main(argv: list[str] \| None = None, *, input_stream: BinaryIO \| None = None, output_stream: TextIO \| None = None, error_stream: TextIO \| None = None, limits: BridgeLimits \| None = None) -> int` | stdin 바이트 → stdout JSON, FAILED일 때만 stderr 오류코드 한 줄 | SUCCEEDED/NEEDS_REVIEW는 0, FAILED는 1. 입력은 한도+1까지만 읽음. 출력 파이프 쓰기 오류는 전파. 임시·결과·캐시 파일 생성 없음 |

`BridgeLimits`는 변경 불가능한 한도 dataclass이며 별도의 생성자 검증은 하지 않습니다. 기본값은 입력 16 MiB, 전체 해제 64 MiB, 개별 엔트리 16 MiB, 1,024개 엔트리, 해제 배율 200, 내용/자식 요소가 있는 워크시트 셀 80,000개(`max_cells`), 서식 빈 셀을 포함한 물리 워크시트 셀 250,000개(`max_xml_cells`), 전체 XML 요소 600,000개·깊이 64, XML 이름/속성 값 8,192자, 단일 값·수식·리치/공유 문자열 32,767자, 물리 셀×병합/열 문맥 작업량 합계 6,000,000, JSON 출력 96 MiB입니다. workbook의 sheetId는 1~4,294,967,295의 최대 10자리 ASCII 정수, 시트명은 1~31자로 제한합니다. 원본 17개 재검증에서 물리 셀 약 20만 개와 전체 XML 약 45만 개를 관측해 물리 셀과 요소에 유한 상한을 별도로 뒀습니다. 첫 파일 문맥 작업량 5,167,709에 맞춰 이 상한을 기존 5,000,000에서 조정했습니다. 주석이 있는 빈 셀은 extractor에서 보존되므로 문맥 비용은 내용 셀 수로 줄이지 않고 물리 셀 기준을 유지합니다. 2026-11-17에 808개 상세행을 가진 대용량 원본의 내용 셀 75,510개·논리 텍스트 37,346,854바이트·JSON 91,888,707바이트(마지막 줄바꿈 포함)를 확인하여 내용 셀·논리 텍스트·응답의 세 상한만 조정했습니다. 입력·해제·개별 엔트리·XML·단일 텍스트·반복 문맥 상한은 유지합니다. 모든 업무 파일을 지원한다는 뜻은 아니며, 핵심 XML 파트는 `.xml` 확장자를 요구하고 매크로·암호화 파일은 처리하지 않습니다.

논리 텍스트 작업량은 공유 문자열 표의 길이와 `각 논리 행의 값/문자열 UTF-8 바이트 합계 × 그 행의 내용 셀 수 + 수식 텍스트 바이트`를 합쳐 40 MiB로 제한합니다. `_extract_sheet`가 빈 서식 셀을 희소 구간으로 다루므로 이 셀은 행 문맥 횟수에서 제외합니다. 수식은 행 설명으로 복제하지 않아 1회분만 계산하되 단일 길이와 전체 텍스트 상한은 계속 적용합니다. 공유 문자열 참조는 원문 길이로 환산하며 복수 표의 같은 인덱스는 가장 긴 값을 사용합니다. XML row 배치 대신 셀 주소의 논리 행을 사용합니다. 큰 상세 ID·공유 문자열의 문맥 증폭은 추출 전에 제한합니다. 이 보수적 작업량은 실제 결과 크기와 다르며, 프로세스 RSS를 강제하는 OS 메모리 쿼터는 아닙니다.

실제 CLI는 JSON을 두 번 순회합니다. 첫 순회에서 전체 형식과 UTF-8 출력 상한을 검사하고, 성공한 응답만 두 번째 순회에서 작은 조각으로 stdout에 씁니다. 원본 후보나 근거 필드를 삭제하지 않으며, 큰 Unicode 문자열과 인코딩 바이트를 동시에 보유하는 추가 메모리를 줄입니다. 출력 스트림 오류는 기존처럼 호출자에게 전파됩니다.

2026-11-17 같은 1월 대용량 원본의 실제 CLI를 macOS 독립 자식 프로세스로 측정했습니다. 분할 출력 적용 전 최대 RSS는 1,412,923,392바이트, 적용 후에는 813,187,072바이트였습니다. 최신 응답은 91,875,423바이트·종료코드0·`SUCCEEDED`·stderr0바이트이며 원본 SHA-256이 같았습니다. 적용 후 실행시간16.55초는 다른 전수 검증이 동시에 실행된 환경의 관측값입니다. 이 측정은 Docker의 JVM과 Python 동시 메모리 사용량을 보장하지 않습니다.

외곽 프로토콜 `1.0`은 `{protocolVersion,status,errorCode,summary,diagnostics,records}`이며, 메타데이터 `parserVersion=stdlib-bridge/1.4`, `contractVersion=preview-records/1.0`과 별도 `submissionData`를 함께 제공합니다. Java는 기존 외곽 프로토콜을 검증하고 Worker는 제공된 버전을 저장합니다. 미리보기·제출 어댑터 계약 버전은 표준 `map_workbook` 출력 계약과 다릅니다.

- summary는 `sheetCount, recordCount, mappedCount, unresolvedCount, unusedTemplateRowCount` 정수입니다. recordCount와 mappedCount는 표준 후보 전체 기준이며 unresolvedCount는 재무 금액 상세 중 의미·금액·업무키가 미확정인 수입니다. 참고·코드표 숫자와 엄격히 확인된 미사용 양식 후보를 이 수에 포함하지 않습니다. unusedTemplateRowCount는 `role.kind=inactive_template` 후보의 시트·행 조합을 중복 제거한 수이며, 원본 후보는 records에 유지합니다. Java는 이전 저장 결과에 이 필드가 없으면 0으로 표시합니다.
- diagnostics는 `{code,severity,stage,message,source}`입니다. severity는 ERROR/WARNING, stage는 PARSING/MAPPING, source는 `{sheet,cell}` 또는 null입니다. 정보성 info 진단은 생략하며 기존 표준 후보는 내부 records에서 보존합니다. 계정·상세 ID·관리분류 오류는 실제 필드 출처와 issue 근거가 확인된 같은 행의 셀만 안내하며, 월별로 반복된 동일 code·sheet·cell은 하나로 묶습니다. 담당자가 원본을 정정한 뒤 전체 파일을 다시 업로드하도록 안내하고, 코드표 셀이나 다른 행을 정정 위치로 추측하지 않습니다. 별도 금액·매핑 불확실성 또는 확인하지 못한 사유는 기존 일반 안내로 남깁니다. 모든 원본 셀·스타일 추출 사전을 이 프로토콜에 포함하는 것은 아닙니다.
- records는 모든 표준 후보의 내부 미리보기 투영입니다. `source(sheet,cell)`, `organization(code,name,status)`, `item(code,name,detail_id,detail_revision,metric_code)`, `period(year,month,quarter,aggregation,scenario)`, `role(kind,aggregatable)`, `value`, `mapping`, `formula`, `raw_value`, `data_type`을 보존합니다. 업무 검증이 있는 후보는 `business_validation(status,issues)`도 보존하며 issues의 코드·관측값·근거 셀을 생략하지 않습니다. value/formula/mapping 내용은 표준 결과 그대로이고 Decimal은 CLI에서 십진 문자열입니다.
- 반복되는 항목·조직·기간 provenance, dimensions, 정상 업무검증의 상세 문맥, 스타일·제출 메타데이터, source의 내부 경로/원본 ID는 이 미리보기 계약에 포함하지 않습니다. 업무 오류 issues의 원본 근거는 그대로입니다. 표준 추출/매핑의 원본 계약은 변경하지 않았고 저장된 원본을 재추출해 확보합니다. 이 결과는 공개 source-cells 전체 조회나 승인 금융 자료가 아닙니다. 후보를 제거해 정상으로 보이게 하거나 서로 다른 자료를 합치지 않습니다.
- 재무 금액 상세가 없거나 관측한 상세의 의미·금액·업무키 검증이 실패하면 NEEDS_REVIEW/PARSER_REVIEW_REQUIRED입니다. 나머지는 SUCCEEDED지만 필수 월·조직 전체 제출, 로그인 조직 일치, 보고 항목 매핑, Gap·소명·승인 검증 완료를 의미하지 않습니다. 빈 셀을 0으로 보충하지 않으며 지원하지 않는 재무 영역의 누락을 완전하다고 주장하지 않습니다.
- 실패는 FAILED와 고정 오류코드, 빈 records입니다. stdin/ZIP/XML/형식/내부 오류의 원문을 stdout 진단이나 stderr에 복사하지 않습니다. stdout을 끝까지 확인한 후 부모 프로세스가 종료코드를 판정해야 실패 JSON을 읽을 수 있습니다.

[test_service_bridge.py](../../tests/test_service_bridge.py)의 `_package`, `_shared_package`는 메모리 ZIP, `_invoke`는 메모리 stdin/stdout/stderr만 사용합니다. `ServiceBridgeTests`의 30개 메서드는 실제 추출·매핑의 십진 정밀도·0·명시 ID, 캐시 누락, 무효 업무 ID의 금액 보존, 기존 경로 API 호환, 파일 열기 금지, 자원 한도, UTF-8/UTF-16 DTD, 원문 비노출, 상세 부재 및 `python -B -m` 종료코드·JSON 프로토콜을 검사합니다. 시트 ID/이름, 속성·텍스트, 리치 문자열, 공유 문자열 ZIP 순서·없는 참조·행 증폭도 검사합니다. 추가 회귀 메서드는 아래와 같습니다. 모두 메모리에서 실행하며 합성 원본·임시 폴더·테스트 결과 파일을 만들거나 Data를 읽지 않습니다.

| 추가 회귀 메서드 | 확인하는 실패 방지 |
| --- | --- |
| `test_drawing_anchor_columns_are_not_worksheet_columns` | 정상 그림 앵커 col을 열 서식으로 오인하지 않음 |
| `test_active_organization_lookup_error_keeps_amount_and_location` | 실제 입력의 조직 조회 오류는 정확한 셀과 NEEDS_REVIEW로 전달하며 정밀 금액·0 보존 |
| `test_unused_template_summary_keeps_candidates_and_rechecks_input` | 여러 미사용 후보를 고유 행 1개로 집계하고 후보 전체 보존. 월에 0을 입력하면 즉시 활성 검토 대상으로 전환 |
| `test_invalid_worksheet_column_ranges_remain_rejected` | 실제 열의 누락·0·역전·범위 초과는 계속 거부 |
| `test_sparse_format_cells_have_a_separate_physical_limit` | 주석만 있는 빈 셀 80개가 추출에서 보존되어도 금액 후보가 되지 않으며 물리 셀·문맥 반복 상한은 유지 |
| `test_formula_text_is_charged_once_without_disabling_text_limit` | 수식의 행 문맥 과대계산 방지와 전체 텍스트 한도 유지 |
| `test_preview_keeps_every_candidate_value_formula_and_issue` | 참고·미확정 후보 수, 값·수식·매핑·원문·업무 오류 근거 보존과 표준 결과 불변 |
| `test_large_financial_cell_boundary_remains_bounded` | 메모리 OOXML의 80,000개 내용 셀을 허용하고 상한 1개 초과를 매핑 전에 거부 |
| `test_large_response_and_text_budget_keep_exact_boundaries` | 논리 텍스트 경계 허용·1바이트 초과 거부 및 줄바꿈 포함 UTF-8 JSON 경계 유지 |
| `test_cli_streams_only_after_complete_response_validation` | CLI의 전체 문자열 수집 방지, 조각 출력과 기존 JSON 값의 일치, 상한 실패 시 부분 JSON 비출력 |
| `test_business_diagnostics_locate_fields_and_preserve_candidates` | 계정·ID·분류의 반복 진단을 실제 입력 셀별로 묶으며 후보·금액·원본과 기존 필수 응답 필드 유지. 각 진단 셀의 원문을 submissionData.sourceCells에서 그대로 조회 가능 |
| `test_missing_business_field_keeps_its_observed_source` | 빈 계정과 오류 계정 모두 확인된 실제 입력 셀만 안내. 오류값을 빈값으로 단정하거나 보정하지 않음 |
| `test_business_location_requires_matching_field_and_row_evidence` | 코드표만 있는 근거, 필드 출처 부재, 다른 행 위치는 일반 안내로 남기고 잘못된 정정 위치를 만들지 않음 |
| `test_business_diagnostics_keep_other_rows_and_unknown_reasons` | 동일 오류만 묶고 다른 행은 별도 표시. 알 수 없는 추가 검증 사유의 일반 안내 유지 |
| `test_business_diagnostic_does_not_hide_missing_amount_cache` | 업무키 오류를 묶어도 별도 금액 캐시 누락은 해당 금액 셀에 남기고 0·빈값·미확정 수 보존 |

### 2026-11-16 사업부 원본 17개 메모리 재검증

사용자가 지정한 `Data/dummy` 최상위 사업부 xlsx 17개를 읽었으며 `18_전체_마스터.xlsx`와 하위 월별 자료는 이 검증에서 제외했습니다. 결과·변환본·캐시·로그는 만들지 않았고 원본 17개의 읽기 전후 SHA-256이 모두 같았습니다. 루트의 구형 `src/excel_parser`에는 service_bridge가 없으므로 같은 수정의 복사 대상을 만들지 않았습니다.

- 모든 파일이 브리지 한도를 통과해 `NEEDS_REVIEW`로 해석됐습니다. 파일마다 9시트, 후보 19,376개, mapped 3,392개, 미확정 재무 후보 1,725개입니다. 매출 35행·비용 24행의 실제 금액 상세 3,380개는 업무키 검증 valid입니다. 비용 양식의 나머지 345행에서 조직 `#N/A`·예산 코드 없음으로 남은 연간 수식 1,725개는 자동 승인하거나 삭제하지 않았습니다.
- 2026 PLAN은 파일마다 매출 420셀·비용 288셀, 전체 12,036셀입니다. 각 셀의 정규화 금액이 ZIP 원본 숫자 캐시 × 1,000(천원 → 원)과 같고 조직 코드/명이 일치합니다. 매출·비용 각각의 12개월 합계와 셀 수가 `manifest_baseline.json`의 plan 기대값과 17개 모두 같습니다. 수식을 재계산한 검증은 아닙니다.
- 원본은 1,299,084~1,304,714바이트입니다. 첫 파일은 물리 워크시트 200,957셀 중 내용 30,734셀, 전체 XML 454,960요소였습니다. 전체 후보를 유지한 내부 응답은 22,214,213~22,240,314바이트이며 기존 전체 문맥 응답 약 76.4MB에서 줄었습니다. 입력 16MiB·출력 64MiB·90초 제한 안에서 처리됩니다.
- Mac Python 직접 실행의 17개 개별 처리·직렬화·대조는 약 2.71~3.00초/파일입니다. 단독 파일 프로세스 최대 RSS 약 385MB와 17개를 한 프로세스에서 반복한 최대 RSS 약 670MB는 별도 측정입니다. 실제 Java는 파일별 별도 Python 프로세스를 사용하며 Docker/HTTP 검증은 백엔드 실행 문서에서 구분합니다.
- 기간 해석은 원본 헤더를 따릅니다. 첫 파일 `답안지_IFRS15後!AT6=추정`, `AT8=9월` 근거로 2026년 1~8월 actual, 9~12월 estimate입니다. manifest의 `close_month=9` 설명과 차이가 있지만 원본을 변경하거나 파서에서 9월을 실적으로 강제하지 않았습니다. 2026 PLAN 대조에는 영향이 없습니다.

관련 회귀 실행은 parser 디렉터리에서 `env -u EXCEL_PARSER_TEST_DATA PYTHONPATH=src:tests python3 -B -m unittest test_extraction test_mapping test_mapping_monthly test_business_validation test_service_bridge`이며 54개 중 52개 통과, 외부 자료 의존 2개 건너뜀입니다. 주석 빈 셀의 마지막 문맥 상한 보강 후에도 17개 원본의 자원 검사를 다시 통과했고 후보·금액·직렬화 계약은 변경하지 않았습니다.

## source_audit.py · 월별 원본의 독립 메모리 대조

정의: [benchmark/source_audit.py](../../src/excel_parser/benchmark/source_audit.py). `월별검증_2026/검증기준/manifest_01.json`부터 `manifest_09.json`까지는 평가 파일·상세 행·연월·단위의 정답 범위로만 사용합니다. 생산 `service_bridge.parse_bytes`에는 원본 XLSX 바이트와 파일명만 전달합니다. 평가기는 표준 추출·정규화·매핑 함수를 정답 생성에 호출하지 않고 ZIP·XML과 독립 Decimal 해석을 사용합니다. 반려·삭제·중복 행도 원본에 있으면 대조 분모에 남기므로 정제 마스터 정확도와 다른 지표입니다.

| 함수·자료형 | 호출 관계와 역할 | 입력·반환·예외·부작용 |
| --- | --- | --- |
| `NativeCell` | `read_native` → 원문 값·자료형·수식·수식 존재 보존 | 불변 메모리 자료형. 실제 빈 셀과 XML 부재를 구분 |
| `_column`, `_row` | 행·슬롯·조직 표의 독립 주소 처리 | 열 번호→문자, 셀 주소→행 번호. 잘못된 입력은 ValueError |
| `_decode`, `read_native` | workbook 관계표 → 모든 시트·공유 문자열·셀 XML 판독 | 숫자 렉심과 원본 수식 보존. ZIP/XML 오류 전파. 외부 관계 조회·수식 실행 없음 |
| `_amount`, `_present` | 원문 값의 독립 금액·입력 존재 판정 | 한국식 그룹·괄호 음수·지수 숫자를 Decimal로 해석. 오류·빈칸은 0으로 바꾸지 않음 |
| `_registry` | 원본의 사업부코드·사업부명 표 → 이름별 코드 집합 | 동명 조직 모호함 보존. manifest 조직명·파일명·ID를 근거로 보충하지 않음 |
| `expected_rows` | manifest 행 + 상세 입력 ID → 원본 행별 48개월 슬롯·비용 추가 추정 12개월 | 2025 실적, 2026 계획/마감 실적·추정, 2027 계획, 별도 2027 추정. 원본 반려·중복·0원 행 유지. 범위 오류는 ValueError/KeyError |
| `_mismatch`, `audit_prediction` | 원본 정답 → 실제 bridge 제출 행·월·근거 비교 | 원본 행·매출/비용 domain·금액·0·빈칸·자료형·기간·단위·배율·ID·조직·수식·출처와 누락/추가/중복 비교. 실패 응답도 원본 분모 유지. 제한된 예시만 메모리 보관 |
| `audit_file` | 원본 읽기 → 독립 OOXML → 실제 parse_bytes → 대조 → 원본 해시 재검사 | 파일 읽기만 수행. 입력 경로가 데이터셋 밖이면 ValueError. 예외·불일치 숨기지 않음 |
| `load_jobs`, `_numbers` | CLI 유형·월 → manifest의 사업조직 작업 목록 | 마스터 파일 제외, 중복 경로 거부, 1~9 범위 선택. manifest를 생산 파서에 전달하지 않음 |
| `_summary`, `main` | 기본 2개 프로세스 → 유형·월별 17파일 즉시 요약 → 전체 요약 | JSON 콘솔 출력만 수행. 불일치 0개면 종료 0, 있으면 1. `--limit`은 진단용 일부 실행. 파일·로그·캐시 생성 없음 |

저장된 원본/수식 캐시와 비교하며 Excel 재계산 결과를 주장하지 않습니다. `NEEDS_REVIEW`는 그 자체로 원본 불일치가 아니며 bridge 상태·업무 진단과 값 정확도를 따로 집계합니다. 실제 원본에 없는 양식 변화·새 단위까지 검증했다는 의미가 아닙니다. 실행은 저장소에서 `PYTHONPATH=parser/src python3 -B -m excel_parser.benchmark.source_audit --dataset <월별검증_2026 절대경로> --types 1-9 --months 1-9 --workers 2`입니다.

[test_source_audit.py](../../tests/test_source_audit.py)의 `fixture`, `prediction_fixture`는 독립 메모리 원본과 비교 결과 변형용 자료를 만듭니다. 일곱 검증은 숫자 문자열·0·빈칸·시나리오 경계·반려행 보존, FAILED 분모 유지, 합계가 상쇄되는 셀 교환, 원문 변경·슬롯 중복, 메모리 ZIP의 XML 빈 셀·수식 판독을 확인합니다. `test_domain_change_is_detected_with_identical_amounts_and_sources`는 정상 fixture에 명시된 domain 하나만 매출에서 비용으로 바꾸어 금액·출처가 같아도 `ROW_DOMAIN`을 검출하는지 확인합니다. 이 합성 결과는 실제 데이터의 정답으로 사용하지 않습니다.

### 연간 0만 남은 빈 매출 양식 행

`mapping._inactive_revenue_template_row(row_number, index, layout)`는 알려진 매출 헤더 아래에서 모든 연간 칸의 숫자 0 상수 또는 확인된 12개월 SUM 캐시 0과 각 12개월 소속을 확인하고, 조직·분류·설명·월 입력·다른 수식·주석 등 다른 내용이 전혀 없을 때만 비활성 양식으로 판정합니다. 공유 SUM은 기존 `_template_formula`로 같은 열의 기준 행·공유 범위·수식 상대 참조를 확인합니다. `map_workbook`은 행별 결과를 캐시하고 `_row_context`에서도 같은 판정을 사용합니다. 연간 0 원본 후보·수식·정규화 값은 `role.kind=inactive_template`, `ROW_INACTIVE_REVENUE_TEMPLATE` 근거로 그대로 보존하며 기존 제출 어댑터가 비활성 행만 제외합니다. 월의 실제 0이나 미확인 연간 수식·문자열·비영 값은 이 규칙으로 제외하지 않습니다.

[test_revenue_template_rows.py](../../tests/test_revenue_template_rows.py)의 `revenue_template_workbook`은 실제 상세와 빈 연간 0 행을 메모리로 합성합니다. 여섯 회귀 테스트는 후보·원본 보존, 월 0·조직·설명·미확인 열·주석의 활성 유지, 연간 비영·문자열·미확인 수식 검토 유지, 명시 SUM·공유 SUM 확인, 잘못된 공유 범위·다른 행·캐시 누락, 불완전 월 헤더의 보수적 처리 경계를 확인합니다.
