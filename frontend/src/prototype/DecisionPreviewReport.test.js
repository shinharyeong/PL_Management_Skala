import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import PrimeVue from 'primevue/config'

let component
/** 보고서 SFC를 파일을 만들지 않고 메모리에서 컴파일한다.
 * Args: 없음. Returns: 컴포넌트 Promise. Raises: 소스·컴파일 오류. Side Effects: 소스 읽기만 수행.
 */
async function reportComponent() {
  if (component) return component
  const filename = new URL('./DecisionPreviewReport.vue', import.meta.url)
  const { descriptor } = parse(await readFile(filename, 'utf8'), { filename: filename.pathname })
  let code = compileScript(descriptor, { id: 'preview-report-test', inlineTemplate: true }).content
  /** 메모리 모듈에서 실제 의존성의 절대 주소를 사용한다.
   * Args: match 전체 표현, quote 따옴표, specifier 모듈명. Returns: import 표현. Raises: 모듈 해석 오류. Side Effects: 없음.
   */
  function resolveImport(match, quote, specifier) { return `from ${quote}${specifier.startsWith('.') ? new URL(specifier, filename).href : import.meta.resolve(specifier)}${quote}` }
  code = code.replace(/from (['"])([^'"]+)\1/g, resolveImport)
  component = (await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)).default
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
