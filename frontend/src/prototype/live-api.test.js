import test from 'node:test'
import assert from 'node:assert/strict'
import {createApiClient,flattenGapTree} from './live-api.js'
import {sum,subtract,percent,compare} from './exact-money.js'

test('decimal totals, differences and ratios preserve large fractional amounts and missing values',()=>{
  assert.equal(sum(['9007199254740993.10','0.02']),'9007199254740993.12')
  assert.equal(subtract('9007199254740993.12','9007199254740993.10'),'0.02')
  assert.equal(compare('-1.02','-1.01'),-1)
  assert.equal(percent('-100','-95'),'5.00')
  assert.equal(percent('0','1'),null)
  assert.equal(sum(['1',null]),null)
})
test('review tree retains separate review IDs, tokens and exact amounts for repeated report items',()=>{
  const rows=flattenGapTree([{reportItemId:'1',name:'매출',children:[{reportItemId:'2',name:'상품',children:[{reportItemId:'2',reviewItemId:'3',name:'10월 실적',plan:'9007199254740993.10',actual:'9007199254740993.12',stateToken:'a',tab:'SELECTED'},{reportItemId:'2',reviewItemId:'4',name:'11월 실적',plan:null,actual:'1',stateToken:'b',tab:'NEEDS_REVIEW'}]}]}],'실적 vs 계획')
  assert.deepEqual(rows.map(r=>r.id),['3','4']);assert.equal(rows[0].stateToken,'a');assert.equal(rows[0].baselineAmount,'9007199254740993.10');assert.equal(rows[1].baselineAmount,null);assert.equal(rows[0].status,'검토 완료')
})
test('command retry reuses idempotency key, logout accepts 204 and only current 401 clears credentials',async()=>{
  const previous=globalThis.fetch,calls=[];let token='first',cleared=0,fail=true
  const api=createApiClient({token:()=>token,clear:()=>{cleared++;token=''}})
  try{
    globalThis.fetch=async(path,options)=>{calls.push(options);return fail?new Response(JSON.stringify({detail:'충돌',code:'STATE_CONFLICT'}),{status:409}):new Response(null,{status:204})}
    await assert.rejects(api('/api/v1/auth/session',{method:'DELETE'}),e=>e.code==='STATE_CONFLICT');fail=false
    assert.equal(await api('/api/v1/auth/session',{method:'DELETE'}),null)
    assert.equal(calls[0].headers.get('Idempotency-Key'),calls[1].headers.get('Idempotency-Key'))
    assert.equal(calls[0].headers.get('Authorization'),'Bearer first')
    globalThis.fetch=async()=>{token='new';return new Response('{}',{status:401})}
    await assert.rejects(api('/api/v1/me'));assert.equal(cleared,0)
    globalThis.fetch=async()=>new Response('{}',{status:401})
    await assert.rejects(api('/api/v1/me'));assert.equal(cleared,1)
  }finally{globalThis.fetch=previous}
})
