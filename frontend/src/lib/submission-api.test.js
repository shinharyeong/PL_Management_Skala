import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeSubmission, normalizeOrgReviewPage, normalizeDiagnostics, normalizeUploadRequest } from './submission-api.js'

test('current submission DTO retains tokens, file and period; only server canSubmit enables submit', () => {
  const raw = { id: '9', kind: 'MONTHLY_ACTUAL', period: '2026.11', status: 'DRAFT', stateToken: 'latest', file: { name: 'D100_2026-11.xlsx' }, organization: { id: '3' }, parsingJob: { status: 'SUCCEEDED', attemptNo: 2 }, validation: { canSubmit: false, errorCount: 0 } }
  const result = normalizeSubmission(raw)
  assert.equal(result.year, 2026); assert.equal(result.month, 11); assert.equal(result.stateToken, 'latest')
  assert.equal(result.originalFilename, raw.file.name); assert.equal(result.readiness.canSubmit, false)
  assert.equal(result.parsing.retryAllowed, false)
  assert.equal(normalizeSubmission({ ...raw, validation: { canSubmit: true } }).readiness.canSubmit, true)
})

test('review hierarchy preserves exact amounts, IDs and tokens without Number conversion', () => {
  const page = normalizeOrgReviewPage({ progress: { answered: 0, required: 1 }, groups: [{ group: 'REVENUE', months: [{ month: 11, scenario: 'ACTUAL', items: [{ reviewItemId: '7', name: '매출', current: '9007199254740993.123', baseline: null, stateToken: 'item-token' }] }] }] })
  assert.equal(page.items[0].currentAmount, '9007199254740993.123'); assert.equal(page.items[0].baselineAmount, null)
  assert.equal(page.items[0].id, '7'); assert.equal(page.items[0].month, 11); assert.equal(page.items[0].stateToken, 'item-token')
})

test('request upload checks own selected target and pending submission', () => {
  const actor = { organization: { id: '3' } }
  const detail = { request: { id: '2', status: 'OPEN' }, targets: [{ organization: { id: '3' }, selected: true, latestSubmission: null }] }
  assert.equal(normalizeUploadRequest(detail, actor).myWorkflow.canUpload, true)
  assert.equal(normalizeUploadRequest(detail, { organization: { id: '4' } }).myWorkflow.canUpload, false)
  detail.targets[0].latestSubmission = { id: '8', status: 'PENDING' }
  assert.equal(normalizeUploadRequest(detail, actor).myWorkflow.canUpload, false)
})

test('diagnostics keeps source and next action, with no invented pagination', () => {
  const page = normalizeDiagnostics({ items: [{ sourceCell: '매출!C9', rule: '금액 누락', guide: '원본을 수정해 주세요.' }] })
  assert.equal(page.items[0].sourceCell, '매출!C9'); assert.equal(page.items[0].guide, '원본을 수정해 주세요.'); assert.equal(page.nextCursor, null)
})
