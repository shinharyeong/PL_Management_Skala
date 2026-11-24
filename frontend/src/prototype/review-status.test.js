import test from 'node:test'
import assert from 'node:assert/strict'
import {submissionReviewStatus} from './review-status.js'
test('submission review result distinguishes pending, approved, rejected and superseded replies',()=>{
 assert.deepEqual(submissionReviewStatus({status:'PENDING'}),{label:'검토 대기',tone:'amber',done:false})
 assert.equal(submissionReviewStatus({status:'APPROVED'}).done,true)
 assert.equal(submissionReviewStatus({status:'REJECTED'}).label,'재제출 요청')
 assert.equal(submissionReviewStatus({status:'REJECTED',displayStatus:'SUPERSEDED'}).label,'이전 제출본 · 재제출됨')
 assert.equal(submissionReviewStatus(null).done,false)
})
