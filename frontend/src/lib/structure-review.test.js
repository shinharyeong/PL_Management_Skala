import assert from 'node:assert/strict'
import test from 'node:test'
import { blockPlainText, boxStyle, changeKind, kindChoices, listRows, pageSizes, reviewCount, reviewReasons, tableView, tidyNotes } from './structure-review.js'

const definition = { extraction: { pages: [{ no: 1, width: 842, height: 595 }, { no: 2, width: 0, height: 595 }, { no: 'x', width: 1, height: 1 }] } }

test('page sizes keep only valid pages from the analysis result', () => {
  assert.deepEqual(pageSizes(definition), { 1: { width: 842, height: 595 } })
  assert.deepEqual(pageSizes({}), {})
  assert.deepEqual(pageSizes(null), {})
})

test('box position is a percentage of the page size and is clamped to the page', () => {
  const sizes = pageSizes(definition)
  const style = boxStyle({ page: 1, bbox: [84.2, 59.5, 421, 297.5] }, sizes)
  assert.deepEqual(style, { left: '10.000%', top: '10.000%', width: '40.000%', height: '40.000%' })
  const wide = boxStyle({ page: 1, bbox: [-20, 0, 900, 595] }, sizes)
  assert.equal(wide.left, '0.000%')
  assert.equal(wide.width, '100.000%')
})

test('no box is drawn when position information is missing or broken', () => {
  const sizes = pageSizes(definition)
  assert.equal(boxStyle({ page: 1 }, sizes), null)
  assert.equal(boxStyle({ page: 1, bbox: [1, 2, 3] }, sizes), null)
  assert.equal(boxStyle({ page: 1, bbox: [10, 10, 5, 50] }, sizes), null)
  assert.equal(boxStyle({ page: 1, bbox: [1, 2, NaN, 4] }, sizes), null)
  assert.equal(boxStyle({ page: 3, bbox: [1, 2, 30, 40] }, sizes), null)
  assert.equal(boxStyle({ page: 1, bbox: [1, 2, 30, 40] }, {}), null)
})

test('review reasons carry label, text and help; unknown codes fall back to the code', () => {
  const reasons = reviewReasons({ needs_review: [{ code: 'header_ambiguous', reason: '병합 범위를 모릅니다' }, { code: 'brand_new_code', reason: 'x' }, null] })
  assert.equal(reasons.length, 3)
  assert.equal(reasons[0].label, '표 머리 병합 확인')
  assert.match(reasons[0].help, /병합/)
  assert.equal(reasons[1].label, 'brand_new_code')
  assert.equal(reasons[1].help, '')
  assert.deepEqual(reviewReasons({}), [])
  assert.equal(reviewCount([{ needs_review: [{}] }, { needs_review: [] }, {}]), 1)
})

test('tidy notes show the rule names and keep unknown keys', () => {
  assert.deepEqual(tidyNotes({ tidy: ['tidy:chart', 'tidy:future'] }), ['막대·막대 이름·축 숫자를 한 차트로 묶음', 'tidy:future'])
  assert.deepEqual(tidyNotes({}), [])
})

test('merged table headers are shown with colspan and rowspan, numbers are hidden', () => {
  const block = {
    kind: 'table',
    table: {
      header_rows: 2, number_cells: 2,
      header_cells: [[{ j: 0, text: '사업부', rowspan: 2 }, { j: 1, text: '영업이익', colspan: 2 }], [{ j: 0, text: '사업부', hide: true }, { j: 1, text: '계획' }, { j: 2, text: '실적' }]],
      rows: [[{ type: 'text', text: '사업부' }, { type: 'text', text: '영업이익' }, { type: 'text', text: '' }], [{ type: 'text', text: '' }, { type: 'text', text: '계획' }, { type: 'text', text: '실적' }], [{ type: 'text', text: '사업부1' }, { type: 'number' }, { type: 'number' }]],
    },
  }
  const view = tableView(block)
  assert.deepEqual(view.head[0], [{ text: '사업부', colspan: 1, rowspan: 2 }, { text: '영업이익', colspan: 2, rowspan: 1 }])
  assert.deepEqual(view.head[1], [{ text: '계획', colspan: 1, rowspan: 1 }, { text: '실적', colspan: 1, rowspan: 1 }])
  assert.deepEqual(view.body, [['사업부1', '▒', '▒']])
  const plain = tableView({ table: { header_rows: 1, rows: [[{ type: 'text', text: 'A' }], [{ type: 'number' }]] } })
  assert.deepEqual(plain.head, [[{ text: 'A', colspan: 1, rowspan: 1 }]])
  assert.deepEqual(tableView({}).body, [])
  assert.deepEqual(tableView({ table: { header_rows: 0, rows: [[{ type: 'text', text: '▲ {수치}p' }]] } }).body, [['▲ ▒p']])      // 가린 숫자 자리는 ▒
})

test('nested list items are flattened with their depth', () => {
  const rows = listRows([{ marker: '가.', text: '시기 이동', children: [{ marker: '–', text: '사업부9', children: [] }] }, { marker: '나.', text: '계약' }])
  assert.deepEqual(rows, [{ depth: 0, marker: '가.', text: '시기 이동' }, { depth: 1, marker: '–', text: '사업부9' }, { depth: 0, marker: '나.', text: '계약' }])
  assert.deepEqual(listRows(undefined), [])
})

test('kind choices allow text kinds, and data blocks can only become text', () => {
  assert.deepEqual(kindChoices({ kind: 'paragraph' }).map(c => c.value), ['paragraph', 'title', 'footnote'])
  assert.deepEqual(kindChoices({ kind: 'table' }).map(c => c.value), ['table', 'title', 'paragraph', 'footnote'])
})

test('changing a block to a text kind keeps its words; other changes are refused', () => {
  const title = { kind: 'paragraph', text: '제목 같은 문장' }
  assert.equal(changeKind(title, 'title'), true)
  assert.deepEqual(title, { kind: 'title', text: '제목 같은 문장', level: 2 })
  const table = { kind: 'table', table: { rows: [[{ type: 'text', text: '구분' }, { type: 'text', text: '매출' }], [{ type: 'number' }, { type: 'text', text: '억원' }]] } }
  assert.equal(changeKind(table, 'paragraph'), true)
  assert.equal(table.kind, 'paragraph')
  assert.equal(table.text, '구분 매출 억원')
  const note = { kind: 'paragraph', text: '주석' }
  changeKind(note, 'footnote')
  assert.equal(note.marker, '※')
  assert.equal(changeKind({ kind: 'paragraph', text: 'x' }, 'table'), false)
  assert.equal(changeKind({ kind: 'title', text: 'x' }, 'title'), false)
  assert.equal(changeKind(null, 'title'), false)
})

test('plain text of data blocks joins their visible words', () => {
  assert.equal(blockPlainText({ kind: 'list', items: [{ text: 'a', children: [{ text: 'b' }] }] }), 'a b')
  assert.equal(blockPlainText({ kind: 'chart', title: '추이', categories: ['6월', '7월'], series: ['영업이익'] }), '추이 6월 7월 영업이익')
  assert.equal(blockPlainText({ kind: 'numbercard', cards: [{ label: '매출' }, { label: '비용' }] }), '매출 비용')
})
