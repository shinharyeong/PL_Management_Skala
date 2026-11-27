import test from 'node:test'
import assert from 'node:assert/strict'
import {closingStep} from './home-closing.js'
test('closing distinguishes single tasks, partial progress, completion and absent targets',()=>{
 assert.deepEqual(closingStep({done:1,total:1}),{state:'complete',label:'완료',value:'완료',percent:100})
 assert.equal(closingStep({done:0,total:1}).value,'미완료')
 assert.equal(closingStep({done:7,total:10}).state,'progress')
 assert.equal(closingStep({done:7,total:10}).percent,70)
 assert.equal(closingStep({done:0,total:0}).value,'대기')
 assert.equal(closingStep({done:11,total:10}).percent,100)
})
