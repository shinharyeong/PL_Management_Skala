import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia } from 'pinia'
import { usePrototypeStore } from './live-store.js'
import { seoulPeriod } from './domain.js'

const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
const turn = () => new Promise(resolve => setImmediate(resolve))
const gap = (id, submissionId) => ({ submission: submissionId ? { id: submissionId } : null,
  tree: [{ reviewItemId: id, name: '11월 실적', plan: '100', actual: '120', tab: 'NEEDS_REVIEW' }] })

test('successful login and logout still finish their UI action after changing the credential', async () => {
  const store = usePrototypeStore(createPinia())
  assert.equal(await store.run(async () => { store.token = 'signed-in' }), true)
  assert.equal(store.busy, false)
  assert.equal(await store.run(async () => store.clear()), true)
  assert.equal(store.busy, false)
})

test('separate app stores send their own credentials', async () => {
  const previous = globalThis.fetch, tokens = []
  try {
    globalThis.fetch = async (path, options) => { tokens.push(options.headers.get('Authorization')); return new Response('{}') }
    const first = usePrototypeStore(createPinia()), second = usePrototypeStore(createPinia())
    first.token = 'first'; second.token = 'second'
    await first.api('/api/v1/me'); await second.api('/api/v1/me')
    assert.deepEqual(tokens, ['Bearer first', 'Bearer second'])
  } finally { globalThis.fetch = previous }
})

test('a late annual response cannot replace the year chosen afterward or erase monthly status', async () => {
  const store = usePrototypeStore(createPinia()), old = deferred(), current = deferred()
  store.organizations = [{ id: 'org', name: '사업부1', submissionStatus: '승인 완료', monthlyRequested: true }]
  store.pages = async path => path.includes('organizations') ? [{ id: 'org', name: '사업부1' }]
    : [{ id: 'old', year: 2025 }, { id: 'new', year: 2026 }]
  store.api = path => path.endsWith('/old') ? old.promise : current.promise
  const first = store.loadAnnual(2025); await turn()
  const second = store.loadAnnual(2026); await turn()
  current.resolve({ request: { id: 'new' }, targets: [] }); await second
  old.resolve({ request: { id: 'old' }, targets: [] }); await first
  assert.equal(store.annualRequest.request.id, 'new')
  assert.equal(store.organizations[0].submissionStatus, '승인 완료')
  assert.equal(store.organizations[0].monthlyRequested, true)
})

test('slow comparison and submission responses cannot replace a newer calculator result', async () => {
  const store = usePrototypeStore(createPinia()), old = deferred(), latest = deferred(), submission = deferred()
  store.activeOrg = 'org'; store.monthlyRequest = { request: { id: 'month' } }
  store.api = path => path.includes('/submissions/') ? submission.promise : path.includes('ACTUAL_VS_PLAN') ? old.promise : latest.promise
  const first = store.loadGap('org', '실적 vs 계획')
  old.resolve(gap('old', 'old-submission')); await turn()
  const second = store.loadGap('org', '추정 vs 계획')
  latest.resolve(gap('latest')); await second
  submission.resolve({ id: 'old-submission' }); await first
  assert.deepEqual(store.items.org.map(item => item.id), ['latest'])
  assert.equal(store.currentSubmission, null)
})

test('a previous organization cannot supply the current organization download', async () => {
  const store = usePrototypeStore(createPinia()), response = deferred()
  store.activeOrg = 'one'; store.monthlyRequest = { request: { id: 'month' } }
  store.api = path => path.includes('/submissions/') ? response.promise : Promise.resolve(gap('one', 'one-submission'))
  const pending = store.loadGap('one'); await turn()
  store.activeOrg = 'two'; store.currentSubmission = { id: 'two-submission' }
  response.resolve({ id: 'one-submission' }); await pending
  assert.equal(store.currentSubmission.id, 'two-submission')
})

test('logout invalidates an in-flight list even when a mock transport cannot abort', async () => {
  const store = usePrototypeStore(createPinia()), response = deferred()
  store.monthlyRequest = { request: { id: 'month' } }; store.api = () => response.promise
  const pending = store.loadGap('org')
  store.clear(); response.resolve(gap('old')); await pending
  assert.deepEqual(store.items, {})
  assert.equal(store.actor, null)
})

test('a resubmitted file replaces the old workspace instead of retaining superseded cells', async () => {
  const store=usePrototypeStore(createPinia()),period=seoulPeriod()
  store.monthlyRequest={request:{id:'month'}}
  store.organizations=[{id:'org',name:'사업부1',latestSubmission:{id:'old',status:'REJECTED'}}]
  store.carts.org=[{id:'old-cell',status:'소명 진행중'}]
  store.itemsByComparison.org={'실적 vs 계획':[{id:'old-cell',status:'소명 진행중'}]}
  store.pages=async path=>path.includes('organizations')?[{id:'org',name:'사업부1'}]:[{id:'month',...period}]
  store.api=async()=>({request:{id:'month'},targets:[{organization:{id:'org'},selected:true,latestSubmission:{id:'new',status:'PENDING'}}]})
  await store.loadMonthly()
  assert.equal(store.organizations[0].latestSubmission.id,'new')
  assert.equal(store.carts.org,undefined)
  assert.equal(store.itemsByComparison.org,undefined)
})

test('approved or rejected files cannot leave an unprocessable workspace tab', async () => {
  const store=usePrototypeStore(createPinia())
  store.activeOrg='org';store.monthlyRequest={request:{id:'month'}}
  store.carts.org=[{id:'old-cell',status:'검토 완료'}]
  store.api=async path=>path.includes('/submissions/')?{id:'sub',status:'APPROVED'}
    :{...gap('old-cell','sub'),submission:{id:'sub',status:'APPROVED'}}
  await store.loadGap('org')
  assert.deepEqual(store.workspaces,[])
})

test('one organization query failure leaves monthly progress available and reports the missing review data', async () => {
  const store = usePrototypeStore(createPinia()), period = seoulPeriod()
  store.actor = { role: 'planner' }
  store.pages = async path => path.includes('organizations') ? [{ id: 'org', name: '사업부1' }]
    : [{ id: 'month', ...period }]
  store.api = async () => ({ request: { id: 'month' }, targets: [{ organization: { id: 'org' }, selected: true,
    latestSubmission: { id: 'sub', status: 'PENDING' } }] })
  store.loadGap = async () => { throw new Error('연결 실패') }
  await store.loadMonthly()
  assert.equal(store.monthlyLoaded, true)
  assert.equal(store.monthlyRequest.request.id, 'month')
  assert.match(store.toast.message, /1개 조직/)
})

test('calculator loads actual and forecast together and selected rows retain their own comparison context', async () => {
  const store=usePrototypeStore(createPinia())
  store.activeOrg='org';store.monthlyRequest={request:{id:'month'}}
  store.api=async path=>path.includes('/submissions/')?{id:'sub',status:'PENDING'}
    :{submission:{id:'sub',status:'PENDING'},tree:[{reviewItemId:path.includes('ACTUAL_VS_PLAN')?'actual':'forecast',
      reportItemId:'account',month:path.includes('ACTUAL_VS_PLAN')?6:7,
      scenario:path.includes('ACTUAL_VS_PLAN')?'ACTUAL':'ESTIMATE',actual:'120',plan:'100',previousEstimate:'110',tab:'SELECTED'}]}
  await store.loadCalculator('org')
  assert.deepEqual(store.items.org.map(item=>item.id),['actual','forecast'])
  assert.equal(store.currentSubmission.id,'sub')
  assert.deepEqual(store.workspaces,[])
  await store.addToCart('org',store.items.org,{type:'실적 vs 계획',rate:'5'})
  assert.deepEqual(store.carts.org.map(item=>item.conditions.type),['실적 vs 계획','추정 vs 계획'])
  await store.loadGap('org')
  assert.deepEqual(store.items.org.map(item=>item.id),['actual','forecast'])
})

test('calculator does not combine rows from two different submission versions', async () => {
  const store=usePrototypeStore(createPinia())
  store.activeOrg='org';store.monthlyRequest={request:{id:'month'}}
  store.items.org=[{id:'old'}]
  store.api=async path=>gap('cell',path.includes('ACTUAL_VS_PLAN')?'one':'two')
  await assert.rejects(store.loadCalculator('org'),/제출본이 변경/)
  assert.deepEqual(store.items.org.map(item=>item.id),['old'])
})
