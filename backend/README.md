# backend

Spring Boot 3.4.1 · Java 21 · JDBC/Flyway · PostgreSQL 기반 제출 접수 백엔드다.
V1~V11 마이그레이션과 서비스 API 92개 메서드·경로를 구현한다. 제출·파싱·검증·소명·승인·공식 마스터에 PRD ver1의 설정·두 소명 정책·보고 작성을 연결했다. 운영 실행 검증 범위는 아래 문서를 따른다.

- [로컬 실행 환경](../docs/development/local-environment.md)

파싱 직후 초안은 validationState=NOT_RUN, canSubmit=false다. 담당자의 validation 명령은 원본 조직·연간 월·보고 분류·정확한 금액·합계 근거를 검증하고, 준비된 초안만 submit으로 PENDING·제출 회차를 부여한다. 기획자는 선택 적재행·보고 집계·진단·정확한 원본 셀을 조회한다. 파싱 후보는 amountUnit=KRW일 때만 원 단위이며 미확정 단위의 숫자는 후보로 보존한다.
경영기획팀의 연간 제출 승인·반려와 검토자·시각·반려 사유 조회를 지원한다. 수정 소명·월간 Gap·연간 확정·공식 마스터 발행, 구조화 반려, 임의 비교 요청을 지원한다. 보고 자료는 미리보기 후 명시 적용으로 새 버전을 저장한다.

## 실행

Docker로 바로 체험하려면 저장소 루트에서 `bash scripts/dev-up.sh`를 실행한다.
[로컬 실행 환경](../docs/development/local-environment.md)은 PostgreSQL·Python·프런트와 예시 계정을 함께 준비한다.

JDK 21, Python 3.11 이상, 별도로 준비한 PostgreSQL이 필요하다.
기본 서버 주소는 127.0.0.1:8080이며 기본 프로필은 health 외 모든 접근을 거부한다.
개발 인증은 local 프로필에서 환경변수 Bearer 토큰과 DB에 등록한 사용자만 사용한다.
실제 로그인 제공자·운영 인증은 아직 연결하지 않았다.

| 환경변수 | 의미 |
| --- | --- |
| DB_URL / DB_USERNAME / DB_PASSWORD | PostgreSQL 접속 정보 |
| SPRING_PROFILES_ACTIVE | 개발 인증을 쓸 때만 local |
| LOCAL_PLANNER_EMAIL / LOCAL_PLANNER_TOKEN | 등록된 PLANNER 이메일·32자 이상 토큰 |
| LOCAL_ORGANIZATION_EMAIL / LOCAL_ORGANIZATION_TOKEN | 등록된 ORG_MANAGER 이메일·별개의 개발 토큰 |
| PARSER_PYTHON | 실행 파일, 기본 python3 |
| PARSER_SOURCE_DIRECTORY | Python 소스, backend 기준 기본 ../parser/src |

아래 명령은 빌드·캐시·보고서를 생성한다. 작업 규칙에 따라 허락된 범위에서 실행한다.
bootRun은 지정 PostgreSQL에 Flyway 마이그레이션을 적용하므로 대상 DB를 확인한다.

```bash
cd backend
mkdir -p build/tmp
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/build/tmp"
./gradlew --no-daemon --project-cache-dir .gradle-user-home/project-cache test
# DB·인증 환경변수와 사용자 등록을 준비한 뒤 별도 실행
./gradlew --no-daemon bootRun
```

테스트는 H2 메모리 DB·합성 XLSX·실제 Python 프로세스를 사용한다.
기본 테스트는 PostgreSQL·Data/를 사용하지 않는다. 실제 원본을 읽는 별도 opt-in 검사는 기본 실행에서 제외한다. 임시파일은 허용된 build/tmp로 제한한다.
상태 확인: `GET http://localhost:8080/actuator/health`

## 구조

```
src/main/java/io/skala/backend/   # 애플리케이션 코드
src/main/resources/application.yml
src/test/java/io/skala/backend/   # 테스트
```

로컬 전용 설정(DB 비밀번호 등)은 `application-local.yml`에 두고 커밋하지 않습니다.

요구사항은 [PRD](../docs/README.md)를 기준으로 한다.
