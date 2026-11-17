# 엑셀 데이터 파서

경영기획용 Excel을 읽기 전용으로 추출하고, 원본 출처가 연결된 표준 레코드로 변환합니다. 입력 데이터와 생성 결과는 별도 관리하며 이 저장소에는 코드·설정·테스트·파싱 문서만 포함합니다.

## 모듈별 처리 내용

| 모듈 | 역할 |
| --- | --- |
| `ooxml.py` | OOXML 관계, 공유 문자열, 셀 주소, 스타일과 메모 읽기 |
| `extraction.py` | 모든 시트의 원본 내용 추출과 빈 스타일 영역의 희소 구조 보존 |
| `normalization.py` | 저장값 우선의 Decimal 숫자·부호·단위·비율 정규화 |
| `mapping.py` | 확인된 헤더·코드표를 통한 조직·항목·대상 기간·역할 연결 |
| `business_validation.py` | 같은 원본의 상세 ID·코드표·집계 수식 계약에 따른 업무필드 검증 |
| `validation.py` | 독립 원본 대조, 출처 연결, 적용 가능한 원본·마스터 비교 |
| `pipeline.py` | 단일·복수 파일 처리와 명시적으로 요청한 결과 저장 |
| `paths.py`, `__main__.py` | 경로 설정과 CLI |

파이프라인은 `원본 추출 → 의미 매핑·숫자 정규화 → 검증 → 요청된 결과 저장` 순서로 동작합니다. 추출·매핑 모듈은 메모리 결과를 반환하며 저장은 별도 계층에서 처리합니다.

## 지원 범위

- `.xlsx`의 모든 시트를 읽습니다. 숨김 시트·행·열, 병합, 셀 메모, 수식·저장 캐시와 원본 스타일을 보존합니다.
- `.xlsm`은 같은 OOXML 읽기 경로를 제공하지만 매크로는 실행하지 않으며 실제 xlsm 자료로 검증하지 않았습니다. `.xlsb`·`.xls`는 미지원 진단을 반환합니다.
- 저장 숫자의 부호를 서식으로 바꾸지 않습니다. 문자열 숫자는 필드 의미와 등록된 규칙이 확인된 경우만 변환합니다.
- 금액은 내부 `Decimal`, JSON에서는 정밀 십진 문자열로 보존합니다. 원본에 없는 정밀도는 복원하지 않습니다.
- 조직·기간·단위·제출 정보가 불명확하면 미확정으로 남깁니다. 상세·소계·합계·비율·여러 제출본을 일괄 합산하지 않습니다.
- 수식 재계산, 범용 서식 렌더링, DB·웹 API·업로드·승인·공식 마스터 발행은 제공하지 않습니다.

## 실행

Python 3.11 이상과 표준 라이브러리만 사용합니다. 저장소 루트에서 모듈 폴더로 이동합니다.

```bash
cd parser
PYTHONPATH=src python3 -B -m excel_parser paths

# 별도 관리하는 단일 원본을 읽고 검증한다.
PYTHONPATH=src python3 -B -m excel_parser parse /path/to/input.xlsx

# 여러 파일 또는 디렉토리를 처리한다.
PYTHONPATH=src python3 -B -m excel_parser parse /path/to/first.xlsx /path/to/second.xlsx
PYTHONPATH=src python3 -B -m excel_parser parse /path/to/inputs --recursive
```

기본 실행은 결과 파일을 만들지 않습니다. 결과 저장을 요청한 작업에만 `--write-output`을 추가합니다. 저장 위치는 `config/paths.toml`의 `output` 하위 새 실행 폴더이며, 기존 결과를 덮어쓰지 않습니다. `Data/` 내부에는 쓰지 않습니다.

```bash
PYTHONPATH=src python3 -B -m excel_parser parse /path/to/inputs --recursive --write-output
```

`--metadata`로 파일별 메타데이터를 전달할 수 있습니다. `--submission-month`와 `--submission-version`은 확인된 제출 정보가 있을 때만 사용합니다. 제출 월은 값의 대상 월과 다릅니다.

## 검증 기준

| 검사 | 독립 기준 | 확인하는 내용 |
| --- | --- | --- |
| 원본 추출 | 생산 추출기와 별도로 읽은 OOXML | 시트·셀 목록, 원문 값, 수식·캐시, 구조 보존 |
| 출처 연결 | 원본 셀과 표준 레코드 참조 | 원문·파일·시트·주소 일치, 후보 누락과 중복 |
| 숫자·예외 | 직접 정한 합성 입력과 기대값 | 부호, 괄호, Unicode, 모호한 기호, 단위·% 이중 환산, 코드·날짜·빈 값 |
| 수식·구조 | 메모리 ZIP fixture | 캐시 누락·오류, 공유/외부 수식, 병합·숨김·메모·조건부 서식 보존 |
| 원본·마스터 | 별도 입력 원본의 합계와 마스터 저장값 | 지원하는 SUMIFS·차감 구조의 값 일치 |
| 파일 보호 | 전후 SHA-256과 쓰기 호출 감시 | 원본 변경, 자동 저장, 허용되지 않은 출력 경로 방지 |

### 데이터 없는 기본 테스트

```bash
PYTHONPATH=src python3 -B -m unittest discover -s tests -v
```

기본 테스트는 메모리 자료와 합성 기대값을 사용합니다. 별도 데이터가 필요한 통합 테스트는 데이터 경로를 지정하지 않으면 명시적으로 건너뜁니다. 테스트용 Excel 파일을 디스크에 만들지 않습니다.

### 별도 데이터로 통합 검증

```bash
EXCEL_PARSER_TEST_DATA=/path/to/inputs PYTHONPATH=src python3 -B -m unittest discover -s tests -v
```

통합 검증 자료는 저장소에 추가하지 않습니다. 입력 집합에 지원하는 마스터가 없으면 마스터 비교는 적용 불가로 구분하며, 그 사실을 집계 일치로 해석하지 않습니다. 형식 검사에는 선택적으로 `ruff check --no-cache src tests`를 사용할 수 있습니다.

### 현재 월별 자료의 원본 대조

저장소 루트에서 다음 명령으로 `월별검증_2026`의 조직 원본을 실제 서비스 파서에 전달하고, 별도로 읽은 원본 XML과 대조합니다. 결과는 콘솔에만 출력하며 원본·캐시·보고서 파일을 생성하지 않습니다.

```bash
PYTHONPATH=parser/src python3 -B -m excel_parser.benchmark.source_audit \
  --dataset /path/to/월별검증_2026 --types 1-9 --months 1-9 --workers 2
```

`--types 5,6`, `--months 1`, `--limit 1`로 재현 범위를 줄일 수 있습니다. 이 검사는 2025~2027년의 금액·기간·시나리오, 실제 0과 빈칸, 행·조직·상세 ID, 원문·수식·출처, 읽기 전후 SHA-256을 확인합니다. 평가용 manifest는 대상과 정답 범위를 정하는 데만 쓰며 생산 파서에는 전달하지 않습니다. 오류·중복 행도 원본 보존 검사에서 제외하지 않습니다. 정제 후 마스터 일치와 제출 승인 여부는 별도 검사입니다.

## 결과와 검증 한계

원본 셀·표준 레코드·워크북 구조·진단은 gzip JSONL, 실행 요약과 검증은 JSON으로 저장합니다. 필드와 타입은 [출력 계약](docs/development/output-contract.md)에 정의합니다.

`raw_extraction_complete`는 원본 추출·독립 대조와 원본 보존 상태입니다. `validation_passed`는 출처 연결과 적용 가능한 마스터 비교도 포함합니다. `mapped`·`confirmed`만으로 모든 업무 의미·환산값의 정답 검증이 끝났다고 해석하지 않습니다. 수식 캐시는 마지막 저장값이며 재계산하거나 최신성을 보증하지 않습니다.

Contingency 제외 영업이익은 원본에 명시되어 있으면 우선 연결합니다. 원본 항목이나 반영 방향이 불분명하면 임의 조정하지 않습니다.

## 파싱 명세

규칙 파서의 독립 원본·정답 대조는 `python3 -B parser/compare.py`로 실행합니다(저장소 루트 기준).
기본은 메모리·콘솔만 사용하며 정답을 파서 입력과 분리합니다.
[원본 대조의 명령·평가 계약·실험 조건](docs/development/parser-comparison.md)을 참고하세요.

월별 검증은 같은 진입점의 `monthly <자료 경로> --types <유형 목록>`으로 실행합니다.
조직 원본만 규칙 파서에 전달하고 마스터 상세는 독립 평가기에서 업무키로 대조합니다.
결과 저장은 사용자가 요청한 경우에만 `--write-output`과 새 `--output-dir`을 지정해 수행합니다.
생성된 `inventory.json`을 이후 기술 마스터 재구성에 사용할 수 있습니다.

조직 원본으로 월별 마스터를 재구성할 때는 `master generate --inventory <최초 inventory.json>
--output-dir <새 실행 폴더> --write-output`을 사용합니다. 이 단계는 기존 마스터를
읽지 않고, 원본 상세·수정번호·출처와 조직별·전사 합계를 저장합니다. 이후
`master evaluate --output-dir <생성 완료 폴더> --write-output`으로 기존 마스터와
상세·합계를 대조합니다. 기준월과 금액의 대상 연월은 분리하고, 실제 0원·빈칸·
캐시 없는 수식을 구분합니다. 필수 상세나 조직이 빠지면 부분 생성으로 표시합니다.

생성 JSON의 Excel 내보내기는 `node parser/src/excel_parser/export_masters.mjs
<생성 완료 폴더> --workers 2`로 실행합니다. 각 월의 Excel은 전사 합계, 조직별 합계,
매출 상세, 비용 상세 네 시트를 제공하며, 저장 후 값·타입·빈칸을 다시 검사합니다.
실행 조건과 수정본 선택 정책은 [비교 개발 문서](docs/development/parser-comparison.md)에 있습니다.

- [함수별 명세와 호출 관계](docs/development/function-reference.md)
- [의미 매핑 규칙](docs/development/mapping-rules.md)
- [숫자·부호·단위 정규화](docs/development/normalization-rules.md)
- [출력 구조와 타입](docs/development/output-contract.md)

월별 마스터의 상세는 숫자 금액 확정과 업무키 유효성을 분리합니다. 같은 원본 등록 계약에 없는 계정·관리분류와 무효 문자열 ID는 원문·금액·출처를 남기고 합계에서 제외합니다. 원본에서 확인한 행 조직은 제출 소유자와 달라도 유지합니다. 원본 빈칸·실제 0원·수식 캐시 누락을 보존하며 기준 마스터의 생략 패턴을 파서 규칙으로 사용하지 않습니다. 저장된 이전·현재 실행의 셀별 회귀 비교와 합계 차액 추적은 [비교 검증 문서](docs/development/parser-comparison.md)에 설명합니다.

`월별검증_2026`의 확인된 생성 계약을 적용하는 v4는 생성 명령에
`--publication-policy source-contract-v4`를 명시합니다. 이 정책은 무효 업무키,
제출 범위 밖 비용행, 당해 1월 실적 빈칸 행, 계약상 비활성 매출행을 최종 상세에서
제외하고 원문 전체를 감사에 보존합니다. 개별 0원을 일괄 생략하지 않으며,
정책을 생략한 기존 조립 동작은 유지합니다. 원본 품질 상태와 발행 상세의 상태는
별도로 기록합니다. 이 더미 생성 계약을 일반 업무 파일에 자동 적용하지 않습니다.

v4 최종 검증은 저장된 Excel을 직접 읽습니다(저장소 루트 기준).

```bash
PYTHONPATH=parser/src python3 -B -m excel_parser.benchmark.excel_evaluation --run <생성 및 Excel 저장 완료 폴더> --output <새 평가 폴더> --write-output
```

`strict_excel_detail_and_total_match`는 기존 엄격 상세 채점 외에도 실제 합계표의
금액·월별 건수·조직 수·Excel 구조가 맞아야 참입니다. JSON 값을 채점 입력으로
보충하지 않습니다. v3 대비 보고는 `benchmark.publication_report`로 저장 평가를
비교하며 기본 경로 계약은 `<생성 폴더>/evaluation/validation.json`입니다.
