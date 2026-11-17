import { formatMoney } from './decimal-display'

/** DB 금액을 소수 6자리 정수로 읽어 브라우저 부동소수점 오차를 막는다.
 * Args: value 서버 십진 문자열/null. Returns: 정수 또는 null. Raises: 없음. Side Effects: 없음.
 * @param {string|null|undefined} value
 * @returns {bigint|null}
 */
export function moneyUnits(value) {
  const match = /^(-?)(\d+)(?:\.(\d{1,6}))?$/.exec(String(value ?? ''))
  if (!match) return null
  return BigInt(match[2] + (match[3] || '').padEnd(6, '0')) * (match[1] ? -1n : 1n)
}

/** 정수 금액을 API와 같은 십진 문자열로 되돌린다.
 * Args: value 소수 6자리 정수/null. Returns: 십진 문자열/null. Raises: 없음. Side Effects: 없음.
 * @param {bigint|null} value
 * @returns {string|null}
 */
export function moneyString(value) {
  if (value === null) return null
  const digits = (value < 0n ? -value : value).toString().padStart(7, '0')
  const fraction = digits.slice(-6).replace(/0+$/, '')
  return `${value < 0n ? '-' : ''}${digits.slice(0, -6)}${fraction ? `.${fraction}` : ''}`
}

/** 비교 금액이 모두 있을 때만 현재 금액에서 기준 금액을 뺀다.
 * Args: current 현재값, baseline 비교값. Returns: 차액/null. Raises: 없음. Side Effects: 없음.
 * @param {string|null|undefined} current
 * @param {string|null|undefined} baseline
 * @returns {string|null}
 */
export function difference(current, baseline) {
  const after = moneyUnits(current); const before = moneyUnits(baseline)
  return after === null || before === null ? null : moneyString(after - before)
}

/** 0분모와 없는 값을 구분하며 기준 절댓값에 대한 증감률을 계산한다.
 * Args: current 현재값, baseline 비교값. Returns: 소수 둘째 자리 % 숫자 문자열/null. Raises: 없음. Side Effects: 없음.
 * @param {string|null|undefined} current
 * @param {string|null|undefined} baseline
 * @returns {string|null}
 */
export function gapRate(current, baseline) {
  const after = moneyUnits(current); const before = moneyUnits(baseline)
  if (after === null || before === null || before === 0n) return null
  const delta = after - before; const divisor = before < 0n ? -before : before
  const hundredths = ((delta < 0n ? -delta : delta) * 10000n + divisor / 2n) / divisor
  return `${delta < 0n ? '-' : ''}${hundredths / 100n}.${(hundredths % 100n).toString().padStart(2, '0')}`
}

/** 대시보드의 큰 금액은 억원 소수 둘째 자리까지 반올림하고 원 단위는 그대로 표시한다.
 * Args: value 서버 금액, unit KRW/HUNDRED_MILLION. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 * @param {string|null|undefined} value
 * @param {string} unit
 * @returns {string}
 */
export function dashboardMoney(value, unit = 'HUNDRED_MILLION') {
  const units = moneyUnits(value)
  if (units === null || unit === 'KRW') return formatMoney(value)
  const rounded = ((units < 0n ? -units : units) + 500000000000n) / 1000000000000n
  const fraction = (rounded % 100n).toString().padStart(2, '0').replace(/0+$/, '')
  return `${units < 0n && rounded ? '-' : ''}${(rounded / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${fraction ? `.${fraction}` : ''}`
}

/** 금액을 부호별 표시 스타일로 분류한다.
 * Args: value 십진값/null. Returns: positive/negative/빈문자열. Raises: 없음. Side Effects: 없음.
 * @param {string|null|undefined} value
 * @returns {string}
 */
export function amountTone(value) { const units = moneyUnits(value); return units === null || units === 0n ? '' : units > 0n ? 'positive' : 'negative' }
