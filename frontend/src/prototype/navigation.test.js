import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { usePrototypeStore } from './store.js'
import { createPrototypeRouter } from './router.js'
import { challengeState } from './auth-demo.js'

test('connected calculator opens before fetching results; cell requires context and workspace now opens cell', async () => {
  const store = {live:true,actor:{role:'planner'},activeOrg:'1',items:{},carts:{}}
  const router=createPrototypeRouter(createMemoryHistory(),store)
  await router.push('/calculator')
  assert.equal(router.currentRoute.value.name,'calculator')
  await router.push('/cell')
  assert.equal(router.currentRoute.value.name,'monthly')
  await router.push('/workspace')
  assert.equal(router.currentRoute.value.name,'monthly')
  store.items['1']=[{id:'2'}];store.carts['1']=[{id:'2'}]
  await router.push('/workspace')
  assert.equal(router.currentRoute.value.name,'cell')
})

test('code expiry and resend use elapsed wall time, including inactive tabs', () => {
  assert.deepEqual(challengeState(null, 1000), { remaining: 0, resendIn: 0, expired: true })
  assert.deepEqual(challengeState(1000, 1000), { remaining: 180, resendIn: 60, expired: false })
  assert.deepEqual(challengeState(1000, 61000), { remaining: 120, resendIn: 0, expired: false })
  assert.deepEqual(challengeState(1000, 181000), { remaining: 0, resendIn: 0, expired: true })
  assert.equal(challengeState(1000, 900000).expired, true)
})

test('direct links and browser history cannot enter another role or an invalid workflow', async () => {
  const store = usePrototypeStore(createPinia())
  const router = createPrototypeRouter(createMemoryHistory(), store)
  await router.push('/calculator')
  assert.equal(router.currentRoute.value.name, 'login')
  store.actor = { role: 'org', orgId: 'F10' }
  await router.push('/home')
  assert.equal(router.currentRoute.value.name, 'org-requests')
  store.activateOrgRequest('explanation-11')
  await router.push('/org-explanation')
  assert.equal(router.currentRoute.value.name, 'org-explanation')
  await router.push('/org-upload')
  assert.equal(router.currentRoute.value.name, 'org-requests')
  store.activeRequest = 'unknown'
  await router.push('/org-explanation')
  assert.equal(router.currentRoute.value.name, 'org-requests')
  store.actor = { role: 'planner' }
  await router.push('/org-history')
  assert.equal(router.currentRoute.value.name, 'home')
  await router.push('/workspace')
  assert.equal(router.currentRoute.value.name, 'monthly')
  store.activeOrg = 'F30'
  await router.push('/calculator')
  assert.equal(router.currentRoute.value.name, 'monthly')
  store.actor = null
  await router.push('/home')
  assert.equal(router.currentRoute.value.name, 'login')
})

/** 최신 업무 경로와 Agent의 기획자·조직 직접 진입이 함께 유지되는지 검사한다.
 * Args: 없음. Returns: Promise<void>. Raises: 경로·역할 검증 실패.
 * Side Effects: 메모리 라우터만 이동하며 API·파일·실제 로그인에 접근하지 않는다.
 */
test('Agent routes coexist with report and monthly pages while preserving role boundaries', async function agentRouteBoundaries() {
  const store = { live: true, actor: { role: 'planner' }, items: {}, carts: {} }
  const router = createPrototypeRouter(createMemoryHistory(), store)
  for (const page of ['decision-agent', 'reports', 'monthly']) {
    await router.push(`/${page}`)
    assert.equal(router.currentRoute.value.name, page)
  }
  await router.push('/org-improvements')
  assert.equal(router.currentRoute.value.name, 'home')
  store.actor = { role: 'org' }
  await router.push('/org-improvements')
  assert.equal(router.currentRoute.value.name, 'org-improvements')
  await router.push('/decision-agent')
  assert.equal(router.currentRoute.value.name, 'org-requests')
  store.actor = null
  await router.push('/org-improvements')
  assert.equal(router.currentRoute.value.name, 'login')
})
