# pipeline — 07 보고서 자료 배치 파이프라인

엑셀 제출본 → 파싱(`parser/` 룰 파서) → Gap → 소명 연결 → master → 07 자료 → 사실 목록·핵심 메시지 문장까지를 한 번에 만든다.
업무 규칙은 `docs/domain-rules.md`, `docs/development/erd.md`, `docs/development/db-mapping.md` 만 따른다.

실행(`ai/` 에서, 입력 경로는 설정으로 지정: `ai/.env` 또는 환경변수 INPUT_DIR, EXPLANATIONS_PATH, APPROVALS_PATH, SCENARIO_DIR, SCENARIO_CSV_PATH):

```bash
python3 -B -m app.pipeline.run_pipeline            # AI 호출 없이(같은 사실 목록의 캐시가 있으면 그 결과 사용)
python3 -B -m app.pipeline.run_pipeline --ai       # 상태(잠정/확정)당 OpenAI 호출(재시도 없음, 캐시·일일 상한 적용)
python3 -B -m app.pipeline.run_pipeline --ai --force-ai   # 캐시를 무시하고 다시 호출(일일 상한은 적용)
python3 -B -m app.pipeline.run_pipeline --hide-reference-explanations   # Gap 아님 참고 소명 숨김
```

- 설정이 없으면 무엇을 설정해야 하는지 알려주는 오류로 멈춘다.
- 결과는 `OUTPUT_DIR`(기본 `ai/output/`): parse_report, gaps, gaps_with_explanations, master_provisional/final, report_materials_provisional/final, ai_input_provisional/final, key_messages, run_manifest, summary_report.md, ai_call_log.json
- 07 자료(`report_materials_*.json`)는 `master_*.json` 만 읽는다. 억원 소수 1자리 ROUND_HALF_UP 한 곳(`common.eok`).
- AI 호출은 외부 접속이 되는 환경에서 **사용자가 터미널에서 직접** 실행한다. 핵심 메시지 검증·캐시·상한·토큰 로그는 `app/services/key_messages.py` 를 공유한다.
- 파서(`parser/`)는 import 해서 읽기만 한다. 파싱 캐시는 시스템 임시 폴더에 둔다.
