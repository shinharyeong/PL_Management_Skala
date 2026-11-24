<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, query } from '../lib/display'
import { chartGeometry, formatMoney } from '../lib/decimal-display'
import { amountTone, dashboardMoney, difference, gapRate, moneyString, moneyUnits } from '../lib/dashboard-values'
import ProfitFlowChart from './ProfitFlowChart.vue'

const session = useSession()
const year = ref(Number(new Intl.DateTimeFormat('en', { timeZone: 'Asia/Seoul', year: 'numeric' }).format(new Date())))
const month = ref(1)
const scope = ref('MONTH')
const organization = ref('')
const unit = ref('HUNDRED_MILLION')
const metric = ref('operatingProfit')
const requests = ref([])
const annualRequest = ref(null)
const monthlyRequest = ref(null)
const annualMaster = ref(null)
const master = ref(null)
const forecast = ref(null)
const previousForecast = ref(null)
const periods = ref([])
const reports = ref([])
const previousReportValues = ref(new Map())
const reviews = ref([])
const recentVersions = ref([])
const versionsError = ref('')
const costOpen = ref(true)
const loading = ref(false)
const error = ref('')
const detailError = ref('')
let controller = new AbortController()
let rowsController = new AbortController()
let generation = 0
let rowsGeneration = 0
const metrics = [{ key: 'revenue', code: 'REVENUE_TOTAL', name: '매출' }, { key: 'cost', code: 'COST_TOTAL', name: '영업비용' }, { key: 'operatingProfit', code: 'OPERATING_PROFIT', name: '영업이익' }]

/** 서버 목록의 모든 페이지를 읽는다. Args: path 경로, filters 필터, signal 취소 신호. Returns: 전체 목록. Raises: HTTP 오류. Side Effects: GET. */
async function pages(path, filters, signal) { const items = []; let cursor = null; do { const result = await session.request(`${path}${query({ ...filters, limit: 100, cursor })}`, { signal }); items.push(...result.items); cursor = result.nextCursor } while (cursor); return items }
/** 실제 요청이 존재하는 연도를 최신순으로 제공한다. Args: 없음. Returns: 연도목록. Raises: 없음. Side Effects: 없음. */
const years = computed(function requestYears() { const values = new Set(); for (const item of requests.value) values.add(item.year); return [...values].sort(newestYear) })
/** 최신연도를 먼저 정렬한다. Args: left/right 연도. Returns: 순서. Raises: 없음. Side Effects: 없음. */
function newestYear(left, right) { return right - left }
/** 요청 목록을 읽고 현재 또는 데이터가 있는 연도를 선택한다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·선택 갱신. */
async function initialize() {
  loading.value = true; error.value = ''
  try { requests.value = await pages('/api/v1/submission-requests', {}, controller.signal); if (!years.value.includes(year.value)) year.value = years.value[0] || year.value; await loadYear() }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { loading.value = false }
}
/** 선택연도의 연간마스터와 발행 월을 읽는다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 이전조회 취소·GET·금액초기화. */
async function loadYear() {
  controller.abort(); controller = new AbortController(); const current = ++generation
  loading.value = true; error.value = ''; detailError.value = ''; organization.value = ''; annualRequest.value = null; annualMaster.value = null; master.value = null; forecast.value = null; periods.value = []
  try {
    let annual = null
    for (const item of requests.value) if (item.year === year.value && item.type === 'ANNUAL_PLAN') annual = item
    const calls = [session.request(`/api/v1/master-periods?year=${year.value}`, { signal: controller.signal })]
    if (annual) calls.push(session.request(`/api/v1/submission-requests/${annual.id}`, { signal: controller.signal }), session.request(`/api/v1/submission-requests/${annual.id}/annual-master`, { signal: controller.signal }))
    const result = await Promise.all(calls); if (current !== generation) return
    periods.value = result[0].items
    if (annual) {
      annualRequest.value = result[1]
      if (result[2].latestMasterId) {
        const savedMaster = await session.request(`/api/v1/annual-masters/${result[2].latestMasterId}`, { signal: controller.signal })
        if (current !== generation) return
        annualMaster.value = savedMaster
      }
    }
    if (current !== generation) return
    let latest = 0; for (const item of periods.value) if (item.latestMasterId) latest = Math.max(latest, item.month)
    month.value = latest || 1; scope.value = latest ? 'MONTH' : 'YEAR'; await loadMonth(current)
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}
/** 선택월의 고정실적과 최신승인 전망·검토를 읽는다. Args: parent 상위세대 또는 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·선택월 갱신. */
async function loadMonth(parent) {
  const current = typeof parent === 'number' ? parent : ++generation
  if (typeof parent !== 'number') { controller.abort(); controller = new AbortController() }
  rowsController.abort(); rowsGeneration++
  loading.value = true; master.value = null; forecast.value = null; previousForecast.value = null; monthlyRequest.value = null; reports.value = []; previousReportValues.value = new Map(); reviews.value = []; recentVersions.value = []; detailError.value = ''; versionsError.value = ''
  try {
    let selectedPeriod = null; let latest = null; let previous = null
    for (const item of periods.value) if (item.month === month.value) selectedPeriod = item
    for (const item of requests.value) if (item.year === year.value && item.type === 'MONTHLY_ACTUAL') { if (item.month === month.value) monthlyRequest.value = item; if (item.month <= month.value && (!latest || item.month > latest.month)) latest = item; if (item.month === month.value - 1) previous = item }
    const calls = []
    if (selectedPeriod?.latestMasterId) calls.push(['master', session.request(`/api/v1/masters/${selectedPeriod.latestMasterId}`, { signal: controller.signal })])
    if (selectedPeriod?.latestMasterId) calls.push(['versions', session.request(`/api/v1/masters${query({ year: year.value, month: month.value, limit: 3 })}`, { signal: controller.signal })])
    if (monthlyRequest.value) calls.push(['request', session.request(`/api/v1/submission-requests/${monthlyRequest.value.id}`, { signal: controller.signal })], ['reviews', pages('/api/v1/review-items', { requestId: monthlyRequest.value.id }, controller.signal)])
    if (latest) calls.push(['forecast', session.request(`/api/v1/submission-requests/${latest.id}/forecast`, { signal: controller.signal })])
    if (previous) calls.push(['previous', session.request(`/api/v1/submission-requests/${previous.id}/forecast`, { signal: controller.signal })])
    const result = await Promise.allSettled(calls.map(jobPromise)); if (current !== generation) return
    for (let index = 0; index < result.length; index++) { const item = result[index]; if (item.status === 'rejected') { if (item.reason.name !== 'AbortError') { if (calls[index][0] === 'versions') versionsError.value = errorText(item.reason); else detailError.value = errorText(item.reason) } continue } switch (calls[index][0]) { case 'master': master.value = item.value; break; case 'request': monthlyRequest.value = item.value; break; case 'reviews': reviews.value = item.value; break; case 'forecast': forecast.value = item.value; break; case 'previous': previousForecast.value = item.value; break; case 'versions': recentVersions.value = item.value.items; break } }
    await loadRows()
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}
/** 병렬 호출의 Promise를 반환한다. Args: job 이름/Promise. Returns: Promise. Raises: 없음. Side Effects: 없음. */
function jobPromise(job) { return job[1] }
/** 선택조직의 고정 보고행과 전월 승인본이 추정했던 당월 보고값을 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: GET·보고행 교체. 전사 필수원천이 모두 준비된 범위만 합산하고 없는 값을 0으로 보정하지 않는다.
 */
async function loadRows() {
  rowsController.abort(); rowsController = new AbortController(); const current = ++rowsGeneration; reports.value = []; previousReportValues.value = new Map()
  if (!master.value) return
  try {
    const rows = await pages(`/api/v1/masters/${master.value.id}/report-values`, { organizationId: organization.value }, rowsController.signal)
    if (current !== rowsGeneration) return
    reports.value = rows
    if (!entity(previousForecast.value)?.ready) return
    const sources = []
    for (const source of previousForecast.value?.sources || []) if (!organization.value || String(source.organizationId) === organization.value) sources.push(source)
    if (!sources.length) return
    const sourceRows = []
    for (let offset = 0; offset < sources.length; offset += 4) {
      const calls = []
      for (const source of sources.slice(offset, offset + 4)) calls.push(pages(`/api/v1/submissions/${source.submissionId}/report-values`, { year: year.value, month: month.value, scenario: 'ESTIMATE' }, rowsController.signal))
      const results = await Promise.all(calls)
      if (current !== rowsGeneration) return
      sourceRows.push(...results)
    }
    previousReportValues.value = aggregatePreviousReports(rows, sourceRows)
  }
  catch (cause) { if (current === rowsGeneration && cause.name !== 'AbortError') detailError.value = errorText(cause) }
}
/** 모든 승인원천에 실제로 존재하는 동일 보고항목만 정밀한 십진 합계로 제공한다.
 * Args: rows 현재 고정 보고항목, sourceRows 승인원천별 전월 추정값 배열. Returns: 보고항목 ID별 금액 Map.
 * Raises: 없음. Side Effects: 없음. 누락·미확정·중복 값이 있으면 해당 항목의 합계를 만들지 않는다.
 */
function aggregatePreviousReports(rows, sourceRows) {
  const totals = new Map()
  for (const row of rows) {
    let amount = 0n; let complete = sourceRows.length > 0
    for (const values of sourceRows) {
      let matched = null; let count = 0
      for (const value of values) if (String(value.reportItemId) === String(row.reportItemId)) { matched = value; count++ }
      const value = matched?.valueStatus === 'AVAILABLE' ? moneyUnits(matched.amount) : null
      if (count !== 1 || value === null) { complete = false; break }
      amount += value
    }
    if (complete) totals.set(String(row.reportItemId), moneyString(amount))
  }
  return totals
}
/** 전사 또는 선택조직 DTO를 찾는다. Args: source 집계. Returns: 선택 DTO/null. Raises: 없음. Side Effects: 없음. */
function entity(source) { if (!organization.value) return source; for (const item of source?.organizations || []) if (String(item.organizationId) === organization.value) return item; return null }
/** 특정월 DTO를 찾는다. Args: items 월목록, value 월. Returns: DTO/null. Raises: 없음. Side Effects: 없음. */
function findMonth(items, value) { for (const item of items || []) if (item.month === value) return item; return null }
/** 실적·계획·전월추정을 같은 범위로 제공한다. Args: 없음. Returns: 비교금액 DTO. Raises: 없음. Side Effects: 없음. */
const selected = computed(function selectedAmounts() {
  const actual = entity(master.value); const annual = entity(annualMaster.value); const outlook = entity(forecast.value); const previous = entity(previousForecast.value)
  if (scope.value === 'YEAR') return outlook?.ready ? { current: outlook.totals, plan: outlook.planTotals, previous: previous?.ready ? previous.totals : null, label: '연간 실적·추정' } : { current: annual?.totals, plan: null, previous: null, label: '연간 계획', isPlan: true }
  const now = findMonth(outlook?.months, month.value); const before = findMonth(previous?.months, month.value)
  return { current: actual?.totals || now?.totals, plan: actual?.planTotals || now?.planTotals || findMonth(annual?.months, month.value), previous: before?.totals, label: actual ? `${month.value}월 실적` : now?.totals ? `${month.value}월 추정` : `${month.value}월 미발행` }
})
/** 집계가 있는 조직을 제공한다. Args: 없음. Returns: 조직목록. Raises: 없음. Side Effects: 없음. */
const organizations = computed(function availableOrganizations() { return annualMaster.value?.organizations || master.value?.organizations || forecast.value?.organizations || [] })
/** 조회범위의 이름을 반환한다. Args: 없음. Returns: 범위명. Raises: 없음. Side Effects: 없음. */
const scopeName = computed(function selectedScope() { if (!organization.value) return '전사'; for (const item of organizations.value) if (String(item.organizationId) === organization.value) return item.organizationName; return '선택 조직' })
/** 실제 최신 제출에 연결된 필수 검토만 센다. Args: 없음. Returns: 검토목록. Raises: 없음. Side Effects: 없음. */
const activeReviews = computed(function currentReviews() { const ids = new Set(); for (const target of monthlyRequest.value?.targets || []) if (target.latestSubmittedSubmissionId) ids.add(String(target.latestSubmittedSubmissionId)); const items = []; for (const item of reviews.value) if (ids.has(String(item.submissionId)) && item.required) items.push(item); return items })
/** 발송된 조직의 업무 할일을 센다. Args: request 요청. Returns: 미제출/검토/반려수. Raises: 없음. Side Effects: 없음. */
function tasks(request) { const counts = { missing: 0, pending: 0, rejected: 0 }; for (const target of request?.targets || []) if (target.selected !== false) { if (target.latestStatus === 'PENDING') counts.pending++; else if (target.latestStatus === 'REJECTED') counts.rejected++; else if (target.latestStatus !== 'APPROVED') counts.missing++ } return counts }
/** 마감 다섯단계와 실제 진행수를 구성한다. Args: 없음. Returns: 단계목록. Raises: 없음. Side Effects: 없음. */
const stages = computed(function closingStages() { const request = monthlyRequest.value; const total = request?.selectedTargetCount ?? request?.targetCount ?? 0; let answered = 0; let accepted = 0; for (const item of activeReviews.value) { if (item.explanationState !== 'UNANSWERED') answered++; if (item.explanationState === 'ACCEPTED') accepted++ } return [{ name: '제출 요청', count: request ? '발송 완료' : '요청 전', done: !!request }, { name: '조직 제출', count: `${request?.submittedCount || 0}/${total}`, done: !!total && request?.submittedCount >= total }, { name: 'Gap 검토', count: `${answered}/${activeReviews.value.length}`, done: !!request?.submittedCount && answered === activeReviews.value.length }, { name: '소명 완료', count: `${accepted}/${activeReviews.value.length}`, done: !!request?.submittedCount && accepted === activeReviews.value.length }, { name: '마스터 생성', count: master.value ? '발행 완료' : '대기', done: !!master.value }] })
/** 남은 기한을 D-day로 표시한다. Args: 없음. Returns: 기한표시. Raises: 없음. Side Effects: 없음. */
const deadline = computed(function deadlineLabel() { if (!monthlyRequest.value?.dueAt) return '기한 미지정'; const days = Math.ceil((new Date(monthlyRequest.value.dueAt).getTime() - Date.now()) / 86400000); return days < 0 ? `기한 ${-days}일 경과` : days === 0 ? '오늘 마감' : `마감 D-${days}` })
/** 실제 보고행을 6열 손익표로 구성한다. Args: 없음. Returns: 요약행. Raises: 없음. Side Effects: 없음. */
const summaryRows = computed(function profitRows() { const result = []; for (const item of metrics) { result.push({ ...item, amount: selected.value.current?.[item.key], plan: selected.value.plan?.[item.key], previous: selected.value.previous?.[item.key], group: item.key === 'cost', total: item.key === 'operatingProfit' }); if (item.key === 'cost' && costOpen.value && scope.value === 'MONTH') for (const row of reports.value) if (row.kind === 'COST') result.push({ key: row.reportItemCode, code: row.reportItemCode, name: row.reportItemName, amount: row.amount, plan: row.planAmount, previous: previousReportValues.value.get(String(row.reportItemId)) ?? null, child: true }) } return result })
/** 고정 승인원천의 수락소명을 항목 툴팁으로 제공한다. Args: row 보고행. Returns: 소명/안내문. Raises: 없음. Side Effects: 없음. */
function reason(row) { const ids = new Set(); for (const source of master.value?.sources || []) if (!organization.value || String(source.organizationId) === organization.value) ids.add(String(source.submissionId)); const answers = []; for (const item of reviews.value) if (ids.has(String(item.submissionId)) && item.month === month.value && item.scenario === 'ACTUAL' && item.reportItem.code === row.code) for (const explanation of item.explanations || []) if (explanation.verdict === 'ACCEPTED' && explanation.answer) answers.push(explanation.answer); return answers.join('\n') || '수락된 소명이 없습니다. 월간 시트 관리에서 확인할 수 있습니다.' }
/** 동일범위 조직별 손익을 제공한다. Args: 없음. Returns: 조직별집계. Raises: 없음. Side Effects: 없음. */
const departments = computed(function departmentRows() { const result = []; const base = scope.value === 'YEAR' ? forecast.value?.ready ? forecast.value : annualMaster.value : master.value; for (const item of base?.organizations || []) if (!organization.value || String(item.organizationId) === organization.value) result.push({ ...item, gap: difference(item.totals?.operatingProfit, item.planTotals?.operatingProfit) }); return result })
/** 비교가능한 차액만 선택한다. Args: row 조직행. Returns: 가능여부. Raises: 없음. Side Effects: 없음. */
function hasGap(row) { return moneyUnits(row.gap) !== null }
/** 차액 절댓값의 내림차순을 정한다. Args: left/right 조직행. Returns: 순서. Raises: 없음. Side Effects: 없음. */
function compareGap(left, right) { const a = moneyUnits(left.gap); const b = moneyUnits(right.gap); const absA = a < 0n ? -a : a; const absB = b < 0n ? -b : b; return absA === absB ? 0 : absA > absB ? -1 : 1 }
/** 월간 보고항목의 매출증감·비용차감 또는 연간 조직 OP차액으로 상위 다섯개를 계산한다. Args: 없음. Returns: 순위. Raises: 없음. Side Effects: 없음; 파생합계를 하위항목과 중복 합산하지 않는다. */
const topFive = computed(function topDeviations() {
  const candidates = []
  if (scope.value === 'MONTH') {
    for (const row of reports.value) if (row.kind === 'REVENUE' || row.kind === 'COST') {
      const delta = moneyUnits(difference(row.amount, row.planAmount))
      if (delta !== null) candidates.push({ organizationId: organization.value, organizationName: row.reportItemName, reportItemId: row.reportItemId, gap: moneyString(row.kind === 'COST' ? -delta : delta) })
    }
  } else candidates.push(...departments.value.filter(hasGap))
  const rows = candidates.sort(compareGap).slice(0, 5); let maximum = 0n
  for (const row of rows) { const value = moneyUnits(row.gap); const absolute = value < 0n ? -value : value; if (absolute > maximum) maximum = absolute }
  const result = []
  for (const row of rows) { const value = moneyUnits(row.gap); result.push({ ...row, width: maximum ? Number((value < 0n ? -value : value) * 10000n / maximum) / 100 : 0 }) }
  return result
})
/** 최신 승인 전망과 계획을 12개월 차트 좌표로 변환한다. Args: 없음. Returns: 차트DTO. Raises: 없음. Side Effects: 없음; 금액비교는 BigInt 사용. */
const trend = computed(function monthlyTrend() { const outlook = entity(forecast.value); const annual = entity(annualMaster.value); const rows = []; const values = []; for (let value = 1; value <= 12; value++) { const item = findMonth(outlook?.months, value); const row = { month: value, scenario: item?.scenario, amount: item?.totals?.[metric.value], plan: item?.planTotals?.[metric.value] ?? findMonth(annual?.months, value)?.[metric.value] }; rows.push(row); values.push(row.amount, row.plan) } const geometry = chartGeometry(values); const points = []; for (let index = 0; index < rows.length; index++) { const row = rows[index]; row.top = Math.min(geometry.zero, geometry.positions[index * 2]); row.height = Math.abs(geometry.positions[index * 2] - geometry.zero); row.planY = geometry.positions[index * 2 + 1]; if (row.plan != null) points.push(`${(index + .5) * 100 / 12},${row.planY}`) } return { rows, zero: geometry.zero, points: points.join(' ') } })
/** 선택월을 조회한다. Args: value 월. Returns: Promise<void>. Raises: 없음. Side Effects: 월선택·GET. */
async function chooseMonth(value) { month.value = value; scope.value = 'MONTH'; await loadMonth() }
/** 현재범위에 맞는 금액을 표시한다. Args: value 십진값. Returns: 표시문자열. Raises: 없음. Side Effects: 없음. */
function money(value) { return dashboardMoney(value, unit.value) }
/** 없는 비율과 0비율을 구분한다. Args: value 비율. Returns: 백분율/—. Raises: 없음. Side Effects: 없음. */
function rate(value) { return value == null ? '—' : `${String(value).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')}%` }
/** 월간 검토로 이동할 문맥을 만든다. Args: 없음. Returns: 라우트. Raises: 없음. Side Effects: 없음. */
function monthlyLink() { return { path: '/planning/gap', query: monthlyRequest.value ? { requestId: monthlyRequest.value.id } : { year: year.value, month: month.value } } }
/** 실제 순위 항목의 조직·보고 항목을 월간 검토 필터로 전달한다.
 * Args: item 순위 DTO. Returns: 요청·조직·항목 라우트. Raises: 없음. Side Effects: 없음; 전사 합계에 임의 조직을 지정하지 않는다.
 */
function topFiveLink(item) {
  const route = monthlyLink()
  if (item.organizationId) route.query.organizationId = String(item.organizationId)
  if (item.reportItemId) route.query.reportItemId = String(item.reportItemId)
  else for (const row of reports.value) if (row.reportItemCode === 'OPERATING_PROFIT') { route.query.reportItemId = String(row.reportItemId); break }
  return route
}
/** 이탈후 응답을 폐기한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: GET취소. */
function dispose() { generation++; rowsGeneration++; controller.abort(); rowsController.abort() }
onMounted(initialize)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="profit-home">
    <header class="home-heading"><h1>전사 손익 현황</h1><div class="home-filters"><select v-model.number="year" aria-label="연도" :disabled="loading" @change="loadYear"><option v-for="value in years" :key="value" :value="value">{{ value }}년</option></select><select v-model.number="month" aria-label="월" :disabled="loading" @change="loadMonth()"><option v-for="value in 12" :key="value" :value="value">{{ value }}월</option></select><select v-model="organization" aria-label="조직 범위" :disabled="loading" @change="loadRows"><option value="">전사 기준</option><option v-for="item in organizations" :key="item.organizationId" :value="String(item.organizationId)">{{ item.organizationName }}</option></select><RouterLink :to="{ path: '/planning/reports', query: { year, month, masterId: master?.id } }" class="button">월말 보고서 추출</RouterLink><button class="button" :disabled="loading" @click="initialize">새로고침</button></div></header>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="loading" class="panel empty" role="status">계획·실적·마감 현황을 불러오고 있습니다…</p>
    <template v-else>
      <section class="home-card closing" data-figma-node="168:1903"><div class="closing-left"><div class="card-head"><h2>{{ month }}월 마감 현황</h2><span class="due-pill">{{ deadline }}</span></div><ol class="closing-stages"><li v-for="(stage, index) in stages" :key="stage.name" :class="{ complete: stage.done }"><RouterLink :to="monthlyLink()"><b>{{ stage.done ? '✓' : index + 1 }}</b><strong>{{ stage.name }}</strong><small>{{ stage.count }}</small></RouterLink></li></ol><progress :value="stages.filter(stage => stage.done).length" max="5" aria-label="마감 단계 완료"></progress></div><div class="home-divider" aria-hidden="true"><img src="/assets/planner-home-divider.svg" alt="" /></div><div class="todo"><h2>To Do</h2><div class="todo-columns"><section><h3>연간</h3><RouterLink to="/planning/annual-plan/new"><span>연간 계획 제출 요청</span><b>→</b></RouterLink><RouterLink :to="{ path: '/planning/annual-plan', query: { requestId: annualRequest?.id } }"><span>연간 계획 미제출</span><em>{{ tasks(annualRequest).missing }}곳</em><b>→</b></RouterLink><RouterLink to="/planning/annual-plan"><span>계획 검토 필요 조직</span><em>{{ tasks(annualRequest).pending }}곳</em><b>→</b></RouterLink><RouterLink to="/planning/annual-plan"><span>반려 후 재제출</span><em>{{ tasks(annualRequest).rejected }}곳</em><b>→</b></RouterLink></section><section><h3>월간</h3><RouterLink :to="monthlyLink()"><span>월간 시트 제출 요청</span><b>→</b></RouterLink><RouterLink :to="monthlyLink()"><span>월간 시트 미제출</span><em>{{ tasks(monthlyRequest).missing }}곳</em><b>→</b></RouterLink><RouterLink :to="monthlyLink()"><span>시트 검토 필요 조직</span><em>{{ tasks(monthlyRequest).pending }}곳</em><b>→</b></RouterLink><RouterLink :to="monthlyLink()"><span>소명 검토 필요 항목</span><em>{{ activeReviews.filter(item => item.explanationState === 'IN_REVIEW').length }}건</em><b>→</b></RouterLink></section></div></div></section>
      <div class="scope-toolbar"><p>{{ scopeName }} · {{ year }}년 {{ selected.label }} <span v-if="master && scope === 'MONTH'">{{ master.versionLabel }}</span></p><div class="segmented"><button :class="{ active: scope === 'MONTH' }" @click="scope = 'MONTH'">당월</button><button :class="{ active: scope === 'YEAR' }" @click="scope = 'YEAR'">연간</button></div><select v-model="unit" aria-label="금액 단위"><option value="HUNDRED_MILLION">단위 억원</option><option value="KRW">단위 원</option></select></div>
      <section class="home-kpis" data-figma-node="167:242"><article v-for="item in metrics" :key="item.key" class="home-card kpi"><div class="card-head"><h2>{{ item.name }}</h2><span v-if="item.key === 'operatingProfit' && selected.current?.operatingMargin != null" class="margin-tag">이익률 {{ rate(selected.current.operatingMargin) }}</span></div><p :title="`${formatMoney(selected.current?.[item.key])}원`"><strong>{{ money(selected.current?.[item.key]) }}</strong><small>{{ unit === 'KRW' ? '원' : '억원' }}</small></p><dl><div><dt>계획 대비</dt><dd :class="amountTone(difference(selected.current?.[item.key], selected.plan?.[item.key]))">{{ rate(gapRate(selected.current?.[item.key], selected.plan?.[item.key])) }}</dd></div><div><dt>전월 추정 대비</dt><dd :class="amountTone(difference(selected.current?.[item.key], selected.previous?.[item.key]))">{{ rate(gapRate(selected.current?.[item.key], selected.previous?.[item.key])) }}</dd></div></dl></article></section>
      <p v-if="!selected.current" class="notice">선택 기간의 승인 실적·추정이 아직 없습니다. 월간 시트 관리에서 제출·승인 상태를 확인해 주세요.</p><p v-if="detailError" class="alert error" role="alert">일부 비교 자료를 불러오지 못했습니다. {{ detailError }}</p>
      <div class="profit-grid"><section class="home-card summary" data-figma-node="168:2031"><div class="card-head"><div><h2>{{ scopeName }} 손익 요약</h2><p>{{ year }}년 {{ selected.label }}</p></div><span>단위 {{ unit === 'KRW' ? '원' : '억원' }}</span></div><div class="table-scroll"><table class="profit-table"><thead><tr><th>손익 항목</th><th class="number">{{ scope === 'MONTH' ? '당월 실적' : selected.isPlan ? '연간 계획' : '연간 전망' }}</th><th class="number">계획 대비</th><th class="number">계획 차이액</th><th class="number">전월 추정 대비</th><th class="number">전월 차이액</th></tr></thead><tbody><tr v-for="row in summaryRows" :key="row.key" :class="{ total: row.total, child: row.child }"><th><button v-if="row.group" class="cost-toggle" :aria-expanded="costOpen" @click="costOpen = !costOpen">{{ costOpen ? '▾' : '▸' }} {{ row.name }}</button><span v-else>{{ row.name }}</span></th><td class="number" :title="`${formatMoney(row.amount)}원`">{{ money(row.amount) }}</td><td class="number" :class="amountTone(difference(row.amount, row.plan))" :title="reason(row)">{{ rate(gapRate(row.amount, row.plan)) }}</td><td class="number" :class="amountTone(difference(row.amount, row.plan))" :title="reason(row)">{{ money(difference(row.amount, row.plan)) }}</td><td class="number" :class="amountTone(difference(row.amount, row.previous))">{{ rate(gapRate(row.amount, row.previous)) }}</td><td class="number" :class="amountTone(difference(row.amount, row.previous))">{{ money(difference(row.amount, row.previous)) }}</td></tr><tr><th>영업이익률</th><td class="number">{{ rate(selected.current?.operatingMargin) }}</td><td colspan="4" class="number muted">계획 {{ rate(selected.plan?.operatingMargin) }}</td></tr></tbody></table></div><p class="card-note">비교 기준이 없거나 기준 금액이 0인 비율은 —로 표시합니다. 차이값 위에서 수락된 소명을 확인할 수 있습니다.</p></section><section class="home-card drivers" data-figma-node="167:372"><h2>손익 변동 원인 Top 5</h2><p class="driver-total">계획 대비 <strong :class="amountTone(difference(selected.current?.operatingProfit, selected.plan?.operatingProfit))">{{ money(difference(selected.current?.operatingProfit, selected.plan?.operatingProfit)) }}</strong><small>{{ unit === 'KRW' ? '원' : '억원' }}</small></p><p v-if="!topFive.length" class="empty compact">승인 실적·계획이 모이면 표시됩니다.</p><ol v-else><li v-for="item in topFive" :key="item.reportItemId || item.organizationId"><RouterLink :to="topFiveLink(item)"><strong>{{ item.organizationName }}</strong><span :class="amountTone(item.gap)">{{ money(item.gap) }}</span></RouterLink><div class="driver-track"><i :class="amountTone(item.gap)" :style="{ width: `${item.width}%` }"></i></div></li></ol><RouterLink :to="monthlyLink()" class="text-button">항목별 Gap · 소명 확인 →</RouterLink></section></div>
      <ProfitFlowChart :totals="selected.current" :unit="unit" :label="`${scopeName} · ${year}년 ${selected.label}`" /><section class="home-card divisions"><div class="card-head"><h2>사업부별 손익</h2><span>단위 {{ unit === 'KRW' ? '원' : '억원' }}</span></div><div class="table-scroll"><table><thead><tr><th>사업부</th><th class="number">매출</th><th class="number">영업비용</th><th class="number">영업이익</th><th class="number">계획 차이액</th></tr></thead><tbody><tr v-for="item in departments" :key="item.organizationId"><th><button class="text-button" @click="organization = String(item.organizationId); loadRows()">{{ item.organizationName }}</button></th><td class="number">{{ money(item.totals?.revenue) }}</td><td class="number">{{ money(item.totals?.cost) }}</td><td class="number">{{ money(item.totals?.operatingProfit) }}</td><td class="number" :class="amountTone(item.gap)">{{ money(item.gap) }}</td></tr><tr v-if="!departments.length"><td colspan="5" class="empty compact">발행한 마스터가 없습니다.</td></tr></tbody></table></div></section>
      <section class="home-card trend" data-figma-node="168:2097"><div class="card-head"><div><h2>월별 {{ metric === 'operatingProfit' ? '영업이익' : '매출' }} 추이</h2><p>실적 · 최신 추정 vs 연간 계획</p></div><div class="segmented"><button :class="{ active: metric === 'operatingProfit' }" @click="metric = 'operatingProfit'">영업이익</button><button :class="{ active: metric === 'revenue' }" @click="metric = 'revenue'">매출</button></div></div><div class="trend-legend"><span><i></i>실적</span><span><i class="estimate"></i>추정</span><span><i class="plan"></i>계획</span></div><div class="trend-plot"><div class="zero-line" :style="{ top: `${trend.zero}%` }"></div><svg class="plan-line" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true"><polyline v-if="trend.points" :points="trend.points" fill="none" stroke="#b58c56" stroke-width="1.6" vector-effect="non-scaling-stroke" /></svg><div v-for="item in trend.rows" :key="item.month" class="trend-column"><span v-if="item.amount != null" :class="['trend-bar', { estimate: item.scenario === 'ESTIMATE', selected: item.month === month }]" :style="{ top: `${item.top}%`, height: `${item.height}%` }" :title="`${item.month}월 ${money(item.amount)}`"></span><img v-if="item.plan != null" class="plan-point" src="/assets/planner-trend-point.svg" alt="" :style="{ top: `${item.planY}%` }" /></div></div><div class="trend-labels"><button v-for="item in trend.rows" :key="item.month" :class="{ selected: item.month === month }" @click="chooseMonth(item.month)">{{ item.month }}월</button></div></section>
      <section class="home-card recent-versions" aria-labelledby="recent-versions-title"><div class="card-head"><div><h2 id="recent-versions-title">최근 버전 변경 내역</h2><p>{{ year }}년 {{ month }}월 · 공식 마스터 최근 3개</p></div><RouterLink v-if="master" :to="`/planning/masters/${master.id}`" class="text-button">전체 버전 보기 →</RouterLink></div><p v-if="versionsError" class="alert error" role="alert">{{ versionsError }}</p><div v-if="recentVersions.length" class="version-cards"><article v-for="version in recentVersions" :key="version.id" class="version-card"><div><span class="version-kind">{{ version.previousMasterId ? '승인 원천 반영' : '최초 발행' }}</span><time :datetime="version.createdAt">{{ dateTime(version.createdAt) }}</time></div><RouterLink :to="`/planning/masters/${version.id}`" class="version-title">{{ version.versionLabel }} <span>→</span></RouterLink><p>{{ version.previousMasterId ? '조직 승인본을 반영한 고정 버전입니다.' : '전사 필수 조직의 승인본으로 발행했습니다.' }}</p><div class="version-links"><RouterLink v-if="version.previousMasterId" :to="`/planning/masters/${version.previousMasterId}`">이전 버전</RouterLink><RouterLink v-if="version.triggerSubmissionId" :to="{ path: `/submissions/${version.triggerSubmissionId}`, query: { from: 'gap', requestId: version.requestId } }">수락 원천 확인</RouterLink></div></article></div><p v-else-if="!versionsError" class="empty compact">선택 월에 발행한 마스터가 없습니다.</p></section>
      <details class="data-basis"><summary>집계 기준 · 마스터 버전</summary><p>공식 실적은 발행한 월간 마스터, 연간 전망은 해당 월의 최신 승인본을 사용합니다. 원본과 이전 버전은 보존됩니다.</p><p v-if="master">{{ master.versionLabel }} · {{ dateTime(master.createdAt) }} 발행 <RouterLink :to="`/planning/masters/${master.id}`" class="text-button">마스터 원천 확인 →</RouterLink></p><p v-if="annualMaster">{{ annualMaster.versionLabel }} <RouterLink :to="`/planning/annual-masters/${annualMaster.id}`" class="text-button">연간 계획 마스터 →</RouterLink></p></details>
    </template>
  </div>
</template>

<style scoped>
.profit-home{display:flex;flex-direction:column;gap:20px}.home-heading,.home-filters,.card-head{display:flex;justify-content:space-between;align-items:center;gap:12px}.home-heading{margin-bottom:10px}.home-heading h1{font-size:26px}.home-filters{justify-content:flex-end;flex-wrap:wrap}.home-filters select{width:auto;margin:0;min-width:78px;max-width:165px}.home-card{min-width:0;padding:22px 24px;background:#fff;border:1px solid #e7e4de;border-radius:12px}.home-card h2{font-size:16px}.card-head>span,.card-head p{font-size:11px;color:#8c9098}.card-head p{margin-top:6px}.closing{display:grid;grid-template-columns:minmax(280px,.85fr) 1px minmax(350px,1.2fr);gap:24px;overflow:hidden}.home-divider{width:1px;height:100%;overflow:hidden}.home-divider img{display:block}.due-pill{color:#c4453a!important;background:#fbeae8;padding:5px 9px;border-radius:20px}.closing-stages{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:6px;margin:30px 0 23px;padding:0;list-style:none}.closing-stages a{display:flex;align-items:center;flex-direction:column;gap:8px}.closing-stages b{display:grid;place-items:center;width:24px;height:24px;border:1px solid #ccc;border-radius:50%;font-size:11px;color:#aaa;font-weight:400}.closing-stages .complete b{border-color:#a9875b;background:#a9875b;color:white}.closing-stages li:first-child.complete b{background:#14171e;border-color:#14171e}.closing-stages strong{font-size:11px;font-weight:500;white-space:nowrap}.closing-stages small{color:#9ea2a9;font-size:10px}.closing progress{height:5px;width:100%;appearance:none;border:0;border-radius:20px;overflow:hidden}.closing progress::-webkit-progress-bar{background:#efeeea}.closing progress::-webkit-progress-value{background:#a9875b}.todo-columns{display:grid;grid-template-columns:1fr 1fr;gap:26px;margin-top:12px}.todo h3{margin-bottom:12px;font-size:14px}.todo a{display:flex;gap:7px;align-items:center;padding:7px 0;font-size:11px}.todo a:before{content:'';width:4px;height:4px;border-radius:50%;background:#bb793a;flex:none}.todo a>span{flex:1}.todo a>b{border-radius:7px;background:#14171e;color:white;padding:4px 8px;font-weight:400}.todo em{font-style:normal;color:#c4453a;font-weight:600;white-space:nowrap}.scope-toolbar{display:flex;gap:10px;align-items:center}.scope-toolbar>p{flex:1;color:#777;font-size:12px}.scope-toolbar>p span{color:#9ea2a9;margin-left:12px}.scope-toolbar>select{width:auto;margin:0;min-height:31px;font-size:11px}.segmented{display:flex;gap:3px;padding:3px;border-radius:8px;background:#efeeea}.segmented button{padding:5px 10px;border-radius:5px;color:#91949b;background:transparent;font-size:11px;white-space:nowrap}.segmented .active{background:white;color:#14171e;font-weight:600}.home-kpis{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.kpi{padding:20px 22px}.kpi h2{font-size:14px;color:#4a4844}.margin-tag{border-radius:15px;background:#f0eeea;padding:3px 8px;color:#14171e!important;font-size:11px!important}.kpi>p{display:flex;align-items:baseline;gap:6px;margin:17px 0 12px;overflow-wrap:anywhere}.kpi>p strong{font-size:clamp(22px,2.2vw,33px);font-variant-numeric:tabular-nums;letter-spacing:-.6px}.kpi>p small{font-size:11px;color:#777}.kpi dl{border-top:1px solid #efede8;padding-top:10px;margin:0;font-size:11px}.kpi dl>div{display:flex;justify-content:space-between;gap:12px;margin:5px 0}.kpi dt{color:#888}.kpi dd{margin:0;font-weight:600}.positive{color:#bd2a20}.negative{color:#2454cc}.profit-grid{display:grid;grid-template-columns:minmax(0,1.85fr) minmax(240px,.85fr);gap:18px}.profit-table{margin-top:20px}.profit-table th,.profit-table td{padding:12px 9px;font-size:11px;border-bottom:1px solid #e7e4de}.profit-table tbody th{background:white;color:#14171e;font-weight:700;min-width:105px}.profit-table .child th,.profit-table .child td{background:#fcfbf9}.profit-table .child th{padding-left:24px;font-weight:400;color:#787a80}.profit-table .total th,.profit-table .total td{background:#faf7f1;font-weight:700}.cost-toggle{color:inherit;font-weight:inherit;background:none;text-align:left;padding:0}.card-note{margin-top:12px;font-size:10px;color:#9a9690;line-height:1.6}.drivers h2{margin-bottom:14px}.driver-total{font-size:16px;color:#8b6c44;font-weight:600}.driver-total strong{font-size:24px;margin-left:5px}.driver-total small{font-size:11px;color:#777;font-weight:400;margin-left:5px}.drivers ol{list-style:none;margin:20px 0;padding:0}.drivers li{margin-top:16px}.drivers li a{display:flex;justify-content:space-between;align-items:center;gap:10px;width:100%;background:transparent;padding:0;font-size:12px}.drivers li strong{font-size:11px}.drivers li span{font-weight:700}.driver-track{margin-top:7px;height:7px;background:#f0eeea;border-radius:10px;overflow:hidden}.driver-track i{display:block;height:100%;border-radius:10px;background:#a9875b}.driver-track .positive{background:#bd2a20}.driver-track .negative{background:#2454cc}.divisions .table-scroll{margin-top:18px;max-height:350px;overflow:auto}.divisions th,.divisions td{font-size:11px;padding:10px 12px}.divisions tbody th{background:transparent}.trend .card-head{align-items:flex-start}.trend-legend{display:flex;gap:13px;justify-content:flex-end;margin:8px 0 20px;font-size:10px;color:#81858e}.trend-legend span{display:flex;align-items:center;gap:5px}.trend-legend i{width:9px;height:9px;background:#cbced6;border-radius:1px}.trend-legend .estimate{background:white;border:1px dashed #626777}.trend-legend .plan{height:2px;background:#b58c56;width:15px}.trend-plot{height:180px;position:relative;display:grid;grid-template-columns:repeat(12,minmax(0,1fr));margin:0 0 14px}.trend-column{position:relative}.zero-line{position:absolute;width:100%;border-top:1px solid #e6e2d9}.trend-bar{position:absolute;left:27%;width:46%;background:#cbced6;border-radius:4px 4px 0 0;min-height:1px}.trend-bar.selected{background:#14171e}.trend-bar.estimate{background:white;border:1px dashed #626777}.plan-line{position:absolute;inset:0;width:100%;height:100%;overflow:visible;z-index:2;pointer-events:none}.plan-point{position:absolute;left:calc(50% - 3px);transform:translateY(-50%);z-index:3}.trend-labels{display:grid;grid-template-columns:repeat(12,minmax(0,1fr))}.trend-labels button{background:none;font-size:10px;color:#9ea2a9}.trend-labels button.selected{font-weight:700;color:#14171e}.data-basis{font-size:12px;padding:4px 0 12px;color:#777}.data-basis summary{font-weight:500}.data-basis p{margin:12px 0;line-height:1.8}.data-basis a{margin-left:10px}
.version-cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-top:18px}.version-card{border:1px solid #e7e4de;border-radius:10px;padding:16px;background:#fcfbf9}.version-card>div:first-child{display:flex;align-items:center;flex-wrap:wrap;gap:7px;justify-content:space-between}.version-kind{font-size:10px;padding:4px 7px;background:#eee8dd;color:#83653e;border-radius:5px}.version-card time{color:#9a9690;font-size:10px}.version-title{display:flex;align-items:center;justify-content:space-between;font-size:14px;font-weight:600;margin-top:15px}.version-title span{font-weight:400;color:#9a9690}.version-card p{margin-top:8px;line-height:1.6;font-size:11px;color:#81858e}.version-links{display:flex;gap:12px;margin-top:14px;font-size:11px;color:#83653e}@media(max-width:760px){.version-cards{grid-template-columns:1fr}.recent-versions .card-head{flex-wrap:wrap}}
@media(max-width:1200px){.closing{grid-template-columns:1fr;gap:20px}.home-divider{display:none}.closing-left{border-bottom:1px solid #e7e4de;padding-bottom:20px}.closing-stages{max-width:500px;margin-top:20px}.profit-grid{grid-template-columns:1fr}.drivers ol{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 24px}.home-heading{align-items:flex-start}.home-filters{max-width:430px}}
@media(max-width:760px){.home-heading{flex-direction:column}.home-filters{justify-content:flex-start;max-width:none}.home-kpis{grid-template-columns:1fr}.home-card{padding:18px}.kpi>p strong{font-size:30px}.todo-columns{gap:14px}.scope-toolbar{flex-wrap:wrap}.scope-toolbar>p{flex-basis:100%}.drivers ol{grid-template-columns:1fr}.profit-table{min-width:650px}.trend .card-head{flex-wrap:wrap}.closing-stages strong{font-size:10px}}
</style>
