import assert from 'node:assert/strict'
import test from 'node:test'
import { circled, eok, highlightParts, isTextual, layoutWidth, monthCell, periodText, reorder, styleCss, withStyle } from './report-studio.js'

test('amounts are shown in 억원 with one decimal and a sign when asked', () => {
  assert.equal(eok('53780000000'), '537.8억')
  assert.equal(eok(-40000000, { sign: true }), '−0.4억')
  assert.equal(eok(40000000, { sign: true }), '+0.4억')
  assert.equal(eok(0, { sign: true }), '0.0억')
  assert.equal(eok('-74000000'), '−0.7억')
  assert.equal(eok(null), '—')
  assert.equal(eok('x'), '—')
  assert.equal(eok(4000000, { sign: true }), '+0.0억'.replace('+', ''))     // 0.04억은 0.0으로 반올림되어 부호 없음
})

test('month cells follow the master state', () => {
  assert.deepEqual(monthCell({ state: 'CONFIRMED' }), { cls: 'def', label: '확정' })
  assert.deepEqual(monthCell({ state: 'PROVISIONAL' }), { cls: 'prov', label: '잠정' })
  assert.deepEqual(monthCell({ state: 'NONE' }), { cls: 'none', label: '데이터 없음' })
  assert.deepEqual(monthCell(undefined), { cls: 'none', label: '데이터 없음' })
})

test('block numbers and period text', () => {
  assert.equal(circled(1), '①')
  assert.equal(circled(20), '⑳')
  assert.equal(circled(21), '21')
  assert.equal(periodText({}), '기준 월 따름')
  assert.equal(periodText({ period: { rule: 'base_offset', offset: -1 } }), '기준 월 −1개월')
  assert.equal(periodText({ period: { rule: 'base_offset', offset: 2 } }), '기준 월 +2개월')
  assert.equal(periodText({ period: { rule: 'base_offset', offset: 0 } }), '기준 월 따름')
  assert.equal(isTextual({ kind: 'footnote' }), true)
  assert.equal(isTextual({ kind: 'table' }), false)
})

test('highlight parts keep the original text and mark values and missing slots', () => {
  const text = '영업이익 28.1억 계획 대비 △0.4억, 추정 [값] 데이터 없음 12건'
  const parts = highlightParts(text)
  assert.equal(parts.map(p => p.text).join(''), text)
  assert.deepEqual(parts.filter(p => p.kind === 'val').map(p => p.text), ['28.1억', '△0.4억', '12건'])
  assert.deepEqual(parts.filter(p => p.kind === 'miss').map(p => p.text), ['[값]', '데이터 없음'])
  assert.deepEqual(highlightParts(''), [])
  assert.deepEqual(highlightParts(null), [])
})

test('reorder moves a block before or after a target and ignores bad ids', () => {
  const b = ['a', 'b', 'c', 'd'].map(id => ({ id }))
  assert.deepEqual(reorder(b, 'a', 'c', true).map(x => x.id), ['b', 'c', 'a', 'd'])
  assert.deepEqual(reorder(b, 'd', 'a', false).map(x => x.id), ['d', 'a', 'b', 'c'])
  assert.deepEqual(reorder(b, 'b', 'b').map(x => x.id), ['a', 'b', 'c', 'd'])
  assert.deepEqual(reorder(b, 'z', 'a').map(x => x.id), ['a', 'b', 'c', 'd'])
  assert.deepEqual(reorder(b, 'a', 'z').map(x => x.id), ['a', 'b', 'c', 'd'])
  assert.equal(b.length, 4)                                                        // 원본은 바뀌지 않는다
})

test('layout width is clamped to 15..100', () => {
  assert.equal(layoutWidth({}), 100)
  assert.equal(layoutWidth({ layout: { w: 50.4 } }), 50)
  assert.equal(layoutWidth({ layout: { w: 3 } }), 15)
  assert.equal(layoutWidth({ layout: { w: 400 } }), 100)
  assert.equal(layoutWidth({ layout: { w: 'x' } }), 100)
})

test('style css only accepts known safe values', () => {
  assert.deepEqual(styleCss({}), {})
  const css = styleCss({ style: { family: 'serif', size: 12, color: '#336699', bold: true, italic: true, underline: true, strike: true, case: 'uppercase', align: 'center', lh: '1.5', ls: 1 } })
  assert.equal(css.fontSize, '12pt'); assert.equal(css.color, '#336699'); assert.equal(css.fontWeight, '700'); assert.equal(css.textDecoration, 'underline line-through')
  assert.equal(css.textTransform, 'uppercase'); assert.equal(css.textAlign, 'center'); assert.equal(css.lineHeight, '1.5'); assert.equal(css.letterSpacing, '1px')
  const bad = styleCss({ style: { size: 99, color: 'red; background:url(x)', case: 'x', align: 'x', lh: 9, ls: 99 } })
  assert.deepEqual(bad, {})
})

test('with style merges a patch and drops empty values', () => {
  assert.deepEqual(withStyle({ style: { bold: true, size: 12 } }, { bold: false, color: '#112233' }), { size: 12, color: '#112233' })
  assert.deepEqual(withStyle({}, { size: 10 }), { size: 10 })
})
