import test from 'node:test'
import assert from 'node:assert/strict'
import {canAnswerExplanation as editable} from './explanation-editing.js'
test('submitted requests can be answered in the first or any later round, not only round 2+',()=>{
  for(const round of [1,2,3])assert.equal(editable('ORG_MANAGER','PENDING',{round,verdict:'PENDING',requestedAt:'2026-11-25T06:34:00Z'}),true)
})
test('an unrequested initial answer, inherited/approved answer and wrong role remain locked',()=>{
  const item={round:1,verdict:'PENDING',requestedAt:'2026-11-25T06:34:00Z'}
  assert.equal(editable('ORG_MANAGER','PENDING',{...item,requestedAt:null}),false)
  assert.equal(editable('ORG_MANAGER','PENDING',{...item,inheritedFrom:{explanationId:'1'}}),false)
  assert.equal(editable('ORG_MANAGER','PENDING',{...item,verdict:'ACCEPTED'}),false)
  assert.equal(editable('PLANNER','PENDING',item),false)
  assert.equal(editable('ORG_MANAGER','APPROVED',item),false)
  assert.equal(editable('ORG_MANAGER','DRAFT',{...item,requestedAt:null}),true)
})
