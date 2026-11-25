import test from 'node:test'
import assert from 'node:assert/strict'
import { createSessionPersistence, SESSION_KEY } from './session-persistence.js'

export function memoryStorage() {
  const rows = new Map()
  return { getItem: key => rows.get(key) ?? null, setItem: (key, value) => rows.set(key, value), removeItem: key => rows.delete(key) }
}
test('tab persistence stores only the credential and expiry, never trusted identity or roles', () => {
  const storage = memoryStorage(), persistence = createSessionPersistence(() => storage)
  const value = { token: 'valid-token', expiresAt: new Date(Date.now() + 3600000).toISOString(), me: { role: 'PLANNER' } }
  assert.equal(persistence.save(value), true)
  assert.deepEqual(persistence.read(), { token: value.token, expiresAt: value.expiresAt })
  assert.equal(JSON.parse(storage.getItem(SESSION_KEY)).me, undefined)
  persistence.clear(); assert.equal(persistence.read(), null)
})
test('expired and malformed credentials are removed without authenticating', () => {
  const storage = memoryStorage(), persistence = createSessionPersistence(() => storage)
  for (const raw of ['broken-json', '{}', JSON.stringify({ token: 'bad\ntoken', expiresAt: '2999-01-01' }), JSON.stringify({ token: 'old', expiresAt: '2000-01-01' })]) {
    storage.setItem(SESSION_KEY, raw)
    assert.equal(persistence.read(), null)
    assert.equal(storage.getItem(SESSION_KEY), null)
  }
})
test('storage-denied browsers keep ordinary login usable', () => {
  const persistence = createSessionPersistence(() => { throw new Error('Storage unavailable') })
  assert.equal(persistence.read(), null)
  assert.equal(persistence.save({ token: 'valid', expiresAt: '2999-01-01' }), false)
  assert.doesNotThrow(() => persistence.clear())
})
