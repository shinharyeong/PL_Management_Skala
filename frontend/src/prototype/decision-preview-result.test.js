import test from 'node:test'
import assert from 'node:assert/strict'
import { buildPreviewResult } from './decision-preview-result.js'
import { validateAssistantResponse } from './decision-conversation-api.js'

/** 실제 자료를 사용하지 않는 고정 기준을 만든다.
 * Args: 없음. Returns: 합성 응답 기준. Raises: 없음. Side Effects: 없음.
 */
function context() { return { masterId: '101', masterLabel: '합성 기준', year: 2026, baseMonth: 10, analysisId: null, revision: null, asOf: '2026-11-01T00:00:00Z' } }
/** 현재 폼의 합성 입력 범위를 만든다.
 * Args: 없음. Returns: 입력 범위. Raises: 없음. Side Effects: 없음.
 */
function scope() { return { masterId: '101', year: 2026, targetProfitEok: '1000', analysisId: null, revision: null } }
/** 원문 그대로 비교할 합성 목표 응답을 만든다.
 * Args: 없음. Returns: 계산 완료 응답 대역. Raises: 없음. Side Effects: 없음.
 */
function goalData() {
  return { year: 2026, baseMonth: 10, unit: 'KRW', basis: 'FIXED_ANNUAL_OUTLOOK', saved: false, targetProfitEok: '1000',
    baselineProfitWon: '92000000000', targetProfitWon: '100000000000', gapWon: '8000000000', surplusWon: '0', forecastMeetsTarget: false }
}
/** 합성 성공 관찰을 구성한다.
 * Args: id 관찰 ID, tool 도구명, data 원문 대역. Returns: 성공 관찰. Raises: 없음. Side Effects: 없음.
 */
function observation(id, tool, data) {
  return { id, label: `합성 ${tool}`, tool, status: 'SUCCESS', basis: { ...context(), capturedAt: '2026-11-26T01:00:00Z' }, data, warnings: [] }
}
/** 폼에서 완료된 합성 실행을 구성한다.
 * Args: observations 관찰 목록. Returns: 요청·응답·폼 입력. Raises: 없음. Side Effects: 없음.
 */
function execution(observations = []) {
  return { executionId: 'turn-synthetic', phase: 'RESULT', source: 'FORM', goalInput: { masterId: '101', year: 2026, targetProfitEok: '1000' },
    payload: { masterId: '101', analysisId: null, expectedRevision: null, message: '합성 목표 비교' },
    response: { mode: 'MODEL', status: 'COMPLETED', context: context(), observations, limitations: [] } }
}
/** 합성 조직 요약의 한 행을 만든다.
 * Args: id 조직 ID, difference 원문 계획 차이, missing 누락 여부. Returns: 요약 행. Raises: 없음. Side Effects: 없음.
 */
function organization(id, difference, missing = false) {
  return { organization: { id, code: `ORG-${id}`, name: `합성 조직 ${id}` }, actualWon: '100.123456', planDifferenceWon: difference,
    direction: 'UNCHANGED', favorability: 'NEUTRAL', missing }
}

/** 실제 목표 인용에 연결된 설명과 미해결 항목을 합성 실행에 붙인다.
 * Args: run 합성 실행. Returns: 실행 원본. Raises: 없음. Side Effects: 전달한 합성 메모리만 변경.
 */
function withBrief(run) {
  run.response.status = 'NEEDS_INPUT'
  run.response.brief = { summary: '목표 차이는 확인했으며 개선 효과는 추가 검토가 필요합니다.',
    findings: [{ id: 'f1', kind: 'FACT', text: '현재 전망은 입력 목표에 미달합니다.', citations: [{ observationId: 'goal', path: '/data/gapWon', quote: '8000000000' }], uncertainty: null }],
    requirements: [{ id: 'r1', requestQuote: '목표 비교', question: '목표와 현재 전망 비교', status: 'ANSWERED', findingIds: ['f1'], missingInformation: null, followUpQuestion: null },
      { id: 'r2', requestQuote: '합성', question: '개선 가능성 확인', status: 'NEEDS_INPUT', findingIds: [], missingInformation: '조직의 개선 가능액 확인이 필요합니다.', followUpQuestion: '조직에서 확인한 개선 가능액이 있나요?' }] }
  return run
}

/** 목표·기준 변경과 로딩에서 이전 해석·질문이 남지 않도록 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 메모리만 사용.
 */
test('structured findings share scope invalidation with financial values', function briefScope() {
  const run = withBrief(execution([observation('goal', 'goal_preview', goalData())]))
  const result = buildPreviewResult(run, scope())
  assert.equal(result.state, 'PARTIAL'); assert.equal(result.status, 'NEEDS_INPUT')
  assert.equal(result.brief.findings[0].citations[0].displayQuote, '80억 원')
  assert.equal(result.brief.unresolved[0].id, 'r2')
  assert.equal(buildPreviewResult(run, { ...scope(), targetProfitEok: '1001' }).brief, null)
  assert.equal(buildPreviewResult({ ...run, phase: 'PENDING' }, scope()).brief, null)
  assert.equal(buildPreviewResult({ ...run, phase: 'ERROR', response: undefined }, scope()).brief, null)
})

/** 조회 자료가 없더라도 실제 미해결 질문을 오류나 영 금액으로 바꾸지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 메모리만 사용.
 */
test('valid clarification remains visible with no successful observations', function clarifyWithoutData() {
  const run = withBrief(execution())
  run.response.brief.findings = []
  run.response.brief.requirements = [run.response.brief.requirements[1]]
  const result = buildPreviewResult(run, scope())
  assert.equal(result.state, 'PARTIAL')
  assert.equal(result.goal, null)
  assert.equal(result.organizationCount, null)
  assert.equal(result.brief.answeredCount, 0)
  assert.equal(result.brief.unresolved.length, 1)
  assert.deepEqual(result.sources, [])
})

/** 설명 검증 실패가 성공한 목표 원문과 보고서 지표를 없애지 않는지 연결 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 메모리만 사용.
 */
test('invalid narrative falls back to partial report while verified goal observations remain visible', function malformedBriefFallback() {
  const run = withBrief(execution([observation('goal', 'goal_preview', goalData())]))
  run.response.brief.findings[0].citations[0].quote = 'fabricated'
  run.response = validateAssistantResponse(run.response, run.payload)
  const result = buildPreviewResult(run, scope())
  assert.equal(result.state, 'PARTIAL')
  assert.equal(result.status, 'FAILED')
  assert.equal(result.brief, null)
  assert.equal(result.goal.gapWon, '8000000000')
  assert.equal(result.sources[0].id, 'goal')
  assert.ok(result.warnings.some(containsExplanationFailure))
  /** 실제 표시 한계에 설명 검증 오류가 포함되는지 확인한다. Args: value 안내. Returns: 포함 여부. Raises: 없음. Side Effects: 없음. */
  function containsExplanationFailure(value) { return value.includes('분석 설명의 근거 연결') }
})

/** 미실행·진행·전송 오류는 과거 금액이나 영 건을 채우지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('empty pending and error states contain no invented figures', function lifecycle() {
  assert.equal(buildPreviewResult(null, scope()).state, 'EMPTY')
  assert.equal(buildPreviewResult({ phase: 'IDLE' }, scope()).state, 'EMPTY')
  const pending = execution([observation('goal', 'goal_preview', goalData())]); pending.phase = 'PENDING'
  const loading = buildPreviewResult(pending, scope())
  assert.equal(loading.state, 'LOADING'); assert.equal(loading.goal, null); assert.equal(loading.organizationCount, null)
  assert.equal(loading.explanationCount, null); assert.deepEqual(loading.sources, [])
  const failed = execution(); failed.phase = 'ERROR'; delete failed.response
  assert.equal(buildPreviewResult(failed, scope()).state, 'ERROR')
})

/** 다른 요청·응답 마스터·연도·분석·revision 결과를 숨긴다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('stale request and response scopes hide money and source links', function scopeBoundaries() {
  for (const change of [{ masterId: '102' }, { analysisId: '2' }, { revision: 3 }, { year: 2025 }]) {
    const current = { ...scope(), ...change }, result = buildPreviewResult(execution([observation('goal', 'goal_preview', goalData())]), current)
    assert.equal(result.state, 'STALE'); assert.equal(result.goal, null); assert.deepEqual(result.organizations, [])
    assert.equal(result.organizationCount, null); assert.deepEqual(result.sources, [])
  }
  const wrongResponse = execution([observation('goal', 'goal_preview', goalData())]); wrongResponse.response.context.year = 2025
  assert.equal(buildPreviewResult(wrongResponse, scope()).state, 'STALE')
  const wrongRequest = execution(); wrongRequest.payload.masterId = '102'
  assert.equal(buildPreviewResult(wrongRequest, scope()).state, 'STALE')
})

/** 폼 목표는 정규화해서 비교하되 실제 입력 변경 시 이전 결과를 숨긴다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('form target normalization preserves equivalent input and invalidates changed goals', function formScope() {
  const run = execution([observation('goal', 'goal_preview', goalData())])
  assert.equal(buildPreviewResult(run, { ...scope(), targetProfitEok: '01000.00000000' }).state, 'READY')
  for (const targetProfitEok of ['1001', '', '1e3']) {
    const result = buildPreviewResult(run, { ...scope(), targetProfitEok })
    assert.equal(result.state, 'STALE'); assert.equal(result.goal, null)
  }
  run.goalInput.year = 2025
  assert.equal(buildPreviewResult(run, scope()).state, 'STALE')
})

/** 대화 조회 목표는 다른 폼 목표로 덮거나 숨기지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('chat preserves its actual observed goal independently of form target', function chatGoal() {
  const run = execution([observation('goal', 'goal_preview', goalData())]); run.source = 'CHAT'; delete run.goalInput
  const result = buildPreviewResult(run, { ...scope(), targetProfitEok: '900' })
  assert.equal(result.state, 'READY'); assert.equal(result.goal.targetProfitWon, '100000000000')
  assert.equal(result.goal.baselineProfitWon, '92000000000'); assert.equal(result.goal.gapWon, '8000000000')
  assert.equal(result.goal.surplusWon, '0'); assert.equal(result.goal.forecastMeetsTarget, false)
  assert.equal(result.goal.capturedAt, '2026-11-26T01:00:00Z')
  assert.equal(result.capturedAt, result.goal.capturedAt)
})

/** 목표 필드가 없거나 형식·저장 상태가 잘못되면 금액을 만들어 내지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('invalid or persisted goal observations never become displayable goals', function invalidGoal() {
  for (const change of [{ saved: true }, { gapWon: null }, { targetProfitWon: 100000000000 }, { surplusWon: 'NaN' },
    { baselineProfitWon: '1e9' }, { forecastMeetsTarget: 'false' }, { year: 2025 }, { targetProfitEok: '900' }]) {
    const result = buildPreviewResult(execution([observation('goal', 'goal_preview', { ...goalData(), ...change })]), scope())
    assert.equal(result.state, 'READY'); assert.equal(result.goal, null)
  }
})

/** 큰 소수 금액의 부호 유지 정렬과 상위 다섯 행·전체 조직 수를 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('organization projection sorts exact signed decimals and preserves full count without mutation', function exactOrganizationOrder() {
  const values = [organization('missing', null, true), organization('negative', '-100000000000000000000'),
    organization('low', '9007199254740993.000001'), organization('high', '9007199254740993.000002'),
    organization('zero', '0'), organization('tiny', '0.000001'), organization('small', '1')]
  const run = execution([observation('orgs', 'organization_pnl', { summary: { scopeOrganizationCount: 7, rankedByOpPlanDifference: values } })])
  const before = JSON.stringify(run), result = buildPreviewResult(run, scope()), ids = []
  for (const row of result.organizations) ids.push(row.id)
  assert.deepEqual(ids, ['high', 'low', 'small', 'tiny', 'zero'])
  assert.equal(result.organizationCount, 7); assert.equal(result.organizations[0].planDifferenceWon, '9007199254740993.000002')
  assert.equal(result.organizations[0].actualWon, '100.123456'); assert.equal(result.organizations[0].sourceObservationId, 'orgs')
  assert.equal(result.organizations[0].favorability, 'NEUTRAL'); assert.equal(JSON.stringify(run), before)
})

/** 손익 차이만 조회한 경우 실제 비교액은 보이고 전망·전체 조직 수는 추정하지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 메모리만 사용한다.
 */
test('profit driver fallback preserves exact differences without inventing outlook or scope counts', function driverProjection() {
  const drivers = observation('drivers', 'profit_drivers', { dataStatus: 'CONFIRMED', total: '-123456789.987654', items: [
    { organization: { id: 'larger-shortfall', name: '합성 조직 가' }, opDiff: '-9007199254740993.000002', opDiffRate: '-2.5' },
    { organization: { id: 'smaller-shortfall', name: '합성 조직 나' }, opDiff: '-9007199254740993.000001', opDiffRate: '-1.5' },
    { organization: { id: 'missing', name: '합성 조직 다' }, opDiff: 100, opDiffRate: null },
    { organization: { id: 'larger-shortfall', name: '중복' }, opDiff: '1' },
  ] })
  const stale = observation('old', 'profit_drivers', { items: [{ organization: { id: 'old' }, opDiff: '999' }] }); stale.basis.masterId = '102'
  const failed = observation('failed', 'profit_drivers', { items: [{ organization: { id: 'failed' }, opDiff: '888' }] }); failed.status = 'ERROR'
  const run = execution([drivers, stale, failed]); run.response.status = 'NEEDS_INPUT'
  const before = JSON.stringify(run), result = buildPreviewResult(run, scope())
  assert.equal(result.state, 'PARTIAL')
  assert.equal(result.organizationSource, 'profit_drivers')
  assert.equal(result.organizationCount, null)
  const ids = []
  for (const row of result.organizations) ids.push(row.id)
  assert.deepEqual(ids, ['smaller-shortfall', 'larger-shortfall', 'missing'])
  assert.equal(result.organizations[0].planDifferenceWon, '-9007199254740993.000001')
  assert.equal(result.organizations[0].actualWon, null)
  assert.equal(result.organizations[0].direction, null)
  assert.equal(result.organizations[0].favorability, null)
  assert.equal(result.organizations[0].missing, false)
  assert.equal(result.organizations[0].comparisonOnly, true)
  assert.equal(result.organizations[0].sourceObservationId, 'drivers')
  assert.equal(result.organizations[2].planDifferenceWon, null)
  assert.equal(result.organizations[2].missing, true)
  assert.equal(JSON.stringify(run), before)
  assert.deepEqual(buildPreviewResult(run, { ...scope(), masterId: 'other' }).organizations, [])
})

/** 전체 조직 조회가 있으면 호출 순서에 관계없이 제한된 차이 목록으로 대체하지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 메모리만 사용한다.
 */
test('full organization observations take precedence over limited driver fallback in either order', function organizationPrecedence() {
  const drivers = observation('drivers', 'profit_drivers', { total: '999', items: [{ organization: { id: 'driver' }, opDiff: '999' }] })
  const orgs = observation('orgs', 'organization_pnl', { summary: { scopeOrganizationCount: 7, rankedByOpPlanDifference: [organization('full', '123')] } })
  for (const observations of [[drivers, orgs], [orgs, drivers]]) {
    const result = buildPreviewResult(execution(observations), scope())
    assert.equal(result.organizationSource, 'organization_pnl')
    assert.equal(result.organizationCount, 7)
    assert.equal(result.organizations[0].id, 'full')
    assert.equal(result.organizations[0].actualWon, '100.123456')
  }
  const empty = buildPreviewResult(execution([observation('empty-drivers', 'profit_drivers', { total: '0', items: [] })]), scope())
  assert.equal(empty.organizationSource, 'profit_drivers')
  assert.equal(empty.organizationCount, null)
  assert.deepEqual(empty.organizations, [])
})

/** 미조회 개수와 성공한 빈 조회를 구분하고 누락 비교액을 영으로 바꾸지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('counts distinguish unqueried from observed zero and missing comparisons remain null', function missingCounts() {
  const onlyGoal = buildPreviewResult(execution([observation('goal', 'goal_preview', goalData())]), scope())
  assert.equal(onlyGoal.organizationCount, null); assert.equal(onlyGoal.explanationCount, null)
  const empty = buildPreviewResult(execution([observation('orgs', 'organization_pnl', { summary: { scopeOrganizationCount: 0, rankedByOpPlanDifference: [] } }),
    observation('explanations', 'approved_explanations', { total: 10, items: [] })]), scope())
  assert.equal(empty.organizationCount, 0); assert.equal(empty.explanationCount, 0)
  const row = organization('1', null, true)
  const missing = buildPreviewResult(execution([observation('orgs', 'organization_pnl', { summary: { scopeOrganizationCount: 1, rankedByOpPlanDifference: [row] } })]), scope())
  assert.equal(missing.organizations[0].planDifferenceWon, null); assert.equal(missing.organizations[0].missing, true)
})

/** 서로 다른 조직의 조회 결과를 마지막 조회나 서버 total로 덮지 않고 합친다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 메모리만 사용한다.
 */
test('approved explanation counts combine returned evidence across organizations', function combinedExplanationCount() {
  const first = observation('org-a', 'approved_explanations', { total: 8, items: [
    { refId: 'explanation-101', organizationId: 'a' }, { refId: 'explanation-102', organizationId: 'a' },
  ] })
  const second = observation('org-b', 'approved_explanations', { total: 7, items: [{ refId: 'explanation-103', organizationId: 'b' }] })
  const empty = observation('empty', 'approved_explanations', { total: 0, items: [] })
  const run = execution([first, second, empty]), before = JSON.stringify(run)
  assert.equal(buildPreviewResult(run, scope()).explanationCount, 3)
  assert.equal(JSON.stringify(run), before)
})

/** 전체 조회와 조직 재조회가 겹쳐도 전역 소명 ID를 한 번만 집계한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 메모리만 사용한다.
 */
test('overlapping explanation queries count each global evidence identifier once', function repeatedExplanationCount() {
  const company = observation('all-orgs', 'approved_explanations', { items: [
    { refId: 'explanation-201', organizationId: 'a' }, { refId: 'explanation-202', organizationId: 'b' },
  ] })
  const again = observation('org-repeat', 'approved_explanations', { items: [
    { refId: 'explanation-201', organizationId: 'a' }, { refId: 'explanation-203', organizationId: 'a' },
  ] })
  assert.equal(buildPreviewResult(execution([company, again, company]), scope()).explanationCount, 3)
  const unidentifiable = observation('missing-id', 'approved_explanations', { items: [{}] })
  assert.equal(buildPreviewResult(execution([company, unidentifiable]), scope()).explanationCount, null)
})

/** 소명 기본키는 조직별 ID가 아니며 다른 기준·실패 관찰은 합집합에 포함하지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 메모리만 사용한다.
 */
test('explanation identity is global while counts remain isolated to successful current scope', function scopedExplanationIdentity() {
  const current = observation('current', 'approved_explanations', { items: [{ refId: 'explanation-301', organizationId: 'a' }] })
  const sameId = observation('same-id', 'approved_explanations', { items: [{ refId: 'explanation-301', organizationId: 'b' }] })
  const stale = observation('stale', 'approved_explanations', { items: [{ refId: 'explanation-302', organizationId: 'a' }] }); stale.basis.revision = 2
  const failed = observation('failed', 'approved_explanations', { items: [{ refId: 'explanation-303', organizationId: 'a' }] }); failed.status = 'ERROR'
  assert.equal(buildPreviewResult(execution([current, sameId, stale, failed]), scope()).explanationCount, 1)
  assert.equal(buildPreviewResult(execution([stale, failed]), scope()).explanationCount, null)
})

/** 부분 실패에서도 같은 기준의 실제 성공 자료만 유지한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('partial failure keeps actual successful evidence and excludes failed stale and duplicate observations', function partialEvidence() {
  const good = observation('orgs', 'organization_pnl', { summary: { scopeOrganizationCount: 1, rankedByOpPlanDifference: [organization('1', '2')] } })
  good.warnings = ['실제 관찰 한계']
  const failed = observation('failed', 'goal_preview', goalData()); failed.status = 'ERROR'
  const stale = observation('stale', 'approved_explanations', { items: [{ refId: 'old' }] }); stale.basis.masterId = '102'
  const explanations = observation('explanations', 'approved_explanations', { items: [{ refId: 'a' }, { refId: 'b' }] })
  const run = execution([good, good, failed, stale, explanations]); run.response.mode = 'UNAVAILABLE'; run.response.status = 'LIMIT_REACHED'
  run.response.limitations = ['실제 실행 한계', '실제 관찰 한계']
  const result = buildPreviewResult(run, scope())
  assert.equal(result.state, 'PARTIAL'); assert.equal(result.goal, null); assert.equal(result.organizationCount, 1); assert.equal(result.explanationCount, 2)
  assert.deepEqual(result.sources, [{ id: 'orgs', label: '합성 organization_pnl' }, { id: 'explanations', label: '합성 approved_explanations' }])
  assert.deepEqual(result.warnings, ['실제 실행 한계', '실제 관찰 한계'])
})

/** 조회 시각이 없을 때 마스터 발행 시각을 대신 표시하지 않는다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('missing capture time never falls back to master issuance time', function captureTime() {
  const value = observation('goal', 'goal_preview', goalData()); delete value.basis.capturedAt
  const result = buildPreviewResult(execution([value]), scope())
  assert.equal(result.capturedAt, null); assert.equal(result.goal.capturedAt, null)
  assert.equal(result.context.asOf, '2026-11-01T00:00:00Z')
})
