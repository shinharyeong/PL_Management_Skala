import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia } from 'pinia'
import { usePrototypeStore } from './live-store.js'
import { createMonthlySession, statuses } from './domain.js'
import { calculatorStageCounts, matchesCalculatorStage } from './calculator-stages.js'

test('전체 includes every workflow state; review includes selected items and replies awaiting review', () => {
  const items = statuses.map((status, id) => ({ id, status }))
  assert.equal(createMonthlySession().stage, '전체')
  assert.deepEqual(items.filter(item => matchesCalculatorStage(item, '전체')), items)
  assert.deepEqual(items.filter(item => matchesCalculatorStage(item, '검토중')).map(item => item.status), ['검토 필요', '검토 완료', '재검토 필요'])
  const counts = calculatorStageCounts(items)
  assert.deepEqual(counts, { 전체: 6, 검토중: 3, '소명 중': 1, 제외됨: 1, 승인됨: 1 })
  assert.equal(counts.전체, counts.검토중 + counts['소명 중'] + counts.제외됨 + counts.승인됨)
})

test('a selected item remains visible in both all and review after opening its workspace', () => {
  const item = { id: 'one', status: '검토 필요' }
  for (const status of ['검토 필요', '검토 완료', '재검토 필요']) {
    item.status = status
    assert.ok(matchesCalculatorStage(item, '전체'))
    assert.ok(matchesCalculatorStage(item, '검토중'))
    assert.equal(matchesCalculatorStage(item, '소명 중'), false)
  }
  item.status = '소명 진행중'
  assert.equal(matchesCalculatorStage(item, '검토중'), false)
  assert.ok(matchesCalculatorStage(item, '소명 중'))
})

test('reopening selected items skips duplicate SELECT; a mixed selection only sends new items', async () => {
  const store = usePrototypeStore(createPinia())
  const selected = { id: 'one', status: '검토 완료', stateToken: 'selected-token' }
  const fresh = { id: 'two', status: '검토 필요', stateToken: 'fresh-token' }
  store.items.F10 = [selected, fresh]
  const calls = []
  store.reviewAction = async (org, rows, action) => {
    calls.push({ org, ids: rows.map(row => row.id), action })
    store.items[org] = store.items[org].map(row => rows.some(item => item.id === row.id) ? { ...row, status: '검토 완료' } : row)
  }
  const conditions = { rate: 5 }
  await store.addToCart('F10', [selected], conditions)
  assert.deepEqual(calls, [])
  assert.equal(store.carts.F10.length, 1)
  await store.addToCart('F10', [selected, fresh], conditions)
  assert.deepEqual(calls, [{ org: 'F10', ids: ['two'], action: 'SELECT' }])
  assert.deepEqual(store.carts.F10.map(row => row.id), ['one', 'two'])
})
