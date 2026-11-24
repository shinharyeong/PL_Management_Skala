/** 서버의 십진 문자열을 정수와 소수 자릿수로 읽는다. 금액은 Number로 변환하지 않는다. */
function decimal(value) {
  if (value === null || value === undefined) return null
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(value).trim())
  if (!match) return null
  return { value: BigInt(match[2] + (match[3] || '')) * (match[1] ? -1n : 1n), scale: (match[3] || '').length }
}

function absolute(value) { return value < 0n ? -value : value }
function scaled(value, scale) { return value.value * 10n ** BigInt(scale - value.scale) }

/** 켠 조건만 검사해 화면 조회에 사용할 독립적인 스냅샷을 만든다. */
export function gapFilterSnapshot(input) {
  const result = { percentEnabled: input.percentEnabled === true, amountEnabled: input.amountEnabled === true, percent: String(input.percent ?? '').trim(), amount: String(input.amount ?? '').trim() }
  for (const field of ['percent', 'amount']) {
    if (result[`${field}Enabled`] && (!/^\d+(?:\.\d+)?$/.test(result[field]) || !decimal(result[field]))) throw new Error(`${field === 'percent' ? '차이율' : '최소 금액'}을 0 이상의 숫자로 입력해 주세요.`)
  }
  return result
}

/**
 * 현재 값과 기준 값의 차이를 조회 조건으로 거른다. 켠 조건은 모두 충족해야 한다.
 * 경계값은 이상(>=)이며 차이율은 표시용 반올림 값 대신 원본 금액으로 정확히 비교한다.
 * 기준 0의 변경은 차이율 조건을 충족한다. 두 조건을 끄면 금액이 바뀐 항목을 보여 준다.
 * 누락된 값은 0으로 만들지 않는다. 서버의 검증·소명 상태와 원본 값은 바꾸지 않는다.
 */
export function matchesGapFilter(row, condition) {
  const baseline = decimal(row.baselineAmount), current = decimal(row.currentAmount)
  if (!baseline || !current) return false
  const scale = Math.max(baseline.scale, current.scale)
  const base = scaled(baseline, scale), gap = absolute(scaled(current, scale) - base)
  if (!condition.percentEnabled && !condition.amountEnabled) return gap !== 0n
  if (condition.amountEnabled) {
    const amount = decimal(condition.amount)
    if (!amount || amount.value < 0n) return false
    const commonScale = Math.max(scale, amount.scale)
    if (gap * 10n ** BigInt(commonScale - scale) < scaled(amount, commonScale)) return false
  }
  if (condition.percentEnabled) {
    const percent = decimal(condition.percent)
    if (!percent || percent.value < 0n) return false
    if (base === 0n) return gap !== 0n
    if (gap * 100n * 10n ** BigInt(percent.scale) < percent.value * absolute(base)) return false
  }
  return true
}
