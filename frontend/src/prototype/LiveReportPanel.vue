<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { saveFile } from './live-api.js'
import { seoulPeriod } from './domain.js'
import { applyReportProposals, manualReport, reportCell, reportDefinition, reportRequest, reportRows } from './report-api.js'
import { reportPng } from './report-export.js'
import PButton from 'primevue/button'
import PInputText from 'primevue/inputtext'
import PInputNumber from 'primevue/inputnumber'
import PSelect from 'primevue/select'
import PSelectButton from 'primevue/selectbutton'
import PTextarea from 'primevue/textarea'
import PCheckbox from 'primevue/checkbox'
import PFileUpload from 'primevue/fileupload'
import PPanel from 'primevue/panel'
import PCard from 'primevue/card'
import PMessage from 'primevue/message'
import PTag from 'primevue/tag'
import ReportDataTable from '../components/ReportDataTable.vue'
import ReportChart from '../components/ReportChart.vue'

const store = usePrototypeStore()
const steps = ['자료 틀 선택', '구성 확인', '데이터 선택', '소명 분류', '완성·편집']
const step = ref(0), busy = ref(false), error = ref(''), notice = ref('')
const definition = ref(null), draftName = ref('새 경영 보고서'), templates = ref([]), cursor = ref(null), metrics = ref([])
const saved = ref(null), savedContent = ref(''), job = ref(null), pageImages = ref([])
const periods = ref(null), base = ref(seoulPeriod())
const categories = ref([]), comparison = ref('ACTUAL_VS_PLAN'), explanations = ref(null)
const results = ref(null), question = ref(''), queryResult = ref(null), metricCode = ref(''), metricName = ref('')
const instruction = ref(''), editResult = ref(null), editSource = ref(''), undo = ref(null)
const addedKind = ref('metric_table'), addedMetric = ref('영업이익')
const pdfInput = ref(null), controller = new AbortController()
const dirty = computed(() => definition.value && JSON.stringify(definition.value) !== savedContent.value)
const assignable = computed(() => categories.value.filter(item => item.assignable))
const currentMonth = computed(() => periods.value?.months?.find(item => item.month === base.value.month))
const stepOptions = computed(() => steps.map((label, value) => ({ label: `${value + 1}. ${label}`, value, disabled: busy.value || (value > 0 && !definition.value) })))
const blockKinds = [{ label: '손익 표', value: 'metric_table' }, { label: '조직별 차트', value: 'chart' }, { label: '숫자 카드', value: 'numbercard' }, { label: '문장', value: 'paragraph' }]
const metricOptions = ['매출', '영업비용', '영업이익'], unitOptions = ['억원', '백만원']
const periodOptions = [{ label: '기준 월', value: 'base' }, { label: '기준 월에서 이동', value: 'base_offset' }]
const comparisonOptions = [{ label: '실적 vs 계획', value: 'ACTUAL_VS_PLAN' }, { label: '추정 vs 계획', value: 'ESTIMATE_VS_PLAN' }, { label: '추정 vs 직전 추정', value: 'ESTIMATE_VS_PREVIOUS' }]
const monthOptions = computed(() => (periods.value?.months || []).map(month => ({ label: `${month.month}월 · ${{ CONFIRMED: '확정', PROVISIONAL: '잠정', NONE: '데이터 없음' }[month.state]}`, value: month.month })))
const categoryOptions = computed(() => [{ label: '미분류', value: '' }, ...assignable.value.map(category => ({ label: category.name, value: category.id }))])


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
}

/** 편집한 정의의 이전 데이터·AI 제안을 무효화한다.
 * Args: 없음. Returns: 없음. Raises: 없음.
 * Side Effects: 화면의 계산 결과·제안 초기화.
 */
function invalidate() { results.value = null; editResult.value = null }

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


/** 선택한 기준 월의 승인 소명과 분류 후보를 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 서버 조회와 소명 목록 갱신.
 */
async function loadExplanations() { explanations.value = await reportRequest(api, 'explanations', { ...base.value, comparison: comparison.value }) }

/** 소명 분류를 사용자가 선택한 값으로 저장한다.
 * Args: item 소명 카드, categoryId 분류 ID 또는 빈 문자열.
 * Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 서버 분류 저장 후 최신 합계 조회.
 */
async function classify(item, categoryId) {
  await reportRequest(api, 'classify', { id: item.explanationId, categoryId, comparison: comparison.value })
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

/** 기간이 반영된 서버 제목을 우선 표시한다.
 * Args: block 정의의 제목 또는 일반 블록.
 * Returns: 서버가 채운 제목 또는 원래 제목 문자열.
 * Raises: 없음. Side Effects: 없음.
 */
function blockTitle(block) {
  if (block.kind !== 'title') return block.title || ''
  return results.value?.blocks?.find(item => item.id === block.id)?.text ?? block.text ?? ''
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
  editResult.value = await reportRequest(api, 'edit', { definition: value, instruction: instruction.value.trim() })
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
  saveFile(await reportPng(definition.value, results.value, base.value), `보고서_${base.value.year}-${String(base.value.month).padStart(2, '0')}.png`)
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

/** 블록의 검증된 서식을 화면 스타일로 바꾼다.
 * Args: block 자료 블록. Returns: Vue 스타일 객체.
 * Raises: 없음. Side Effects: 없음.
 */
function blockStyle(block) {
  const style = block.style || {}
  return { fontFamily: style.family === 'serif' ? 'serif' : undefined, fontSize: Number.isFinite(style.size) && style.size >= 8 && style.size <= 40 ? `${style.size}pt` : undefined, fontWeight: style.bold ? '700' : undefined }
}
</script>

<template>
  <section class="report-panel">
    <h1>보고서 생성</h1>
    <p class="muted">기존 PDF나 저장한 틀을 바탕으로 보고서를 만들고, 확인한 데이터로 채웁니다.</p>
    <PMessage v-if="error" severity="error" :closable="false">{{error}}</PMessage>
    <PMessage v-if="notice" severity="info" :closable="false">{{notice}}</PMessage>
    <PSelectButton v-model="step" :options="stepOptions" optionLabel="label" optionValue="value" optionDisabled="disabled" :allowEmpty="false" :disabled="busy" class="report-steps" aria-label="보고서 작성 단계" />
    <PMessage v-if="busy" severity="info" :closable="false">처리 중입니다…</PMessage>
    <fieldset :disabled="busy" :aria-busy="busy" class="report-body">
      <div v-if="step===0" class="report-grid">
        <PPanel header="PDF에서 시작" class="report-card">
          <p>숫자를 가린 PDF를 분석해 자료 구성을 추출합니다. 최대 30MB입니다.</p>
          <PFileUpload ref="pdfInput" mode="basic" customUpload :auto="false" :multiple="false" accept=".pdf,application/pdf" :maxFileSize="30*1024*1024" chooseLabel="PDF 선택·분석" :disabled="busy" invalidFileTypeMessage="{0}: PDF 파일을 선택해 주세요." invalidFileSizeMessage="{0}: 30MB 이하 파일을 선택해 주세요." :pt="{input:{'aria-label':'분석할 PDF'}}" @select="uploadPdf" />
          <p v-if="job">분석 상태: <PTag :value="{RUNNING:'진행 중',SUCCEEDED:'완료',FAILED:'실패'}[job.status] || job.status" :severity="job.status==='FAILED'?'danger':job.status==='SUCCEEDED'?'success':'info'" /></p>
          <PButton v-if="job" label="분석 상태 새로고침" severity="secondary" outlined :disabled="busy" @click="run(refreshJob)" />
        </PPanel>
        <PPanel header="직접 만들기" class="report-card">
          <label for="report-draft-name">자료 틀 이름<PInputText id="report-draft-name" v-model="draftName" maxlength="120" :disabled="busy" /></label>
          <PButton label="새 보고서 만들기" :disabled="busy || !draftName.trim()" @click="run(startManual)" />
        </PPanel>
        <PPanel header="저장한 자료 틀" class="report-card report-wide">
          <ul class="report-template-list"><li v-for="item in templates" :key="item.id"><strong>{{item.name}}</strong><PTag :value="`${item.latestVersion}버전`" severity="secondary" /><PButton label="불러오기" severity="secondary" outlined :disabled="busy" @click="run(()=>loadTemplate(item.id))" /></li></ul>
          <PMessage v-if="!templates.length" severity="secondary" :closable="false">저장된 자료 틀이 없습니다.</PMessage>
          <PButton v-if="cursor" label="더 보기" severity="secondary" text :disabled="busy" @click="run(()=>loadTemplates())" />
        </PPanel>
      </div>

      <template v-else-if="definition">
        <PPanel v-if="step===1" header="자료 구성 확인" class="report-card">
          <label for="report-definition-name">자료 틀 이름<PInputText id="report-definition-name" v-model="definition.name" :disabled="busy || !!saved" maxlength="120" @update:modelValue="invalidate" /></label>
          <div v-if="pageImages.length" class="report-pages"><figure v-for="page in pageImages" :key="page.no"><img :src="page.url" :alt="`숫자를 가린 원본 ${page.no}쪽`" /><figcaption>원본 {{page.no}}쪽</figcaption></figure></div>
          <p class="muted">추출된 제목·표·차트를 확인하고, 확인이 필요한 항목의 지표와 기간을 다음 단계에서 지정해 주세요.</p>
          <PPanel v-for="block in definition.blocks" :key="block.id" :header="`${block.title || block.kind} · ${block.id}`" class="report-block-editor" toggleable>
            <template #icons><div class="actions"><PButton icon="pi pi-arrow-up" severity="secondary" text :disabled="busy" :aria-label="`${block.id} 위로`" @click="changeBlock(block.id,'up')" /><PButton icon="pi pi-arrow-down" severity="secondary" text :disabled="busy" :aria-label="`${block.id} 아래로`" @click="changeBlock(block.id,'down')" /><PButton label="삭제" icon="pi pi-trash" severity="danger" text :disabled="busy" @click="changeBlock(block.id,'remove')" /></div></template>
            <PMessage v-if="block.needs_review?.length" severity="warn" :closable="false">원본과 비교해 항목 구성을 확인해 주세요.</PMessage>
            <label v-if="['title','paragraph','text','footnote'].includes(block.kind)" :for="`report-text-${block.id}`">본문<PTextarea :id="`report-text-${block.id}`" v-model="block.text" rows="3" autoResize :disabled="busy" @update:modelValue="invalidate" /></label>
            <label v-else :for="`report-title-${block.id}`">제목<PInputText :id="`report-title-${block.id}`" v-model="block.title" :disabled="busy" @update:modelValue="invalidate" /></label>
            <label v-if="block.needs_review?.length" :for="`report-reviewed-${block.id}`" class="report-check"><PCheckbox :inputId="`report-reviewed-${block.id}`" :modelValue="false" binary :disabled="busy" @update:modelValue="acknowledgeBlock(block)" /><span>이 항목을 확인했습니다</span></label>
          </PPanel>
          <div class="actions"><label for="report-added-kind">추가할 항목<PSelect inputId="report-added-kind" v-model="addedKind" :options="blockKinds" optionLabel="label" optionValue="value" :disabled="busy" /></label><label v-if="addedKind!=='paragraph'" for="report-added-metric">지표<PSelect inputId="report-added-metric" v-model="addedMetric" :options="metricOptions" :disabled="busy" /></label><PButton label="항목 추가" icon="pi pi-plus" severity="secondary" outlined :disabled="busy" @click="addBlock" /></div>
        </PPanel>

        <PPanel v-if="step===2" header="기준 기간과 데이터" class="report-card">
          <div class="actions"><label for="report-year">연도<PInputNumber inputId="report-year" :modelValue="base.year" :maxFractionDigits="0" :min="2000" :max="9999" :useGrouping="false" :allowEmpty="false" :disabled="busy" @update:modelValue="changeYearValue" /></label><label for="report-month">기준 월<PSelect inputId="report-month" :modelValue="base.month" :options="monthOptions" optionLabel="label" optionValue="value" :disabled="busy" @update:modelValue="changeMonth" /></label></div>
          <PMessage severity="info" :closable="false">{{currentMonth?.state==='PROVISIONAL'?'잠정 자료는 승인된 조직만 포함할 수 있습니다.':currentMonth?.state==='CONFIRMED'?'확정 마스터를 기준으로 조회합니다.':'선택한 월의 데이터가 없습니다.'}}</PMessage>
          <PPanel v-for="block in definition.blocks.filter(item=>!['title','paragraph','text','footnote'].includes(item.kind))" :key="block.id" :header="block.title || block.kind" class="report-block-editor" toggleable>
            <div class="actions"><label :for="`report-unit-${block.id}`">금액 표시 단위<PSelect :inputId="`report-unit-${block.id}`" v-model="block.unit" :options="unitOptions" :disabled="busy" @update:modelValue="invalidate" /></label><label :for="`report-period-${block.id}`">기준 월과의 관계<PSelect :inputId="`report-period-${block.id}`" :modelValue="block.period?.rule || 'base'" :options="periodOptions" optionLabel="label" optionValue="value" :disabled="busy" @update:modelValue="value=>changePeriod(block,value)" /></label><label v-if="block.period?.rule==='base_offset'" :for="`report-offset-${block.id}`">이동할 개월 수<PInputNumber :inputId="`report-offset-${block.id}`" :modelValue="block.period.offset" :maxFractionDigits="0" :min="-24" :max="24" :useGrouping="false" :disabled="busy" @update:modelValue="value=>changeOffset(block,value)" /></label></div>
          </PPanel>
          <h3>등록된 지표 추가</h3><div class="actions"><PButton v-for="metric in metrics" :key="metric.id" :label="`${metric.name} 추가`" icon="pi pi-plus" severity="secondary" outlined :disabled="busy" @click="addMetric(metric.id)" /><span v-if="!metrics.length" class="muted">등록된 지표가 없습니다.</span></div>
          <form class="report-question" @submit.prevent="run(ask)"><label for="report-question">데이터에 질문하기<PInputText id="report-question" v-model="question" maxlength="1000" placeholder="계획 대비 영업이익 차이가 큰 조직을 알려줘" required :disabled="busy" @update:modelValue="queryResult=null" /></label><PButton type="submit" label="조회" icon="pi pi-search" :disabled="busy || !question.trim()" /></form>
          <PPanel v-if="queryResult" header="질문 결과" class="report-answer">
            <p>{{queryResult.explanation || queryResult.clarifyQuestion || queryResult.message || queryResult.status}}</p>
            <div class="actions"><PButton v-for="option in queryResult.clarifyOptions || []" :key="String(option)" :label="typeof option==='string'?option:option.label" severity="secondary" outlined :disabled="busy" @click="clarifyQuestion(option)" /></div>
            <ReportDataTable v-if="queryResult.preview" :data="{table:queryResult.preview}" />
            <form v-if="queryResult.status==='OK' && queryResult.sql" class="actions" @submit.prevent="run(registerMetric)"><label for="report-metric-code">지표 코드<PInputText id="report-metric-code" v-model="metricCode" pattern="[A-Z][A-Z0-9_]{1,79}" placeholder="OP_BY_ORG" required :disabled="busy" /></label><label for="report-metric-name">지표 이름<PInputText id="report-metric-name" v-model="metricName" maxlength="200" required :disabled="busy" /></label><PButton type="submit" label="확인한 조회를 지표로 저장·추가" :disabled="busy" /></form>
          </PPanel>
        </PPanel>

        <PPanel v-if="step===3" header="승인 소명 분류" class="report-card">
          <div class="actions"><label for="report-comparison">비교 기준<PSelect inputId="report-comparison" v-model="comparison" :options="comparisonOptions" optionLabel="label" optionValue="value" :disabled="busy" @update:modelValue="explanations=null" /></label><PButton label="소명 불러오기" severity="secondary" outlined :disabled="busy" @click="run(loadExplanations)" /></div>
          <template v-if="explanations">
            <p>Gap 합계 {{explanations.gapTotal}}원 · 분류 합계 {{explanations.classifiedTotal}}원</p>
            <PMessage :severity="explanations.matchesGapTotal?'success':'warn'" :closable="false">{{explanations.matchesGapTotal?'분류 합계가 Gap과 일치합니다.':'아직 분류되지 않았거나 검토 중인 소명이 있습니다.'}}</PMessage>
            <PPanel v-for="item in explanations.items" :key="item.explanationId" :header="`${item.organization.name} · ${item.item}`" class="report-block-editor" toggleable>
              <p class="preserve-lines">{{item.answer}}</p><p>차이 {{item.gapAmount}}원</p>
              <label :for="`report-category-${item.explanationId}`">사유 유형<PSelect :inputId="`report-category-${item.explanationId}`" :modelValue="item.categoryId || ''" :options="categoryOptions" optionLabel="label" optionValue="value" :disabled="busy" @update:modelValue="value=>run(()=>classify(item,value))" /></label>
              <PButton v-if="item.suggestedCategoryId && item.suggestedCategoryId!==item.categoryId" :label="`제안 분류 적용 · ${categories.find(c=>c.id===item.suggestedCategoryId)?.name}`" severity="secondary" outlined :disabled="busy" @click="run(()=>classify(item,item.suggestedCategoryId))" />
            </PPanel>
            <PMessage v-if="!explanations.items?.length" severity="secondary" :closable="false">분류할 승인 소명이 없습니다.</PMessage>
            <ul><li v-for="(item,i) in explanations.reviewing || []" :key="i">검토 중 · {{item.organization.name}} · {{item.item}}</li></ul>
          </template>
        </PPanel>

        <PPanel v-if="step===4" :header="definition.name" class="report-card">
          <PButton label="현재 데이터로 채우기" icon="pi pi-refresh" :disabled="busy || !definition.blocks.length" @click="run(fill)" />
          <PMessage v-if="results" severity="info" :closable="false">{{base.year}}년 {{base.month}}월 · {{ {CONFIRMED:'확정 자료',PROVISIONAL:'잠정 자료',NONE:'조회할 데이터 없음'}[results.dataStatus] || results.dataStatus }}</PMessage>
          <div class="report-preview">
            <article v-for="block in definition.blocks" :key="block.id" class="report-output" :style="blockStyle(block)">
              <h3>{{blockTitle(block)}}</h3>
              <template v-for="data in results?.blocks?.filter(item=>item.id===block.id) || []" :key="data.id">
                <p v-if="data.text && block.kind!=='title'">{{data.text}}</p>
                <PMessage v-if="data.missing" severity="warn" :closable="false">일부 데이터가 없습니다. {{(data.why || data.reasons || []).join(' · ')}}</PMessage>
                <ReportDataTable v-if="reportRows(data).length" :data="data" />
                <div v-if="data.cards" class="report-cards"><PCard v-for="(card,i) in data.cards" :key="i"><template #title>{{card.label}}</template><template #content><p class="report-card-value">{{card.value ?? '데이터 없음'}} {{card.value==null?'':card.unit}}</p><small>{{card.delta}}</small></template></PCard></div>
                <ReportChart v-if="block.kind==='chart' && Array.isArray(data.rows)" :block="{...block,...data}" />
                <ul v-if="data.items"><li v-for="(item,i) in data.items" :key="i">{{reportCell(item)}}</li></ul>
              </template>
              <p v-if="!results && block.kind!=='title'" class="muted">{{block.text || '데이터를 채우면 내용을 확인할 수 있습니다.'}}</p>
            </article>
          </div>
          <form class="report-question" @submit.prevent="run(requestEdit)"><label for="report-edit-instruction">AI로 구성 편집<PInputText id="report-edit-instruction" v-model="instruction" maxlength="1000" placeholder="영업이익 차트를 추가해 줘" required :disabled="busy" /></label><PButton type="submit" label="편집안 받기" :disabled="busy || !instruction.trim()" /></form>
          <PPanel v-if="editResult" header="AI 편집안 확인" class="report-answer"><p>{{editResult.message}}</p><ul><li v-for="(proposal,i) in editResult.proposals || []" :key="i">{{proposal.description}}</li></ul><div class="actions"><PButton v-if="editResult.status==='EDIT' && editResult.proposals?.length" label="확인한 편집안 적용" :disabled="busy" @click="run(acceptEdit)" /><PButton label="닫기" severity="secondary" outlined :disabled="busy" @click="editResult=null" /></div></PPanel>
          <div class="actions"><PButton v-if="undo" label="마지막 AI 편집 되돌리기" icon="pi pi-undo" severity="secondary" outlined :disabled="busy" @click="undoEdit" /><PButton :label="saved?'새 버전 저장':'자료 틀 저장'" icon="pi pi-save" :disabled="busy || !dirty" @click="run(saveTemplate)" /><PButton label="XLSX 내보내기" icon="pi pi-file-excel" severity="secondary" outlined :disabled="busy || !saved || dirty" @click="run(exportExcel)" /><PButton label="내용 PNG" icon="pi pi-image" severity="secondary" outlined :disabled="busy || !results" @click="run(exportPng)" /><PButton label="인쇄 · PDF 저장" icon="pi pi-print" severity="secondary" outlined :disabled="busy || !results" @click="run(printReport)" /><span class="muted">{{saved?`저장 버전 ${saved.version}${dirty?' · 저장하지 않은 변경 있음':''}`:'XLSX 내보내기 전에 자료 틀을 저장해 주세요.'}}</span></div>
        </PPanel>
        <div class="actions report-step-actions"><PButton label="이전" icon="pi pi-arrow-left" severity="secondary" outlined :disabled="busy || step===0" @click="step--" /><PButton v-if="step<4" label="다음" icon="pi pi-arrow-right" iconPos="right" :disabled="busy" @click="step++" /></div>
      </template>
    </fieldset>
  </section>
</template>

<style scoped>
.report-body { border:0; padding:0; margin:0; min-width:0; }
.report-panel { display:grid; gap:16px; }
.report-panel h1, .report-panel h2, .report-panel h3 { margin:0 0 12px; }
.report-steps { display:flex; flex-wrap:wrap; }
.report-grid { display:grid; grid-template-columns:1fr 1fr; gap:16px; }
.report-wide { grid-column:1/-1; }
.report-panel label { display:grid; gap:6px; margin:8px 0; font-size:14px; }
.report-panel .report-check { display:flex; align-items:center; gap:8px; }
.report-template-list { padding:0; list-style:none; }
.report-template-list li { display:flex; gap:16px; align-items:center; border-bottom:1px solid var(--p-content-border-color); padding:12px 0; }
.report-template-list strong { flex:1; }
.report-block-editor { margin:12px 0; }
.report-block-editor :deep(.p-panel-header-actions) { flex-wrap:wrap; }
.report-pages { display:flex; overflow:auto; gap:16px; }
.report-pages figure { margin:0; min-width:200px; }
.report-pages img { max-height:240px; max-width:100%; border:1px solid var(--p-content-border-color); }
.report-question { display:flex; gap:12px; align-items:end; margin-top:24px; }
.report-question label { flex:1; }
.report-question > .p-button { margin-bottom:8px; }
.report-answer { margin:16px 0; }
.report-preview { background:var(--p-content-background); border:1px solid var(--p-content-border-color); padding:24px; margin:16px 0; }
.report-output { margin:0 0 24px; break-inside:avoid; }
.report-output p, .preserve-lines { white-space:pre-wrap; overflow-wrap:anywhere; }
.report-cards { display:flex; gap:12px; flex-wrap:wrap; margin:12px 0; }
.report-card-value { font-size:24px; margin:8px 0; }
.report-panel .actions { display:flex; gap:10px; flex-wrap:wrap; align-items:center; }
.report-step-actions { margin-top:16px; justify-content:flex-end; }
@media(max-width:760px) { .report-grid { grid-template-columns:1fr; }.report-question { display:block; }.report-template-list li { flex-wrap:wrap; }.report-preview { padding:12px; } }
@media print { :global(body.report-printing *) { visibility:hidden; }:global(body.report-printing .report-preview), :global(body.report-printing .report-preview *) { visibility:visible; }:global(body.report-printing .report-preview) { position:absolute; left:0; top:0; width:100%; border:0; margin:0; padding:12mm; }:global(body.report-printing .report-table), :global(body.report-printing .p-datatable-table-container) { overflow:visible; } }
</style>
