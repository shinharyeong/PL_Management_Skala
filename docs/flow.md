# 업무 흐름도

[도메인 규칙](domain-rules.md)과 [논리 ERD v0.2](development/erd.md)의 업무 흐름입니다. 파서의 기술 마스터 조립과 서비스의 승인 마스터 발행을 구분하며, 이 흐름의 백엔드 구현 완료를 뜻하지 않습니다.

**색 구분**

| 색 | 주체 |
| --- | --- |
| 파랑 | 경영기획 담당자 |
| 노랑 | 조직 담당자 |
| 회색 | 시스템 (자동 처리) |

## 전체 단계

```mermaid
flowchart LR
    S1["1. 연간 계획 수립"] --> S2["2. 월 실적 제출"]
    S2 --> S3["3. 소명 검토·승인"]
    S3 --> S4["4. master·대시보드·보고"]
    S4 -->|다음 달| S2
```

## 1. 연간 계획 수립

```mermaid
flowchart TD
    A(["시작"])
    B["연간 계획 제출 요청<br/>대상 연도·전 조직 집합 고정"]
    C["조직 담당자에게 알림"]
    D["연간 계획 전체 파일 업로드<br/>예) D100_2026-PLAN.xlsx"]
    FN{"파일명·조직·열린 요청 확인?"}
    FN1["이름·요청 오류<br/>파싱하지 않음"]
    DR["DRAFT 생성·회차 없음"]
    E{"파싱·업무키·전체 범위<br/>검증 통과?"}
    E1["오류 위치 표시<br/>(제출되지 않음)"]
    CH{"확정 전 재제출의<br/>원천 금액 변경?"}
    RE["변경 보고 항목별 소명 작성<br/>최초 계획은 소명 대상 없음"]
    F["필수 검증·소명 확인 후 최종 제출<br/>회차 부여·검토 대기"]
    G{"계획이 올바른가?"}
    H["재제출 요청 + 사유"]
    I["재제출 요청 알림"]
    J["연간 계획 수정"]
    R{"필수 소명 마지막 회차<br/>수용 완료? 대상 없으면 충족"}
    S["소명 보완 재요청<br/>새 소명 회차·제출 회차 유지"]
    T["파일 재업로드 없이 소명 보완"]
    K["조직 계획 승인"]
    L{"모든 조직<br/>승인 완료?"}
    M["남은 조직 대기"]
    N["전사 계획 합산"]
    O["Contingency 입력<br/>(전사 기준, 1회)"]
    P["조직별 최종 승인 계획본과<br/>Contingency 원자적 확정·잠금"]
    Q(["월 실적 단계로"])

    A --> B --> C --> D --> FN
    FN -->|아니오| FN1 --> D
    FN -->|예| DR --> E
    E -->|아니오| E1 --> D
    E -->|예| CH
    CH -->|예| RE --> F
    CH -->|아니오| F
    F --> G
    G -->|아니오| H --> I --> J --> D
    G -->|예| R
    R -->|아니오| S --> T --> R
    R -->|예| K --> L
    L -->|아니오| M
    L -->|예| N --> O --> P --> Q

    classDef planner fill:#E3EDFD,stroke:#3B6FD8,color:#1A2B4C
    classDef org fill:#FFF3D1,stroke:#C99400,color:#3D2E00
    classDef system fill:#EFEFEF,stroke:#8A8A8A,color:#222
    classDef terminal fill:#D7F4DD,stroke:#3E9E55,color:#173D20
    class B,G,H,K,O,R,S planner
    class D,J,RE,T org
    class C,FN,FN1,DR,E,E1,CH,F,I,L,M,N,P system
    class A,Q terminal
```

## 2. 월 실적 제출

```mermaid
flowchart TD
    A(["월 실적 시즌"])
    B["월 실적 제출 요청<br/>대상 월·전 조직 집합 고정"]
    C["조직 담당자에게 알림"]
    D{"업로드 가능?<br/>열린 요청 있음<br/>그리고 검토 대기 제출본 없음"}
    D1["업로드 버튼 비활성<br/>(사유 표시)"]
    E["전체 스냅샷 업로드<br/>해당 월 실적 + 다음 달~12월 추정<br/>과거 실적 포함 시 해당 월도 전체 범위"]
    FN{"파일 이름 규칙?<br/>조직코드 · 대상 월 일치"}
    FN1["이름 오류 표시<br/>(파싱하지 않음)"]
    DR["DRAFT 생성·회차 없음"]
    F{"파싱·업무키·금액·분류<br/>전체 범위 검증 통과?"}
    F1["오류 위치 표시<br/>(제출되지 않음)"]
    GP["원천 금액 수정 감지 + OP 손익 Gap<br/>비교본·계획본·기준 ID 선택<br/>비교 범위는 도메인 규칙 표 적용"]
    X{"원천 금액 수정<br/>또는 OP 기준 초과?"}
    Y["검토 대상 표시<br/>원천 금액 구성까지 같은 수용 소명만 이어받기"]
    Z["보고 항목·연월·시나리오별 필수 소명<br/>수정·계획 대비·직전 대비 태그"]
    V{"최종 제출 시 비교본·계획본·기준 ID가<br/>계산 시점과 같은 최신 유효 값인가?"}
    K["전체 검증·필수 소명 재확인 후 제출 완료<br/>회차 부여 D100_2026-02-NN.xlsx<br/>상태: 검토 대기"]
    L["경영기획 담당자에게 알림<br/>(소명 포함)"]
    M(["소명 검토 단계로"])

    A --> B --> C --> D
    D -->|아니오| D1
    D -->|예| E --> FN
    FN -->|아니오| FN1 --> E
    FN -->|예| DR --> F
    F -->|아니오| F1 --> E
    F -->|예| GP --> X
    X -->|예| Y --> Z --> V
    X -->|아니오| V
    V -->|아니오| GP
    V -->|예| K
    K --> L --> M

    classDef planner fill:#E3EDFD,stroke:#3B6FD8,color:#1A2B4C
    classDef org fill:#FFF3D1,stroke:#C99400,color:#3D2E00
    classDef system fill:#EFEFEF,stroke:#8A8A8A,color:#222
    classDef terminal fill:#D7F4DD,stroke:#3E9E55,color:#173D20
    class B planner
    class E,Z org
    class C,D,D1,FN,FN1,DR,F,F1,GP,X,Y,V,K,L system
    class A,M terminal
```

## 3. 소명 검토·승인

```mermaid
flowchart TD
    A(["검토 대기 제출본"])
    B["수정·OP Gap 필수 소명 목록<br/>대상 없으면 승인 판단으로"]
    G{"소명이 적절한가?<br/>(칸별)"}
    H["수용"]
    I["현재 답변 검토 후 새 소명 회차 재요청<br/>사유 첨부·제출 회차 유지"]
    J["소명 재요청 알림"]
    K["그 소명만 다시 작성<br/>(파일 재업로드 없음)"]
    N{"모든 필수 소명 마지막 회차<br/>수용 완료? 대상 없으면 충족"}
    O{"제출본 승인?"}
    P["승인·아카이브<br/>조직별 현황에 버전·소명 기록"]
    R["재제출 요청"]
    R1["상태: 재제출 요청<br/>그 요청에 대한 재제출만 가능"]
    Q(["master 단계로"])

    A --> B
    B -->|제출본 자체 오류| R
    B -->|필수 항목 있음| G
    B -->|필수 항목 없음| O
    G -->|예| H --> N
    G -->|아니오| I --> J --> K --> G
    N -->|아니오| G
    N -->|예| O
    O -->|예| P --> Q
    O -->|아니오| R --> R1

    classDef planner fill:#E3EDFD,stroke:#3B6FD8,color:#1A2B4C
    classDef org fill:#FFF3D1,stroke:#C99400,color:#3D2E00
    classDef system fill:#EFEFEF,stroke:#8A8A8A,color:#222
    classDef terminal fill:#D7F4DD,stroke:#3E9E55,color:#173D20
    class B,G,H,I,O,R planner
    class K org
    class J,N,P,R1 system
    class A,Q terminal
```

### Gap 기준 설정 (경영기획 담당자, 수시)

```mermaid
flowchart LR
    T1["기준 선택<br/>실적 칸 / 추정 칸"]
    T2["슬라이더 조정<br/>% · 금액, 실시간 미리보기"]
    T3{"적용?"}
    T4["새 기준 이력 저장<br/>이후 최종 제출부터 적용"]

    T1 --> T2 --> T3
    T3 -->|아니오| T2
    T3 -->|예| T4

    classDef planner fill:#E3EDFD,stroke:#3B6FD8,color:#1A2B4C
    classDef org fill:#FFF3D1,stroke:#C99400,color:#3D2E00
    classDef system fill:#EFEFEF,stroke:#8A8A8A,color:#222
    classDef terminal fill:#D7F4DD,stroke:#3E9E55,color:#173D20
    class T1,T2,T3 planner
    class T4 system
```

## 4. master·대시보드·보고

```mermaid
flowchart TD
    A(["조직 제출본 승인"])
    B{"해당 월 고정 전 조직<br/>유효 승인본 준비 완료?"}
    B1["대기<br/>홈에 master 미생성 (승인 N/M 조직)<br/>미승인 조직 목록 표시"]
    C["master 생성<br/>master-YYYY-MM-01<br/>조직 승인본 합산"]
    D["홈: 전사 손익 현황 갱신<br/>합산 영업이익 / Contingency / 보고 영업이익"]
    E["연·월 필터로 이전 master 조회<br/>소명 요청·회신 현황"]
    F["보고서 화면<br/>확정 월별 지표·소명 메모<br/>사용한 master ID·버전 기록"]
    G["표·차트 내보내기"]
    H(["끝: 각자 장표에 활용"])
    X{"승인본의 대상 ACTUAL 월에<br/>기존 master가 있는가?"}
    CH{"해당 조직의 금액·원천 구성 변경?<br/>상쇄 변경도 포함"}
    KEEP["기존 master 유지"]
    Y["기존 source 전체 집합 복사<br/>새 승인 조직만 참조 교체·새 버전<br/>다른 조직 승인본과 이전 버전 유지"]

    A --> X
    X -->|예| CH
    CH -->|예| Y --> D
    CH -->|아니오| KEEP --> D
    X -->|아니오| B
    B -->|아니오| B1
    B -->|예| C --> D --> E
    D --> F --> G --> H

    classDef planner fill:#E3EDFD,stroke:#3B6FD8,color:#1A2B4C
    classDef system fill:#EFEFEF,stroke:#8A8A8A,color:#222
    classDef terminal fill:#D7F4DD,stroke:#3E9E55,color:#173D20
    class E,F,G planner
    class B,B1,C,D,X,CH,KEEP,Y system
    class A,H terminal
```

승인본에 포함된 ACTUAL 월마다 위 분기를 적용합니다. 과거 월도 해당 조직·월의 전체 스냅샷이 필요합니다. 승인과 영향 월 발행은 원자적으로 처리하며, 동시 승인의 중복·누락 발행을 방지합니다. 홈은 ACTUAL만 사용합니다.

## 상태

### 제출본

```mermaid
stateDiagram-v2
    state "작성 중 DRAFT" as draft
    state "검토 대기" as pending
    state "재제출 요청" as rejected
    state "승인" as approved

    [*] --> draft: 전체 파일 업로드
    draft --> pending: 전체 검증·필수 소명·기준 재확인·회차 부여
    pending --> approved: 모든 필수 소명 수용 후 승인
    pending --> rejected: 반려
    rejected --> [*]: 새 전체 파일로 별도 DRAFT 생성 가능
    approved --> [*]: 아카이브
```

### 검토 항목 — 금액 수정·손익 Gap 소명

```mermaid
stateDiagram-v2
    state "소명 필요" as required
    state "소명 작성됨" as answered
    state "재요청" as rerequested
    state "수용" as accepted

    [*] --> required: 원천 금액 수정 또는 OP 기준 초과
    [*] --> accepted: 원천 금액 구성이 같고 이전 소명 수용
    required --> answered: 조직이 소명 작성 후 제출
    answered --> accepted: 경영기획 수용
    answered --> rerequested: 경영기획 재요청
    rerequested --> answered: 조직이 다시 작성
    accepted --> [*]
```
