import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { usePrototypeStore } from './live-store.js'
import { createPrototypeRouter } from './router.js'

test('calculation does not create a workspace, even when the server returns previously selected items', async () => {
  const store = usePrototypeStore(createPinia())
  store.organizations = [{ id: 'F10', name: '사업부1' }]
  store.monthlyRequest = { request: { id: 'monthly' } }
  let tab = 'SELECTED'
  store.api = async () => ({ tree: [{ reviewItemId: 'one', name: '11월 실적', tab, stateToken: 'fresh', plan: '100', actual: '150' }] })
  await store.loadGap('F10')
  assert.equal(store.items.F10.length, 1)
  assert.deepEqual(store.workspaces, [])
  const conditions = { type: '실적 vs 계획', rate: 5 }
  await store.addToCart('F10', store.items.F10, conditions)
  assert.deepEqual(store.workspaces, [{ org: 'F10', name: '사업부1', count: 1 }])
  store.monthlySession('F10').draft.rate = 20
  await store.loadGap('F10')
  assert.equal(store.workspaces[0].count, 1)
  assert.equal(store.carts.F10[0].conditions.rate, 5)
  assert.equal(store.carts.F10[0].stateToken, 'fresh')
  tab = 'IN_PROGRESS'
  await store.loadGap('F10')
  assert.equal(store.workspaces[0].count, 1)
  tab='ACCEPTED'
  await store.loadGap('F10')
  assert.deepEqual(store.workspaces, [])
})

test('cell comparison requires a workspace item chosen from the review page', async () => {
  const store = usePrototypeStore(createPinia())
  store.actor = { role: 'planner' }
  store.activeOrg = 'F10'
  store.items.F10 = [{ id: 'one' }]
  const router = createPrototypeRouter(createMemoryHistory(), store)
  await router.push('/cell')
  assert.equal(router.currentRoute.value.name, 'monthly')
  store.carts.F10 = [{ id: 'one' }]
  await router.push('/cell')
  assert.equal(router.currentRoute.value.name, 'workspace')
  store.monthlySession('F10').activeCell = 'one'
  await router.push('/cell')
  assert.equal(router.currentRoute.value.name, 'cell')
})
