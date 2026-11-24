import test from 'node:test'
import assert from 'node:assert/strict'
import { gapFilterSnapshot, matchesGapFilter } from './gap-result-filter.js'

const condition = (values = {}) => gapFilterSnapshot({ percentEnabled: true, percent: '5', amountEnabled: true, amount: '50', ...values })
const row = (baselineAmount, currentAmount, gapRate = null) => ({ baselineAmount, currentAmount, gapRate })

test('rate and amount independently enable and combine with AND at inclusive boundaries', () => {
  assert.equal(matchesGapFilter(row('1000', '1050'), condition()), true)
  assert.equal(matchesGapFilter(row('1000', '1049.999'), condition()), false)
  assert.equal(matchesGapFilter(row('100', '106'), condition()), false)
  assert.equal(matchesGapFilter(row('100', '106'), condition({ amountEnabled: false, amount: '' })), true)
  assert.equal(matchesGapFilter(row('10000', '10050'), condition({ percentEnabled: false, percent: '' })), true)
  assert.equal(matchesGapFilter(row('10000', '10050'), condition()), false)
})

test('decreases and negative bases use absolute differences and rates', () => {
  assert.equal(matchesGapFilter(row('1000', '950'), condition()), true)
  assert.equal(matchesGapFilter(row('-1000', '-1050'), condition()), true)
  assert.equal(matchesGapFilter(row('-1000', '-950'), condition()), true)
  assert.equal(matchesGapFilter(row('-1000', '-960'), condition()), false)
})

test('zero baseline, zero differences and missing values have explicit behavior', () => {
  assert.equal(matchesGapFilter(row('0', '50'), condition()), true)
  assert.equal(matchesGapFilter(row('0', '0'), condition({ amount: '0', percent: '0' })), false)
  assert.equal(matchesGapFilter(row('100', '100'), condition({ amount: '0', percent: '0' })), true)
  for (const value of [null, undefined, '', 'unknown']) {
    assert.equal(matchesGapFilter(row(value, '100'), condition()), false)
    assert.equal(matchesGapFilter(row('100', value), condition()), false)
  }
})

test('both disabled means all comparable changes and permits empty disabled fields', () => {
  const off = condition({ percentEnabled: false, amountEnabled: false, percent: '', amount: '' })
  assert.equal(matchesGapFilter(row('100000000000000000000', '100000000000000000000.001'), off), true)
  assert.equal(matchesGapFilter(row('100', '100.000'), off), false)
  assert.equal(matchesGapFilter(row(null, '100'), off), false)
})

test('large amounts and precise decimal boundaries never pass through floating point', () => {
  const exact = condition({ percent: '0.0000000000000001', amount: '1' })
  assert.equal(matchesGapFilter(row('1000000000000000000', '1000000000000000001', '0'), exact), true)
  assert.equal(matchesGapFilter(row('1000000000000000000', '1000000000000000000.999999999999', '999'), exact), false)
  assert.equal(matchesGapFilter(row('3', '4', '33.33333333'), condition({ percent: '33.33333333333333333333333333333333334', amountEnabled: false })), false)
  assert.equal(matchesGapFilter(row('3', '4'), condition({ percent: '33.33333333333333333333333333333333333', amountEnabled: false })), true)
})

test('snapshot validates only enabled criteria and does not follow further draft edits', () => {
  const draft = { percentEnabled: true, percent: ' 5 ', amountEnabled: false, amount: '' }
  const applied = gapFilterSnapshot(draft)
  draft.percent = '80'
  assert.equal(applied.percent, '5')
  assert.throws(() => condition({ percent: '-1' }), /차이율/)
  assert.throws(() => condition({ amount: '' }), /최소 금액/)
  assert.throws(() => condition({ amount: '1e8' }), /최소 금액/)
})
