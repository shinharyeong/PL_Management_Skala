import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { useSession } from '../stores/session.js'
import { usePrototypeStore } from './live-store.js'
import { createPrototypeRouter } from './router.js'
import { SESSION_KEY } from '../lib/session-persistence.js'

const planner = { user: { id: '1', name: '기획', email: 'planner@example.test' }, role: 'PLANNER', organization: null }
const manager = { user: { id: '2', name: '담당', email: 'org@example.test' }, role: 'ORG_MANAGER', organization: { id: '15', code: 'F73', name: '사업부13' } }
const login = me => ({ token: 'valid-token', expiresAt: new Date(Date.now()+3600000).toISOString(), me })
async function fixture(run) {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage'), fetch = globalThis.fetch
  const values = new Map(), storage = { getItem: key => values.get(key) ?? null, setItem: (key,value) => values.set(key,value), removeItem: key => values.delete(key) }
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: storage })
  try { await run(storage) } finally {
    if (descriptor) Object.defineProperty(globalThis, 'sessionStorage', descriptor); else delete globalThis.sessionStorage
    globalThis.fetch = fetch
  }
}
test('fresh app validates /me before routing and restores the server-confirmed role', () => fixture(async storage => {
  useSession(createPinia()).accept(login(planner))
  let complete
  globalThis.fetch = (path, options) => {
    assert.equal(path, '/api/v1/me')
    assert.equal(options.headers.get('Authorization'), 'Bearer valid-token')
    return new Promise(resolve => { complete = resolve })
  }
  const pinia = createPinia(), store = usePrototypeStore(pinia)
  const pending = store.restoreLogin()
  assert.equal(store.actor, null)
  complete(new Response(JSON.stringify(manager), { status: 200 }))
  assert.equal(await pending, true)
  assert.equal(store.actor.role, 'org'); assert.equal(store.actor.orgId, '15')
  const router = createPrototypeRouter(createMemoryHistory(), store)
  await router.push('/org-history'); assert.equal(router.currentRoute.value.name, 'org-history')
  await router.push('/annual'); assert.equal(router.currentRoute.value.name, 'org-requests')
  assert.ok(storage.getItem(SESSION_KEY))
}))
test('planner refresh retains annual page; missing workspace context safely returns to monthly', () => fixture(async () => {
  useSession(createPinia()).accept(login(planner))
  globalThis.fetch = async () => new Response(JSON.stringify(planner), { status: 200 })
  const store = usePrototypeStore(createPinia()); await store.restoreLogin()
  const router = createPrototypeRouter(createMemoryHistory(), store)
  await router.push('/annual'); assert.equal(router.currentRoute.value.name, 'annual')
  await router.push('/calculator'); assert.equal(router.currentRoute.value.name, 'monthly')
  await router.push('/workspace'); assert.equal(router.currentRoute.value.name, 'monthly')
  await router.push('/login'); assert.equal(router.currentRoute.value.name, 'home')
}))
test('server-rejected sessions are removed and never restore a user', () => fixture(async storage => {
  for (const status of [401,403]) {
    useSession(createPinia()).accept(login(planner))
    globalThis.fetch = async () => new Response('{}', { status })
    const store = usePrototypeStore(createPinia())
    assert.equal(await store.restoreLogin(), false)
    assert.equal(store.actor, null); assert.equal(storage.getItem(SESSION_KEY), null)
  }
}))
test('temporary server outage keeps credentials for retry but does not trust cached identity', () => fixture(async storage => {
  useSession(createPinia()).accept(login(planner))
  globalThis.fetch = async () => new Response('{}', { status: 503 })
  const store = usePrototypeStore(createPinia())
  await assert.rejects(store.restoreLogin())
  assert.equal(store.actor, null); assert.ok(storage.getItem(SESSION_KEY))
  globalThis.fetch = async () => new Response(JSON.stringify(planner), { status: 200 })
  assert.equal(await store.restoreLogin(), true)
}))
test('logout removes persistence so a fresh app cannot restore the logged-out user', () => fixture(async storage => {
  const session = useSession(createPinia()); session.accept(login(planner))
  globalThis.fetch = async (path, options) => {
    assert.equal(path, '/api/v1/auth/session'); assert.equal(options.method, 'DELETE')
    return new Response(null, { status: 204 })
  }
  await session.logout()
  assert.equal(storage.getItem(SESSION_KEY), null)
  assert.equal(await useSession(createPinia()).restore(), false)
}))

test('logout during an in-flight profile check cannot resurrect the old session', () => fixture(async storage => {
  useSession(createPinia()).accept(login(planner))
  let finish
  globalThis.fetch = () => new Promise(resolve => { finish=resolve })
  const session = useSession(createPinia()), pending=session.restore()
  session.clear()
  finish(new Response(JSON.stringify(planner),{status:200}))
  assert.equal(await pending,false)
  assert.equal(session.actor,null); assert.equal(storage.getItem(SESSION_KEY),null)
}))
