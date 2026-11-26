import test from 'node:test'
import assert from 'node:assert/strict'
import {cellChange,loadCellVersionEvidence} from './cell-version-data.js'

test('difference uses the last two displayed versions without losing integer precision',()=>{
  assert.deepEqual(cellChange({values:['1','9007199254740993','9007199254740994']},3),{diff:'1',rate:'0.00'})
  assert.deepEqual(cellChange({values:['190580000','220580000']},2),{diff:'30000000',rate:'15.74'})
  assert.deepEqual(cellChange({values:['20','10']},2),{diff:'-10',rate:'-50.00'})
})
test('one version and missing cells are unavailable; zero baseline has an amount but no percentage',()=>{
  assert.deepEqual(cellChange({values:['20']},1),{diff:null,rate:null})
  assert.deepEqual(cellChange({values:[null,'20']},2),{diff:null,rate:null})
  assert.deepEqual(cellChange({values:['0','20']},2),{diff:'20',rate:null})
})
test('each version keeps its exact source text, including line breaks and spaces',async()=>{
  const detail={id:'new-item',path:'매출 › Data › 11월 실적',month:11,scenario:'ACTUAL'}
  const raw='  계약 A\n단가 × 수량  '
  const result=await loadCellVersionEvidence({detail,columns:[{submissionId:'old'},{submissionId:'new'}],currentSubmission:{id:'new'},evidence:{items:[{sourceSheet:'매출',sourceCell:'AU7',description:raw}]},api:async path=>{
    if(path==='/api/v1/submissions/old')return {id:'old'}
    if(path.startsWith('/api/v1/review-items?'))return {groups:[{months:[{month:11,scenario:'ACTUAL',items:[{name:'Data',reviewItemId:'old-item'}]}]}]}
    if(path==='/api/v1/review-items/old-item')return {...detail,id:'old-item'}
    if(path.endsWith('/old-item/evidence'))return {items:[{sourceSheet:'매출',sourceCell:'AU7',description:'이전 계약 원문'}]}
    throw Error(path)
  }})
  assert.deepEqual(result.new.texts['매출!AU7'],[raw])
  assert.deepEqual(result.old.texts['매출!AU7'],['이전 계약 원문'])
})
test('failed historical lookup never inherits latest basis; blank latest text remains blank',async()=>{
  const result=await loadCellVersionEvidence({detail:{path:'매출 › Data › 11월 실적',month:11,scenario:'ACTUAL'},columns:[{submissionId:'old'},{submissionId:'new'}],currentSubmission:{id:'new'},evidence:{items:[{sourceSheet:'매출',sourceCell:'AU7',description:''}]},api:async()=>{throw Error('unavailable')}})
  assert.equal(result.old.state,'unavailable')
  assert.deepEqual(result.old.texts,{})
  assert.equal(result.new.state,'ready')
  assert.deepEqual(result.new.texts,{})
})
