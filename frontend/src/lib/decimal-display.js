/** 서버 십진 문자열을 부호·숫자열·소수 자릿수로 읽는다.
 * Args: value 십진 문자열/null. Returns: 숫자 구성 또는 null. Raises: 없음. Side Effects: 없음.
 * @param {string | null | undefined} value
 * @returns {{negative: boolean, digits: string, scale: number} | null}
 */
function parts(value) {
  if (value === null || value === undefined) return null
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(value))
  return match ? { negative: match[1] === '-', digits: match[2] + (match[3] || ''), scale: (match[3] || '').length } : null
}

/** 원 단위 금액을 정밀도 손실 없이 원 또는 억원으로 표시한다.
 * Args: value 서버 금액, unit KRW/HUNDRED_MILLION. Returns: 구분자 포함 표시 문자열; 없으면 —.
 * Raises: 없음. Side Effects: 없음; 문자열 소수점 이동만 하며 반올림·Number 변환을 하지 않는다.
 * @param {string | null | undefined} value
 * @param {'KRW' | 'HUNDRED_MILLION'} unit
 * @returns {string}
 */
export function formatMoney(value, unit = 'KRW') {
  const parsed = parts(value)
  if (!parsed) return '—'
  const scale = parsed.scale + (unit === 'HUNDRED_MILLION' ? 8 : 0)
  const digits = parsed.digits.padStart(scale + 1, '0')
  const integer = (scale ? digits.slice(0, -scale) : digits).replace(/^0+(?=\d)/, '')
  const fraction = scale ? digits.slice(-scale).replace(/0+$/, '') : ''
  const negative = parsed.negative && /[1-9]/.test(digits) ? '-' : ''
  return negative + integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (fraction ? `.${fraction}` : '')
}

/** 양수·음수·0을 포함한 십진 금액을 공통 세로 좌표로 환산한다.
 * Args: values 서버 금액 배열. Returns: 0선과 각 값의 위쪽 기준 백분율 위치.
 * Raises: 없음. Side Effects: 없음; 금액 비교는 BigInt, Number는 0~100의 차트 좌표에만 사용한다.
 * @param {Array<string | null | undefined>} values
 * @returns {{zero: number, positions: Array<number>}}
 */
export function chartGeometry(values) {
  const parsed = values.map(parts)
  let scale = 0
  for (const value of parsed) if (value) scale = Math.max(scale, value.scale)
  const numbers = []
  let minimum = 0n
  let maximum = 0n
  for (const value of parsed) {
    const number = value ? BigInt(value.digits) * 10n ** BigInt(scale - value.scale) * (value.negative ? -1n : 1n) : 0n
    numbers.push(number)
    if (number < minimum) minimum = number
    if (number > maximum) maximum = number
  }
  if (maximum === minimum) return { zero: 50, positions: numbers.map(zeroPosition) }
  const range = maximum - minimum
  const positions = []
  for (const number of numbers) positions.push(Number((maximum - number) * 10000n / range) / 100)
  return { zero: Number(maximum * 10000n / range) / 100, positions }
}

/** 모든 금액이 0일 때 차트 가운데에 0선을 둔다.
 * Args: 없음. Returns: 백분율 50. Raises: 없음. Side Effects: 없음.
 * @returns {number}
 */
function zeroPosition() { return 50 }
