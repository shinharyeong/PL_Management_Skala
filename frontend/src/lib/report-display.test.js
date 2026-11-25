import test from 'node:test'
import assert from 'node:assert/strict'
import { reportBodyCell, reportChartModel, reportTableModel } from './report-display.js'

test('PDF 병합 머리의 좌표와 두 줄 헤더, 본문 정밀 문자열을 유지한다', () => {
  const data = {header_rows:2, rows:[[{text:'항목',hdr:true,rowspan:2},{text:'실적',hdr:true,colspan:2},{hide:true}], [{hide:true},{text:'당월',hdr:true},{text:'누계',hdr:true}], ['매출','123456789012345678901234567890.000001',null]]}
  const model = reportTableModel(data)
  assert.equal(model.headers.length,2)
  assert.deepEqual(model.headers[0].map(cell=>[cell.key,cell.rowspan,cell.colspan]),[[0,2,1],[1,1,2]])
  assert.deepEqual(model.headers[1].map(cell=>cell.key),[1,2])
  assert.equal(model.rows[0].cells[1],'123456789012345678901234567890.000001')
  assert.equal(model.rows[0].cells[2],null)
})

test('SQL 객체 행에서 0과 누락값을 구별하고 헤더 없는 표를 지원한다', () => {
  const model=reportTableModel({table:{columns:[{key:'a',label:'매출'}, {key:'b',label:'비용'}],rows:[{a:0}]}})
  assert.deepEqual(model.rows[0].cells,[0,null])
  assert.deepEqual(reportTableModel({header_rows:0,rows:[[0,null]]}).rows[0].cells,[0,null])
  assert.equal(reportTableModel({rows:[]}).width,0)
})

test('본문 병합과 숨김 셀을 같은 좌표에 전달한다', () => {
  const row={cells:[{text:'합계',colspan:2,rowspan:3},{hide:true}]}
  assert.deepEqual(reportBodyCell(row,0),{colspan:2,rowSpan:3})
  assert.deepEqual(reportBodyCell(row,1),{colspan:1,rowSpan:1,style:{display:'none'}})
})

test('차트는 원문 금액을 보존하며 누락값을 0으로 만들지 않는다', () => {
  const block={chart_type:'waterfall',rows:[{name:'매출',won:'9007199254740993',text:'9,007,199,254,740,993원'},{name:'비용',won:'-10',text:'-10원'},{name:'미정',won:null},{name:'영',won:'0',text:'0원'}]}
  const before=JSON.stringify(block),model=reportChartModel(block)
  assert.equal(model.rows[0].text,'9,007,199,254,740,993원')
  assert.equal(model.rows[0].won,'9007199254740993')
  assert.equal(model.values[2],null)
  assert.equal(model.rows[3].text,'0원')
  assert.equal(JSON.stringify(block),before)
  assert.equal(reportChartModel({chart_type:'donut',rows:[]}).type,'doughnut')
})

/** 실제 PrimeVue 렌더링에서 상위 rowspan 속성이 본문 병합을 덮지 않는지 검증한다.
 * Args: 없음. Returns: Promise<void>. Raises: 컴파일·렌더링·검증 실패.
 * Side Effects: 소스 파일을 읽어 메모리에서만 SFC와 HTML을 생성한다.
 */
test('실제 PrimeVue DataTable은 머리와 본문의 세로·가로 병합 및 정밀 금액을 렌더링한다', async () => {
  const { readFile } = await import('node:fs/promises')
  const { parse, compileScript } = await import('@vue/compiler-sfc')
  const { createSSRApp } = await import('vue')
  const { renderToString } = await import('@vue/server-renderer')
  const { default: PrimeVue } = await import('primevue/config')
  const filename = new URL('../components/ReportDataTable.vue', import.meta.url)
  const { descriptor } = parse(await readFile(filename, 'utf8'), { filename: filename.pathname })
  let code = compileScript(descriptor, { id: 'report-merge-regression', inlineTemplate: true }).content
  code = code.replace(/from (['"])([^'"]+)\1/g, (_, quote, specifier) => `from ${quote}${specifier.startsWith('.') ? new URL(specifier, filename).href : import.meta.resolve(specifier)}${quote}`)
  const component = (await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)).default
  const data = { header_rows: 2, rows: [
    [{ text: '손익 항목', hdr: true, rowspan: 2 }, { text: '금액', hdr: true, colspan: 2 }, { hide: true }],
    [{ hide: true }, { text: '계획', hdr: true }, { text: '실적', hdr: true }],
    [{ text: '국내', rowspan: 2 }, '9007199254740993.000001', '0'],
    [{ hide: true }, { text: '자료 없음', colspan: 2 }, { hide: true }],
  ] }
  const html = await renderToString(createSSRApp(component, { data }).use(PrimeVue))
  const header = html.match(/<thead[\s\S]*?<\/thead>/)[0]
  const body = html.match(/<tbody[\s\S]*?<\/tbody>/)[0]
  assert.match(header, /<th[^>]*rowspan="2"/)
  assert.match(header, /<th[^>]*colspan="2"/)
  assert.match(body, /<td[^>]*rowspan="2"/)
  assert.match(body, /<td[^>]*colspan="2"/)
  assert.equal((body.match(/display:none/g) || []).length, 2)
  assert.ok(body.includes('9007199254740993.000001'))
  assert.match(body, />0<!--/)
  assert.equal((header.match(/<th\b/g) || []).length, 4)
})
