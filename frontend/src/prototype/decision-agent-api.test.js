import test from 'node:test'
import assert from 'node:assert/strict'
import { createApiClient } from './live-api.js'
import { analysisPayload, candidateDraft, candidatePayload, decimalText, decisionRequest, emptyCandidate, eokToWon, exclusionText, isReadOnlyPreviewError, startAnalysisAction, wonToEok } from './decision-agent-api.js'

/** 읽기 전용 신호를 오류 문구나 일반 권한 오류와 구별한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('read only mode requires the exact server code instead of status or message', function readOnlySignal() {
  assert.equal(isReadOnlyPreviewError({ code: 'READ_ONLY_PREVIEW' }), true)
  assert.equal(isReadOnlyPreviewError({ code: 'FORBIDDEN', status: 403, message: '읽기 전용 확인 화면입니다.' }), false)
  assert.equal(isReadOnlyPreviewError({ status: 503, message: 'READ_ONLY_PREVIEW' }), false)
  assert.equal(isReadOnlyPreviewError(null), false)
})

/** 조회 전용 시작은 저장 콜백을 호출하지 않고 정상 환경의 생성 흐름은 유지한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 메모리 호출 기록만 변경.
 */
test('read only goal comparison never invokes analysis creation and normal creation remains available', async function readOnlyStart() {
  const calls = []
  /** 저장 없는 비교 호출을 기록한다. Args: 없음. Returns: Promise<boolean>. Raises: 없음. Side Effects: 메모리 기록. */
  async function preview() { calls.push('preview'); return false }
  /** 기존 저장 호출을 기록한다. Args: 없음. Returns: Promise<boolean>. Raises: 없음. Side Effects: 메모리 기록. */
  async function create() { calls.push('create'); return true }
  assert.equal(await startAnalysisAction(true, preview, create), false)
  assert.deepEqual(calls, ['preview'])
  assert.equal(await startAnalysisAction(false, preview, create), true)
  assert.deepEqual(calls, ['preview', 'create'])
})

/** 실제 HTTP 클라이언트가 읽기 전용 오류 코드를 화면 분기까지 보존한다.
 * Args: t 테스트 문맥. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 메모리 fetch 대역.
 */
test('analysis list read only problem retains its code through the authenticated client', async function readOnlyTransport(t) {
  /** 저장 목록 조회에 읽기 전용 오류를 반환한다.
   * Args: path 요청 경로. Returns: Promise<Response>. Raises: 검증 실패. Side Effects: 없음.
   */
  async function respond(path) { assert.equal(path, '/api/v1/decision-agent/analyses'); return new Response(JSON.stringify({ code: 'READ_ONLY_PREVIEW', detail: '조회 전용입니다.' }), { status: 503 }) }
  /** 테스트 인증 토큰을 반환한다. Args: 없음. Returns: 문자열. Raises: 없음. Side Effects: 없음. */
  function token() { return 'test-token' }
  /** 세션 삭제는 이 응답에서 호출되지 않아야 한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
  function clear() { assert.fail('읽기 전용 안내는 인증 실패가 아닙니다.') }
  t.mock.method(globalThis, 'fetch', respond)
  const api = createApiClient({ token, clear })
  try { await decisionRequest(api, 'analyses'); assert.fail('오류가 필요합니다.') }
  catch (cause) { assert.equal(isReadOnlyPreviewError(cause), true) }
})

/** 원·억원 변환이 안전 정수 범위를 넘는 값과 원 미만 금액을 보존하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('money conversion preserves all 24 integer and 6 fractional won digits', function exactMoney() {
  const won = '999999999999999999999999.123456'
  assert.equal(eokToWon(wonToEok(won)), won)
  assert.equal(eokToWon('0.00000000000001'), '0.000001')
  assert.equal(eokToWon('30.125'), '3012500000')
  assert.equal(wonToEok('9007199254740993.12'), '90071992.5474099312')
  assert.equal(decimalText('00030.12000'), '30.12')
})

/** 빈 값·지수·쉼표·음수·정밀도 초과가 0이나 근사 금액으로 바뀌지 않는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('invalid or overprecision money is rejected without approximation', function rejectedMoney() {
  const invalid = ['', ' ', '1e9', '1,000', '-1', '.5', '0.000000000000001', '10000000000000000']
  for (const value of invalid) {
    /** 잘못된 금액을 변환하여 검증 오류를 발생시킨다.
     * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
     */
    function convert() { eokToWon(value) }
    assert.throws(convert)
  }
  /** Number 금액 입력을 거부하는지 확인한다.
   * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
   */
  function numericInput() { eokToWon(20) }
  assert.throws(numericInput)
})

/** 가상 효과나 조직을 새 후보에 넣지 않고, 입력한 후보만 정밀 DTO로 변환하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('candidate draft preserves IDs and requires a real snapshot organization', function candidateContract() {
  const draft = emptyCandidate('candidate-1')
  assert.equal(draft.amountEok, '')
  assert.equal(draft.organizationId, null)
  assert.equal(draft.recognitionMonth, null)
  Object.assign(draft, { title: '납품 일정 검토', organizationId: '9007199254740993', amountEok: '12.00000000000001', recognitionMonth: 12, category: 'OPERATIONS', implementationCostEok: '0.1', embeddedEffectEok: '0.2' })
  const snapshot = { baseMonth: 11, organizations: [{ id: '9007199254740993' }] }
  const payload = candidatePayload(draft, snapshot)
  assert.equal(payload.organizationId, '9007199254740993')
  assert.equal(payload.amountWon, '1200000000.000001')
  assert.equal(payload.implementationCostWon, '10000000')
  assert.equal(payload.embeddedEffectWon, '20000000')
  assert.equal(payload.evidenceStatus, 'ASSUMPTION')
  assert.equal(payload.contributionMarginPercent, null)
  assert.deepEqual(candidateDraft(payload), draft)
  /** 기준에 없는 조직으로 저장할 때 오류를 확인한다.
   * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
   */
  function foreignOrganization() { candidatePayload({ ...draft, organizationId: 'different-org' }, snapshot) }
  assert.throws(foreignOrganization, /기준 마스터/)
})

/** 근거 검토 조건과 매출 공헌이익률을 확인하고, 제외 판정은 서버에 남기는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('reviewed evidence requires note and reference; revenue requires an exact valid margin', function evidenceContract() {
  const draft = { ...emptyCandidate('c1'), title: '매출 후보', organizationId: 'org', type: 'REVENUE', amountEok: '2', contributionMarginPercent: '25.125', recognitionMonth: 12, reviewed: true }
  const snapshot = { baseMonth: 11, organizations: [{ id: 'org' }] }
  /** 근거 없는 검토 완료 표시를 검증한다.
   * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
   */
  function missingEvidence() { candidatePayload(draft, snapshot) }
  assert.throws(missingEvidence, /검토 내용과 근거 위치/)
  draft.evidenceNote = '인식 시점 확인'; draft.evidenceRef = '계약 번호 C-01'
  const payload = candidatePayload(draft, snapshot)
  assert.equal(payload.contributionMarginPercent, '25.125')
  assert.equal(payload.evidenceStatus, 'REVIEWED')
  assert.equal(payload.category, 'UNKNOWN')
  /** 백분율 상한을 넘는 입력을 검증한다.
   * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
   */
  function excessiveMargin() { candidatePayload({ ...draft, contributionMarginPercent: '100.000001' }, snapshot) }
  assert.throws(excessiveMargin, /0~100/)
  /** 공헌이익률의 서버 허용 정밀도를 넘는 값을 검증한다.
   * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
   */
  function overprecisionMargin() { candidatePayload({ ...draft, contributionMarginPercent: '25.0000001' }, snapshot) }
  assert.throws(overprecisionMargin, /소수 6자리/)
  /** 기준 월 효과를 미래 과제로 저장하지 않도록 검증한다.
   * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
   */
  function currentMonth() { candidatePayload({ ...draft, recognitionMonth: 11 }, snapshot) }
  assert.throws(currentMonth, /기준 월 이후/)
})

/** 계산 제외 코드를 설명으로 표시하고 알 수 없는 사유도 누락하지 않는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('exclusion reasons are readable Korean and preserve unknown reasons', function exclusionLabels() {
  assert.equal(exclusionText(['PROTECTED_CATEGORY', 'DUPLICATE_EFFECT']), '보호할 비용에 해당 · 다른 후보와 효과 중복')
  assert.equal(exclusionText(['NEW_REASON']), '추가 제외 사유 확인 필요')
  assert.equal(exclusionText([]), '')
})

/** 경영 목표를 사용자가 선택한 마스터와 보호 제약으로 고정하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('analysis creation requires user goal and preserves explicit constraints', function analysisContract() {
  const form = { title: ' 연간 목표 ', prompt: ' 인력·R&D 유지 ', masterId: 'large-master-id', targetEok: '1070', protectedCategories: ['HEADCOUNT', 'RND'] }
  assert.deepEqual(analysisPayload(form), { title: '연간 목표', prompt: '인력·R&D 유지', masterId: 'large-master-id', targetProfitWon: '107000000000', protectedCategories: ['HEADCOUNT', 'RND'] })
  /** 목표 0을 서버 요청 전에 거부한다.
   * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
   */
  function zeroGoal() { analysisPayload({ ...form, targetEok: '0' }) }
  assert.throws(zeroGoal, /0보다/)
})

/** 실제 HTTP 전송 계층에서 조회·생성·revision 저장·계산 계약을 검증한다.
 * Args: t 테스트 복원 문맥. Returns: Promise<void>. Raises: 검증 실패.
 * Side Effects: 메모리 fetch 대역과 호출 기록만 사용한다.
 */
test('API uses encoded IDs, decimal strings, expected revisions and idempotent writes', async function requestContract(t) {
  const calls = []
  /** 요청을 기록하고 revision을 반환하는 메모리 서버이다.
   * Args: path API 주소, options 요청 옵션. Returns: Promise<Response>.
   * Raises: 없음. Side Effects: 메모리 호출 목록 변경.
   */
  async function respond(path, options) { calls.push({ path, options }); return new Response(JSON.stringify({ id: 'analysis/1', revision: calls.length })) }
  /** 테스트용 인증 토큰을 반환한다.
   * Args: 없음. Returns: 토큰 문자열. Raises: 없음. Side Effects: 없음.
   */
  function token() { return 'test-token' }
  /** 테스트용 세션 초기화 대역이다.
   * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음.
   */
  function clear() {}
  t.mock.method(globalThis, 'fetch', respond)
  const api = createApiClient({ token, clear })
  await decisionRequest(api, 'baselines', { year: 2026 })
  await decisionRequest(api, 'analyses')
  await decisionRequest(api, 'create', { title: '목표 검토', prompt: '근거 확인', masterId: 'm1', targetProfitWon: '9007199254740993.123456', protectedCategories: ['HEADCOUNT'] })
  await decisionRequest(api, 'load', { id: 'analysis/1' })
  const saved = await decisionRequest(api, 'save', { id: 'analysis/1', expectedRevision: 4, candidates: [] })
  await decisionRequest(api, 'run', { id: saved.id, expectedRevision: saved.revision })
  assert.equal(calls[0].path, '/api/v1/decision-agent/baselines?year=2026')
  assert.equal(calls[1].path, '/api/v1/decision-agent/analyses')
  assert.equal(JSON.parse(calls[2].options.body).targetProfitWon, '9007199254740993.123456')
  assert.equal(calls[3].path, '/api/v1/decision-agent/analyses/analysis%2F1')
  assert.equal(calls[4].options.method, 'PUT')
  assert.deepEqual(JSON.parse(calls[4].options.body), { expectedRevision: 4, candidates: [] })
  assert.equal(calls[5].path, '/api/v1/decision-agent/analyses/analysis%2F1/runs')
  assert.deepEqual(JSON.parse(calls[5].options.body), { expectedRevision: 5 })
  for (const index of [2, 4, 5]) assert.ok(calls[index].options.headers.get('Idempotency-Key'))
})
