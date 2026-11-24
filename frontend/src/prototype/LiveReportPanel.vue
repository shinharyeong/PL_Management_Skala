<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { saveFile } from './live-api.js'
import { seoulPeriod } from './domain.js'
import { applyReportProposals, manualReport, reportCell, reportDefinition, reportRequest, reportRows } from './report-api.js'
import { reportPng } from './report-export.js'

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
 * Args: event 파일 선택 이벤트. Returns: Promise<void>. Raises: run에서 처리.
 * Side Effects: 서버 PDF 작업 생성과 화면 상태 변경.
 */
async function uploadPdf(event) {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file) return
  await run(async () => { clearImages(); job.value = await reportRequest(api, 'pdf', { file }); await refreshJob() })
}

/** 편집한 정의의 이전 데이터·AI 제안을 무효화한다.
 * Args: 없음. Returns: 없음. Raises: 없음.
 * Side Effects: 화면의 계산 결과·제안 초기화.
 */
function invalidate() { results.value = null; editResult.value = null }

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
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <nav class="report-steps" aria-label="보고서 작성 단계">
      <button v-for="(label, index) in steps" :key="label" class="btn" :class="{primary:step===index}" :aria-current="step===index?'step':undefined" :disabled="busy || (index>0 && !definition)" @click="step=index">{{index+1}}. {{label}}</button>
    </nav>
    <p v-if="busy" role="status" class="muted">처리 중입니다…</p>
    <fieldset :disabled="busy" class="report-body">
    <div v-if="step===0" class="report-grid">
      <section class="card"><h2>PDF에서 시작</h2><p>숫자를 가린 PDF를 분석해 자료 구성을 추출합니다. 최대 30MB입니다.</p><input ref="pdfInput" type="file" accept=".pdf,application/pdf" :disabled="busy" aria-label="분석할 PDF" @change="uploadPdf" /><p v-if="job">분석 상태: {{ {RUNNING:'진행 중',SUCCEEDED:'완료',FAILED:'실패'}[job.status] || job.status }}</p><button v-if="job" class="btn" :disabled="busy" @click="run(refreshJob)">분석 상태 새로고침</button></section>
      <section class="card"><h2>직접 만들기</h2><label>자료 틀 이름<input v-model="draftName" maxlength="120" /></label><button class="btn primary" :disabled="busy || !draftName.trim()" @click="run(startManual)">새 보고서 만들기</button></section>
      <section class="card report-wide"><h2>저장한 자료 틀</h2><ul class="report-template-list"><li v-for="item in templates" :key="item.id"><strong>{{item.name}}</strong><span>{{item.latestVersion}}버전</span><button class="btn" :disabled="busy" @click="run(()=>loadTemplate(item.id))">불러오기</button></li></ul><p v-if="!templates.length" class="muted">저장된 자료 틀이 없습니다.</p><button v-if="cursor" class="btn" :disabled="busy" @click="run(()=>loadTemplates())">더 보기</button></section>
    </div>

    <template v-else-if="definition">
      <section v-if="step===1" class="card">
        <label>자료 틀 이름<input v-model="definition.name" :disabled="busy || !!saved" maxlength="120" @input="invalidate" /></label>
        <div v-if="pageImages.length" class="report-pages"><figure v-for="page in pageImages" :key="page.no"><img :src="page.url" :alt="`숫자를 가린 원본 ${page.no}쪽`" /><figcaption>원본 {{page.no}}쪽</figcaption></figure></div>
        <p class="muted">추출된 제목·표·차트를 확인하고, 확인이 필요한 항목의 지표와 기간을 다음 단계에서 지정해 주세요.</p>
        <article v-for="block in definition.blocks" :key="block.id" class="report-block-editor">
          <div class="row-between"><strong>{{block.title || block.kind}} · {{block.id}}</strong><div class="actions"><button class="btn" :disabled="busy" @click="changeBlock(block.id,'up')" :aria-label="`${block.id} 위로`">↑</button><button class="btn" :disabled="busy" @click="changeBlock(block.id,'down')" :aria-label="`${block.id} 아래로`">↓</button><button class="btn danger" :disabled="busy" @click="changeBlock(block.id,'remove')">삭제</button></div></div>
          <p v-if="block.needs_review?.length" class="notice">원본과 비교해 항목 구성을 확인해 주세요.</p>
          <label v-if="['title','paragraph','text','footnote'].includes(block.kind)">본문<textarea v-model="block.text" :disabled="busy" @input="invalidate" /></label>
          <label v-else>제목<input v-model="block.title" :disabled="busy" @input="invalidate" /></label>
          <label v-if="block.needs_review?.length"><input type="checkbox" :disabled="busy" @change="block.needs_review=[];invalidate()" /> 이 항목을 확인했습니다</label>
        </article>
        <div class="actions"><label>추가할 항목<select v-model="addedKind"><option value="metric_table">손익 표</option><option value="chart">조직별 차트</option><option value="numbercard">숫자 카드</option><option value="paragraph">문장</option></select></label><label v-if="addedKind!=='paragraph'">지표<select v-model="addedMetric"><option>매출</option><option>영업비용</option><option>영업이익</option></select></label><button class="btn" :disabled="busy" @click="addBlock">항목 추가</button></div>
      </section>

      <section v-if="step===2" class="card">
        <h2>기준 기간과 데이터</h2><div class="actions"><label>연도<input v-model.number="base.year" type="number" min="2000" max="9999" :disabled="busy" @change="run(changeYear)" /></label><label>기준 월<select v-model.number="base.month" :disabled="busy" @change="invalidate();explanations=null;queryResult=null"><option v-for="month in periods?.months || []" :key="month.month" :value="month.month">{{month.month}}월 · {{ {CONFIRMED:'확정',PROVISIONAL:'잠정',NONE:'데이터 없음'}[month.state] }}</option></select></label></div>
        <p class="notice">{{currentMonth?.state==='PROVISIONAL'?'잠정 자료는 승인된 조직만 포함할 수 있습니다.':currentMonth?.state==='CONFIRMED'?'확정 마스터를 기준으로 조회합니다.':'선택한 월의 데이터가 없습니다.'}}</p>
        <article v-for="block in definition.blocks.filter(item=>!['title','paragraph','text','footnote'].includes(item.kind))" :key="block.id" class="report-block-editor"><strong>{{block.title || block.kind}}</strong><label>금액 표시 단위<select v-model="block.unit" @change="invalidate"><option value="억원">억원</option><option value="백만원">백만원</option></select></label><label>기준 월과의 관계<select :value="block.period?.rule || 'base'" @change="block.period=$event.target.value==='base'?{rule:'base'}:{rule:'base_offset',offset:-1};invalidate()"><option value="base">기준 월</option><option value="base_offset">기준 월에서 이동</option></select></label><label v-if="block.period?.rule==='base_offset'">이동할 개월 수<input v-model.number="block.period.offset" type="number" min="-24" max="24" @change="if(!block.period.offset)block.period={rule:'base'};invalidate()" /></label></article>
        <h3>등록된 지표 추가</h3><div class="actions"><button v-for="metric in metrics" :key="metric.id" class="btn" :disabled="busy" @click="addMetric(metric.id)">{{metric.name}} 추가</button><span v-if="!metrics.length" class="muted">등록된 지표가 없습니다.</span></div>
        <form class="report-question" @submit.prevent="run(ask)"><label>데이터에 질문하기<input v-model="question" @input="queryResult=null" maxlength="1000" placeholder="계획 대비 영업이익 차이가 큰 조직을 알려줘" required /></label><button class="btn" :disabled="busy || !question.trim()">조회</button></form>
        <section v-if="queryResult" class="report-answer"><p>{{queryResult.explanation || queryResult.clarifyQuestion || queryResult.message || queryResult.status}}</p><div class="actions"><button v-for="option in queryResult.clarifyOptions || []" :key="String(option)" class="btn" @click="clarifyQuestion(option)">{{typeof option==='string'?option:option.label}}</button></div><div v-if="queryResult.preview" class="report-table"><table><tbody><tr v-for="(row,i) in reportRows({table:queryResult.preview})" :key="i"><component :is="i===0?'th':'td'" v-for="(cell,j) in row" :key="j">{{reportCell(cell)}}</component></tr></tbody></table></div><form v-if="queryResult.status==='OK' && queryResult.sql" class="actions" @submit.prevent="run(registerMetric)"><label>지표 코드<input v-model="metricCode" pattern="[A-Z][A-Z0-9_]{1,79}" placeholder="OP_BY_ORG" required /></label><label>지표 이름<input v-model="metricName" maxlength="200" required /></label><button class="btn" :disabled="busy">확인한 조회를 지표로 저장·추가</button></form></section>
      </section>

      <section v-if="step===3" class="card">
        <h2>승인 소명 분류</h2><div class="actions"><label>비교 기준<select v-model="comparison" :disabled="busy" @change="explanations=null"><option value="ACTUAL_VS_PLAN">실적 vs 계획</option><option value="ESTIMATE_VS_PLAN">추정 vs 계획</option><option value="ESTIMATE_VS_PREVIOUS">추정 vs 직전 추정</option></select></label><button class="btn" :disabled="busy" @click="run(loadExplanations)">소명 불러오기</button></div>
        <template v-if="explanations"><p>Gap 합계 {{explanations.gapTotal}}원 · 분류 합계 {{explanations.classifiedTotal}}원</p><p class="notice">{{explanations.matchesGapTotal?'분류 합계가 Gap과 일치합니다.':'아직 분류되지 않았거나 검토 중인 소명이 있습니다.'}}</p><article v-for="item in explanations.items" :key="item.explanationId" class="report-block-editor"><strong>{{item.organization.name}} · {{item.item}}</strong><p>{{item.answer}}</p><p>차이 {{item.gapAmount}}원</p><label>사유 유형<select :value="item.categoryId || ''" :disabled="busy" @change="run(()=>classify(item,$event.target.value))"><option value="">미분류</option><option v-for="category in assignable" :key="category.id" :value="category.id">{{category.name}}</option></select></label><button v-if="item.suggestedCategoryId && item.suggestedCategoryId!==item.categoryId" class="btn" :disabled="busy" @click="run(()=>classify(item,item.suggestedCategoryId))">제안 분류 적용 · {{categories.find(c=>c.id===item.suggestedCategoryId)?.name}}</button></article><p v-if="!explanations.items?.length" class="muted">분류할 승인 소명이 없습니다.</p><ul><li v-for="(item,i) in explanations.reviewing || []" :key="i">검토 중 · {{item.organization.name}} · {{item.item}}</li></ul></template>
      </section>

      <section v-if="step===4" class="card">
        <div class="row-between"><h2>{{definition.name}}</h2><button class="btn primary" :disabled="busy || !definition.blocks.length" @click="run(fill)">현재 데이터로 채우기</button></div>
        <p v-if="results" class="notice">{{base.year}}년 {{base.month}}월 · {{ {CONFIRMED:'확정 자료',PROVISIONAL:'잠정 자료',NONE:'조회할 데이터 없음'}[results.dataStatus] || results.dataStatus }}</p>
        <div class="report-preview"><article v-for="block in definition.blocks" :key="block.id" class="report-output" :style="blockStyle(block)"><h3>{{blockTitle(block)}}</h3><template v-for="data in results?.blocks?.filter(item=>item.id===block.id) || []" :key="data.id"><p v-if="data.text && block.kind!=='title'">{{data.text}}</p><p v-if="data.missing" class="notice">일부 데이터가 없습니다. {{(data.why || data.reasons || []).join(' · ')}}</p><div v-if="reportRows(data).length" class="report-table"><table><tbody><tr v-for="(row,i) in reportRows(data)" :key="i"><template v-for="(cell,j) in row" :key="j"><component v-if="!cell?.hide" :is="cell?.hdr || i < (data.header_rows || 1)?'th':'td'" :colspan="cell?.colspan || 1" :rowspan="cell?.rowspan || 1">{{reportCell(cell)}}</component></template></tr></tbody></table></div><dl v-if="data.cards" class="report-cards"><div v-for="(card,i) in data.cards" :key="i"><dt>{{card.label}}</dt><dd>{{card.value ?? '데이터 없음'}} {{card.value==null?'':card.unit}}</dd><small>{{card.delta}}</small></div></dl><div v-if="block.kind==='chart' && Array.isArray(data.rows)" class="report-chart" role="img" :aria-label="block.title || block.metric || '차트'"><div v-for="(row,i) in data.rows" :key="i" class="report-chart-row"><span>{{row.name}}</span><span class="report-bar" :style="{width:`${Math.min(100,Math.abs(Number(row.won)||0)/Math.max(1,...data.rows.map(item=>Math.abs(Number(item.won)||0)))*100)}%`}"></span><strong>{{row.text}}</strong></div></div><ul v-if="data.items"><li v-for="(item,i) in data.items" :key="i">{{reportCell(item)}}</li></ul></template><p v-if="!results && block.kind!=='title'" class="muted">{{block.text || '데이터를 채우면 내용을 확인할 수 있습니다.'}}</p></article></div>
        <form class="report-question" @submit.prevent="run(requestEdit)"><label>AI로 구성 편집<input v-model="instruction" maxlength="1000" placeholder="영업이익 차트를 추가해 줘" required /></label><button class="btn" :disabled="busy || !instruction.trim()">편집안 받기</button></form>
        <aside v-if="editResult" class="report-answer"><p>{{editResult.message}}</p><ul><li v-for="(proposal,i) in editResult.proposals || []" :key="i">{{proposal.description}}</li></ul><div class="actions"><button v-if="editResult.status==='EDIT' && editResult.proposals?.length" class="btn primary" :disabled="busy" @click="run(acceptEdit)">확인한 편집안 적용</button><button class="btn" :disabled="busy" @click="editResult=null">닫기</button></div></aside>
        <div class="actions"><button v-if="undo" class="btn" :disabled="busy" @click="undoEdit">마지막 AI 편집 되돌리기</button><button class="btn primary" :disabled="busy || !dirty" @click="run(saveTemplate)">{{saved?'새 버전 저장':'자료 틀 저장'}}</button><button class="btn" :disabled="busy || !saved || dirty" @click="run(exportExcel)">XLSX 내보내기</button><button class="btn" :disabled="busy || !results" @click="run(exportPng)">내용 PNG</button><button class="btn" :disabled="busy || !results" @click="run(printReport)">인쇄 · PDF 저장</button><span class="muted">{{saved?`저장 버전 ${saved.version}${dirty?' · 저장하지 않은 변경 있음':''}`:'XLSX 내보내기 전에 자료 틀을 저장해 주세요.'}}</span></div>
      </section>
      <div class="actions report-step-actions"><button class="btn" :disabled="busy || step===0" @click="step--">이전</button><button v-if="step<4" class="btn primary" :disabled="busy" @click="step++">다음</button></div>
    </template>
    </fieldset>
  </section>
</template>

<style scoped>
.report-body{border:0;padding:0;margin:0;min-width:0}
@media print{:global(body.report-printing *){visibility:hidden}:global(body.report-printing .report-preview),:global(body.report-printing .report-preview *){visibility:visible}:global(body.report-printing .report-preview){position:absolute;left:0;top:0;width:100%;border:0;margin:0;padding:12mm}:global(body.report-printing .report-table){overflow:visible}}
.report-panel{display:grid;gap:16px}.report-panel h1,.report-panel h2,.report-panel h3{margin:0 0 12px}.report-steps,.report-step-actions{display:flex;gap:8px;flex-wrap:wrap}.report-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.report-wide{grid-column:1/-1}.report-panel .card{padding:24px}.report-panel label{display:grid;gap:6px;margin:8px 0;font-size:14px}.report-panel input:not([type=checkbox]),.report-panel select,.report-panel textarea{border:1px solid #cbd5e1;border-radius:6px;padding:9px;font:inherit;max-width:100%;background:white}.report-panel textarea{min-height:84px;resize:vertical}.report-panel input[type=checkbox]{width:auto}.report-template-list{padding:0;list-style:none}.report-template-list li{display:flex;gap:16px;align-items:center;border-bottom:1px solid #e2e8f0;padding:12px 0}.report-template-list strong{flex:1}.report-block-editor{border:1px solid #e2e8f0;padding:16px;margin:12px 0;border-radius:8px}.report-block-editor .actions{flex-shrink:0}.report-pages{display:flex;overflow:auto;gap:16px}.report-pages figure{margin:0;min-width:200px}.report-pages img{max-height:240px;max-width:100%;border:1px solid #cbd5e1}.report-question{display:flex;gap:12px;align-items:end;margin-top:24px}.report-question label{flex:1}.report-question button{margin-bottom:8px}.report-answer{padding:16px;background:#f8fafc;border:1px solid #dbe4ed;border-radius:8px}.report-table{overflow:auto}.report-table table{border-collapse:collapse;width:100%}.report-table th,.report-table td{border:1px solid #dbe4ed;padding:8px;white-space:pre-wrap;text-align:left}.report-table th{background:#f1f5f9}.report-preview{background:white;border:1px solid #dbe4ed;padding:24px;margin:16px 0}.report-output{margin:0 0 24px;break-inside:avoid}.report-output p{white-space:pre-wrap}.report-cards{display:flex;gap:12px;flex-wrap:wrap}.report-cards>div{padding:16px;background:#f8fafc;border-radius:8px}.report-cards dd{font-size:24px;margin:8px 0}.report-chart-row{display:grid;grid-template-columns:120px 1fr 90px;align-items:center;gap:8px;margin:8px 0}.report-bar{height:18px;min-width:1px;background:#3064df;border-radius:3px}.report-panel .actions{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.report-step-actions{justify-content:flex-end}@media(max-width:760px){.report-grid{grid-template-columns:1fr}.report-question{display:block}.report-panel .card{padding:16px}.report-template-list li{flex-wrap:wrap}.report-chart-row{grid-template-columns:80px 1fr 70px}}
</style>
