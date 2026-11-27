import test from 'node:test'
import assert from 'node:assert/strict'
import { monthlyReviewItems, monthlyExplanationStatus } from './monthly-review-progress.js'
import { syncWorkspace } from './workspace-items.js'

test('monthly progress includes estimate replies and preserves a workspace comparison without duplicate cells', () => {
  const actual={id:'a',status:'소명 승인 완료',conditions:{type:'실적 vs 계획'}}
  const plan={id:'e',status:'재검토 필요',currentAmount:'120',baselineAmount:'100',conditions:{type:'추정 vs 계획'}}
  const previous={...plan,baselineAmount:'110',conditions:{type:'추정 vs 추정'}}
  const saved=[{...previous,conditions:{type:'추정 vs 추정',rate:'5'}}]
  const rows=monthlyReviewItems({'실적 vs 계획':[actual],'추정 vs 계획':[plan],'추정 vs 추정':[previous]},saved)
  assert.equal(rows.length,2)
  assert.equal(rows.find(row=>row.id==='e').baselineAmount,'110')
  assert.equal(monthlyExplanationStatus(rows),'소명 회신')
  const restored=syncWorkspace([],rows,{type:'실적 vs 계획',rate:'5'})
  assert.equal(restored[0].conditions.type,'추정 vs 추정')
  assert.equal(restored[0].id,'e')
})

test('monthly progress distinguishes waiting, received, unfinished and completed reviews', () => {
  const items=(...states)=>states.map(status=>({status}))
  assert.equal(monthlyExplanationStatus(items('소명 진행중','소명 승인 완료')),'소명 진행중')
  assert.equal(monthlyExplanationStatus(items('검토 필요','소명 승인 완료')),'검토 중')
  assert.equal(monthlyExplanationStatus(items('소명 승인 완료','제외됨')),'소명 승인 완료')
  assert.equal(monthlyExplanationStatus([]),'대상 없음')
})
