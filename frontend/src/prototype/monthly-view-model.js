import { sum } from './exact-money.js'

/** 현재 제출본에서 검토 대상으로 선택할 수 있는 항목인지 판정한다.
 * Args: row 검토 항목; editable 제출본의 편집 가능 여부.
 * Returns: 선택 가능 여부. Raises: 없음. Side Effects: 없음.
 * @param {{ status: string }} row
 * @param {boolean} editable
 * @returns {boolean}
 */
export function canSelectGapItem(row, editable) { return Boolean(editable) && ['검토 필요', '검토 완료'].includes(row.status) }

/** 대·중·소 분류와 검토 항목을 실제 트리로 구성하고 각 분류의 금액을 합산한다.
 * Args: items 검토 항목; level 분류 깊이; path 상위 분류명 배열.
 * Returns: 원본 reviewItem ID와 정밀 금액을 보존하는 TreeNode 배열.
 * Raises: 없음. Side Effects: 없음(원본 항목을 변경하지 않는다).
 * @param {Array<object>} items
 * @param {number} [level=0]
 * @param {Array<string>} [path=[]]
 * @returns {Array<object>}
 */
export function buildGapTree(items, level = 0, path = []) {
  if (level === 3) return items.map(item => ({ key: `review:${item.id}`, leaf: true, data: { ...item, level, base: item.baselineAmount, current: item.currentAmount } }))
  const field = ['major', 'group', 'category'][level]
  return [...new Set(items.map(item => item[field] || '분류 없음'))].map(name => {
    const children = items.filter(item => (item[field] || '분류 없음') === name)
    const names = [...path, name]
    return {
      key: `group:${JSON.stringify(names)}`,
      data: { name, level, base: sum(children.map(item => item.baselineAmount)), current: sum(children.map(item => item.currentAmount)) },
      children: buildGapTree(children, level + 1, names),
    }
  })
}

/** 원천 시트별 행 순서를 묶되 버전별 금액과 셀 식별자를 그대로 보존한다.
 * Args: rows 서버 버전 비교 행. Returns: 시트 그룹 표시용 행 배열.
 * Raises: 없음. Side Effects: 없음(원본 응답을 변경하지 않는다).
 * @param {Array<object>} rows
 * @returns {Array<object>}
 */
export function groupVersionRows(rows) {
  const groups = new Map()
  for (const row of rows) {
    const sheet = row.cell?.includes('!') ? row.cell.split('!')[0] : '원천 셀'
    if (!groups.has(sheet)) groups.set(sheet, [])
    groups.get(sheet).push({ ...row, sheet })
  }
  return [...groups.values()].flat()
}
