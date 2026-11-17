<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, query, saveBlob } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'

const session = useSession()
const year = ref(new Date().getFullYear())
const month = ref('')
const masterId = ref('')
const includeExplanations = ref(true)
const periods = ref([])
const versions = ref([])
const versionCursor = ref(null)
const report = ref(null)
const loading = ref(false)
const versionsLoading = ref(false)
const previewLoading = ref(false)
const downloading = ref(false)
const error = ref('')
const versionsError = ref('')
const previewError = ref('')
const downloadError = ref('')
const metrics = [{ key: 'revenue', name: '매출' }, { key: 'cost', name: '영업비용' }, { key: 'operatingProfit', name: '영업이익' }]
let periodsController = new AbortController()
let versionsController = new AbortController()
let previewController = new AbortController()
let downloadController = new AbortController()
let periodsGeneration = 0
let versionsGeneration = 0
let previewGeneration = 0
let downloadGeneration = 0
let appliedInput = null

/** 서버가 발행 완료로 반환한 월만 선택지로 제공한다.
 * Args: 없음. Returns: 발행 월 배열. Raises: 없음. Side Effects: 없음.
 */
const publishedPeriods = computed(function selectPublishedPeriods() {
  const result = []
  for (const item of periods.value) if (item.publicationState === 'PUBLISHED') result.push(item)
  return result
})

/** 선택한 고정 버전에서 반환된 단일 보고 기간을 제공한다.
 * Args: 없음. Returns: 보고 기간/null. Raises: 없음. Side Effects: 없음.
 */
const period = computed(function selectReportPeriod() { return report.value?.periods?.[0] || null })

/** 이전 미리보기와 다운로드를 취소하고 화면의 적용 버전을 비운다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 증가·화면 초기화.
 */
function resetReport() {
  previewController.abort(); previewController = new AbortController(); previewGeneration++
  downloadController.abort(); downloadController = new AbortController(); downloadGeneration++
  report.value = null; appliedInput = null; previewLoading.value = false; downloading.value = false
  previewError.value = ''; downloadError.value = ''
}

/** 실제 연도별 발행 현황을 읽고 최신 발행 월의 버전을 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 이전 조회 취소·GET·기간/버전/보고 초기화; 미발행 금액을 생성하지 않는다.
 */
async function loadPeriods() {
  periodsController.abort(); periodsController = new AbortController(); const current = ++periodsGeneration
  versionsController.abort(); versionsGeneration++; versionsLoading.value = false
  resetReport(); periods.value = []; versions.value = []; versionCursor.value = null; month.value = ''; masterId.value = ''
  error.value = ''; versionsError.value = ''; loading.value = true
  try {
    if (!Number.isInteger(year.value) || year.value < 2000 || year.value > 9999) { error.value = '연도는 2000~9999 사이의 정수로 입력해 주세요.'; return }
    const result = await session.request(`/api/v1/master-periods${query({ year: year.value })}`, { signal: periodsController.signal })
    if (current !== periodsGeneration) return
    periods.value = result.items
    for (const item of publishedPeriods.value) if (Number(item.month) > Number(month.value)) month.value = String(item.month)
    if (month.value) await loadVersions()
  } catch (cause) { if (current === periodsGeneration && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === periodsGeneration) loading.value = false }
}

/** 선택 월의 실제 버전 이력을 조회하고 처음에는 최신 버전을 선택한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: GET·cursor 갱신; 월 변경 시 기존 미리보기·다운로드 취소.
 */
async function loadVersions(more = false) {
  if (!month.value || (more && (versionsLoading.value || !versionCursor.value))) return
  versionsController.abort(); versionsController = new AbortController(); const current = ++versionsGeneration
  if (!more) { resetReport(); versions.value = []; versionCursor.value = null; masterId.value = '' }
  versionsLoading.value = true; versionsError.value = ''
  try {
    const page = await session.request(`/api/v1/masters${query({ year: year.value, month: month.value, limit: 50, cursor: more ? versionCursor.value : null })}`, { signal: versionsController.signal })
    if (current !== versionsGeneration) return
    versions.value = more ? [...versions.value, ...page.items] : page.items; versionCursor.value = page.nextCursor
    if (!more && versions.value.length) { masterId.value = String(versions.value[0].id); await loadPreview() }
  } catch (cause) { if (current === versionsGeneration && cause.name !== 'AbortError') versionsError.value = errorText(cause) }
  finally { if (current === versionsGeneration) versionsLoading.value = false }
}

/** 선택 버전과 소명 포함 여부를 고정해 실제 보고 미리보기를 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 읽기 전용 POST·이전 응답 무효화; 보고 DB 행이나 파일을 만들지 않는다.
 */
async function loadPreview() {
  resetReport()
  if (!masterId.value) return
  const current = previewGeneration
  const input = { masterIds: [masterId.value], includeExplanations: includeExplanations.value }
  previewLoading.value = true
  try {
    const result = await session.request('/api/v1/reports/preview', { method: 'POST', body: JSON.stringify(input), signal: previewController.signal })
    if (current !== previewGeneration) return
    report.value = result; appliedInput = input
  } catch (cause) { if (current === previewGeneration && cause.name !== 'AbortError') previewError.value = errorText(cause) }
  finally { if (current === previewGeneration) previewLoading.value = false }
}

/** 화면에 적용된 동일 고정 버전·소명 옵션으로 Excel을 다운로드한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 사용자 클릭 시 POST·Blob 다운로드; 선택 변경 후 도착한 파일은 저장하지 않는다.
 */
async function downloadExcel() {
  if (!period.value || !appliedInput || downloading.value || previewLoading.value) return
  downloadController.abort(); downloadController = new AbortController(); const current = ++downloadGeneration
  const selectedPreview = previewGeneration
  const input = appliedInput
  const filename = `financial-report-${period.value.versionLabel}.xlsx`
  downloading.value = true; downloadError.value = ''
  try {
    const blob = await session.request('/api/v1/reports/excel', { method: 'POST', body: JSON.stringify(input), signal: downloadController.signal, blob: true })
    if (current !== downloadGeneration || selectedPreview !== previewGeneration) return
    saveBlob(blob, filename)
  } catch (cause) { if (current === downloadGeneration && cause.name !== 'AbortError') downloadError.value = errorText(cause) }
  finally { if (current === downloadGeneration) downloading.value = false }
}

/** 비율의 null을 실제 0과 구분해 표시한다.
 * Args: value 서버 비율/null. Returns: 백분율 또는 —. Raises: 없음. Side Effects: 없음.
 */
function rate(value) { return value === null || value === undefined ? '—' : `${value}%` }

/** 보고 출처에 포함된 조직의 실제 표시 이름을 찾는다.
 * Args: organizationId 조직 ID. Returns: 조직 이름 또는 식별자. Raises: 없음. Side Effects: 없음.
 */
function organizationName(organizationId) {
  for (const item of period.value?.sources || []) if (String(item.organizationId) === String(organizationId)) return item.organizationName
  return `조직 ${organizationId}`
}

/** 보고 집계에 포함된 항목의 실제 표시 이름을 찾는다.
 * Args: reportItemId 보고 항목 ID. Returns: 항목 이름 또는 식별자. Raises: 없음. Side Effects: 없음.
 */
function reportItemName(reportItemId) {
  for (const item of period.value?.totalRows || []) if (String(item.reportItemId) === String(reportItemId)) return item.reportItemName || item.reportItemCode
  return `항목 ${reportItemId}`
}

/** 화면을 떠날 때 읽기와 다운로드의 오래된 응답을 모두 무효화한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 증가.
 */
function dispose() { periodsGeneration++; versionsGeneration++; periodsController.abort(); versionsController.abort(); resetReport() }
onMounted(loadPeriods)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="report-page">
    <header class="report-heading"><div><p class="report-crumb">경영기획팀 / 보고서</p><h1>보고서 자료</h1><p class="muted">발행한 월간 마스터의 실적과 확정 계획을 확인합니다.</p></div><button class="button primary" :disabled="!period || previewLoading || downloading" @click="downloadExcel">{{ downloading ? 'Excel 준비 중…' : 'Excel 다운로드' }}</button></header>
    <section class="panel report-filters" aria-label="보고 기준 선택">
      <label>연도<input v-model.number="year" type="number" min="2000" max="9999" @change="loadPeriods" /></label>
      <label>기준월<select v-model="month" :disabled="loading || !publishedPeriods.length" @change="loadVersions(false)"><option v-if="!publishedPeriods.length" value="">발행 월 없음</option><option v-for="item in publishedPeriods" :key="item.month" :value="String(item.month)">{{ item.month }}월</option></select></label>
      <label>마스터 버전<select v-model="masterId" :disabled="versionsLoading || !versions.length" @change="loadPreview"><option v-if="!versions.length" value="">버전 없음</option><option v-for="item in versions" :key="item.id" :value="String(item.id)">{{ item.versionLabel }} · {{ dateTime(item.createdAt) }}</option></select></label>
      <label class="explanation-toggle"><input v-model="includeExplanations" type="checkbox" @change="loadPreview" />수용 소명 포함</label>
      <button class="button" :disabled="loading" @click="loadPeriods">새로고침</button>
      <button v-if="versionCursor" class="button" :disabled="versionsLoading" @click="loadVersions(true)">이전 버전 더 보기</button>
    </section>
    <div v-if="error" class="alert error" role="alert">{{ error }}</div>
    <div v-if="versionsError" class="alert error" role="alert">{{ versionsError }} <button class="text-button" @click="loadVersions(false)">버전 다시 조회</button></div>
    <div v-if="previewError" class="alert error" role="alert">{{ previewError }} <button class="text-button" @click="loadPreview">보고 자료 다시 조회</button></div>
    <div v-if="downloadError" class="alert error" role="alert">{{ downloadError }}</div>
    <p v-if="loading || versionsLoading || previewLoading" class="panel empty" role="status">선택한 보고 자료를 불러오는 중…</p>
    <p v-else-if="!error && !versionsError && !previewError && !period" class="panel empty">선택 연도에 보고할 월간 마스터가 없습니다. 월간 요청의 조직별 승인을 완료하면 선택할 수 있습니다.</p>
    <template v-if="period && !previewLoading">
      <p class="notice">{{ period.year }}년 {{ period.month }}월 · {{ period.versionLabel }} · ACTUAL · 단위 원 · 조회 {{ dateTime(report.generatedAt) }} <RouterLink :to="`/planning/masters/${period.masterId}`" class="text-button">마스터 확인 →</RouterLink></p>
      <section class="report-card"><h2>전사 손익 요약</h2><div class="table-scroll"><table><thead><tr><th>항목</th><th class="number">확정 계획</th><th class="number">실적</th><th class="number">실적 − 계획</th><th class="number">차이율</th></tr></thead><tbody><tr v-for="metric in metrics" :key="metric.key"><th scope="row">{{ metric.name }}</th><td class="number">{{ formatMoney(period.planTotals?.[metric.key]) }}</td><td class="number">{{ formatMoney(period.totals?.[metric.key]) }}</td><td class="number">{{ formatMoney(period.comparisons?.[metric.key]?.gapAmount) }}</td><td class="number">{{ rate(period.comparisons?.[metric.key]?.gapRate) }}</td></tr><tr><th scope="row">영업이익률</th><td class="number">{{ rate(period.planTotals?.operatingMargin) }}</td><td class="number">{{ rate(period.totals?.operatingMargin) }}</td><td>—</td><td>—</td></tr></tbody></table></div><p v-for="item in report.annualContingencies" :key="item.annualPlanId" class="muted">{{ item.year }}년 전사 연간 Contingency {{ formatMoney(item.amount) }}원 · 월별 배분이나 실적 손익 차감 없음</p></section>
      <section class="report-card"><h2>사업조직별 손익</h2><div class="table-scroll"><table><thead><tr><th>조직</th><th class="number">매출 실적</th><th class="number">비용 실적</th><th class="number">영업이익 계획</th><th class="number">영업이익 실적</th><th class="number">손익 차액</th></tr></thead><tbody><tr v-for="item in period.organizationSummaries" :key="item.organizationId"><th scope="row">{{ item.organizationName }}<small>{{ item.organizationCode }}</small></th><td class="number">{{ formatMoney(item.totals?.revenue) }}</td><td class="number">{{ formatMoney(item.totals?.cost) }}</td><td class="number">{{ formatMoney(item.planTotals?.operatingProfit) }}</td><td class="number">{{ formatMoney(item.totals?.operatingProfit) }}</td><td class="number">{{ formatMoney(item.comparisons?.operatingProfit?.gapAmount) }}</td></tr></tbody></table></div></section>
      <section v-if="includeExplanations" class="report-card"><h2>수용된 소명</h2><p class="muted">선택 버전의 원천 제출본과 해당 월 ACTUAL에 연결된 수용 소명입니다.</p><p v-if="!report.explanations?.length" class="empty compact">이 버전에 포함된 수용 소명이 없습니다.</p><article v-for="item in report.explanations" :key="item.explanationId" class="report-explanation"><h3>{{ organizationName(item.organizationId) }} · {{ reportItemName(item.reportItemId) }}</h3><p class="answer">{{ item.answer }}</p><p class="muted">{{ item.round }}차 · 작성 {{ item.answeredBy?.name || '—' }} · 수용 {{ item.reviewedBy?.name || '—' }} · {{ dateTime(item.reviewedAt) }}</p><RouterLink :to="`/submissions/${item.submissionId}`" class="text-button">소명 원천 제출본 →</RouterLink></article></section>
      <section class="report-card"><h2>집계 출처</h2><p class="muted">이 마스터 버전에 고정된 실적본과 확정 계획본입니다.</p><div class="table-scroll"><table><thead><tr><th>조직</th><th>실적 제출 회차</th><th>고정 원천</th></tr></thead><tbody><tr v-for="item in period.sources" :key="item.organizationId"><th scope="row">{{ item.organizationName }}<small>{{ item.organizationCode }}</small></th><td>{{ item.versionLabel }}</td><td><RouterLink :to="`/submissions/${item.submissionId}`" class="text-button">실적본</RouterLink><span> · </span><RouterLink v-if="item.planSubmissionId" :to="`/submissions/${item.planSubmissionId}`" class="text-button">확정 계획본</RouterLink></td></tr></tbody></table></div></section>
    </template>
  </div>
</template>

<style scoped>
.report-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; }.report-crumb { color:var(--muted); font-size:12px; margin-bottom:6px; }.report-heading .muted { margin-top:8px; }.report-filters { display:flex; flex-wrap:wrap; align-items:flex-end; gap:14px; }.report-filters label { margin:0; min-width:110px; }.report-filters input,.report-filters select { margin-bottom:0; }.report-filters input[type=number] { max-width:120px; }.report-filters select { max-width:360px; }.report-filters .explanation-toggle { display:flex; align-items:center; gap:8px; padding-bottom:10px; }.explanation-toggle input { width:16px; min-height:16px; accent-color:var(--ink); }.report-card { margin:18px 0; padding:22px; background:white; border:1px solid var(--line); border-radius:12px; }.report-card h2 { font-size:17px; margin-bottom:14px; }.report-card .muted { margin:14px 0 0; }.report-card table { white-space:nowrap; }.report-card th small { display:block; font-weight:400; color:var(--muted); margin-top:4px; }.report-explanation { padding:18px 0; border-top:1px solid var(--line); }.report-explanation h3 { font-size:14px; }.report-explanation .answer { white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.8; margin-top:12px; }.report-explanation .text-button { display:inline-block; margin-top:10px; }@media(max-width:760px) { .report-heading { align-items:flex-start; flex-direction:column; }.report-filters label { flex:1; }.report-filters select { width:100%; }.report-card { padding:16px; } }
</style>
