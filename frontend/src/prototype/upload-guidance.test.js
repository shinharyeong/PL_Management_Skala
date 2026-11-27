import test from 'node:test'
import assert from 'node:assert/strict'
import { uploadGuidance } from './upload-guidance.js'

test('filename guidance uses the requested year rather than this year or the response deadline', () => {
  const actor={orgCode:'F20'}
  assert.match(uploadGuidance(actor,{title:'2027년 1월 실적·추정 제출'}),/F20_2027-01\.xlsx/)
  assert.match(uploadGuidance(actor,{title:'2027년 연간 계획 제출',dueAt:'2026-11-30T00:00:00Z'}),/F20_2027-PLAN\.xlsx/)
  assert.match(uploadGuidance(actor,{year:2026,title:'11월 실적·추정 제출'}),/F20_2026-11\.xlsb/)
  assert.doesNotMatch(uploadGuidance(actor,{title:'2026년 11월 실적·추정 제출'}),/연간/)
})
