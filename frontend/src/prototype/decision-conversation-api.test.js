import test from 'node:test'
import assert from 'node:assert/strict'
import { analysisBrief, answerActions, answerCitations, askAssistant, assistantCoverageLabel, assistantExecutionSummary, assistantIncompleteReason, assistantPayload, assistantStatusLabel, canStartGoalPreview, citationValue, conversationHistory, conversationKey, goalPreviewQuestion, observationCell, observationExplanations, observationForExecution, observationTables, reviewDraftSource, successfulObservations, validateAssistantResponse } from './decision-conversation-api.js'

/** 같은 UNAVAILABLE라도 실행 한도와 응답 중단을 구분하고 원인 안내·실제 관찰을 보존한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 응답만 검사한다.
 */
test('failure labels distinguish execution limits without replacing the server cause or successful evidence', function failureStatusLabels() {
  const payload = assistantPayload('101', null, '계획 차이 확인')
  const cases = [
    { mode: 'UNAVAILABLE', status: 'LIMIT_REACHED', answer: '모델의 일일 호출 한도에 도달했습니다.', label: '답변 처리 한도 도달' },
    { mode: 'MODEL', status: 'LIMIT_REACHED', answer: '실행 시간이 소진되었습니다.', label: '답변 처리 한도 도달' },
    { mode: 'UNAVAILABLE', status: 'FAILED', answer: '모델 응답 대기 시간이 초과되었습니다.', label: '답변 생성 중단' },
    { mode: 'UNAVAILABLE', status: 'FAILED', answer: '모델이 설정되지 않았습니다.', label: '답변 생성 중단' },
    { mode: 'MODEL', status: 'FAILED', answer: '업무 자료 조회를 완료하지 못했습니다.', label: '조회·답변 처리 실패' },
  ]
  for (const item of cases) {
    const response = { ...groundedResponse(), mode: item.mode, status: item.status, answer: item.answer, brief: null }
    const checked = validateAssistantResponse(response, payload)
    assert.equal(assistantStatusLabel(checked), item.label)
    assert.equal(checked.answer, item.answer)
    assert.equal(checked.observations, response.observations)
    assert.equal(successfulObservations(checked).length, 1)
  }
  assert.equal(assistantStatusLabel({ mode: 'MODEL', status: 'COMPLETED', brief: {} }), '요청 항목 확인 완료')
  assert.equal(assistantStatusLabel({ mode: 'MODEL', status: 'COMPLETED' }), '근거 조회 완료')
  assert.equal(assistantStatusLabel({ mode: 'MODEL', status: 'NEEDS_INPUT' }), '추가 정보 필요')
})

/** 조회 성공과 부분 분석을 보존하되 원인을 추측하거나 전체 완료로 표시하지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 응답만 메모리에서 검사한다.
 */
test('partial analysis keeps its server reason and distinguishes lookup success from answer completion', function partialAnalysisStatus() {
  const response = groundedResponse()
  response.status = 'LIMIT_REACHED'
  response.answer = '시스템 안내: 최종 요약의 표현 검증을 마치지 못했습니다.'
  response.brief.requirements = [response.brief.requirements[0]]
  const brief = analysisBrief(response)
  assert.equal(brief.answeredCount, 1)
  assert.equal(assistantCoverageLabel(response, brief), '부분 검증 1 / 1개 · 전체 답변 미완료')
  assert.equal(assistantExecutionSummary(response), '조회 성공 1건 · 답변 미완료')
  assert.equal(assistantIncompleteReason(response), response.answer)
  assert.equal(assistantStatusLabel(response), '답변 처리 한도 도달')
  const unavailable = { ...response, mode: 'UNAVAILABLE', status: 'FAILED', observations: [], brief: null, answer: '모델 서비스를 사용할 수 없습니다.' }
  assert.equal(assistantExecutionSummary(unavailable), '조회 성공 0건 · 답변 미완료')
  assert.equal(assistantIncompleteReason(unavailable), unavailable.answer)
  assert.equal(assistantIncompleteReason({ ...response, status: 'COMPLETED' }), null)
  assert.equal(assistantExecutionSummary({ ...response, status: 'COMPLETED' }), '조회 성공 1건 · 답변 완료')
  assert.equal(assistantExecutionSummary({ ...response, status: 'NEEDS_INPUT' }), '조회 성공 1건 · 추가 확인 필요')
})

/** 실제 근거 연결과 미해결 요구 항목을 가진 합성 응답을 만든다.
 * Args: 없음. Returns: 독립된 메모리 응답. Raises: 없음. Side Effects: 외부 데이터·파일 접근 없음.
 */
function groundedResponse() {
  const context = { masterId: '101', year: 2026, baseMonth: 10, analysisId: null, revision: null }
  const citation = { observationId: 'obs-1', path: '/data/planDifferenceWon', quote: '-9007199254740993.123456' }
  return { mode: 'MODEL', status: 'NEEDS_INPUT', context, observations: [{ id: 'obs-1', tool: 'organization_pnl', label: '조직 손익', status: 'SUCCESS', basis: { ...context }, data: { planDifferenceWon: citation.quote, name: '합성 사업부', text: '고객 납품 일정 변경이 소명에 기록되어 있습니다.' } }], citations: [citation],
    brief: { summary: '계획 차이와 소명을 확인했으나 개선 가능액은 추가 자료가 필요합니다.',
      findings: [{ id: 'f1', kind: 'FACT', text: '해당 사업부의 전망은 계획에 미달합니다.', citations: [citation], uncertainty: null },
        { id: 'f2', kind: 'HYPOTHESIS', text: '납품 일정 회복 가능성을 검토할 수 있습니다.', citations: [{ observationId: 'obs-1', path: '/data/text', quote: '고객 납품 일정 변경' }], uncertainty: '회복 시점과 비용이 확인되지 않았습니다.' }],
      requirements: [{ id: 'r1', requestQuote: '계획 차이', question: '조직의 계획 차이 확인', status: 'ANSWERED', findingIds: ['f1'], missingInformation: null, followUpQuestion: null },
        { id: 'r2', requestQuote: '개선 가능성', question: '개선 가능성 검토', status: 'NEEDS_INPUT', findingIds: ['f2'], missingInformation: '조직이 확인한 납품 일정과 추가 비용이 필요합니다.', followUpQuestion: '조직에서 확인한 납품 일정과 추가 비용을 알려줄 수 있나요?' }] } }
}

/** 실제 근거의 정밀 표시와 가설의 불확실성·미해결 질문이 함께 보존되는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 합성 응답만 사용.
 */
test('grounded brief keeps exact evidence, hypotheses and unresolved requirements distinct', function groundedBrief() {
  const response = groundedResponse(), before = JSON.stringify(response), brief = analysisBrief(response, '계획 차이와 개선 가능성 확인')
  assert.equal(brief.findings[0].citations[0].displayQuote, '-90,071,992.54740993123456억 원')
  assert.equal(brief.findings[1].kindLabel, '검토 가설')
  assert.equal(brief.findings[1].uncertainty, '회복 시점과 비용이 확인되지 않았습니다.')
  assert.equal(brief.answeredCount, 1)
  assert.equal(brief.unresolved[0].id, 'r2')
  assert.match(brief.unresolved[0].followUpQuestion, /추가 비용/)
  assert.equal(JSON.stringify(response), before)
})

/** 원문 위조·다른 범위·미해결 완료·없는 분석 연결은 부분 표시로 덮지 않고 거부한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 합성 응답만 수정.
 */
test('brief rejects ungrounded or stale citations and falsely complete requirements', function rejectedBrief() {
  let response = groundedResponse()
  response.brief.findings[0].citations[0].quote = '100000000'
  assert.equal(analysisBrief(response), null)
  response = groundedResponse(); response.observations[0].basis.masterId = 'other'
  assert.equal(analysisBrief(response), null)
  response = groundedResponse(); response.status = 'COMPLETED'
  assert.equal(analysisBrief(response), null)
  response = groundedResponse(); response.brief.requirements[0].findingIds = ['nonexistent']
  assert.equal(analysisBrief(response), null)
  response = groundedResponse(); response.brief.findings[1].uncertainty = null
  assert.equal(analysisBrief(response), null)
  assert.equal(analysisBrief(groundedResponse(), '전혀 다른 요청'), null)
})

/** 새 분석이 없던 이전 응답은 호환하고 설명 오류에도 실제 관찰을 보존한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('response validation accepts legacy payloads and preserves observations when the brief is invalid', function briefBoundary() {
  const payload = assistantPayload('101', null, '계획 차이와 개선 가능성 확인'), response = groundedResponse()
  assert.equal(validateAssistantResponse(response, payload), response)
  assert.equal(validateAssistantResponse({ ...response, brief: null }, payload).brief, null)
  response.answer = '현재 질문과 연결되지 않은 설명'
  const before = JSON.stringify(response), failed = validateAssistantResponse(response, { ...payload, message: '새로운 질문' })
  assert.equal(failed.brief, null)
  assert.equal(failed.status, 'FAILED')
  assert.equal(failed.answer, '조회 자료는 확보했지만 분석 설명을 확인하지 못했습니다.')
  assert.equal(failed.observations, response.observations)
  assert.equal(successfulObservations(failed).length, 1)
  assert.match(failed.limitations.at(-1), /근거 연결을 확인하지 못해/)
  assert.ok(!JSON.stringify(failed).includes('현재 질문과 연결되지 않은 설명'))
  assert.equal(JSON.stringify(response), before)
  /** 다른 기준 응답은 설명 실패와 달리 전체를 거부한다. Args: 없음. Returns: 없음. Raises: 계약 오류. Side Effects: 없음. */
  function wrongScope() { validateAssistantResponse({ ...response, context: { ...response.context, masterId: '102' } }, payload) }
  assert.throws(wrongScope, /기준이 달라/)
  const noEvidence = validateAssistantResponse({ ...response, observations: [] }, payload)
  assert.ok(!noEvidence.answer.includes('자료는 확보'))
})

/** 상세 조회 scalar의 원본 금액 의미를 유지하고 원문 필드를 임의 계산하지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('detail citations inherit exact source money semantics', function detailedMoney() {
  const original = { id: 'obs-1', tool: 'organization_pnl', status: 'SUCCESS', data: { opDiff: '-9007199254740993.123456' } }
  const detail = { id: 'obs-2', tool: 'observation_detail', status: 'SUCCESS', label: '조회 근거 상세', data: { sourceObservationId: 'obs-1', path: '/data/opDiff', value: original.data.opDiff } }
  const citations = answerCitations({ observations: [original, detail], citations: [{ observationId: 'obs-2', path: '/data/value', quote: original.data.opDiff }] })
  assert.equal(citations[0].field, '영업이익 차이')
  assert.equal(citations[0].displayQuote, '-90,071,992.54740993123456억 원')
  assert.equal(citations[0].observationId, 'obs-2')
})

/** 후속 질문이 가설 번호를 참조할 수 있고 과거 원문 금액은 다시 근거로 보내지 않는지 확인한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 자료만 사용.
 */
test('follow up context retains numbered findings and unresolved intent without old observations', function briefHistory() {
  const response = groundedResponse()
  response.brief.findings.push({ ...response.brief.findings[1], id: 'f3', text: '외주 계약 조정 가능성도 별도 확인이 필요합니다.' })
  const history = conversationHistory([{ question: '계획 차이와 개선 가능성을 확인해 줘', response }]), context = history[1].content
  assert.match(context, /검토 가설 2: 외주 계약 조정/)
  assert.match(context, /요구 2 \(추가 확인\)/)
  assert.match(context, /현재 사실의 근거가 아닙니다/)
  assert.ok(context.length <= 2000)
  assert.ok(!context.includes('-9007199254740993.123456') && !context.includes('/data/') && !context.includes('고객 납품 일정 변경이 소명'))
})

/** 관찰 ID가 반복되어도 원문이 다른 실행으로 잘못 연결되지 않는지 확인한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 메모리 자료만 사용.
 */
test('report source resolves the exact execution instead of a reused observation id', function exactReportSource() {
  const context = { masterId: '101', year: 2026, baseMonth: 10, analysisId: null, revision: null }
  const first = { id: 'obs-1', status: 'SUCCESS', tool: 'goal_preview', basis: { ...context }, data: { targetProfitWon: '100' } }
  const second = { ...first, data: { targetProfitWon: '200' } }
  const turns = [{ id: 'first-execution', response: { context, observations: [first] } }, { id: 'second-execution', response: { context, observations: [second] } }]
  assert.equal(observationForExecution(turns, 'first-execution', 'obs-1'), first)
  assert.equal(observationForExecution(turns, 'second-execution', 'obs-1'), second)
  assert.equal(observationForExecution(turns, '', 'obs-1'), null)
  assert.equal(observationForExecution(turns, 'removed-execution', 'obs-1'), null)
  second.status = 'ERROR'
  assert.equal(observationForExecution(turns, 'second-execution', 'obs-1'), null)
})

/** 폼의 두 조회 요청과 정밀 목표를 보존하고 정책 안내를 추가 요청으로 보내지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('goal form becomes a precise read only comparison question with no analysis payload', function previewQuestion() {
  const input = { masterId: '101', year: 2026, targetProfitEok: '9007199254740993.12345678' }
  const message = goalPreviewQuestion(input), payload = assistantPayload(input.masterId, null, message)
  assert.match(message, /2026년 현재 연간 영업이익 전망을 목표 9007199254740993\.12345678억 원과 비교해 부족액 또는 초과액/)
  assert.match(message, /조직별 영업이익 계획 차이/)
  assert.doesNotMatch(message, /저장|승인|보호|개선 과제|검증/)
  assert.equal(payload.message, message)
  assert.equal(payload.masterId, '101')
  assert.equal(payload.analysisId, null)
  assert.equal(payload.expectedRevision, null)
  assert.equal(goalPreviewQuestion({ ...input, targetProfitEok: '0.00000001' }).includes('0.00000001억 원'), true)
  for (const value of ['0', '-1', '1e3', '10000000000000000', '0.000000001']) {
    /** 잘못된 목표는 조회 전에 거부한다. Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음. */
    function invalid() { goalPreviewQuestion({ ...input, targetProfitEok: value }) }
    assert.throws(invalid)
  }
})

/** 새 비교 요청이 작성 중인 질문·현재 실행·다른 기준을 덮어쓰지 않게 한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('goal comparison preserves a user draft and respects in flight and scope boundaries', function previewGuard() {
  const draft = '내가 작성 중인 질문'
  assert.equal(canStartGoalPreview(draft, false, false, '101', '101'), false)
  assert.equal(canStartGoalPreview('', true, false, '101', '101'), false)
  assert.equal(canStartGoalPreview('', false, true, '101', '101'), false)
  assert.equal(canStartGoalPreview('', false, false, '102', '101'), false)
  assert.equal(canStartGoalPreview('', false, false, '', ''), false)
  assert.equal(canStartGoalPreview(' ', false, false, '101', '101'), true)
  assert.equal(draft, '내가 작성 중인 질문')
})

/** 후속 이력이 최근 네 문답의 텍스트만 전달하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('history is bounded to eight text-only messages of 2000 characters', function boundedHistory() {
  const turns = []
  for (let index = 0; index < 10; index++) turns.push({ question: `질문${index}${'가'.repeat(2200)}`, response: { status: 'COMPLETED', answer: `답변${index}${'나'.repeat(2300)}`, observations: [{ secret: 'must-not-send' }] } })
  const history = conversationHistory(turns)
  assert.equal(history.length, 8)
  assert.ok(history[0].content.startsWith('질문6'))
  for (const item of history) { assert.ok(item.content.length <= 2000); assert.deepEqual(Object.keys(item), ['role','content']) }
  assert.ok(!JSON.stringify(history).includes('답변'))
  assert.ok(!JSON.stringify(history).includes('must-not-send'))
})

/** 부분 성공·실패 질문도 이어 받되 과거 수치와 본문은 현재 근거로 전달하지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('partial and failed questions retain narrow execution context without old facts', function partialHistory() {
  const turns = [{ question: '계획 대비 이익이 큰 조직을 보여줘', response: { status: 'LIMIT_REACHED', answer: '이전 답변 123456789원', toolCalls: [{ tool: 'organization_pnl', status: 'SUCCESS' }, { tool: 'approved_explanations', status: 'ERROR' }], observations: [{ data: { text: '과거 원문을 보내지 않음', amountWon: '123456789' } }] } }, { question: '지금까지 확인한 걸 정리해 줘', response: { status: 'FAILED', answer: 'provider secret', toolCalls: [{ tool: 'company_pnl', status: 'SUCCESS' }] } }, { question: '응답이 없던 질문', error: '네트워크 오류 비공개 상세' }, { question: '아직 처리 중', pending: true }]
  const history = conversationHistory(turns), text = JSON.stringify(history)
  assert.equal(history.length, 6)
  assert.match(text, /organization_pnl\(SUCCESS\)/)
  assert.match(text, /approved_explanations\(ERROR\)/)
  assert.match(text, /company_pnl\(SUCCESS\)/)
  assert.match(text, /현재 실행에서 다시 조회/)
  assert.ok(!text.includes('123456789') && !text.includes('provider secret') && !text.includes('과거 원문') && !text.includes('비공개 상세') && !text.includes('아직 처리 중'))
})

/** 완료되지 않은 답변에서 같은 실행 기준의 성공 관찰만 표시한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('successful observations remain visible for failed and limited answers with exact scope', function partialResults() {
  const context = { masterId: '101', analysisId: '201', revision: 3, year: 2026, baseMonth: 11 }
  const observation = { id: 'ok', status: 'SUCCESS', tool: 'company_pnl', basis: { ...context }, data: { kpis: [] } }
  const observations = [observation, observation, { ...observation, id: 'error', status: 'ERROR' }, { ...observation, id: 'old', basis: { ...context, revision: 2 } }, { ...observation, id: 'year', basis: { ...context, year: 2025 } }, { ...observation, id: 'month', basis: { ...context, baseMonth: 10 } }, { ...observation, id: 'other', basis: { ...context, masterId: '102' } }]
  for (const status of ['COMPLETED', 'LIMIT_REACHED', 'FAILED', 'NEEDS_INPUT']) assert.deepEqual(successfulObservations({ mode: 'UNAVAILABLE', status, context, observations }), [observation])
})

/** 계획 초과 순과 큰 변동 순을 구분하고 초대형 소수 금액의 차이를 보존한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('organization differences default to signed descending with an explicit absolute option', function sortedDifferences() {
  const observation = { tool: 'profit_drivers', data: { items: [{ organization: { name: '계획 미달' }, opDiff: '-10000000000000000' }, { organization: { name: '초과 작은 소수' }, opDiff: '9007199254740993.000001' }, { organization: { name: '비교 불가' }, opDiff: null }, { organization: { name: '초과 큰 소수' }, opDiff: '9007199254740993.000002' }] } }
  const before = JSON.stringify(observation), signed = observationTables(observation)[0], absolute = observationTables(observation, 'ABS_DESC')[0]
  assert.deepEqual(signed.table.rows.map(firstCell), ['초과 큰 소수', '초과 작은 소수', '계획 미달', '비교 불가'])
  assert.deepEqual(absolute.table.rows.map(firstCell), ['계획 미달', '초과 큰 소수', '초과 작은 소수', '비교 불가'])
  assert.match(signed.label, /계획 초과액 큰 순/)
  assert.match(absolute.label, /차이 절댓값 큰 순/)
  assert.equal(signed.table.rows[0][1], '+90,071,992.54740993000002억 원')
  assert.equal(absolute.table.rows[0][1], '-100,000,000억 원')
  assert.equal(JSON.stringify(observation), before)
})
/** 표의 첫 셀로 정렬된 조직명을 읽는다.
 * Args: row 표시 행. Returns: 첫 셀. Raises: 없음. Side Effects: 없음.
 */
function firstCell(row) { return row[0] }

/** 서버 손익 요약의 유불리와 누락을 보존하며 거대한 원본 행 없이도 표시한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('compact company and organization summaries retain server meaning and missing data', function compactSummaries() {
  const metric = { key: 'COST', actualWon: '200000000', planDifferenceWon: '-100000000', direction: 'DECREASE', favorability: 'FAVORABLE', missing: false }
  const company = observationTables({ tool: 'company_pnl', data: { dataStatus: 'CONFIRMED', kpis: [], summary: { metrics: [metric] } } })
  assert.deepEqual(company[0].table.rows[0], ['비용', '2억 원', '-1억 원', '감소', '유리', '아니요'])
  const row = { organization: { name: 'A' }, rank: 2, key: 'OP', actualWon: '500000000', planDifferenceWon: '100000000', direction: 'INCREASE', favorability: 'FAVORABLE', missing: false }
  const org = observationTables({ tool: 'organization_pnl', data: { summary: { rankedByOpPlanDifference: [row] }, columns: [], total: {} } })
  assert.deepEqual(org[0].table.rows[0].slice(0, 6), ['A', '5억 원', '+1억 원', '증가', '유리', '아니요'])
  assert.ok(!org[0].table.columns.includes('순위'))
})

/** 월간 실적은 별도 기간 제목과 인용 단위로 표시하며 연간 전망으로 오인하지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 관찰만 검사한다.
 */
test('monthly tool renders precise monthly values and citations with explicit period', function monthlyTables() {
  const metric = { key: 'OP', actualWon: '100000000.123456', planDifferenceWon: '-20000000', direction: 'DECREASE', favorability: 'UNFAVORABLE', missing: false }
  const observation = { id: 'month', tool: 'monthly_pnl', label: '월간 손익', status: 'SUCCESS', data: { period: 'MONTH', unit: 'KRW', company: { summary: { metrics: [metric] } }, organizations: { summary: { rankedByOpPlanDifference: [{ ...metric, organization: { name: '합성 조직' } }] } } } }
  const before = JSON.stringify(observation), tables = observationTables(observation)
  assert.equal(tables.length, 2)
  assert.match(tables[0].label, /해당 월/)
  assert.ok(tables[0].table.columns.includes('해당 월 실적'))
  assert.ok(!tables[0].table.columns.includes('현재 연간 전망'))
  assert.ok(tables[0].table.rows[0].includes('1.00000000123456억 원'))
  assert.match(tables[1].orderNote, /영업이익 실적 − 계획/)
  const citations = answerCitations({ observations: [observation], citations: [{ observationId: 'month', path: '/data/company/summary/metrics/0/actualWon', quote: metric.actualWon }] })
  assert.equal(citations[0].field, '해당 월 실적')
  assert.equal(citations[0].displayQuote, '1.00000000123456억 원')
  assert.deepEqual(observationTables({ ...observation, data: { ...observation.data, period: 'YEAR' } }), [])
  assert.equal(JSON.stringify(observation), before)
})

/** 상세 항목의 계획 기준·원천 상한과 정밀 금액을 표시하며 개선 가능액으로 부르지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 관찰만 검사한다.
 */
test('report item breakdown displays exact amounts with its same submission plan limitation', function itemBreakdownTables() {
  const data = { period: 'YEAR', unit: 'KRW', sourceLimitMayApply: true, items: [{ label: '합성 비용', organizationId: 'synthetic', text: '동일 제출본의 비용 비교', facts: { forecastWon: '9007199254740993.123456', planWon: '9007199254740993.123455', varianceWon: '0.000001', profitImpactWon: '-0.000001' } }] }
  const before = JSON.stringify(data), table = observationTables({ tool: 'report_item_breakdown', data })[0]
  assert.ok(table.table.rows[0].includes('90,071,992.54740993123456억 원'))
  assert.ok(table.table.rows[0].includes('-0.00000000000001억 원'))
  assert.ok(table.table.columns.includes('같은 제출본의 계획'))
  assert.match(table.orderNote, /별도 확정 계획 마스터 대비 차이와 같다고 볼 수 없/)
  assert.match(table.orderNote, /조회 상한/)
  assert.equal(JSON.stringify(data), before)
})

/** 소명 본문과 목표 전망 비교가 숫자 창작·저장·달성 판단 없이 표시되는지 확인한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('explanations expose full text and goal preview preserves exact unstored outlook comparison', function explanationAndGoal() {
  const text = '납품 지연 사유를 확인했습니다.\n다음 달 인식 일정은 고객 확인이 필요합니다.'
  const explanation = { tool: 'approved_explanations', data: { total: 1, items: [{ refId: 'explanation-1', label: 'A사업부 · 매출 · 수락 소명', text, facts: { reviewedAt: '2026-11-26T00:00:00Z' } }] } }
  assert.equal(observationExplanations(explanation)[0].text, text)
  assert.equal(observationExplanations(explanation)[0].refId, 'explanation-1')
  const data = { year: 2026, baseMonth: 11, baselineProfitWon: '92000000000.123456', targetProfitWon: '107000000000', targetProfitEok: '1070', gapWon: '14999999999.876544', surplusWon: '0', signedGapWon: '14999999999.876544', forecastMeetsTarget: false, saved: false }
  const before = JSON.stringify(data), goal = observationTables({ tool: 'goal_preview', data })[0]
  assert.deepEqual(goal.table.rows[0], ['기준 영업이익', '920.00000000123456억 원'])
  assert.deepEqual(goal.table.rows[2], ['목표까지 부족액', '149.99999999876544억 원'])
  assert.deepEqual(goal.table.rows[3], ['목표 초과 전망액', '0억 원'])
  assert.deepEqual(goal.table.rows[5], ['연간 전망이 목표 이상', '아니요'])
  assert.equal(JSON.stringify(data), before)
})

/** 분석 없는 읽기 질문과 고정 분석 질문의 기준을 분리한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('question scope is explicit and changes with master and analysis revision', function questionBasis() {
  assert.deepEqual(assistantPayload('m1', null, ' 질문 '), { masterId: 'm1', analysisId: null, expectedRevision: null, message: '질문', history: [] })
  const analysis = { id: 'a1', revision: 3, snapshot: { masterId: 'm1' } }
  assert.equal(assistantPayload('m1', analysis, '질문').expectedRevision, 3)
  assert.notEqual(conversationKey('m1', analysis), conversationKey('m1', { ...analysis, revision: 4 }))
  /** 다른 마스터의 분석 사용을 거부한다.
   * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
   */
  function mismatch() { assistantPayload('m2', analysis, '질문') }
  assert.throws(mismatch, /기준이 다릅니다/)
})

/** 원문 JSON pointer 인용을 대조하며 경로 위조와 임의 숫자를 거부한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('citations resolve RFC6901 scalar paths and reject fabricated values or prototype access', function exactCitations() {
  const observation = { id: 'obs-1', status: 'SUCCESS', label: '전사 손익', data: { 'a/b': { '~value': '9007199254740993.123456' }, note: '승인된 소명 원문입니다.' } }
  assert.equal(citationValue(observation, '/data/a~1b/~0value'), '9007199254740993.123456')
  assert.equal(citationValue(observation, '/data/__proto__/polluted'), undefined)
  assert.equal(citationValue(observation, '/data/a~2b'), undefined)
  const citations = answerCitations({ observations: [observation], citations: [{ observationId: 'obs-1', path: '/data/a~1b/~0value', quote: '9007199254740993.123456' }, { observationId: 'obs-1', path: '/data/note', quote: '승인된 소명' }, { observationId: 'obs-1', path: '/data/a~1b/~0value', quote: '1000000000' }, { observationId: 'unknown', path: '/data/note', quote: '승인된 소명' }] })
  assert.equal(citations.length, 2)
  assert.equal(citations[0].quote, '9007199254740993.123456')
})

/** 숫자 일부 인용으로 부호·단위가 사라지지 않고 null을 사실대로 표시하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('decimal citations require the complete signed value and null remains a valid scalar', function scalarCitations() {
  const observation = { id: 'o', label: '차이', status: 'SUCCESS', data: { amount: '-15000000000', unavailable: null } }
  const response = { observations: [observation], citations: [{ observationId: 'o', path: '/data/amount', quote: '150' }, { observationId: 'o', path: '/data/amount', quote: '-15000000000' }, { observationId: 'o', path: '/data/unavailable', quote: 'null' }] }
  assert.deepEqual(answerCitations(response).map(citationQuote), ['-15000000000', 'null'])
})
/** 인용 목록의 원문을 검증용으로 읽는다.
 * Args: citation 인용. Returns: 인용문. Raises: 없음. Side Effects: 없음.
 */
function citationQuote(citation) { return citation.quote }

/** 기존 HOME 응답의 금액 단위와 중첩 조직명을 읽기 쉬운 표에 유지한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('existing home tools display money units and nested organization names', function homeObservation() {
  const observation = { tool: 'organization_pnl', data: { columns: [{ organization: { id: 'o1', name: '검증 조직', code: 'A' }, revenue: '200000000', op: '-150000000', opMarginRate: '1.5' }] } }
  const table = observationTables(observation)[0].table
  assert.deepEqual(table.rows[0], ['검증 조직', 'A', '2억 원', '-1.5억 원', '1.5'])
  assert.equal(observation.data.columns[0].organization.name, '검증 조직')
})

/** 검증된 같은 분석의 요청 초안만 업무 작성 화면으로 연결한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('review drafts require current analysis revision and known organization without inventing amounts', function verifiedDraft() {
  const analysis = { id: 'a1', revision: 2, snapshot: { masterId: 'm1', organizations: [{ id: 'o1' }] } }
  const observation = { tool: 'review_request_draft', status: 'SUCCESS', basis: { masterId: 'm1', analysisId: 'a1', revision: 2 }, data: { analysisId: 'a1', expectedAnalysisRevision: 2, sourceKind: 'hypothesis', sourceId: 'h1', organizationId: 'o1', title: '검토 가설', message: '조건과 가능액을 확인해 주세요.', needsConfirmation: true } }
  assert.deepEqual(reviewDraftSource(observation, analysis), { kind: 'hypothesis', id: 'h1', organizationId: 'o1', title: '검토 가설', questions: ['조건과 가능액을 확인해 주세요.'] })
  assert.equal(reviewDraftSource(observation, { ...analysis, revision: 3 }), null)
  assert.equal(reviewDraftSource(observation, null), null)
  assert.equal(reviewDraftSource({ ...observation, status: 'ERROR' }, analysis), null)
})

/** 원 금액의 정밀도를 보존하고 알려진 탐색 동작만 제공한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('observation tables preserve exact amounts and navigation never becomes a write action', function sourceDisplay() {
  assert.equal(observationCell('profitWon', '9007199254740993.123456'), '90,071,992.54740993123456억 원')
  const tables = observationTables({ data: { items: [{ organizationName: '조직', profitWon: '9007199254740993.123456', nested: { x: 1 } }] } })
  assert.deepEqual(tables[0].table.rows, [['조직', '90,071,992.54740993123456억 원']])
  assert.deepEqual(answerActions(['OPEN_WORKFLOW', 'APPROVE', 'OPEN_WORKFLOW']), [{ key: 'OPEN_WORKFLOW', label: '조직 검토·실행 열기', tab: 'workflow' }])
})

/** 모델 부재를 성공으로 바꾸지 않고 다른 기준의 응답을 거부한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('unavailable responses stay unavailable and stale context is rejected', function responseBasis() {
  const request = assistantPayload('m1', null, '질문')
  const response = { mode: 'UNAVAILABLE', status: 'FAILED', context: { masterId: 'm1', analysisId: null, revision: null } }
  assert.equal(validateAssistantResponse(response, request).mode, 'UNAVAILABLE')
  /** 다른 조회 기준 응답을 거부한다.
   * Args: 없음. Returns: 없음. Raises: 계약 오류. Side Effects: 없음.
   */
  function stale() { validateAssistantResponse({ ...response, context: { masterId: 'm2' } }, request) }
  assert.throws(stale, /기준이 달라/)
})

/** 공개 엔드포인트에 조회 요청과 취소 신호를 전달한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 메모리 대역만 사용.
 */
test('assistant transport passes only the public request and abort signal', async function transport() {
  const payload = assistantPayload('m1', null, '손익 조회'), controller = new AbortController(), calls = []
  /** 인증 요청을 대체하고 안전한 응답을 반환한다.
   * Args: path 경로, options 옵션. Returns: Promise<object>. Raises: 없음. Side Effects: 메모리 호출 기록.
   */
  async function api(path, options) { calls.push({ path, options }); return { mode: 'MODEL', status: 'COMPLETED', answer: '근거를 확인했습니다.', context: { masterId: 'm1', analysisId: null, revision: null }, toolCalls: [], observations: [], citations: [] } }
  await askAssistant(api, payload, controller.signal)
  assert.equal(calls[0].path, '/api/v1/decision-agent/assistant')
  assert.deepEqual(calls[0].options.body, payload)
  assert.equal(calls[0].options.signal, controller.signal)
})
