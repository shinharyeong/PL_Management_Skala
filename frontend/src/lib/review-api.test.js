import test from 'node:test'
import assert from 'node:assert/strict'
import { reviewEvidencePath, reviewActionBody, baselineLabel } from './review-api.js'
import { createApiClient } from '../prototype/live-api.js'

/** 소명 항목과 제출본을 혼동하지 않고 명세의 경로·명령 본문을 전송하는지 검증한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 메모리 fetch 대역을 복원한다.
 */
test('review evidence and explanation requests use the actual review ID and current token', async function reviewContract() {
  const original = globalThis.fetch, calls = []
  const api = createApiClient({ token: () => 'session', clear: () => {} })
  try {
    globalThis.fetch = async function response(path, options) {
      /** Args: path·options HTTP 입력. Returns: 모의 응답. Raises: 없음. Side Effects: 메모리 호출 기록. */
      calls.push({ path, options })
      return new Response(JSON.stringify({ items: [{ sourceSheet: '매출', sourceCell: 'C9', amount: '9007199254740993.12' }] }))
    }
    const source = await api(reviewEvidencePath('71'))
    assert.equal(calls[0].path, '/api/v1/review-items/71/evidence')
    assert.equal(source.items[0].amount, '9007199254740993.12')
    const body = reviewActionBody('REQUEST_EXPLANATION', [{ id: '71', stateToken: 'new-token' }], { dueAt: '2026-12-01T09:00:00+09:00', message: ' 차이 원인을 설명해 주세요. ' })
    await api('/api/v1/review-items/actions', { method: 'POST', body })
    assert.deepEqual(JSON.parse(calls[1].options.body), { action: 'REQUEST_EXPLANATION', items: [{ reviewItemId: '71', expectedStateToken: 'new-token' }], dueAt: '2026-12-01T09:00:00+09:00', message: '차이 원인을 설명해 주세요.' })
    assert.ok(calls[1].options.headers.get('Idempotency-Key'))
  } finally { globalThis.fetch = original }
})

/** 제외 사유와 현재 항목 토큰을 필수로 유지하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('exclusions require a reason and never invent missing review tokens', function exclusionContract() {
  const rows = [{ id: '71', stateToken: 'current' }]
  assert.throws(() => reviewActionBody('EXCLUDE', rows), /제외 사유/)
  assert.throws(() => reviewActionBody('SELECT', [{ id: '71' }]), /최신 상태/)
  assert.throws(() => reviewActionBody('SELECT', [...rows, ...rows]), /최신 상태/)
  assert.deepEqual(reviewActionBody('EXCLUDE', rows, { note: ' 일회성 항목으로 제외 ' }), { action: 'EXCLUDE', items: [{ reviewItemId: '71', expectedStateToken: 'current' }], note: '일회성 항목으로 제외' })
  assert.throws(() => reviewEvidencePath(undefined), /소명 항목/)
})

/** 연간 계획·반려·승인·기준 없음의 서버 구분을 사용자에게 유지한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('January annual-plan baseline is not mislabeled as a previous month', function baselineContract() {
  assert.equal(baselineLabel({ kind: 'ANNUAL_PLAN', label: 'plan2026.01' }), '확정 연간 계획 · plan2026.01')
  assert.equal(baselineLabel({ kind: 'REJECTED', label: 'v1.02' }), '직전 반려본 · v1.02')
  assert.equal(baselineLabel({ kind: 'NONE' }), '비교 기준 없음')
})
