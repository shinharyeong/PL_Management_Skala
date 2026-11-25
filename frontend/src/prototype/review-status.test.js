import test from 'node:test'
import assert from 'node:assert/strict'
import {submissionReviewStatus,annualTargetStatus,annualProgressRows} from './review-status.js'
test('submission review result distinguishes pending, approved, rejected and superseded replies',()=>{
 assert.deepEqual(submissionReviewStatus({status:'PENDING'}),{label:'검토 대기',tone:'amber',done:false})
 assert.equal(submissionReviewStatus({status:'APPROVED'}).done,true)
 assert.equal(submissionReviewStatus({status:'REJECTED'}).label,'재제출 요청')
 assert.equal(submissionReviewStatus({status:'REJECTED',displayStatus:'SUPERSEDED'}).label,'이전 제출본 · 재제출됨')
 assert.equal(submissionReviewStatus(null).done,false)
})
test('annual status distinguishes unsent from dispatched requests without a submission',()=>{
 assert.equal(annualTargetStatus({selected:false,displayStatus:'WAITING'}).label,'요청 필요')
 assert.equal(annualTargetStatus({selected:true,displayStatus:'WAITING'}).label,'회신 대기')
 assert.equal(annualTargetStatus({selected:true,displayStatus:'OVERDUE'}).label,'기한 경과')
})
test('annual submission review, approval and resubmission statuses remain distinct',()=>{
 for(const status of ['PENDING','APPROVED','REJECTED']){
  const submission={status}
  assert.deepEqual(annualTargetStatus({selected:true,latestSubmission:submission}),submissionReviewStatus(submission))
 }
})
test('annual progress shows request-needed organizations before dispatch and omits unsent dates',()=>{
 const organizations=[{id:'13',name:'사업부13'}]
 const rows=annualProgressRows(organizations,undefined)
 assert.equal(rows.length,1)
 assert.equal(annualTargetStatus(rows[0]).label,'요청 필요')
 const unsent=annualProgressRows(organizations,[{organization:organizations[0],selected:false,requestedAt:'old',dueAt:'old'}])[0]
 assert.equal(unsent.requestedAt,null)
 assert.equal(unsent.dueAt,null)
 assert.deepEqual(annualProgressRows(organizations,[]),[])
})
