import test from 'node:test'
import assert from 'node:assert/strict'
import { createMemoryHistory } from 'vue-router'
import { createPrototypeRouter } from './router.js'
import { createApiClient } from './live-api.js'
import { canApproveWork, dueAtKst, replyCandidateDraft, workCreatePayload, workReplyPayload, workflowRequest, workStateLabel, workDateText } from './decision-workflow-api.js'

/** 서버 UTC 시각과 빈 값이 한국 시간과 누락 안내로 표시되는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('workflow timestamps display explicit Korean local time', function koreanTime() {
  assert.equal(workDateText('2026-12-05T14:59:59.000Z'), '2026-12-05 23:59 KST')
  assert.equal(workDateText('2026-12-05T15:00:00.000Z'), '2026-12-06 00:00 KST')
  assert.equal(workDateText(null), '—')
  assert.equal(workDateText('invalid'), '날짜 확인 필요')
})

/** 가설 회신 초안이 확인되지 않은 금액·월을 채우지 않는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('hypothesis reply keeps amount and timing empty and uses server candidate identity', function hypothesisDraft() {
  const item = { candidateId: 'server-candidate', organizationId: '9007199254740993', title: '일정 확인 가설', sourceHypothesis: { category: 'REVENUE', amountWon: null, recognitionMonth: null } }
  const draft = replyCandidateDraft(item)
  assert.equal(draft.id, 'server-candidate')
  assert.equal(draft.organizationId, '9007199254740993')
  assert.equal(draft.amountEok, '')
  assert.equal(draft.recognitionMonth, null)
  assert.equal(draft.reviewed, false)
  assert.equal(draft.category, 'REVENUE')
})

/** 조직이 회신한 정밀 금액과 요청 조직·후보 ID만 서버에 전달하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('feasible replies preserve decimal precision but cannot mark their own evidence reviewed', function replyIdentity() {
  const item = { candidateId: 'server-candidate', organizationId: 'org-1', title: '계약 조건 확인', baseMonth: 11, revision: 3 }
  const candidate = replyCandidateDraft(item)
  Object.assign(candidate, { id: 'wrong-id', organizationId: 'wrong-org', amountEok: '90071992.54740993123456', recognitionMonth: 12, reviewed: true, category: 'PROCUREMENT' })
  const payload = workReplyPayload(item, { feasibility: 'FEASIBLE', candidate, note: ' 변경 계약 조건 확인 ', evidenceRef: ' 계약 C-01 ' })
  assert.equal(payload.expectedRevision, 3)
  assert.equal(payload.candidate.amountWon, '9007199254740993.123456')
  assert.equal(payload.candidate.id, 'server-candidate')
  assert.equal(payload.candidate.organizationId, 'org-1')
  assert.equal(payload.candidate.evidenceStatus, 'ASSUMPTION')
  assert.equal(payload.candidate.evidenceNote, '변경 계약 조건 확인')
  assert.equal(payload.candidate.evidenceRef, '계약 C-01')
  assert.equal(candidate.id, 'wrong-id')
})

/** 불가 회신에 임의 금액을 붙이지 않고 사유와 근거를 요구하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('not feasible replies have no candidate and still require evidence', function infeasibleReply() {
  const payload = workReplyPayload({ revision: 7 }, { feasibility: 'NOT_FEASIBLE', candidate: null, note: '고객 검수 일정 불가', evidenceRef: '검수 일정표' })
  assert.equal(payload.candidate, null)
  assert.equal(payload.feasibility, 'NOT_FEASIBLE')
  /** 근거 없는 불가 회신을 검증한다.
   * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
   */
  function missingEvidence() { workReplyPayload({ revision: 7 }, { feasibility: 'NOT_FEASIBLE', candidate: null, note: '불가', evidenceRef: '' }) }
  assert.throws(missingEvidence, /근거 위치/)
})

/** 가설·후보 요청이 서로 다른 참조를 사용하며 한국 시간 기한을 보존하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('request drafts use one source and an explicit KST due date', function requestDraft() {
  const analysis = { revision: 8 }
  const form = { message: ' 반영 시점을 확인해 주세요 ', dueDate: '2026-12-01' }
  assert.deepEqual(workCreatePayload(analysis, { id: 'hyp-1', kind: 'hypothesis' }, form), { expectedAnalysisRevision: 8, candidateId: null, hypothesisId: 'hyp-1', message: '반영 시점을 확인해 주세요', dueAt: '2026-12-01T14:59:59.000Z' })
  assert.equal(workCreatePayload(analysis, { id: 'candidate-1', kind: 'candidate' }, form).hypothesisId, null)
  assert.equal(dueAtKst('2028-02-29'), '2028-02-29T14:59:59.000Z')
  /** 존재하지 않는 날짜의 자동 보정을 거부한다.
   * Args: 없음. Returns: 없음. Raises: 입력 오류. Side Effects: 없음.
   */
  function invalidDate() { dueAtKst('2026-02-30') }
  assert.throws(invalidDate, /유효한/)
})

/** 추진 기록은 채택된 요청의 최신 계산에 포함된 근거 검토 후보에 한정되는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('approval requires accepted work and a currently included reviewed effect', function approvalReadiness() {
  const item = { candidateId: 'c1', state: 'ACCEPTED' }
  assert.equal(canApproveWork({ result: null }, item), false)
  assert.equal(canApproveWork({ result: { effects: [{ candidateId: 'c1', included: true, reviewed: false }] } }, item), false)
  assert.equal(canApproveWork({ result: { effects: [{ candidateId: 'c1', included: false, reviewed: true }] } }, item), false)
  assert.equal(canApproveWork({ result: { effects: [{ candidateId: 'c1', included: true, reviewed: true }] } }, item), true)
  assert.equal(canApproveWork({ result: { effects: [{ candidateId: 'c1', included: true, reviewed: true }] } }, { ...item, state: 'REPLIED' }), false)
  assert.equal(workStateLabel('COMPLETED'), '후속 기준 연결')
})

/** 목표·조사·요청·회신·검토·결정·진척 경로와 버전·멱등 헤더를 검증한다.
 * Args: t fetch 복원 문맥. Returns: Promise<void>. Raises: 검증 실패.
 * Side Effects: 합성 메모리 fetch와 호출 기록만 사용한다.
 */
test('workflow calls preserve separate work and analysis revisions and use idempotent writes', async function workflowContract(t) {
  const calls = []
  /** 호출을 기록하는 메모리 HTTP 응답이다.
   * Args: path 경로, options 옵션. Returns: Promise<Response>. Raises: 없음.
   * Side Effects: 호출 기록만 변경한다.
   */
  async function respond(path, options) { calls.push({ path, options }); return new Response(JSON.stringify({ items: [] })) }
  /** 테스트 인증 토큰을 반환한다.
   * Args: 없음. Returns: 문자열. Raises: 없음. Side Effects: 없음.
   */
  function token() { return 'test-token' }
  /** 세션 해제 대역이다.
   * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음.
   */
  function clear() {}
  t.mock.method(globalThis, 'fetch', respond)
  const api = createApiClient({ token, clear })
  await workflowRequest(api, 'goal', { body: { prompt: '목표 확인', year: 2026 } })
  await workflowRequest(api, 'research', { analysisId: 'analysis/1' })
  await workflowRequest(api, 'discover', { analysisId: 'analysis/1', body: { expectedRevision: 4 } })
  await workflowRequest(api, 'items', { analysisId: 'analysis/1' })
  await workflowRequest(api, 'items')
  await workflowRequest(api, 'create', { analysisId: 'analysis/1', body: { expectedAnalysisRevision: 4, candidateId: null, hypothesisId: 'h1' } })
  const body = { expectedRevision: 8, expectedAnalysisRevision: 5, note: '검토 기록' }
  for (const operation of ['issue', 'reply', 'review', 'decision', 'progress', 'cancel']) await workflowRequest(api, operation, { itemId: 'item/2', body })
  await workflowRequest(api, 'followup', { itemId: 'item/2' })
  assert.equal(calls[0].path, '/api/v1/decision-agent/goal-proposals')
  assert.equal(calls[1].path, '/api/v1/decision-agent/analyses/analysis%2F1/research')
  assert.equal(calls[2].path, '/api/v1/decision-agent/analyses/analysis%2F1/discover')
  assert.equal(calls[3].path, '/api/v1/decision-agent/work-items?analysisId=analysis%2F1')
  assert.equal(calls[4].path, '/api/v1/decision-agent/work-items')
  assert.equal(calls[7].path, '/api/v1/decision-agent/work-items/item%2F2/replies')
  assert.equal(calls[8].path, '/api/v1/decision-agent/work-items/item%2F2/reviews')
  assert.equal(calls[9].path, '/api/v1/decision-agent/work-items/item%2F2/decisions')
  assert.equal(calls[12].path, '/api/v1/decision-agent/work-items/item%2F2/followup-baselines')
  for (const call of calls) if (call.options.method !== 'GET') { assert.deepEqual(call.options.headers.get('Authorization'), 'Bearer test-token'); assert.ok(call.options.headers.get('Idempotency-Key')) }
  assert.deepEqual(JSON.parse(calls[11].options.body), body)
})

/** 조직 전용 업무와 경영기획 분석의 역할별 라우트 경계를 검증한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 메모리 라우터만 변경한다.
 */
test('organization and planner routes keep separate workspaces', async function workflowRoutes() {
  const store = { actor: { role: 'org' }, live: true, carts: {}, items: {} }
  const router = createPrototypeRouter(createMemoryHistory(), store)
  await router.push('/org-improvements')
  assert.equal(router.currentRoute.value.name, 'org-improvements')
  await router.push('/decision-agent')
  assert.equal(router.currentRoute.value.name, 'org-requests')
  store.actor = { role: 'planner' }
  await router.push('/decision-agent')
  assert.equal(router.currentRoute.value.name, 'decision-agent')
  await router.push('/org-improvements')
  assert.equal(router.currentRoute.value.name, 'home')
})
