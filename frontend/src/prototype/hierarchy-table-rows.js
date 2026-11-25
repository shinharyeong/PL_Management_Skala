import { sum } from './exact-money.js'

export function hierarchyTableRows(items, collapsed = new Set(), level = 0, path = '') {
  const fields = ['major', 'group', 'category'], result = []
  for (const name of new Set(items.map(item => item[fields[level]] || '분류 없음'))) {
    const children = items.filter(item => (item[fields[level]] || '분류 없음') === name)
    const key = JSON.stringify([path, name])
    const head = { key, name, level, rails: [], base: sum(children.map(item => item.baselineAmount)), current: sum(children.map(item => item.currentAmount)) }
    if (level === 0 && collapsed.has(name)) {
      result.push({ ...head, collapsed: true })
      continue
    }
    const descendants = level < 2 ? hierarchyTableRows(children, collapsed, level + 1, key)
      : children.map(item => ({ ...item, key: item.id, level: 3, rails: [], base: item.baselineAmount, current: item.currentAmount }))
    descendants[0].rails.unshift({ level, span: descendants.length + 1 })
    result.push(head, ...descendants, { key: key+'/close', close: true, level })
  }
  return result
}
export const expandedGapItems = (items, collapsed) => items.filter(item => !collapsed.has(item.major || '분류 없음'))
