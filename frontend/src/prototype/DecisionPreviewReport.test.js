import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import PrimeVue from 'primevue/config'
import { buildPreviewResult } from './decision-preview-result.js'

let component
const compiledModules = new Map()
/** SFC와 하위 SFC를 파일 생성 없이 메모리 모듈로 컴파일한다.
 * Args: filename SFC 파일 URL. Returns: data URL Promise. Raises: 읽기·컴파일·해석 오류.
 * Side Effects: 소스 읽기와 메모리 캐시만 사용한다.
 */
async function sfcModule(filename) {
  if (compiledModules.has(filename.href)) return compiledModules.get(filename.href)
  const { descriptor } = parse(await readFile(filename, 'utf8'), { filename: filename.pathname })
  let code = compileScript(descriptor, { id: 'preview-report-test', inlineTemplate: true }).content
  const imports = [...code.matchAll(/from (['"])([^'"]+)\1/g)]
  for (const [match, quote, specifier] of imports) {
    const resolved = specifier.endsWith('.vue') ? await sfcModule(new URL(specifier, filename))
      : specifier.startsWith('.') ? new URL(specifier, filename).href : import.meta.resolve(specifier)
    code = code.replace(match, `from ${quote}${resolved}${quote}`)
  }
  const result = `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
  compiledModules.set(filename.href, result)
  return result
}
/** 실제 보고서와 하위 결과 컴포넌트를 메모리에서 불러온다.
 * Args: 없음. Returns: 컴포넌트 Promise. Raises: 소스·컴파일 오류. Side Effects: 소스 읽기만 수행.
 */
async function reportComponent() {
  if (!component) component = (await import(await sfcModule(new URL('./DecisionPreviewReport.vue', import.meta.url)))).default
  return component
}
/** 검증 DTO를 실제 PrimeVue 컴포넌트의 HTML로 렌더링한다.
 * Args: result 보고서 DTO. Returns: HTML Promise. Raises: 렌더링 오류. Side Effects: 메모리 렌더링만 수행.
 */
async function renderReport(result) { return renderToString(createSSRApp(await reportComponent(), { result, year: 2026, baselineLabel: '선택한 확정 기준' }).use(PrimeVue)) }
/** 상태별 테스트에 필요한 보고서 기본 구조를 만든다.
 * Args: overrides 바꿀 필드. Returns: 합성 DTO. Raises: 없음. Side Effects: 없음.
 */
function reportData(overrides = {}) { return { state: 'EMPTY', context: null, goal: null, organizations: [], organizationCount: null, explanationCount: null, warnings: [], sources: [], capturedAt: null, ...overrides } }

/** 분석 표시 모델의 사실·해석·미해결 질문을 합성한다.
 * Args: 없음. Returns: 표시용 분석. Raises: 없음. Side Effects: 없음.
 */
function reportBrief() {
  const requirements = [{ id: 'r1', question: '계획 대비 차이 확인', status: 'ANSWERED', statusLabel: '답변됨' },
    { id: 'r2', question: '개선 가능성 확인', status: 'NEEDS_INPUT', statusLabel: '추가 확인', missingInformation: '납품 일정과 추가 비용 자료가 필요합니다.', followUpQuestion: '조직이 확인한 납품 일정은 언제인가요?' }]
  return { summary: '계획 차이는 확인했지만 개선 가능성은 추가 자료가 필요합니다.', answeredCount: 1, requirements, unresolved: [requirements[1]],
    findings: [{ id: 'f1', kind: 'FACT', kindLabel: '확인된 사실', text: '해당 사업부는 계획에 미달합니다.', uncertainty: null, citations: [{ observationId: 'obs-1', label: '조직 손익', field: '계획 대비 차이', displayQuote: '-1.23456789123456억 원' }] },
      { id: 'f2', kind: 'INTERPRETATION', kindLabel: '근거 해석', text: '소명과 계획 차이를 함께 검토할 필요가 있습니다.', uncertainty: '소명이 차이 전체를 설명하는지는 확인이 필요합니다.', citations: [{ observationId: 'obs-2', label: '승인 소명', field: '근거 내용', displayQuote: '납품 일정 변경' }] },
      { id: 'f3', kind: 'HYPOTHESIS', kindLabel: '검토 가설', text: '납품 일정 회복을 검토할 수 있습니다.', uncertainty: '금액과 반영 시점은 아직 확인하지 않았습니다.', citations: [{ observationId: 'obs-2', label: '승인 소명', field: '근거 내용', displayQuote: '<script>원문 텍스트</script>' }] }] }
}

/** 보고서가 정형 지표를 넘어 근거 해석·가설·구체적 질문을 같은 카드에 담는지 검증한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 메모리 렌더링만 수행.
 */
test('report renders grounded explanation and actual follow-up requirements with safe citations', async function analysisReport() {
  const html = await renderReport(reportData({ state: 'PARTIAL', status: 'NEEDS_INPUT', brief: reportBrief(), sources: [{ id: 'obs-1', label: '조직 손익' }, { id: 'obs-2', label: '승인 소명' }] }))
  assert.match(html, /계획 차이는 확인했지만 개선 가능성은 추가 자료가 필요합니다/)
  assert.match(html, /확인된 사실/); assert.match(html, /근거 해석/); assert.match(html, /검토 가설/)
  assert.match(html, /판단의 한계/); assert.match(html, /금액과 반영 시점은 아직 확인하지 않았습니다/)
  assert.match(html, /조직이 확인한 납품 일정은 언제인가요/)
  assert.match(html, /-1\.23456789123456억 원/)
  assert.match(html, /1 \/ 2개 답변/)
  assert.ok(!html.includes('<script>원문'))
  assert.ok(!html.includes('목표 기간과 확정 기준이 같은 범위인지 확인합니다'))
  assert.equal((html.match(/class="preview-report"/g) || []).length, 1)
})

/** 미조회 상태를 0원·0건이나 완료로 표현하지 않는지 검증한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 메모리 렌더링만 수행.
 */
test('empty report shows one report outline without invented figures or zero evidence count', async function emptyReport() {
  const html = await renderReport(reportData())
  assert.match(html, /검토할 목표를 기다리고 있습니다/)
  assert.match(html, /승인 소명은 추가 확인이 필요합니다/)
  assert.ok(!html.includes('승인 소명 0건'))
  assert.equal((html.match(/class="preview-report"/g) || []).length, 1)
  assert.ok(!html.includes('완료되었습니다'))
})

/** 부분 결과에서도 정확한 전망·목표·차이와 전체 조직 수가 보존되는지 확인한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 메모리 렌더링만 수행.
 */
test('partial report renders precise backend goal values and keeps total organization count', async function partialReport() {
  const organizations = []
  for (let index = 1; index <= 5; index++) organizations.push({ id: String(index), name: `사업부 ${index}`, actualWon: '146092115000', planDifferenceWon: index === 5 ? '-123456789.123456' : '123456789.123456', direction: index === 5 ? 'DECREASE' : 'INCREASE', favorability: index === 5 ? 'UNFAVORABLE' : 'FAVORABLE', missing: false })
  const html = await renderReport(reportData({ state: 'PARTIAL', context: { masterLabel: '11월 확정', year: 2026, baseMonth: 11 }, goal: { baselineProfitWon: '146092115000', targetProfitWon: '100000000000', gapWon: '0', surplusWon: '46092115000', forecastMeetsTarget: true }, organizations, organizationCount: 16, explanationCount: 0, sources: [{ id: 'obs-1', label: '목표 비교 원문' }], capturedAt: '2026-11-26T00:00:00Z' }))
  assert.match(html, /현재 전망은 목표 이상입니다/)
  assert.match(html, /1,460\.92115/)
  assert.match(html, /460\.92115/)
  assert.match(html, /-1\.23456789123456/)
  assert.match(html, /계획 미달/)
  assert.match(html, /16개 조직 중/)
  assert.match(html, /5개 표시/)
  assert.match(html, /승인 소명 0건 조회/)
  assert.match(html, /답변 일부 미완료/)
  assert.match(html, /목표 비교 원문/)
})

/** 실제 차이 조회 계약을 보고서까지 연결해 누락된 표를 표시하고 미조회 전망을 구분한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 합성 자료의 메모리 투영·SSR만 수행한다.
 */
test('driver-only results render organization differences with their limited scope and source', async function driverOnlyReport() {
  const context = { masterId: '101', year: 2026, baseMonth: 10, analysisId: null, revision: null }
  const observation = { id: 'driver-source', tool: 'profit_drivers', label: '합성 손익 차이', status: 'SUCCESS', basis: context,
    data: { total: '-9', items: [{ organization: { id: 'synthetic-org', name: '합성 조직' }, opDiff: '-123456789.123456', opDiffRate: '-1.2' }] }, warnings: [] }
  const result = buildPreviewResult({ phase: 'RESULT', source: 'CHAT', payload: { masterId: '101', analysisId: null, expectedRevision: null },
    response: { mode: 'MODEL', status: 'NEEDS_INPUT', context, observations: [observation], limitations: [] } }, context)
  const html = await renderReport(result)
  assert.match(html, /합성 조직/)
  assert.match(html, /-1\.23456789123456/)
  assert.match(html, /차이만 조회/)
  assert.match(html, /연간 전망 미조회/)
  assert.match(html, /전체 조직 수는 조회되지 않았습니다/)
  assert.match(html, /합성 손익 차이/)
  assert.ok(!html.includes('조직별 계획 차이는 추가 조회가 필요합니다'))
  assert.ok(!html.includes('기준 1개 조직 중'))
})

/** stale·로딩 상태에서는 이전 금액이 DTO에 남아 있어도 표시하지 않는다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 메모리 렌더링만 수행.
 */
test('stale and loading report states suppress old goal values and source actions', async function staleReport() {
  const previous = { goal: { baselineProfitWon: '9876543210000', targetProfitWon: '100000000000', surplusWon: '9776543210000', gapWon: '0', forecastMeetsTarget: true }, organizations: [{ id: 'old', name: '이전 조직', actualWon: '9876543210000' }], sources: [{ id: 'old', label: '이전 원문' }], brief: reportBrief() }
  for (const state of ['STALE', 'LOADING']) {
    const html = await renderReport(reportData({ ...previous, state }))
    assert.ok(!html.includes('98,765.4321') && !html.includes('이전 조직') && !html.includes('이전 원문'))
    assert.ok(!html.includes('납품 일정') && !html.includes('근거를 연결한 분석'))
    assert.match(html, state === 'STALE' ? /기준이 바뀌어 다시 비교가 필요합니다/ : /현재 전망과 목표를 확인하고 있습니다/)
  }
})

/** 중단된 분석의 유효 문장은 남기고 미완료 안내와 서버 원인을 가리지 않는다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증 실패. Side Effects: 합성 DTO의 메모리 SSR만 수행한다.
 */
test('partial narrative is shown with incomplete status and the server reason rather than generic retry advice', async function partialNarrative() {
  const brief = reportBrief(), reason = '시스템 안내: 모델 응답의 요약 검증을 완료하지 못했습니다.'
  const html = await renderReport(reportData({ state: 'PARTIAL', status: 'LIMIT_REACHED', brief,
    executionSummary: '조회 성공 2건 · 답변 미완료', incompleteReason: reason, sources: [{ id: 'obs-1', label: '합성 조회 근거' }] }))
  assert.ok(html.includes(brief.summary))
  assert.ok(html.includes(brief.findings[0].text))
  assert.ok(html.includes(reason))
  assert.match(html, /조회 성공 2건 · 답변 미완료/)
  assert.ok(!html.includes('질문 범위를 좁혀'))
  assert.ok(!html.includes('요청 항목 확인 완료'))
  const stale = await renderReport(reportData({ state: 'STALE', brief, executionSummary: '조회 성공 2건 · 답변 미완료', incompleteReason: reason }))
  assert.ok(!stale.includes(reason))
  assert.ok(!stale.includes(brief.findings[0].text))
})

/** 실제 도구 응답을 한 장 보고서에 연결하고 파일·소명 범위와 원문 이스케이프를 확인한다.
 * Args: 없음. Returns: Promise<void>. Raises: AssertionError. Side Effects: 메모리 응답 투영·SSR만 사용한다.
 */
test('report tool results render preview, linked download and explanation coverage even after incomplete narrative', async function reportTools() {
  const context = { masterId: '101', year: 2026, baseMonth: 10, analysisId: null, revision: null }
  /** 같은 기준의 성공 관찰을 만든다. Args: tool 이름, id 식별자, data 자료. Returns: 관찰. Raises: 없음. Side Effects: 없음. */
  function observation(tool, id, data) { return { tool, id, data, label: tool, basis: context, status: 'SUCCESS', warnings: [] } }
  const basis = { masterId: '101', year: 2026, month: 10, sourceVersion: 'master-2026-10' }
  const observations = [
    observation('report_preview', 'obs-1', { ...basis, templateId: '7', templateVersion: 3, templateName: '경영회의 자료', blockId: null, dataStatus: 'CONFIRMED', saved: false,
      blocks: [{ id: 't', kind: 'title', text: '실제 조회한 보고서' }, { id: 'p', kind: 'paragraph', text: '<script>수락 소명 원문</script>' }, { id: 'c', kind: 'numbercard', cards: [{ label: '조회 금액', won: '-123456789.123456' }] }] }),
    observation('report_export', 'obs-2', { artifactId: '12345678-1234-4321-8432-123456789012', previewObservationId: 'obs-1', filename: '경영회의_2026-10.xlsx',
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', byteSize: 4000, expiresAt: '2099-01-01T00:00:00Z',
      ready: true, masterId: '101', templateId: '7', templateVersion: 3, format: 'XLSX', saved: false }),
    observation('explanation_overview', 'obs-3', { ...basis, comparison: 'ESTIMATE_VS_PREVIOUS', itemYear: 2026, itemMonth: 11, itemScenario: 'ESTIMATE', hasApplicablePeriod: true,
      selectedOrganizationId: '8', summary: { acceptedCount: 12, reviewingCount: 4, unclassifiedAcceptedCount: 2, fullyClassified: false },
      gapTotalWon: '-123456789.123456', classifiedTotalWon: '-100000000', matchesGapTotal: false,
      categoryTotals: [{ categoryId: '1', name: '집행 시점', gapAmountWon: '-100000000', subtotal: true }], categories: { items: [{ id: '1', name: '집행 시점' }] },
      items: [{ explanationId: '20', organization: { id: '8', name: '조회한 조직' }, item: '광고비', gapAmount: '-123456789.123456', answer: '<img src=x onerror=bad()>일정 변경', categoryId: '1' }],
      reviewing: [{ organization: { name: '확인 조직' }, item: '임차료', gapAmount: '150000000' }], offset: 0, limit: 10, nextOffset: 10, hasMore: true }),
  ]
  const result = buildPreviewResult({ executionId: 'current-report', phase: 'RESULT', source: 'CHAT', payload: { masterId: '101', analysisId: null, expectedRevision: null },
    response: { mode: 'MODEL', status: 'LIMIT_REACHED', context, observations, limitations: [] } }, context)
  const html = await renderReport(result)
  assert.match(html, /실제 조회한 보고서/); assert.match(html, /XLSX 다운로드/); assert.match(html, /경영회의_2026-10.xlsx/)
  assert.match(html, /보고서 파일을 준비했습니다/); assert.ok(!html.includes('목표 비교 자료를 추가로 확인해야 합니다'))
  assert.match(html, /조회한 페이지의 수락 원문 1건 확인/); assert.ok(!html.includes('승인 소명은 추가 확인이 필요합니다'))
  assert.match(html, /-1\.23456789123456억 원/); assert.match(html, /서버 메모리에만 보관/)
  assert.match(html, /소명 대상: 2026년 11월 · 추정/); assert.match(html, /전사 수락 소명/); assert.match(html, /12건/)
  assert.match(html, /위 분류 합계·건수는 전사 기준/); assert.match(html, /상위 합계/); assert.match(html, /영업이익 영향이나 개선 가능액이 아닙니다/)
  assert.match(html, /상세 자료가 더 있습니다/); assert.match(html, /수락 소명이 없는 항목을 모두 미회신으로 판단하지 않습니다/)
  assert.ok(!html.includes('<script>수락') && !html.includes('<img src=x'))
  assert.equal((html.match(/class="preview-report"/g) || []).length, 1)
  for (const state of ['LOADING', 'STALE', 'EMPTY']) {
    const hidden = await renderReport({ ...result, state })
    assert.ok(!hidden.includes('경영회의_2026-10.xlsx') && !hidden.includes('실제 조회한 보고서') && !hidden.includes('조회한 조직'))
    assert.ok(!hidden.includes('보고서 파일을 준비했습니다') && !hidden.includes('수락 원문 1건'))
  }
  const repeated = { ...result, explanationCount: 3, toolResults: { ...result.toolResults,
    explanations: [...result.toolResults.explanations, { ...result.toolResults.explanations[0], observationId: 'obs-4', comparison: 'ACTUAL_VS_PLAN' }] } }
  const repeatedHtml = await renderReport(repeated)
  assert.match(repeatedHtml, /조회한 페이지의 수락 원문 1건 확인/)
  assert.match(repeatedHtml, /별도 승인 소명 조회 3건과 중복될 수 있습니다/)
  assert.ok(!repeatedHtml.includes('수락 원문 12건') && !repeatedHtml.includes('수락 원문 2건'))
  for (const [excluded, expected] of [[['artifacts'], '보고서 미리보기를 확인했습니다'], [['artifacts', 'previews'], '전사 소명 현황을 확인했습니다']]) {
    const toolResults = { ...result.toolResults }
    for (const key of excluded) toolResults[key] = []
    const partialHtml = await renderReport({ ...result, toolResults })
    assert.ok(partialHtml.includes(expected)); assert.ok(!partialHtml.includes('보고서 파일을 준비했습니다'))
  }
})

/** 빈 자료 틀 목록을 목표 비교 실패와 구분하고 아직 없는 파일을 약속하지 않는다.
 * Args: 없음. Returns: Promise<void>. Raises: AssertionError. Side Effects: 합성 결과의 메모리 SSR만 사용한다.
 */
test('empty report template catalog has an explicit report preparation state', async function emptyTemplates() {
  const toolResults = { templates: [{ observationId: 'obs-1', items: [], nextCursor: null }], previews: [], artifacts: [], explanations: [], invalidCount: 0 }
  const result = reportData({ state: 'PARTIAL', sources: [{ id: 'obs-1', label: '보고서 자료 틀 조회' }], toolResults })
  const html = await renderReport(result)
  assert.match(html, /사용 가능한 보고서 자료 틀이 없습니다/)
  assert.match(html, /이번 실행에서 파일을 만들지 않았습니다/)
  assert.ok(!html.includes('XLSX 다운로드') && !html.includes('목표 비교 자료를 추가로 확인해야 합니다'))
  const stale = await renderReport({ ...result, state: 'STALE', explanationCount: 19 })
  assert.ok(!stale.includes('자료 틀을 등록한 뒤') && !stale.includes('승인 소명 19건'))
})
