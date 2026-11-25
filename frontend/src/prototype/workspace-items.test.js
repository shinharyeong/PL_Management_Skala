import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia } from 'pinia'
import { usePrototypeStore } from './live-store.js'
import { syncWorkspace } from './workspace-items.js'

test('eight requested items stay counted, reply/re-request stay active, and only approval/exclusion reduce the badge',()=>{
  const store=usePrototypeStore(createPinia())
  const selected=Array.from({length:8},(_,i)=>({id:String(i+1),status:'검토 완료',conditions:{rate:5}}))
  const fresh=selected.map(item=>({...item,status:'소명 진행중',stateToken:'fresh'}))
  store.carts.F10=syncWorkspace(selected,fresh,{})
  assert.equal(store.workspaces[0].count,8)
  fresh[0].status='재검토 필요'
  store.carts.F10=syncWorkspace(store.carts.F10,fresh,{})
  assert.equal(store.workspaces[0].count,8)
  fresh[0].status='소명 승인 완료';fresh[1].status='제외됨'
  store.carts.F10=syncWorkspace(store.carts.F10,fresh,{})
  assert.equal(store.workspaces[0].count,6)
  assert.equal(store.carts.F10[0].conditions.rate,5)
  fresh.forEach(item=>item.status='소명 승인 완료')
  store.carts.F10=syncWorkspace(store.carts.F10,fresh,{})
  assert.deepEqual(store.workspaces,[])
})

test('server requests can restore a lost tab while merely selected calculator rows cannot create one',()=>{
  const restored=syncWorkspace([],[{id:'1',status:'소명 진행중'},{id:'2',status:'재검토 필요'},{id:'3',status:'검토 완료'},{id:'4',status:'소명 승인 완료'}],{rate:10})
  assert.deepEqual(restored.map(item=>item.id),['1','2'])
  assert.ok(restored.every(item=>item.restored))
})

test('sending an explanation retains the workspace and never sends already waiting or completed items again',async()=>{
  const store=usePrototypeStore(createPinia()),calls=[]
  store.carts.F10=[{id:'1',status:'검토 완료'},{id:'2',status:'소명 진행중'},{id:'3',status:'소명 승인 완료'}]
  store.reviewAction=async(org,rows,action)=>{
    calls.push({ids:rows.map(item=>item.id),action})
    store.carts[org]=store.carts[org].map(item=>item.id==='1'?{...item,status:'소명 진행중'}:item)
  }
  store.loadMonthly=async()=>{}
  store.refreshBadge=async()=>{}
  await store.sendExplanation('F10','차이 사유를 확인해 주세요.','2026-12-01T18:00')
  assert.deepEqual(calls,[{ids:['1'],action:'REQUEST_EXPLANATION'}])
  assert.equal(store.workspaces[0].count,2)
  await assert.rejects(store.sendExplanation('F10','중복 요청','2026-12-01T18:00'),/새로 소명을 요청할 항목/)
  assert.equal(calls.length,1)
})
