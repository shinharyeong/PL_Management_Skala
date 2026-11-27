import test from 'node:test'
import assert from 'node:assert/strict'
import { artifactExpired, fetchReportArtifact, reportToolMoney, reportToolResults, REPORT_XLSX_TYPE, saveReportBlob, validArtifact } from './decision-report-tools.js'
import { buildPreviewResult } from './decision-preview-result.js'
import { conversationHistory, observationExplanations, observationTables } from './decision-conversation-api.js'
import { createApiClient } from './live-api.js'

const context = { masterId: '101', year: 2026, baseMonth: 10, analysisId: null, revision: null }
/** 현재 실행 기준의 합성 관찰을 만든다.
 * Args: tool 도구명, id 관찰 ID, data 응답 자료. Returns: 성공 관찰. Raises: 없음. Side Effects: 메모리만 사용.
 */
function observation(tool, id, data) { return { id, tool, label: tool, status: 'SUCCESS', basis: { ...context }, data, warnings: [] } }
/** 실제 파일·DB 없이 자료 틀 채우기 결과를 만든다.
 * Args: patch 바꿀 값. Returns: 합성 preview. Raises: 없음. Side Effects: 없음.
 */
function preview(patch = {}) { return { masterId: '101', year: 2026, month: 10, sourceVersion: 'master-2026-10', templateId: '7', templateVersion: 3, templateName: '경영회의', blockId: null, dataStatus: 'CONFIRMED', saved: false, blocks: [{ id: 'heading', kind: 'title', text: '경영회의 초안' }], ...patch } }
/** 다운로드 메타데이터 합성값을 만든다.
 * Args: patch 바꿀 값. Returns: 합성 산출물. Raises: 없음. Side Effects: 파일·DB 생성 없음.
 */
function artifact(patch = {}) { return { artifactId: '12345678-1234-4321-8432-123456789012', previewObservationId: 'obs-2', filename: '경영회의_2026-10.xlsx', contentType: REPORT_XLSX_TYPE, byteSize: 4, expiresAt: '2099-01-01T00:00:00Z', ready: true, masterId: '101', templateId: '7', templateVersion: 3, format: 'XLSX', saved: false, ...patch } }
/** 전사 소명 현황의 원문·기간·합계 범위를 구성한다.
 * Args: patch 바꿀 값. Returns: 합성 현황. Raises: 없음. Side Effects: 메모리만 사용.
 */
function overview(patch = {}) {
  return { masterId: '101', year: 2026, month: 10, sourceVersion: 'master-2026-10', comparison: 'ESTIMATE_VS_PREVIOUS', selectedOrganizationId: '8',
    itemYear: 2026, itemMonth: 11, itemScenario: 'ESTIMATE', hasApplicablePeriod: true,
    summary: { acceptedCount: 12, reviewingCount: 4, unclassifiedAcceptedCount: 2, fullyClassified: false },
    gapTotalWon: '-123456789.123456', classifiedTotalWon: '-100000000', matchesGapTotal: false,
    categoryTotals: [{ categoryId: '1', name: '집행 시점', gapAmountWon: '-100000000', subtotal: false }],
    categories: { tableVersion: 1, items: [{ id: '1', name: '집행 시점' }] },
    items: [{ explanationId: '20', organization: { id: '8', name: '합성 조직' }, item: '광고비', gapAmount: '-123456789.123456', answer: '<script>집행 일정 변경</script>', categoryId: '1' }],
    reviewing: [{ organization: { id: '9', name: '다른 조직' }, item: '임차료', gapAmount: '150000000' }], offset: 0, limit: 10, nextOffset: 10, hasMore: true, ...patch }
}

/** 실제 preview와 같은 실행에서 검증한 파일만 연결한다.
 * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 메모리 관찰만 사용.
 */
test('report artifacts require the exact successful preview and matching basis', function artifactBinding() {
  const rows = [observation('report_preview', 'obs-2', preview()), observation('report_export', 'obs-3', artifact())]
  const original = JSON.stringify(rows)
  assert.equal(reportToolResults(rows, context).artifacts.length, 1)
  assert.equal(JSON.stringify(rows), original)
  for (const patch of [{ masterId: '102' }, { templateVersion: 4 }, { previewObservationId: 'obs-9' }, { artifactId: '../file' }, { filename: '../../file.xlsx' }, { ready: false }]) {
    assert.equal(reportToolResults([rows[0], observation('report_export', 'obs-3', artifact(patch))], context).artifacts.length, 0)
  }
  assert.equal(reportToolResults([rows[1]], context).artifacts.length, 0)
  assert.equal(reportToolResults([{ ...rows[0], status: 'ERROR' }, rows[1]], context).artifacts.length, 0)
  assert.equal(reportToolResults(rows, { ...context, masterId: '102' }).previews.length, 0)
  assert.equal(reportToolResults([observation('report_preview', 'obs-2', preview({ sourceVersion: 'master-2026-11' }))], context).invalidCount, 1)
  for (const malformed of [{ table: { columns: '잘못된 열' } }, { cards: [null] }, { rows: [null] }, { rows: { headers: ['금액'], rows: [null] } }]) {
    assert.equal(reportToolResults([observation('report_preview', 'obs-2', preview({ blocks: [{ id: 'broken', kind: 'table', ...malformed }] }))], context).invalidCount, 1)
  }
})

/** 실패한 모델 요약과 무관하게 같은 실행의 보고서·파일은 남기고 기준 변경에는 숨긴다.
 * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 메모리 투영만 수행한다.
 */
test('partial reports retain verified exports while pending and changed scopes hide them', function partialExport() {
  const payload = { masterId: '101', analysisId: null, expectedRevision: null }
  const response = { mode: 'MODEL', status: 'LIMIT_REACHED', context, observations: [observation('report_preview', 'obs-2', preview()), observation('report_export', 'obs-3', artifact())], limitations: [] }
  const execution = { executionId: 'execution-one', phase: 'RESULT', source: 'CHAT', payload, response }
  const result = buildPreviewResult(execution, context)
  assert.equal(result.state, 'PARTIAL'); assert.equal(result.toolResults.artifacts.length, 1); assert.equal(result.executionId, 'execution-one')
  assert.equal(buildPreviewResult({ ...execution, phase: 'PENDING' }, context).toolResults, null)
  assert.equal(buildPreviewResult(execution, { ...context, masterId: '102' }).toolResults, null)
})

/** 정확한 시점·필터·단순 Gap 의미를 보존하고 원문 소명을 별도 읽기 자료로 남긴다.
 * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 숫자 합산·외부 호출 없음.
 */
test('explanation overview preserves organization details and company totals without calculating profit effects', function explanationContract() {
  const row = observation('explanation_overview', 'obs-1', overview())
  const projected = reportToolResults([row], context).explanations[0]
  assert.equal(projected.itemMonth, 11); assert.equal(projected.month, 10)
  assert.equal(projected.summary.acceptedCount, 12); assert.equal(projected.items.length, 1)
  assert.equal(projected.hasMore, true); assert.equal(projected.gapTotalWon, '-123456789.123456')
  assert.equal(reportToolMoney(projected.gapTotalWon), '-1.23456789123456억 원')
  assert.equal(observationExplanations(row)[0].text, '<script>집행 일정 변경</script>')
  const tables = observationTables(row)
  assert.match(tables[0].label, /전사/); assert.match(tables[1].label, /상위 합계 중복 주의/); assert.match(tables[2].label, /미회신 판정 아님/)
  assert.equal(reportToolResults([observation('explanation_overview', 'obs-1', overview({ categories: null }))], context).invalidCount, 1)
})

/** 이력에는 도구 상태만 남기고 다운로드 인증값·원문은 전달하지 않는다.
 * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 없음.
 */
test('new tools appear in follow-up history without artifact identifiers or file metadata', function toolHistory() {
  const history = conversationHistory([{ question: '보고서 준비', response: { status: 'COMPLETED', toolCalls: [{ tool: 'report_export', status: 'SUCCESS' }, { tool: 'explanation_overview', status: 'SUCCESS' }], observations: [observation('report_export', 'obs-3', artifact())] } }])
  assert.match(history[1].content, /report_export\(SUCCESS\)/); assert.match(history[1].content, /explanation_overview\(SUCCESS\)/)
  assert.ok(!history[1].content.includes(artifact().artifactId)); assert.ok(!history[1].content.includes('.xlsx'))
})

/** 다운로드 요청은 고정 인증 경로·정확한 메타데이터로 제한한다.
 * Args: 없음. Returns: Promise<void>. Raises: AssertionError. Side Effects: HTTP 대역·메모리 Blob만 사용한다.
 */
test('artifact download validates expiry, fixed path, binary size and content type', async function downloadContract() {
  const calls = [], controller = new AbortController(), expected = new Blob(['test'], { type: REPORT_XLSX_TYPE })
  /** 요청을 메모리에 기록한다. Args: path 경로, options 옵션. Returns: 합성 Blob. Raises: 없음. Side Effects: 메모리 기록만. */
  async function api(path, options) { calls.push({ path, options }); return expected }
  assert.equal(await fetchReportArtifact(api, artifact(), controller.signal, 1), expected)
  assert.deepEqual(calls[0], { path: '/api/v1/decision-agent/report-artifacts/' + artifact().artifactId, options: { blob: true, signal: controller.signal } })
  assert.equal(artifactExpired(artifact(), Date.parse('2099-01-01T00:00:00Z')), true)
  await assert.rejects(fetchReportArtifact(api, artifact({ expiresAt: '2000-01-01T00:00:00Z' }), controller.signal), /만료/)
  await assert.rejects(fetchReportArtifact(api, artifact({ byteSize: 5 }), controller.signal), /크기/)
  /** 잘못된 MIME 응답을 제공한다. Args: 없음. Returns: 메모리 Blob. Raises: 없음. Side Effects: 없음. */
  async function wrongType() { return new Blob(['test'], { type: 'application/json' }) }
  await assert.rejects(fetchReportArtifact(wrongType, artifact(), controller.signal), /형식/)
  assert.equal(validArtifact(artifact({ filename: 'bad\nname.xlsx' })), false)
})

/** 응답을 기다리는 사이 기준이 바뀌면 파일을 저장할 수 없게 한다.
 * Args: 없음. Returns: Promise<void>. Raises: AssertionError. Side Effects: 메모리 중단 신호만 발생한다.
 */
test('artifact download respects cancellation even when transport returns a late blob', async function cancelDownload() {
  const controller = new AbortController()
  /** 늦은 응답 직전 취소를 재현한다. Args: 없음. Returns: Blob. Raises: 없음. Side Effects: 합성 중단 신호. */
  async function lateApi() { controller.abort(); return new Blob(['test'], { type: REPORT_XLSX_TYPE }) }
  await assert.rejects(fetchReportArtifact(lateApi, artifact(), controller.signal), { name: 'AbortError' })
})

/** 기존 인증 전송 계층의 Blob 다운로드와 중단 신호 전달을 검증한다.
 * Args: 없음. Returns: Promise<void>. Raises: AssertionError.
 * Side Effects: 전역 fetch를 메모리 대역으로 잠시 교체하고 항상 복구한다. 외부 HTTP 없음.
 */
test('report artifact reuses the authenticated binary API transport', async function authenticatedDownload() {
  const originalFetch = globalThis.fetch, requests = [], controller = new AbortController()
  /** 요청을 기록하고 XLSX 응답을 돌려준다. Args: path 경로, options 요청. Returns: 메모리 Response. Raises: 없음. Side Effects: 메모리 기록만. */
  async function fakeFetch(path, options) { requests.push({ path, options }); return new Response('test', { headers: { 'Content-Type': REPORT_XLSX_TYPE } }) }
  /** 합성 인증값을 조회한다. Args: 없음. Returns: 테스트 문자열. Raises: 없음. Side Effects: 없음. */
  function token() { return 'synthetic-session' }
  /** 성공 응답에서 세션을 초기화하지 않는지 확인한다. Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 없음. */
  function clear() { assert.fail('정상 다운로드는 인증을 초기화하지 않는다.') }
  globalThis.fetch = fakeFetch
  try {
    const blob = await fetchReportArtifact(createApiClient({ token, clear }), artifact(), controller.signal, 1)
    assert.equal(await blob.text(), 'test')
    assert.equal(requests[0].options.headers.get('Authorization'), 'Bearer synthetic-session')
    assert.equal(requests[0].options.method, 'GET'); assert.equal(requests[0].options.signal, controller.signal)
    assert.equal(requests[0].options.cache, 'no-store')
  } finally { globalThis.fetch = originalFetch }
})

/** 클릭 오류에도 임시 DOM과 객체 URL이 남지 않는지 확인한다.
 * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: DOM·URL 대역 메모리만 변경한다.
 */
test('download URLs and anchors are released after successful and failed clicks', function cleanupDownload() {
  for (const fails of [false, true]) {
    const events = []
    /** 링크 해제를 기록한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 이벤트 기록. */
    function remove() { events.push('removed') }
    /** 사용자 클릭 실패를 재현한다. Args: 없음. Returns: 없음. Raises: 합성 차단 Error. Side Effects: 메모리 이벤트 기록. */
    function click() { events.push('clicked'); if (fails) throw new Error('blocked') }
    const anchor = { remove, click }
    /** 합성 링크를 제공한다. Args: 없음. Returns: 링크 대역. Raises: 없음. Side Effects: 없음. */
    function createElement() { return anchor }
    /** 링크 부착을 기록한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 이벤트 기록. */
    function appendChild() { events.push('attached') }
    /** 합성 객체 URL을 제공한다. Args: 없음. Returns: 합성 URL. Raises: 없음. Side Effects: 실제 URL 생성 없음. */
    function createObjectURL() { return 'blob:local' }
    /** URL 해제를 기록한다. Args: value URL. Returns: 없음. Raises: 없음. Side Effects: 메모리 이벤트 기록. */
    function revokeObjectURL(value) { events.push(value) }
    const document = { createElement, body: { appendChild } }, urls = { createObjectURL, revokeObjectURL }
    if (fails) assert.throws(() => saveReportBlob(new Blob(), 'report.xlsx', document, urls), /blocked/)
    else saveReportBlob(new Blob(), 'report.xlsx', document, urls)
    assert.deepEqual(events, ['attached', 'clicked', 'removed', 'blob:local'])
    assert.equal(anchor.download, 'report.xlsx')
  }
})
