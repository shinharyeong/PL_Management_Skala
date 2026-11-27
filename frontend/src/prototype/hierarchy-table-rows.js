import { sum } from './exact-money.js'

export function hierarchyTableRows(items, collapsed = new Set(), level = 0, path = '') {
  const fields = ['major', 'group', 'category'], result = []
  for (const name of new Set(items.map(item => item[fields[level]] || '분류 없음'))) {
    const children = items.filter(item => (item[fields[level]] || '분류 없음') === name)
    const key = JSON.stringify([path, name])
    const totals = Object.fromEntries(['planAmount','previousEstimate','actualAmount','estimateAmount'].map(field => {
      const values=children.filter(item => field === 'actualAmount' ? item.scenario === 'ACTUAL' : field === 'estimateAmount' ? item.scenario === 'ESTIMATE' : true).map(item=>item[field])
      return [field,values.length ? sum(values) : null]
    }))
    const head = { key, name, level, rails: [], ...totals, base: sum(children.map(item => item.baselineAmount)), current: sum(children.map(item => item.currentAmount)) }
    if ((level === 0 && collapsed.has(name)) || (level === 2 && collapsed.has(key))) {
      result.push({ ...head, collapsed: true })
      continue
    }
    const descendants = level < 2 ? hierarchyTableRows(children, collapsed, level + 1, key)
      : [...children].sort(compareItemPeriods).map(item => ({ ...item, key: item.id, level: 3, rails: [], base: item.baselineAmount, current: item.currentAmount }))
    descendants[0].rails.unshift({ level, span: descendants.length + 1 })
    result.push(head, ...descendants, { key: key+'/close', close: true, level })
  }
  return result
}
export const expandedGapItems = (items, collapsed) => items.filter(item => !collapsed.has(item.major || '분류 없음'))

// Current actual first, then future estimates in calendar order. Same-period approvals come last.
export function compareItemPeriods(a,b) {
  const month = item => Number(item.month ?? String(item.cell || item.name || '').match(/(\d+)월/)?.[1] ?? 0)
  const actual = item => (item.scenario === 'ACTUAL' || /실적/.test(item.cell || item.name || '')) ? 0 : 1
  return actual(a)-actual(b) || month(a)-month(b) || Number(['소명 승인 완료','승인됨'].includes(a.status))-Number(['소명 승인 완료','승인됨'].includes(b.status))
}
