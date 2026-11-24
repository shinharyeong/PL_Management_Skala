// Read-only integration check against an isolated local profile. Never prints codes or tokens.
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {createPinia} from 'pinia'
const base=process.env.API_CHECK_BASE || 'http://127.0.0.1:14175'
assert.ok(['127.0.0.1','localhost','[::1]'].includes(new URL(base).hostname),'local server required')
const log=process.env.API_CHECK_AUTH_LOG
assert.ok(log,'API_CHECK_AUTH_LOG must point at the isolated local-profile server log')
const server=await createServer({server:{middlewareMode:true,hmr:false,ws:false},appType:'custom'})
const originalFetch=globalThis.fetch
globalThis.fetch=(path,options)=>originalFetch(new URL(path,base),options)
try{
  const {usePrototypeStore}=await server.ssrLoadModule('/src/prototype/live-store.js')
  const store=usePrototypeStore(createPinia())
  await assert.rejects(store.requestCode('not-registered@sk.com'));console.log('PASS unregistered email rejected')
  const offset=(await readFile(log)).length
  const challenge=await store.requestCode('kim.plan@sk.com')
  assert.ok(challenge.expiresAt && challenge.resendAvailableAt)
  const tail=(await readFile(log)).subarray(offset).toString()
  const code=/email=kim\.plan@sk\.com code=(\d{6})/.exec(tail)?.[1]
  assert.ok(code,'local auth code emitted')
  await store.login(challenge.email,code);assert.equal(store.actor.role,'planner');console.log('PASS real code login and role mapping')
  await store.refreshBadge();await store.loadMonthly();assert.ok(store.organizations.length);console.log('PASS active organization and current-month request DTOs')
  if(store.monthlyRequest){await store.loadGap(store.activeOrg);const items=store.items[store.activeOrg];assert.ok(Array.isArray(items));for(const row of items){assert.ok(row.id && row.stateToken);assert.ok(row.currentAmount===null||typeof row.currentAmount==='string')}console.log('PASS hierarchy IDs, concurrency tokens and decimal strings')}
  if(process.argv.includes('--apply')){
    assert.equal(store.monthlyRequest?.request.message,'합성 API 검증 전용 요청','only verifier-owned synthetic request may be changed')
    const row=store.items[store.activeOrg].find(r=>r.status==='검토 필요')
    assert.ok(row,'synthetic item available for selection')
    const conditions={type:'실적 vs 계획',baseVersion:'fixed',compareVersion:'latest',rateEnabled:false,amountEnabled:false}
    store.monthlySession(store.activeOrg).applied=conditions
    await store.addToCart(store.activeOrg,[row],conditions)
    assert.ok(store.workspaces.some(w=>w.org===store.activeOrg && w.count>0))
    assert.ok(store.carts[store.activeOrg].some(r=>r.id===row.id))
    console.log('PASS explicit SELECT creates organization workspace and cell count')
    const due=new Date(Date.now()+86400000).toISOString().slice(0,16)
    const orgId=store.activeOrg
    await store.sendExplanation(orgId,'합성 프론트 연결 검증: 선택 항목의 차이를 확인해 주세요.',due)
    assert.equal(store.carts[orgId].length,0)
    assert.equal(store.items[orgId].find(r=>r.id===row.id).status,'소명 진행중')
    console.log('PASS explicit explanation request, refreshed server state and workspace removal')
  }
  await store.loadNotifications();assert.ok(Array.isArray(store.notificationPage.groups));console.log('PASS grouped notifications and badge')
  for(const period of ['MONTH','YEAR'])for(const path of ['status','pnl','op-drivers','divisions','waterfall']){
    const r=await store.api(`/api/v1/home/${path}?period=${period}&year=2026&month=11`);assert.ok(r);if(r.dataStatus)assert.ok(['NONE','CONFIRMED','PROVISIONAL'].includes(r.dataStatus))
  }
  console.log('PASS latest home API, both periods and explicit no-data state')
  await store.logout();assert.equal(store.actor,null);console.log('PASS idempotent logout and 204 response')
}finally{globalThis.fetch=originalFetch;await server.close()}
