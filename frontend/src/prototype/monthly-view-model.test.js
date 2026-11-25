import test from 'node:test'
import assert from 'node:assert/strict'
import { buildGapTree, canSelectGapItem, groupVersionRows } from './monthly-view-model.js'

/** 계층별 합계와 상세의 정밀 금액·서버 식별자 보존을 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('Gap 트리는 대·중·소·상세 계층에서 정밀 합계와 원본 reviewItem을 보존한다', () => {
  const first = Object.freeze({ id: '901', stateToken: 'state-1', major: '매출', group: '국내', category: '용역', name: '첫 항목', baselineAmount: '999999999999999999999999.123456', currentAmount: '999999999999999999999999.123457', status: '검토 필요' })
  const second = Object.freeze({ ...first, id: '902', name: '둘째 항목', stateToken: 'state-2', baselineAmount: '0.000001', currentAmount: '-0.000001' })
  const other = Object.freeze({ ...first, id: '903', group: '해외', baselineAmount: '2.5', currentAmount: '3.5' })
  const input = Object.freeze([first, second, other])
  const tree = buildGapTree(input)
  assert.equal(tree.length, 1)
  assert.equal(tree[0].data.base, '1000000000000000000000001.623457')
  assert.equal(tree[0].data.current, '1000000000000000000000002.623456')
  assert.equal(tree[0].children[0].data.base, '999999999999999999999999.123457')
  const category = tree[0].children[0].children[0]
  assert.equal(category.data.level, 2)
  assert.equal(category.data.current, '999999999999999999999999.123456')
  assert.deepEqual(category.children.map(node => node.key), ['review:901', 'review:902'])
  assert.equal(category.children[0].data.id, first.id)
  assert.equal(category.children[0].data.stateToken, first.stateToken)
  assert.equal(category.children[0].data.base, first.baselineAmount)
  assert.equal(category.children[0].data.level, 3)
  assert.equal(category.children[0].leaf, true)
  assert.equal(first.level, undefined)
})

/** 분류 경로 충돌과 누락 금액의 가짜 영 합계를 방지하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('동일한 분류명과 슬래시 경로는 구별하며 누락 금액은 null로 유지한다', () => {
  const tree = buildGapTree([
    { id: 1, major: 'A/B', group: 'C', category: '공통', baselineAmount: '1', currentAmount: null },
    { id: 2, major: 'A', group: 'B/C', category: '공통', baselineAmount: '2', currentAmount: '0' },
  ])
  const firstCategory = tree[0].children[0].children[0]
  const secondCategory = tree[1].children[0].children[0]
  assert.notEqual(firstCategory.key, secondCategory.key)
  assert.equal(tree[0].data.current, null)
  assert.equal(tree[1].data.current, '0')
  assert.equal(firstCategory.children[0].data.id, 1)
  assert.deepEqual(buildGapTree([]), [])
})

/** PrimeVue 체크박스의 선택 가능 상태가 업무 조건과 일치하는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('검토 필요·검토 완료만 편집 가능한 제출본에서 선택할 수 있다', () => {
  for (const status of ['검토 필요', '검토 완료']) {
    assert.equal(canSelectGapItem({ status }, true), true)
    assert.equal(canSelectGapItem({ status }, false), false)
  }
  for (const status of ['소명 진행중', '재검토 필요', '소명 승인 완료', '제외됨']) assert.equal(canSelectGapItem({ status }, true), false)
})

/** 버전 표의 시트 그룹화가 값의 순서와 빈 셀·소명 근거를 바꾸지 않는지 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('버전 표는 시트를 묶고 버전별 decimal 문자열과 누락 셀·근거를 보존한다', () => {
  const first = Object.freeze({ cell: '실적!A1', name: '매출', values: Object.freeze([null, '9007199254740993.000001', '0']), basis: '계약 근거', uploadExplanation: '수주 증가' })
  const second = Object.freeze({ cell: '추정!A1', name: '추정 매출', values: Object.freeze(['1.00', null, '2.000001']) })
  const third = Object.freeze({ cell: '실적!A2', name: '원가', values: Object.freeze(['-0.000001', '0', null]) })
  const input = Object.freeze([first, second, third])
  const rows = groupVersionRows(input)
  assert.deepEqual(rows.map(row => row.cell), ['실적!A1', '실적!A2', '추정!A1'])
  assert.deepEqual(rows.map(row => row.sheet), ['실적', '실적', '추정'])
  assert.deepEqual(rows[0].values, [null, '9007199254740993.000001', '0'])
  assert.equal(rows[0].basis, first.basis)
  assert.equal(rows[0].uploadExplanation, first.uploadExplanation)
  assert.equal(first.sheet, undefined)
  assert.equal(groupVersionRows([{ cell: 'A1', values: [] }])[0].sheet, '원천 셀')
})
