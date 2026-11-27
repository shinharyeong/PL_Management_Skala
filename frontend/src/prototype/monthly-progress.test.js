import { test } from 'node:test'
import assert from 'node:assert/strict'
import { monthlyProgress, monthlyOrganizationStatus } from './monthly-progress.js'

test('통합 상태는 시트·재제출·소명 회신 대기와 경영기획 검토, 제출본 승인을 구분한다', () => {
  assert.equal(monthlyOrganizationStatus({monthlyRequested:false}), '요청 필요')
  assert.equal(monthlyOrganizationStatus({monthlyRequested:true}), '회신 대기')
  assert.equal(monthlyOrganizationStatus({latestSubmission:{status:'REJECTED'}}), '회신 대기')
  assert.equal(monthlyOrganizationStatus({latestSubmission:{status:'PENDING'},explanationStatus:'소명 진행중'}), '회신 대기')
  for(const explanationStatus of ['소명 회신','검토 중','소명 승인 완료'])
    assert.equal(monthlyOrganizationStatus({latestSubmission:{status:'PENDING'},explanationStatus}), '검토 필요')
  assert.equal(monthlyOrganizationStatus({latestSubmission:{status:'APPROVED'}}), '승인 완료')
})

test('미요청 조직은 요청 필요로 표시하고 발송일·기한은 비운다', () => {
  const result = monthlyProgress({ selected: false, displayStatus: 'NOT_REQUESTED', requestedAt: '2026-11-24T07:00:00Z', dueAt: '2026-11-30T09:00:00Z' })
  assert.equal(result.submissionStatus, '요청 필요')
  assert.equal(result.monthlyRequested, false)
  assert.equal(result.requestedAt, null)
  assert.equal(result.requestDue, null)
})

test('16개 요청 조직과 미선택 사업부13을 회신 대기 집계에서 구분한다', () => {
  const rows = Array.from({ length: 17 }, (_, index) => monthlyProgress({ selected: index !== 12, displayStatus: 'WAITING' }))
  assert.equal(rows.filter(row => row.monthlyRequested).length, 16)
  assert.equal(rows.filter(row => row.monthlyRequested && row.submissionStatus === '회신 대기').length, 16)
  assert.equal(rows[12].submissionStatus, '요청 필요')
})

test('요청 후 제출한 조직은 제출 상태와 제출본을 유지한다', () => {
  const submission = { id: '123', label: 'v1.00' }
  const result = monthlyProgress({ selected: true, displayStatus: 'SUBMITTED', latestSubmission: submission })
  assert.equal(result.submissionStatus, '제출 완료')
  assert.equal(result.latestSubmission, submission)
  assert.equal(monthlyProgress().submissionStatus, '요청 필요')
})
