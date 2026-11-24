import test from 'node:test'
import assert from 'node:assert/strict'
import { createApiClient } from './live-api.js'
import { applyReportProposals, manualReport, reportCell, reportDefinition, reportRequest, reportRows } from './report-api.js'
import { reportLines } from './report-export.js'

/** 실제 전송 계층으로 조회·지표 등록·데이터 채우기의 DTO를 검증한다.
 * Args: t 메모리 HTTP 대역을 복원하는 테스트 문맥.
 * Returns: Promise<void>. Raises: 검증 실패.
 * Side Effects: 메모리 HTTP 호출만 기록하며 파일·서버 상태를 만들지 않는다.
 */
test('query, metric registration and data filling keep current API shapes and decimal strings', async function queryMetricContract(t) {
  const calls = []
  const preview = { dataStatus: 'CONFIRMED', columns: [{ key: 'org', label: '조직', type: 'STRING' }, { key: 'amount', label: '금액', type: 'NUMBER' }], rows: [{ amount: '9007199254740993.12', org: '사업부1' }] }
  const replies = [{ status: 'OK', sql: 'SELECT amount FROM v_op WHERE version = :version', preview }, { id: '31', code: 'OP_BY_ORG', name: '조직별 이익' }, { dataStatus: 'PROVISIONAL', blocks: [{ id: 'metric-1', table: { columns: ['금액'], rows: [['9007199254740993.12']] } }] }]
  /** API 요청을 기록하고 다음 계약 응답을 반환한다.
   * Args: path API 주소, options 전송 옵션. Returns: Promise<Response>.
   * Raises: 없음. Side Effects: 메모리 호출 목록 변경.
   */
  t.mock.method(globalThis, 'fetch', async function respond(path, options) { calls.push({ path, options }); return new Response(JSON.stringify(replies.shift())) })
  const api = createApiClient({ token: () => 'session', clear: () => {} })
  const base = { year: 2026, month: 11 }
  const query = await reportRequest(api, 'query', { question: '조직별 이익', base })
  assert.equal(calls[0].path, '/api/v1/report/nl-query')
  assert.deepEqual(JSON.parse(calls[0].options.body), { question: '조직별 이익', baseYear: 2026, baseMonth: 11 })
  assert.deepEqual(reportRows({ table: query.preview }), [['조직', '금액'], ['사업부1', '9007199254740993.12']])
  const metric = await reportRequest(api, 'metric', { code: 'OP_BY_ORG', name: '조직별 이익', description: '질문 결과', sqlText: query.sql, preview })
  assert.deepEqual(JSON.parse(calls[1].options.body), { code: 'OP_BY_ORG', name: '조직별 이익', description: '질문 결과', sqlText: query.sql })
  assert.ok(calls[1].options.headers.get('Idempotency-Key'))
  const blocks = [{ id: 'metric-1', kind: 'table', metricId: metric.id }]
  const data = await reportRequest(api, 'data', { base, blocks })
  assert.deepEqual(JSON.parse(calls[2].options.body), { base, blocks })
  assert.deepEqual(reportRows(data.blocks[0]), [['금액'], ['9007199254740993.12']])
  assert.equal(data.dataStatus, 'PROVISIONAL')
})

/** 저장·버전 충돌 방지·불러오기·XLSX 내보내기 요청을 서버 DTO와 대조한다.
 * Args: t HTTP 대역 복원 문맥. Returns: Promise<void>. Raises: 검증 실패.
 * Side Effects: 메모리 HTTP 요청과 응답만 사용한다.
 */
test('template versions and export use saved IDs, expectedVersion and period', async function templateVersionContract(t) {
  const calls = [], definition = manualReport('월간 보고')
  const record = { id: '42', name: definition.name, latestVersion: 3, version: 3, definition }
  /** 요청을 기록하고 틀 JSON 또는 다운로드 바이트를 반환한다.
   * Args: path API 주소, options 전송 옵션. Returns: Promise<Response>.
   * Raises: 없음. Side Effects: 메모리 호출 목록 변경.
   */
  t.mock.method(globalThis, 'fetch', async function respond(path, options) { calls.push({ path, options }); return path.endsWith('/export') ? new Response('xlsx-bytes') : new Response(JSON.stringify(record)) })
  const api = createApiClient({ token: () => 'session', clear: () => {} })
  await reportRequest(api, 'save', { definition, sourcePdfJobId: '19' })
  assert.deepEqual(JSON.parse(calls[0].options.body), { name: '월간 보고', definition, sourcePdfJobId: '19' })
  await reportRequest(api, 'save', { definition, id: '42', expectedVersion: 2 })
  assert.equal(calls[1].path, '/api/v1/report/templates/42/versions')
  assert.deepEqual(JSON.parse(calls[1].options.body), { definition, expectedVersion: 2 })
  assert.ok(calls[1].options.headers.get('Idempotency-Key'))
  const restored = await reportRequest(api, 'template', { id: '42', version: 3 })
  assert.equal(calls[2].path, '/api/v1/report/templates/42?version=3')
  assert.deepEqual(restored, record)
  const file = await reportRequest(api, 'export', { id: restored.id, version: restored.version, base: { year: 2026, month: 11 }, blockId: 'summary' })
  assert.equal(await file.text(), 'xlsx-bytes')
  assert.deepEqual(JSON.parse(calls[3].options.body), { templateId: '42', version: 3, period: { year: 2026, month: 11 }, blockId: 'summary', format: 'XLSX' })
})

/** 페이지 커서와 분류 비교 기준이 query parameter로 전송되는지 검증한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패.
 * Side Effects: 메모리 호출 목록만 변경한다.
 */
test('pagination, periods and classification match controller query parameters', async function queryParameterContract() {
  const calls = []
  /** 요청을 기록하는 순수 메모리 API 대역이다.
   * Args: path API 주소, options 전송 옵션. Returns: Promise<Object>.
   * Raises: 없음. Side Effects: 메모리 호출 목록 변경.
   */
  async function api(path, options) { calls.push({ path, options }); return { items: [], nextCursor: null } }
  await reportRequest(api, 'templates', { cursor: '9' })
  await reportRequest(api, 'metrics', { q: '이익 & 비용', cursor: '50', limit: 100 })
  await reportRequest(api, 'periods', { year: 2026 })
  await reportRequest(api, 'explanations', { year: 2026, month: 11, comparison: 'ESTIMATE_VS_PREVIOUS' })
  await reportRequest(api, 'classify', { id: '91', categoryId: '7', comparison: 'ESTIMATE_VS_PREVIOUS' })
  await reportRequest(api, 'classify', { id: '91', categoryId: '' })
  assert.equal(calls[0].path, '/api/v1/report/templates?limit=50&cursor=9')
  assert.equal(calls[1].path, '/api/v1/report/metrics?q=%EC%9D%B4%EC%9D%B5+%26+%EB%B9%84%EC%9A%A9&cursor=50&limit=100')
  assert.equal(calls[2].path, '/api/v1/report/periods?year=2026')
  assert.equal(calls[3].path, '/api/v1/report/explanations?year=2026&month=11&comparison=ESTIMATE_VS_PREVIOUS')
  assert.deepEqual(calls[4], { path: '/api/v1/report/explanations/91/classification?comparison=ESTIMATE_VS_PREVIOUS', options: { method: 'PUT', body: { categoryId: '7' } } })
  assert.deepEqual(calls[5].options.body, { categoryId: null })
})

/** PDF 업로드의 마스킹 기본값과 크기 경계를 확인한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패.
 * Side Effects: 메모리 File/FormData만 만들고 파일 시스템을 사용하지 않는다.
 */
test('PDF upload masks numbers and rejects empty, oversized or non-PDF input', async function pdfContract() {
  const calls = []
  /** 요청을 기록하는 메모리 API 대역이다.
   * Args: path API 주소, options 전송 옵션. Returns: Promise<Object>.
   * Raises: 없음. Side Effects: 메모리 호출 목록 변경.
   */
  async function api(path, options) { calls.push({ path, options }); return { id: '19', status: 'RUNNING' } }
  const file = new File(['%PDF'], 'report.PDF', { type: 'application/pdf' })
  const job = await reportRequest(api, 'pdf', { file })
  assert.equal(job.status, 'RUNNING')
  assert.equal(calls[0].options.body.get('file'), file)
  assert.equal(calls[0].options.body.get('maskNumbers'), 'true')
  await assert.rejects(reportRequest(api, 'pdf', { file: { name: 'report.pdf', size: 0 } }), /30MB/)
  await assert.rejects(reportRequest(api, 'pdf', { file: { name: 'report.pdf', size: 30 * 1024 * 1024 + 1 } }), /30MB/)
  await assert.rejects(reportRequest(api, 'pdf', { file: { name: 'report.xlsx', size: 4 } }), /PDF/)
  await reportRequest(api, 'job', { id: job.id })
  await reportRequest(api, 'page', { id: job.id, no: 2 })
  assert.equal(calls[1].path, '/api/v1/report/pdf-jobs/19')
  assert.deepEqual(calls[2], { path: '/api/v1/report/pdf-jobs/19/pages/2', options: { blob: true } })
})

/** 보고서 정의를 복사하고 같은 원본에 같은 편집을 적용하면 같은 ID가 나오는지 확인한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('proposal application preserves stable IDs, existing values and the original definition', function stableProposalContract() {
  const definition = manualReport()
  definition.blocks.push({ id: 'added-3', kind: 'table', spec: { cols: [], rows: [] }, rows: [[{ text: '9007199254740993.12', won: '9007199254740993.12' }]] })
  const before = JSON.stringify(definition)
  const proposals = [
    { op: 'remove_block', target: 'summary', patch: {} },
    { op: 'add_block', target: 'title', patch: { after: 'title', spec: { type: 'chart', metric: '매출', chart_type: 'bar', limit: 10, scope: null, months_n: null } } },
    { op: 'move_block', target: 'added-3', patch: { direction: 'up' } },
    { op: 'set_style', target: 'title,added-3', patch: { patch: { family: 'serif', size: 20, bold: true } } },
    { op: 'add_column', target: 'added-3', patch: { compare: '계획' } },
  ]
  const next = applyReportProposals(definition, proposals)
  assert.deepEqual(next.blocks.map(block => block.id), ['title', 'added-3', 'added-4'])
  assert.deepEqual(next, applyReportProposals(definition, proposals))
  assert.equal(JSON.stringify(definition), before)
  assert.equal(next.blocks[1].rows[0][0].won, '9007199254740993.12')
  assert.deepEqual(next.blocks[1].spec.cols, [{ t: 'cmp', name: '계획' }])
  assert.deepEqual(applyReportProposals(next, [proposals.at(-1)]).blocks[1].spec.cols, next.blocks[1].spec.cols)
  next.blocks[1].rows[0][0].text = '변경한 복사본'
  assert.equal(JSON.stringify(definition), before)
})

/** 삭제된 ID를 재사용하거나 결과 숫자·SQL을 AI 변경안으로 넣지 못하게 한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('proposals reject stale targets and cannot inject numerical results or SQL', function proposalRejectionContract() {
  const definition = manualReport()
  definition.blocks.push({ id: 'added-3', kind: 'paragraph', text: '기존 문장' })
  const next = applyReportProposals(definition, [{ op: 'remove_block', target: 'added-3' }, { op: 'add_block', patch: { spec: { type: 'breakdown', metric: '매출' } } }])
  assert.equal(next.blocks.at(-1).id, 'added-4')
  assert.throws(() => applyReportProposals(next, [{ op: 'set_style', target: 'added-3', patch: { patch: { bold: true } } }]), /편집 대상/)
  assert.throws(() => applyReportProposals(definition, [{ op: 'set_value', target: 'summary', patch: { value: '100000' } }]), /지원하지 않는/)
  assert.throws(() => applyReportProposals(definition, [{ op: 'set_style', target: 'summary', patch: { patch: { value: '100000' } } }]), /허용되지 않은/)
  assert.throws(() => applyReportProposals(definition, [{ op: 'add_block', patch: { spec: { type: 'metric_table', metrics: ['매출'], rows: [[100000]] } } }]), /허용되지 않은/)
  assert.throws(() => applyReportProposals(definition, [{ op: 'add_block', patch: { spec: { type: 'chart', metric: '매출', sql: 'SELECT 100000' } } }]), /허용되지 않은/)
  assert.throws(() => applyReportProposals(definition, [{ op: 'add_block', patch: { spec: { type: 'numbercard', cards: [{ label: '매출', value: '100000' }] } } }]), /조회할 지표/)
  assert.throws(() => applyReportProposals(definition, [null]), /편집안/)
})

/** 봉투 검증과 표시 어댑터가 원 단위 문자열·누락·병합 셀 정보를 보존하는지 확인한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('definition and display adapters preserve exact amounts and all supported table formats', function displayContract() {
  const definition = manualReport()
  const clone = reportDefinition(definition)
  assert.notEqual(clone.blocks, definition.blocks)
  assert.throws(() => reportDefinition({ ...definition, blocks: [definition.blocks[0], definition.blocks[0]] }), /중복/)
  assert.throws(() => reportDefinition({ ...definition, schema_version: 0 }), /형식/)
  const rows = [[{ text: '실적', hdr: true, colspan: 2 }], [{ text: '90,071,992.55', won: '9007199254740993.12' }]]
  assert.equal(reportRows({ rows }), rows)
  assert.deepEqual(reportRows({ rows: { headers: ['지표'], rows: [['매출']] } }), [['지표'], ['매출']])
  assert.deepEqual(reportRows({ table: { headers: ['지표'], rows: [['매출']] } }), [['지표'], ['매출']])
  assert.deepEqual(reportRows({ rows: [{ name: '조직', won: '100' }] }), [])
  assert.equal(reportCell('9007199254740993.12'), '9007199254740993.12')
  assert.equal(reportCell(rows[1][0]), '90,071,992.55')
  assert.equal(reportCell({ missing: true, text: '0' }), '데이터 없음')
  assert.equal(reportCell(null), '데이터 없음')
})

/** 내보낼 문장이 원 금액·표시값과 잠정 상태를 유지하는지 확인한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패.
 * Side Effects: 없음; 캔버스와 다운로드를 만들지 않는다.
 */
test('PNG text preparation follows definition order and preserves exact server values', function exportTextContract() {
  const definition = { name: '보고', blocks: [{ id: 'table', kind: 'table', title: '손익' }, { id: 'chart', kind: 'chart', title: '조직 비교' }] }
  const result = { dataStatus: 'PROVISIONAL', blocks: [{ id: 'chart', rows: [{ name: '사업부1', text: '90,071,992.55', won: '9007199254740993.12' }] }, { id: 'table', table: { columns: ['원 금액'], rows: [['9007199254740993.12']] } }] }
  const before = JSON.stringify({ definition, result })
  assert.deepEqual(reportLines(definition, result, { year: 2026, month: 11 }), ['보고', '2026년 11월 · 잠정', '', '손익', '원 금액', '9007199254740993.12', '', '조직 비교', '사업부1: 90,071,992.55', ''])
  assert.equal(JSON.stringify({ definition, result }), before)
})

/** 누락된 내보내기 값은 0으로 바꾸지 않고 실제 0과 구분한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패.
 * Side Effects: 없음; 캔버스와 다운로드를 만들지 않는다.
 */
test('PNG text preparation distinguishes missing amounts from genuine zero', function exportMissingContract() {
  const definition = { name: '보고', blocks: [{ id: 'missing', kind: 'table' }, { id: 'cards', kind: 'numbercard' }] }
  const result = { dataStatus: 'NONE', blocks: [{ id: 'cards', missing: true, cards: [{ label: '누락', value: null, unit: '원' }, { label: '실제 영', value: '0', unit: '원' }], rows: [[{ text: '0', missing: true }]] }] }
  const lines = reportLines(definition, result, { year: 2026, month: 11 })
  assert.ok(lines.includes('데이터 없음'))
  assert.ok(lines.includes('일부 데이터 없음'))
  assert.ok(lines.includes('누락: 데이터 없음'))
  assert.ok(lines.includes('실제 영: 0 원'))
  assert.ok(!lines.includes('누락: 0 원'))
  assert.throws(() => reportLines(definition, null, { year: 2026, month: 11 }), /데이터로 보고서를 채워/)
})

/** 병합 셀의 숨겨진 자리를 내보내기 내용에서 제외한다.
 * Args: 없음. Returns: 없음.
 * Raises: AssertionError 병합 머리의 중복·빈 자리가 표시된 경우.
 * Side Effects: 없음; 캔버스와 다운로드를 만들지 않는다.
 */
test('PNG text omits hidden merged header cells', function exportMergedHeaderContract() {
  const definition = { name: '보고', blocks: [{ id: 'table', kind: 'table' }] }
  const result = { dataStatus: 'CONFIRMED', blocks: [{ id: 'table', rows: [[{ text: '손익', colspan: 2, hdr: true }, { text: '숨김', hide: true }, { text: '합계', hdr: true }]] }] }
  const lines = reportLines(definition, result, { year: 2026, month: 11 })
  assert.ok(lines.includes('손익  |  합계'))
  assert.ok(!lines.join('\n').includes('숨김'))
})
