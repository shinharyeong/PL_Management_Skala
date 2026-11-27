import test from 'node:test'
import assert from 'node:assert/strict'
import {annualProgress} from './home-annual-progress.js'
test('Annual progress separates submission, approval and confirmed master',()=>{
 const target=status=>({selected:true,latestSubmission:{status}})
 const steps=annualProgress({targets:[target('PENDING'),target('APPROVED'),target('REJECTED'),{selected:false}]},{state:'PENDING',items:[]})
 assert.deepEqual(steps.map(s=>[s.done,s.total]),[[1,1],[2,3],[1,3],[0,1]])
 assert.equal(annualProgress(null,{state:'CONFIRMED',items:[{id:'1'}]})[3].done,1)
 assert.deepEqual(annualProgress(null,null).map(s=>s.done),[0,0,0,0])
})
