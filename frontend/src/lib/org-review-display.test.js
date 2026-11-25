import test from 'node:test'
import assert from 'node:assert/strict'
import {resubmissionChange,readReviewDraft,saveReviewDraft,clearReviewDraft} from './org-review-display.js'
test('correction compares rejected submission and keeps missing values unavailable',()=>{
  assert.deepEqual(resubmissionChange('1240','1150'),{diff:'90',rate:'7.8'})
  assert.deepEqual(resubmissionChange('72',null),{diff:null,rate:null})
  assert.deepEqual(resubmissionChange('72','0'),{diff:'72',rate:null})
  assert.deepEqual(resubmissionChange('90','-100'),{diff:'190',rate:'190.0'})
  assert.deepEqual(resubmissionChange('9007199254740993.01','9007199254740993.00'),{diff:'0.01',rate:'0.0'})
  assert.deepEqual(resubmissionChange('1','3'),{diff:'-2',rate:'-66.7'})
})
test('drafts preserve empty edits and isolate server revisions',()=>{
  const key='actor:submission:item:token'
  saveReviewDraft(key,''); assert.equal(readReviewDraft(key,'server'),'')
  saveReviewDraft(key,'작성 중');assert.equal(readReviewDraft(key),'작성 중')
  assert.equal(readReviewDraft(key+'-new','new server'),'new server')
  clearReviewDraft(key);assert.equal(readReviewDraft(key,'server'),'server')
})
