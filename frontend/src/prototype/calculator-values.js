import { matchesGapFilter } from '../lib/gap-result-filter.js'
import { subtract, percent } from './exact-money.js'

// The prior forecast belongs to the same month in the previous approved month's file.
// A missing source stays null; it is never replaced with the annual plan or zero.
export function calculatorRows(actualRows, estimateRows, closeMonth = null) {
  if (closeMonth != null) {
    actualRows = actualRows.filter(row => rowMonth(row) === Number(closeMonth))
    estimateRows = estimateRows.filter(row => rowMonth(row) > Number(closeMonth))
  }
  const actualMonths = new Set(actualRows.filter(row => row.actualAmount != null).map(periodKey))
  return [...actualRows, ...estimateRows.filter(row => !actualMonths.has(periodKey(row)))]
}
const rowMonth = row => Number(row.month ?? row.cell?.match(/\d+/)?.[0])
const periodKey = row => JSON.stringify([row.reportItemId, row.month ?? row.cell?.match(/\d+/)?.[0]])

export function calculatorComparisons(row) {
  if (Array.isArray(row.comparisons)) return row.comparisons.map(c=>({
    label:c.label,base:c.baselineAmount,current:c.currentAmount,
    diff:subtract(c.currentAmount,c.baselineAmount),rate:percent(c.baselineAmount,c.currentAmount),
  }))
  const current = row.actualAmount ?? row.estimateAmount ?? null
  const target = row.actualAmount != null ? '실적' : '추정'
  return [['계획', row.planAmount], ['이전 추정', row.previousEstimate]].map(([label, base]) => ({
    label: `${label} 대비 ${target}`, base, current,
    diff: subtract(current, base), rate: percent(base, current),
  }))
}

export function matchesCalculatorConditions(row, conditions) {
  return calculatorComparisons(row).some(comparison => matchesGapFilter({
    baselineAmount: comparison.base, currentAmount: comparison.current,
  }, { percentEnabled: conditions.rateEnabled, amountEnabled: conditions.amountEnabled,
    percent: String(conditions.rate), amount: String(conditions.minimum) }))
}
