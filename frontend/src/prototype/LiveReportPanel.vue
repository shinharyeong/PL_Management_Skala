<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { saveFile } from './live-api.js'
import { seoulPeriod } from './domain.js'
import { applyReportProposals, manualReport, reportDefinition, reportRequest } from './report-api.js'
import { reportPngOf } from './report-export.js'
import StructureReview from '../components/StructureReview.vue'
import ReportStart from '../components/ReportStart.vue'
import ReportDataSelect from '../components/ReportDataSelect.vue'
import ReportClassify from '../components/ReportClassify.vue'
import ReportStudio from '../components/ReportStudio.vue'
import ReportDataTable from '../components/ReportDataTable.vue'
import { pageSizes } from '../lib/structure-review.js'
import { STEP_LABELS, isTextual } from '../lib/report-studio.js'
import '../styles/report-studio.css'

const store = usePrototypeStore()
const steps = STEP_LABELS
const step = ref(0), busy = ref(false), error = ref(''), notice = ref('')
const definition = ref(null), draftName = ref('새 경영 보고서'), templates = ref([]), cursor = ref(null), metrics = ref([])
const saved = ref(null), savedContent = ref(''), job = ref(null), pageImages = ref([])
const periods = ref(null), base = ref(seoulPeriod())
const categories = ref([]), explanations = ref(null), editPreview = ref([])
/** 3단계에서 고른 금액 단위 중 가장 많이 쓴 단위(없으면 억원). 4단계 소명 금액을 이 단위로 보여 준다. */
const reportUnit = computed(() => {
  const count = {}
  for (const block of definition.value?.blocks || []) if (!isTextual(block)) count[block.unit || '억원'] = (count[block.unit || '억원'] || 0) + 1
  return Object.entries(count).sort((a, b) => b[1] - a[1])[0]?.[0] || '억원'
})
const COMPARISONS = ['ACTUAL_VS_PLAN', 'ESTIMATE_VS_PLAN']   // 실적 비교(기준 월 항목)와 추정 비교(기준 월+1 항목)를 모두 불러와 한 목록으로 쓴다. 화면에서 고르지 않는다.
const results = ref(null), question = ref(''), queryResult = ref(null), metricCode = ref(''), metricName = ref('')
const instruction = ref(''), editResult = ref(null), editSource = ref(''), undo = ref(null)
const addedKind = ref('metric_table'), addedMetric = ref('영업이익')
const pdfInput = ref(null), controller = new AbortController()
const pageSizeMap = computed(() => pageSizes(definition.value))
const dirty = computed(() => definition.value && JSON.stringify(definition.value) !== savedContent.value)
const assignable = computed(() => categories.value.filter(item => item.assignable))


/** 연결이 끝난 화면의 응답을 차단하는 인증 요청을 보낸다.
 * Args: path 공개 API 경로, options 전송 옵션.
 * Returns: API 응답 Promise. Raises: HTTP·중단 오류.
 * Side Effects: 인증된 HTTP 요청.
 */
async function api(path, options = {}) { return store.api(path, { ...options, signal: controller.signal }) }

/** 화면 작업을 직렬 실행하고 오류를 표시한다.
 * Args: work 실행할 비동기 작업.
 * Returns: 완료 여부. Raises: 없음; 오류를 화면에 표시한다.
 * Side Effects: 화면 상태와 work가 정의한 서버 작업.
 */
async function run(work) {
  if (busy.value) return false
  busy.value = true; error.value = ''; notice.value = ''
  try { await work(); return !controller.signal.aborted }
  catch (cause) { if (!controller.signal.aborted) error.value = cause.message; return false }
  finally { busy.value = false }
}

/** 서버의 자료 틀 목록을 다음 페이지까지 추가한다.
 * Args: reset 첫 페이지부터 다시 조회할지 여부.
 * Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 자료 틀 목록과 다음 페이지 위치 변경.
 */
async function loadTemplates(reset = false) {
  const response = await reportRequest(api, 'templates', { cursor: reset ? null : cursor.value })
  templates.value = [...(reset ? [] : templates.value), ...(response.items || [])]
  cursor.value = response.nextCursor
}

/** 화면 초기 목록과 기본 기준 월을 서버에서 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 서버 조회와 화면 목록 변경.
 */
async function initialize() {
  await loadTemplates(true)
  periods.value = await reportRequest(api, 'periods')
  if (periods.value.default) base.value = { ...periods.value.default }
  if (periods.value.year !== base.value.year) periods.value = await reportRequest(api, 'periods', base.value)
  metrics.value = []
  let metricCursor
  do {
    const response = await reportRequest(api, 'metrics', { cursor: metricCursor })
    metrics.value.push(...(response.items || [])); metricCursor = response.nextCursor
  } while (metricCursor)
  categories.value = (await reportRequest(api, 'categories')).items || []
}
onMounted(() => run(initialize))

/** 다운로드한 페이지 이미지의 브라우저 메모리를 해제한다.
 * Args: 없음. Returns: 없음. Raises: 없음.
 * Side Effects: object URL 해제와 이미지 목록 초기화.
 */
function clearImages() { for (const page of pageImages.value) URL.revokeObjectURL(page.url); pageImages.value = [] }
onBeforeUnmount(() => { controller.abort(); clearImages(); document.body.classList.remove('report-printing') })

/** 새 자료 틀 작업을 시작하며 이전 분석 결과를 분리한다.
 * Args: value 사용할 정의, record 저장된 틀이면 서버 메타데이터.
 * Returns: 없음. Raises: Error 잘못된 자료 틀.
 * Side Effects: 편집·조회·저장 상태 초기화.
 */
function openDefinition(value, record = null) {
  definition.value = reportDefinition(value); saved.value = record
  savedContent.value = record ? JSON.stringify(definition.value) : ''
  results.value = null; editResult.value = null; undo.value = null; queryResult.value = null
  step.value = 1
}

/** 기존 자료 틀의 최신 버전을 불러온다.
 * Args: id 자료 틀 ID. Returns: Promise<void>. Raises: HTTP·정의 오류.
 * Side Effects: 서버 조회와 현재 작업 교체.
 */
async function loadTemplate(id) {
  const record = await reportRequest(api, 'template', { id })
  clearImages(); job.value = null; openDefinition(record.definition, record)
}

/** 직접 만든 자료 틀로 새 작업을 시작한다.
 * Args: 없음. Returns: 없음. Raises: Error 이름 오류.
 * Side Effects: 화면 작업 초기화.
 */
function startManual() { clearImages(); job.value = null; openDefinition(manualReport(draftName.value)) }

/** PDF 작업의 최신 상태와 성공한 분석 결과를 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP·분석 오류.
 * Side Effects: 서버 조회, 분석 정의와 마스킹 이미지 메모리 표시.
 */
async function refreshJob() {
  if (!job.value?.id) return
  job.value = await reportRequest(api, 'job', { id: job.value.id })
  if (job.value.status === 'FAILED') throw new Error(job.value.errorMessage || 'PDF 분석에 실패했습니다. 다시 시도하거나 직접 만들어 주세요.')
  if (job.value.status !== 'SUCCEEDED') { notice.value = 'PDF를 분석하고 있습니다. 잠시 후 분석 상태를 새로고침해 주세요.'; return }
  openDefinition(job.value.definition)
  clearImages()
  for (const page of job.value.pages || []) {
    const blob = await reportRequest(api, 'page', { id: job.value.id, no: page.no })
    if (!controller.signal.aborted) pageImages.value.push({ no: page.no, url: URL.createObjectURL(blob) })
  }
}

/** 선택한 PDF를 업로드하고 비동기 분석 상태를 조회한다.
 * Args: event PrimeVue FileUpload select 이벤트. Returns: Promise<void>. Raises: run에서 처리.
 * Side Effects: 서버 PDF 작업 생성과 화면 상태 변경.
 */
async function uploadPdf(event) {
  const file = event.files?.[0]
  if (!file) return
  pdfInput.value?.clear()
  await run(async () => { clearImages(); job.value = await reportRequest(api, 'pdf', { file }); await refreshJob() })
  pollJob()
}

/** 분석이 끝날 때까지 3초마다 상태를 확인한다(최대 2분). 화면을 떠나거나 단계가 바뀌면 멈춘다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(오류는 화면에 표시).
 * Side Effects: 서버 조회, 완료 시 분석 정의 표시.
 */
async function pollJob() {
  for (let i = 0; i < 40 && job.value?.status === 'RUNNING' && step.value === 0 && !controller.signal.aborted; i++) {
    await new Promise(resolve => setTimeout(resolve, 3000))
    if (job.value?.status !== 'RUNNING' || step.value !== 0 || controller.signal.aborted) break
    try { job.value = await reportRequest(api, 'job', { id: job.value.id }) } catch { continue }   // 진행 중에는 조용히 조회(화면을 잠그지 않는다)
    if (job.value?.status !== 'RUNNING') await run(refreshJob)
  }
  if (job.value?.status === 'RUNNING' && step.value === 0 && !controller.signal.aborted) notice.value = 'PDF 분석이 평소보다 오래 걸리고 있습니다. 잠시 뒤 “상태 새로고침”을 눌러 확인해 주세요.'
}

/** 편집한 정의의 이전 데이터·AI 제안을 무효화한다.
 * Args: 없음. Returns: 없음. Raises: 없음.
 * Side Effects: 화면의 계산 결과·제안 초기화.
 */
function invalidate() { results.value = null; editResult.value = null; editPreview.value = [] }

/** 확인이 필요한 추출 블록을 사용자가 확인했음을 반영한다.
 * Args: block 확인할 블록. Returns: 없음. Raises: 없음.
 * Side Effects: 확인 목록과 오래된 결과를 초기화한다.
 */
function acknowledgeBlock(block) { block.needs_review = []; invalidate() }

/** 기준 월 변경에 맞춰 이전 조회 결과를 비운다.
 * Args: month 선택한 월 숫자. Returns: 없음. Raises: 없음.
 * Side Effects: 기준 월과 데이터·소명·질문 결과를 변경한다.
 */
function changeMonth(month) { base.value.month = month; invalidate(); explanations.value = null; queryResult.value = null }

/** 선택한 기간 관계를 서버의 기준 월 규칙으로 변환한다.
 * Args: block 편집할 블록, rule base 또는 base_offset. Returns: 없음. Raises: 없음.
 * Side Effects: 블록 기간과 오래된 결과를 변경한다.
 */
function changePeriod(block, rule) { block.period = rule === 'base' ? { rule: 'base' } : { rule: 'base_offset', offset: -1 }; invalidate() }

/** 월 이동 값을 반영하고 0은 기준 월로 정규화한다.
 * Args: block 편집할 블록, offset 이동할 개월 수. Returns: 없음. Raises: 없음.
 * Side Effects: 유효한 정수이면 블록 기간·오래된 결과를 변경하고, 아니면 오류만 표시한다.
 */
function changeOffset(block, offset) {
  const months = offset ?? 0
  if (!Number.isInteger(months) || months < -24 || months > 24) { error.value = '월 이동 값은 -24부터 24까지의 정수로 입력해 주세요.'; return }
  block.period = months ? { rule: 'base_offset', offset: months } : { rule: 'base' }
  invalidate()
}


/** 사용자가 고른 종류의 빈 보고 블록을 추가한다.
 * Args: 없음. Returns: 없음. Raises: Error 잘못된 정의.
 * Side Effects: 현재 정의 변경; 숫자는 채우기 전까지 생성하지 않는다.
 */
function addBlock() {
  if (addedKind.value === 'paragraph') {
    let index = definition.value.blocks.length + 1
    while (definition.value.blocks.some(block => block.id === `text-${index}`)) index++
    definition.value.blocks.push({ id: `text-${index}`, kind: 'paragraph', text: '' })
  } else {
    const spec = addedKind.value === 'numbercard' ? { type: 'numbercard', cards: [{ label: addedMetric.value, unit: '억원', delta_label: '계획比' }] }
      : addedKind.value === 'chart' ? { type: 'chart', metric: addedMetric.value, chart_type: 'bar', limit: 10 }
        : { type: 'metric_table', metrics: [addedMetric.value], compare: ['계획'] }
    definition.value = applyReportProposals(definition.value, [{ op: 'add_block', patch: { spec } }])
  }
  invalidate()
}

/** 한 블록을 이동하거나 삭제한다.
 * Args: id 블록 ID, direction up/down/remove.
 * Returns: 없음. Raises: Error 잘못된 편집 대상.
 * Side Effects: 화면 정의 변경.
 */
function changeBlock(id, direction) {
  definition.value = applyReportProposals(definition.value, [{ op: direction === 'remove' ? 'remove_block' : 'move_block', target: id, patch: { direction } }])
  invalidate()
}

/** 기준 연도의 조회 가능 월과 소명 상태를 갱신한다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 서버 조회와 오래된 결과 초기화.
 */
async function changeYear() { periods.value = await reportRequest(api, 'periods', base.value); invalidate(); explanations.value = null; queryResult.value = null }

/** InputNumber가 확정한 연도로 조회 가능 월을 갱신한다.
 * Args: year 확정한 연도 숫자. Returns: Promise<boolean>. Raises: 없음(화면 오류로 표시).
 * Side Effects: 2000~9999 정수 연도만 변경·조회하며, 아니면 오류만 표시한다. blur 이후 모델 갱신을 사용한다.
 */
function changeYearValue(year) {
  if (!Number.isInteger(year) || year < 2000 || year > 9999) { error.value = '연도는 2000부터 9999까지의 정수로 입력해 주세요.'; return Promise.resolve(false) }
  base.value.year = year
  return run(changeYear)
}


/** 선택한 기준 월의 승인 소명을 실적·추정 비교 모두 읽어 한 목록으로 합친다.
 * 소명마다 어느 비교에서 왔는지(comparison)를 달아 두어 분류를 저장할 때 그 비교로 보낸다. 합계는 두 비교의 합이다.
 * Args: 없음. Returns: Promise<void>. Raises: 두 비교 모두 실패하면 HTTP 오류.
 * Side Effects: 서버 조회와 소명 목록 갱신.
 */
async function loadExplanations() {
  const settled = await Promise.allSettled(COMPARISONS.map(comparison => reportRequest(api, 'explanations', { ...base.value, comparison })))
  const got = settled.map((result, i) => ({ comparison: COMPARISONS[i], result })).filter(entry => entry.result.status === 'fulfilled')
  if (!got.length) throw settled[0].reason
  const total = key => got.reduce((sum, entry) => sum + (Number(entry.result.value[key]) || 0), 0)
  const seen = new Set()
  const items = got.flatMap(entry => (entry.result.value.items || []).map(item => ({ ...item, comparison: entry.comparison })))
    .filter(item => !seen.has(item.explanationId) && seen.add(item.explanationId))
  explanations.value = { ...got[0].result.value, items, reviewing: got.flatMap(entry => entry.result.value.reviewing || []),
    gapTotal: total('gapTotal'), classifiedTotal: total('classifiedTotal'), matchesGapTotal: got.every(entry => entry.result.value.matchesGapTotal) }
}

/** 소명 분류를 사용자가 선택한 값으로 저장한다.
 * Args: item 소명 카드, categoryId 분류 ID 또는 빈 문자열.
 * Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 서버 분류 저장 후 최신 합계 조회.
 */
async function classify(item, categoryId) {
  await reportRequest(api, 'classify', { id: item.explanationId, categoryId, comparison: item.comparison || COMPARISONS[0] })
  await loadExplanations()
}

/** 현재 블록을 기준 월의 실제 데이터로 채운다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP·정의 오류.
 * Side Effects: 서버 조회와 미리보기 교체.
 */
async function fill() {
  const value = reportDefinition(definition.value)
  results.value = await reportRequest(api, 'data', { base: { ...base.value }, blocks: value.blocks })
  for (const block of definition.value.blocks) {
    const result = results.value.blocks?.find(item => item.id === block.id)
    if (block.kind === 'table' && !block.spec && result?.spec) block.spec = JSON.parse(JSON.stringify(result.spec))
  }
  step.value = 4
}

/** 자연어 질문의 SQL과 데이터 미리보기를 요청한다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 읽기 전용 조회와 화면 응답 갱신.
 */
async function ask() { queryResult.value = await reportRequest(api, 'query', { question: question.value.trim(), base: { ...base.value } }) }

/** 모호한 질문에 대한 선택을 원래 질문에 덧붙이고 지난 조회를 비운다.
 * Args: option 서버가 제안한 확인 선택지.
 * Returns: 없음. Raises: 없음.
 * Side Effects: 질문 입력과 조회 결과만 변경한다.
 */
function clarifyQuestion(option) {
  const label = typeof option === 'string' ? option : option.label
  question.value = `${question.value}\n추가 조건: ${label}`.slice(0, 1000)
  queryResult.value = null
}

/** 확인한 질문 결과를 재사용할 지표로 등록한다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP·SQL 검증 오류.
 * Side Effects: 서버 지표 사전 저장과 블록 추가.
 */
async function registerMetric() {
  const metric = await reportRequest(api, 'metric', { code: metricCode.value.trim(), name: metricName.value.trim(), description: question.value, sqlText: queryResult.value.sql })
  metrics.value = [...metrics.value, metric]
  addMetric(metric.id); metricCode.value = ''; metricName.value = ''
  notice.value = '지표를 저장하고 보고서에 추가했습니다.'
}

/** 저장된 지표를 자료 틀에 참조로 추가한다.
 * Args: id 서버 지표 ID. Returns: 없음. Raises: 없음.
 * Side Effects: 현재 정의 변경; SQL은 서버에서 해석한다.
 */
function addMetric(id) {
  const metric = metrics.value.find(item => item.id === id)
  if (!metric || !definition.value) return
  let index = definition.value.blocks.length + 1
  while (definition.value.blocks.some(block => block.id === `metric-${index}`)) index++
  definition.value.blocks.push({ id: `metric-${index}`, kind: 'table', title: metric.name, metricId: id })
  invalidate()
}

/** AI 편집안을 요청하되 현재 정의에는 적용하지 않는다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP·정의 오류.
 * Side Effects: AI 호출과 제안 미리보기 표시.
 */
async function requestEdit() {
  editSource.value = JSON.stringify(definition.value)
  const value = reportDefinition(definition.value)
  value.baseRule = { mode: 'fixed', ...base.value }
  const reply = await reportRequest(api, 'edit', { definition: value, instruction: instruction.value.trim() })
  editPreview.value = []
  if (reply.status === 'EDIT' && reply.proposals?.some(p => p.op === 'add_block')) {   // 추가될 차트·표를 채팅 안에서 미리 보여 준다(값은 코드가 계산한 데이터)
    const ids = new Set(value.blocks.map(block => block.id))
    const added = applyReportProposals(value, reply.proposals).blocks.filter(block => !ids.has(block.id))
    if (added.length) {
      const data = await reportRequest(api, 'data', { base: { ...base.value }, blocks: added })
      editPreview.value = added.map(block => ({ block, result: data.blocks?.find(item => item.id === block.id) || null }))
    }
  }
  editResult.value = reply
}

/** 확인한 AI 편집안을 적용하고 데이터 미리보기를 다시 만든다.
 * Args: 없음. Returns: Promise<void>. Raises: Error 오래된 제안, HTTP 오류.
 * Side Effects: 정의 변경·되돌리기 상태 보관·데이터 조회.
 */
async function acceptEdit() {
  if (JSON.stringify(definition.value) !== editSource.value) throw new Error('자료 구성이 바뀌었습니다. 편집안을 다시 요청해 주세요.')
  const next = applyReportProposals(definition.value, editResult.value.proposals)
  undo.value = JSON.stringify(definition.value); definition.value = next; invalidate(); await fill()
}

/** 마지막 AI 편집을 되돌린다.
 * Args: 없음. Returns: 없음. Raises: 없음.
 * Side Effects: 정의 복원과 결과 초기화.
 */
function undoEdit() { if (undo.value) { definition.value = JSON.parse(undo.value); undo.value = null; invalidate() } }

/** 현재 구성을 새 틀 또는 기존 틀의 다음 버전으로 저장한다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP·버전 충돌 오류.
 * Side Effects: 서버에 불변 틀 버전 저장.
 */
async function saveTemplate() {
  const record = await reportRequest(api, 'save', { definition: definition.value, id: saved.value?.id, expectedVersion: saved.value?.latestVersion, sourcePdfJobId: job.value?.status === 'SUCCEEDED' ? job.value.id : null })
  saved.value = record; definition.value = record.definition; savedContent.value = JSON.stringify(record.definition)
  await loadTemplates(true); notice.value = `자료 틀 ${record.version}버전을 저장했습니다.`
}

/** 저장된 현재 버전과 기준 월을 XLSX로 내려받는다.
 * Args: 없음. Returns: Promise<void>. Raises: Error 미저장 변경, HTTP 오류.
 * Side Effects: 서버 조회와 사용자 브라우저 다운로드.
 */
async function exportExcel() {
  if (!saved.value || dirty.value) throw new Error('현재 구성을 먼저 저장한 뒤 내보내 주세요.')
  const file = await reportRequest(api, 'export', { id: saved.value.id, version: saved.value.version, base: { ...base.value } })
  saveFile(file, `보고서_${base.value.year}-${String(base.value.month).padStart(2, '0')}.xlsx`)
}

/** 조회된 보고서 내용을 PNG로 내려받는다.
 * Args: 없음. Returns: Promise<void>. Raises: PNG 생성 오류.
 * Side Effects: 메모리 캔버스와 사용자 브라우저 다운로드.
 */
async function exportPng() {
  saveFile(await reportPngOf(definition.value, results.value, base.value), `보고서_${base.value.year}-${String(base.value.month).padStart(2, '0')}.png`)
}

/** 미리보기 영역만 브라우저 인쇄·PDF 저장 대화상자에 표시한다.
 * Args: 없음. Returns: 없음. Raises: 브라우저 인쇄 오류.
 * Side Effects: 인쇄 대화상자 표시와 인쇄 전용 스타일 활성화.
 */
function printReport() {
  document.body.classList.add('report-printing')
  window.addEventListener('afterprint', () => document.body.classList.remove('report-printing'), { once: true })
  try { window.print() } catch (cause) { document.body.classList.remove('report-printing'); throw cause }
}


/** 단계를 옮긴다. 소명 분류 단계에 처음 들어오면 승인 소명을 자동으로 불러온다.
 * Args: next 이동할 단계 번호. Returns: 없음. Raises: 없음(오류는 화면에 표시).
 * Side Effects: 단계 변경, 필요하면 서버 조회.
 */
function goto(next) {
  if (busy.value || (next > 0 && !definition.value)) return
  step.value = next
  if (next === 3 && !explanations.value) run(loadExplanations)   // 소명 분류 단계에 처음 들어오면 쌓인 승인 소명을 불러온다
}
</script>


<template>
  <section class="rs">
    <header class="rs-top">
      <div class="rs-brand"><b>보고서 만들기</b><span>PDF나 저장한 틀로 시작해 확인한 데이터로 채웁니다</span></div>
      <nav class="rs-steps" aria-label="보고서 작성 단계">
        <button v-for="(label, i) in steps" :key="label" type="button" class="rs-step" :class="{ on: step === i, done: step > i }" :disabled="busy || (i > 0 && !definition)" :aria-current="step === i ? 'step' : undefined" @click="goto(i)">{{ i + 1 }}. {{ label }}</button>
      </nav>
    </header>
    <div v-if="error" class="rs-msg err" role="alert">{{ error }}</div>
    <div v-if="notice" class="rs-msg info" role="status">{{ notice }}</div>
    <div v-if="busy" class="rs-msg info" role="status">처리 중입니다…</div>

    <fieldset :disabled="busy" :aria-busy="busy">
      <ReportStart v-if="step === 0" v-model:name="draftName" :templates="templates" :hasMore="!!cursor" :job="job" :busy="busy"
        @pick="file => uploadPdf({ files: [file] })" @refresh="run(refreshJob)" @manual="run(startManual)" @load="id => run(() => loadTemplate(id))" @more="run(() => loadTemplates())" />

      <template v-else-if="definition">
        <template v-if="step === 1">
          <div class="rs-bar">
            <div class="rs-bar-acts"><button type="button" class="rs-btn ghost" :disabled="busy" @click="goto(0)">← 다시 올리기</button>
              <input v-model="definition.name" class="rs-input" style="width:260px" maxlength="120" aria-label="자료 틀 이름" :disabled="busy || !!saved" @input="invalidate" /></div>
            <div class="rs-bar-acts"><button type="button" class="rs-btn" :disabled="busy" @click="goto(2)">구성 확정 → 데이터 선택</button></div>
          </div>
          <StructureReview :blocks="definition.blocks" :pages="pageImages" :sizes="pageSizeMap" :busy="busy" @edited="invalidate" @move="changeBlock($event.id, $event.dir)" @remove="changeBlock($event, 'remove')" @acknowledge="acknowledgeBlock" />
          <div class="rs-extra"><div class="rs-addmenu"><label for="rs-s1-kind">항목 추가</label>
            <select id="rs-s1-kind" v-model="addedKind" :disabled="busy"><option value="metric_table">손익 표</option><option value="chart">조직별 차트</option><option value="numbercard">숫자 카드</option><option value="paragraph">문장</option></select>
            <select v-if="addedKind !== 'paragraph'" v-model="addedMetric" aria-label="지표" :disabled="busy"><option>매출</option><option>영업비용</option><option>영업이익</option></select>
            <button type="button" class="rs-btn ghost" style="height:32px" :disabled="busy" @click="addBlock">+ 추가</button></div></div>
        </template>

        <template v-else-if="step === 2">
          <div class="rs-bar"><div class="rs-bar-acts"><button type="button" class="rs-btn ghost" :disabled="busy" @click="goto(1)">← 구성 확인</button></div>
            <div class="rs-bar-acts"><button type="button" class="rs-btn" :disabled="busy" @click="goto(3)">소명 분류 →</button></div></div>
          <ReportDataSelect :definition="definition" :base="base" :periods="periods" :metrics="metrics" :busy="busy"
            @year="changeYearValue" @month="changeMonth" @period="changePeriod" @offset="changeOffset" @edited="invalidate" @addMetric="addMetric" />
          <div class="rs-extra rs-panel">
            <b>데이터에 질문하기</b>
            <form class="rs-row" @submit.prevent="run(ask)"><input v-model="question" class="rs-input" style="flex:1;min-width:200px" maxlength="1000" placeholder="계획 대비 영업이익 차이가 큰 조직을 알려줘" aria-label="데이터에 질문하기" required :disabled="busy" @input="queryResult = null" /><button type="submit" class="rs-btn" :disabled="busy || !question.trim()">조회</button></form>
            <div v-if="queryResult" class="rs-hint">
              <p style="margin:0 0 6px">{{ queryResult.explanation || queryResult.clarifyQuestion || queryResult.message || queryResult.status }}</p>
              <div class="rs-acts"><button v-for="option in queryResult.clarifyOptions || []" :key="String(option)" type="button" class="rs-btn ghost" :disabled="busy" @click="clarifyQuestion(option)">{{ typeof option === 'string' ? option : option.label }}</button></div>
              <ReportDataTable v-if="queryResult.preview" :data="{ table: queryResult.preview }" />
              <form v-if="queryResult.status === 'OK' && queryResult.sql" class="rs-row" style="margin-top:8px" @submit.prevent="run(registerMetric)">
                <input v-model="metricCode" class="rs-input" style="width:180px" pattern="[A-Z][A-Z0-9_]{1,79}" placeholder="지표 코드 OP_BY_ORG" aria-label="지표 코드" required :disabled="busy" />
                <input v-model="metricName" class="rs-input" style="width:200px" maxlength="200" placeholder="지표 이름" aria-label="지표 이름" required :disabled="busy" />
                <button type="submit" class="rs-btn" :disabled="busy">확인한 조회를 지표로 저장·추가</button></form>
            </div>
          </div>
        </template>

        <template v-else-if="step === 3">
          <div class="rs-bar"><div class="rs-bar-acts"><button type="button" class="rs-btn ghost" :disabled="busy" @click="goto(2)">← 데이터 선택</button></div>
            <div class="rs-bar-acts"><button type="button" class="rs-btn" :disabled="busy" @click="run(fill)">보고서 생성 →</button></div></div>
          <ReportClassify :explanations="explanations" :categories="assignable" :unit="reportUnit" :busy="busy"
            @classify="(item, id) => run(() => classify(item, id))" @load="run(loadExplanations)" />
        </template>

        <ReportStudio v-else :definition="definition" :results="results" :base="base" :busy="busy" :canUndo="!!undo" :editResult="editResult" :preview="editPreview" :saved="saved" :dirty="!!dirty"
          @back="goto(3)" @fill="run(fill)" @undo="undoEdit" @png="run(exportPng)" @print="run(printReport)" @save="run(saveTemplate)" @xlsx="run(exportExcel)"
          @reorder="definition.blocks = $event" @edited="invalidate" @remove="changeBlock($event, 'remove')" @add="addedKind = $event.kind; addedMetric = $event.metric; addBlock()"
          @edit="instruction = $event; run(requestEdit)" @accept="run(acceptEdit)" @dismiss="editResult = null" />
      </template>
    </fieldset>
    <p v-if="step === 4" class="rs-foot">{{ saved ? `저장 버전 ${saved.version}${dirty ? ' · 저장하지 않은 변경 있음' : ''}` : 'XLSX 내보내기 전에 자료 틀을 저장해 주세요.' }}</p>
  </section>
</template>

<style scoped>
@media print { :global(body.report-printing *) { visibility:hidden; }:global(body.report-printing .report-preview), :global(body.report-printing .report-preview *) { visibility:visible; }:global(body.report-printing .report-preview) { position:absolute; left:0; top:0; width:100%; border:0; margin:0; padding:12mm; }:global(body.report-printing .report-table), :global(body.report-printing .p-datatable-table-container) { overflow:visible; } }
</style>
