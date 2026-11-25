import { gapFilterSnapshot } from '../lib/gap-result-filter.js'

function amountText(value) {
  const text = String(value ?? '').trim()
  return /^\d{1,3}(?:,\d{3})+(?:\.\d+)?$/.test(text) ? text.replaceAll(',', '') : text
}

// Shift the decimal point as text; financial thresholds never pass through Number.
export function convertMinimumUnit(value, from, to) {
  const powers = { KRW: 0, THOUSAND: 3, TEN_MILLION: 7, HUNDRED_MILLION: 8 }
  if (!Object.hasOwn(powers, from) || !Object.hasOwn(powers, to)) throw new Error('지원하지 않는 금액 단위입니다.')
  const text = amountText(value), match = /^(\d+)(?:\.(\d+))?$/.exec(text)
  if (!match) return text // Keep incomplete/invalid input visible for validation.
  const digits = match[1] + (match[2] || '')
  const position = match[1].length + powers[from] - powers[to]
  const shifted = position <= 0 ? '0.' + '0'.repeat(-position) + digits
    : position >= digits.length ? digits + '0'.repeat(position - digits.length)
      : digits.slice(0, position) + '.' + digits.slice(position)
  const [whole, fraction = ''] = shifted.split('.')
  const trimmed = fraction.replace(/0+$/, '')
  return whole.replace(/^0+(?=\d)/, '') + (trimmed ? '.' + trimmed : '')
}

export function calculatorConditionsSnapshot(draft) {
  if (!draft.rateEnabled && !draft.amountEnabled) throw new Error('기준 입력 필요 · 차이율 또는 최소 금액 기준을 하나 이상 켜 주세요.')
  const filter = gapFilterSnapshot({ percentEnabled: draft.rateEnabled, amountEnabled: draft.amountEnabled,
    percent: draft.rate, amount: amountText(draft.minimum) })
  return { ...draft, rate: filter.percent, minimum: filter.amount }
}

export function calculatorConditionsError(draft) {
  try { calculatorConditionsSnapshot(draft); return '' } catch (error) { return error.message }
}
