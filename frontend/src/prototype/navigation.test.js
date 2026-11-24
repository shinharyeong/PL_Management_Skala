import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { usePrototypeStore } from './store.js'
import { createPrototypeRouter } from './router.js'
import { challengeState } from './auth-demo.js'

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
