import test from 'node:test'
import assert from 'node:assert/strict'
import { calculatorConditionsError, calculatorConditionsSnapshot, convertMinimumUnit } from './calculator-conditions.js'
import { formatMoney } from '../lib/decimal-display.js'
import { matchesConditions } from './domain.js'

const draft = { rateEnabled: true, amountEnabled: true, rate: '12.5', minimum: '50,000,000' }
test('unit toggles preserve the same exact threshold including fractional and large amounts', () => {
  assert.equal(convertMinimumUnit('5', 'TEN_MILLION', 'KRW'), '50000000')
  assert.equal(convertMinimumUnit('50000000', 'KRW', 'HUNDRED_MILLION'), '0.5')
  assert.equal(convertMinimumUnit('0.5', 'HUNDRED_MILLION', 'TEN_MILLION'), '5')
  assert.equal(convertMinimumUnit('0.0000000001', 'HUNDRED_MILLION', 'KRW'), '0.01')
  const large = '9007199254740993.01'
  assert.equal(convertMinimumUnit(convertMinimumUnit(large, 'KRW', 'TEN_MILLION'), 'TEN_MILLION', 'KRW'), large)
  assert.equal(convertMinimumUnit('', 'TEN_MILLION', 'KRW'), '')
  assert.equal(convertMinimumUnit('1.', 'TEN_MILLION', 'KRW'), '1.')
})
test('custom decimal percentage and exact monetary threshold include the boundary with AND', () => {
  const condition = calculatorConditionsSnapshot(draft)
  assert.equal(condition.minimum, '50000000')
  assert.equal(matchesConditions({ baselineAmount: '400000000', currentAmount: '450000000' }, condition), true)
  assert.equal(matchesConditions({ baselineAmount: '400000000', currentAmount: '449999999.99' }, condition), false)
  assert.equal(matchesConditions({ baselineAmount: '1000000000', currentAmount: '1050000000' }, condition), false)
})
test('both disabled is rejected; inactive empty fields are ignored', () => {
  assert.match(calculatorConditionsError({ ...draft, rateEnabled: false, amountEnabled: false }), /기준 입력 필요/)
  assert.equal(calculatorConditionsError({ ...draft, rateEnabled: false, rate: '' }), '')
  assert.equal(calculatorConditionsError({ ...draft, amountEnabled: false, minimum: '' }), '')
  for (const value of ['', '-1', '1e4', 'abc']) assert.notEqual(calculatorConditionsError({ ...draft, rate: value }), '')
  assert.notEqual(calculatorConditionsError({ ...draft, minimum: '10,00' }), '')
})
test('large fractional thresholds keep precision and applied criteria do not follow later edits', () => {
  const input = { ...draft, rateEnabled: false, minimum: '9007199254740993.01' }
  const condition = calculatorConditionsSnapshot(input)
  input.minimum = '1'
  assert.equal(condition.minimum, '9007199254740993.01')
  assert.equal(matchesConditions({ baselineAmount: '0', currentAmount: '9007199254740993.00' }, condition), false)
  assert.equal(matchesConditions({ baselineAmount: '0', currentAmount: '9007199254740993.01' }, condition), true)
})

test('thousand-won input and review display share the same threshold as hundred-million-won', () => {
  const minimum = convertMinimumUnit('10,000', 'THOUSAND', 'KRW')
  assert.equal(minimum, '10000000')
  assert.equal(convertMinimumUnit(minimum, 'KRW', 'HUNDRED_MILLION'), '0.1')
  assert.equal(convertMinimumUnit('0.1', 'HUNDRED_MILLION', 'THOUSAND'), '10000')
  assert.equal(formatMoney(minimum, 'THOUSAND'), '10,000')
  assert.equal(formatMoney(minimum, 'HUNDRED_MILLION'), '0.1')
  const conditions = calculatorConditionsSnapshot({ ...draft, rateEnabled: false, minimum })
  assert.equal(matchesConditions({ baselineAmount:'100000000',currentAmount:'110000000' },conditions),true)
  assert.equal(matchesConditions({ baselineAmount:'100000000',currentAmount:'109999999.99' },conditions),false)
})
