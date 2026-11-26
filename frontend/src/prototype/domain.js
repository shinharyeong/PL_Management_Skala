import { matchesGapFilter } from '../lib/gap-result-filter.js'
// All money in these fixtures is an exact, safe integer in KRW. Display units never affect filtering.
export const statuses = ['검토 필요', '검토 완료', '소명 진행중', '재검토 필요', '소명 승인 완료', '제외됨']
export const statusTones = ['review', 'submitted', 'progress', 'recheck', 'approved', 'excluded']
export const comparisonTypes = ['실적 vs 계획', '추정 vs 추정', '추정 vs 계획']
export const gapFixtures = [
  { id: 'A3', cell: 'A3', name: '일반광고비', group: '마케팅비용', category: '광고선전비', plan: 100000000, actual: 110000000, previousEstimate: 108000000, estimate: 112000000, source: '매출!BD128', status: '검토 필요' },
  { id: 'A4', cell: 'A4', name: '유지보수비', group: '운영지원비', category: '유지보수비', plan: 20000000, actual: 23000000, previousEstimate: 22000000, estimate: 24000000, source: 'OpEx!BD212', status: '검토 필요' },
  { id: 'A5', cell: 'A5', name: '외주용역비', group: '운영지원비', category: '외주용역비', plan: 200000000, actual: 206000000, previousEstimate: 204000000, estimate: 208000000, source: 'OpEx!BD214', status: '검토 필요' },
  { id: 'A7', cell: 'A7', name: '교육비', group: '인력운영비', category: '교육비', plan: 50000000, actual: 51000000, previousEstimate: 50000000, estimate: 52000000, source: 'OpEx!BD218', status: '검토 필요' },
]
export function valuesFor(item, type = comparisonTypes[0]) {
  if ('baselineAmount' in item) return { base:item.baselineAmount,current:item.currentAmount }
  if (type === '추정 vs 추정') return { base: item.previousEstimate, current: item.estimate }
  if (type === '추정 vs 계획') return { base: item.plan, current: item.estimate }
  return { base: item.plan, current: item.actual }
}
export function differenceRate(base, current) {
  if (base == null || current == null) return null
  if (base === 0) return current === 0 ? 0 : null
  return (current - base) / Math.abs(base) * 100
}
export function matchesConditions(item, conditions) {
  if ('baselineAmount' in item) return matchesGapFilter(item,{percentEnabled:conditions.rateEnabled,amountEnabled:conditions.amountEnabled,percent:String(conditions.rate),amount:String(conditions.minimum)})
  const { base, current } = valuesFor(item, conditions.type)
  if (![base, current].every(Number.isSafeInteger) || base === current) return false
  const diff = BigInt(current) - BigInt(base)
  const abs = n => n < 0n ? -n : n
  if (conditions.amountEnabled && abs(diff) < BigInt(conditions.minimum)) return false
  // Non-zero change against a zero baseline has undefined percentage; include explicitly for manual review.
  if (conditions.rateEnabled && base !== 0 && abs(diff) * 100n < abs(BigInt(base)) * BigInt(conditions.rate)) return false
  return true
}
export function snapshotKey(org, period, baseVersion, compareVersion, type, itemId) {
  return JSON.stringify([org, period, baseVersion, compareVersion, type, itemId])
}
export const replyKey = item => item.key || item.id
// datetime-local omits a zone; business deadlines are always entered in Seoul time.
export function seoulDeadline(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return NaN
  const timestamp = Date.parse(`${value}:00+09:00`)
  if (!Number.isFinite(timestamp)) return NaN
  return new Date(timestamp + 9 * 60 * 60 * 1000).toISOString().slice(0, 16) === value ? timestamp : NaN
}
export function createMonthlySession() {
  return {
    draft: { baseVersion: 'v1.02', compareVersion: '', type: comparisonTypes[0], rateEnabled: true, amountEnabled: true, rate: 5, minimum: 10000000 },
    minimumUnit: 'THOUSAND', applied: null, selection: [], stage: '검토 필요', search: '', sort: '차액 내림차순', unit: 'THOUSAND',
    activeCell: '', detailSource: 'cart', columnWidths: null,
  }
}
export function seoulPeriod() {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', year: 'numeric', month: 'numeric' }).formatToParts(new Date())
  return { year: Number(parts.find(p => p.type === 'year').value), month: Number(parts.find(p => p.type === 'month').value) }
}
