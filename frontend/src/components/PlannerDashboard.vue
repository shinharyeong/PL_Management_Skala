<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, query } from '../lib/display'
import { chartGeometry, formatMoney } from '../lib/decimal-display'
import MonthlyOverview from './MonthlyOverview.vue'

const session = useSession()
const viewMode = ref('PLAN')
const requests = ref([])
const selectedRequestId = ref('')
const requestDetail = ref(null)
const publication = ref(null)
const master = ref(null)
const organization = ref('')
const period = ref('year')
const month = ref(1)
const metric = ref('operatingProfit')
const unit = ref('KRW')
const loading = ref(false)
const listLoading = ref(false)
const error = ref('')
let listController = new AbortController()
let controller = new AbortController()
let listGeneration = 0
let generation = 0

/** 연간 요청 전체를 읽고 서울 현재 연도를 우선 선택한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(API 오류를 화면 표시).
 * Side Effects: 목록 GET·선택 상세 연결; 이전 선택이 있으면 유지하고 구 응답은 버린다.
 */
async function loadRequests() {
  listController.abort(); listController = new AbortController()
  controller.abort(); generation++
  const current = ++listGeneration
  listLoading.value = true; loading.value = false; error.value = ''
  requestDetail.value = null; publication.value = null; master.value = null
  try {
    const items = []
    let cursor = null
    do {
      const page = await session.request(`/api/v1/submission-requests${query({ type: 'ANNUAL_PLAN', limit: 100, cursor })}`, { signal: listController.signal })
      if (current !== listGeneration) return
      items.push(...page.items); cursor = page.nextCursor
    } while (cursor)
    items.sort(compareYears); requests.value = items
    let selected = null
    const currentYear = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Seoul', year: 'numeric' }).format(new Date())
    for (const item of items) if (String(item.id) === selectedRequestId.value) selected = item
    if (!selected) for (const item of items) if (String(item.year) === currentYear) selected = item
    selected ||= items[0]
    selectedRequestId.value = selected ? String(selected.id) : ''
    if (selected) await loadSelection()
  } catch (cause) { if (current === listGeneration && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === listGeneration) listLoading.value = false }
}

/** 요청을 최신 연도부터 정렬한다.
 * Args: left/right 요청 DTO. Returns: 순서. Raises: 없음. Side Effects: 없음; 금액을 다루지 않는다.
 */
function compareYears(left, right) { return right.year - left.year }

/** 선택 연도의 수집 상태와 최신 발행본을 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(API 오류를 화면 표시).
 * Side Effects: 이전 상세 취소·구 금액 제거·GET; 뒤늦은 응답은 버린다.
 */
async function loadSelection() {
  controller.abort(); controller = new AbortController()
  const current = ++generation
  loading.value = true; error.value = ''; organization.value = ''
  requestDetail.value = null; publication.value = null; master.value = null
  try {
    const base = `/api/v1/submission-requests/${selectedRequestId.value}`
    const [detail, status] = await Promise.all([session.request(base, { signal: controller.signal }), session.request(`${base}/annual-master`, { signal: controller.signal })])
    if (current !== generation) return
    requestDetail.value = detail; publication.value = status
    if (status.publicationState === 'PUBLISHED') {
      if (!status.latestMasterId) throw new Error('발행된 마스터 식별자를 확인하지 못했습니다.')
      const result = await session.request(`/api/v1/annual-masters/${status.latestMasterId}`, { signal: controller.signal })
      if (current !== generation) return
      if (!Array.isArray(result.organizations) || result.organizations.length !== result.sources.length) throw new Error('마스터의 조직별 합계를 확인하지 못했습니다.')
      master.value = result
    }
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}

/** 조직 선택을 모든 금액에 적용할 전사·조직 DTO를 찾는다.
 * Args: 없음. Returns: 선택 DTO/null. Raises: 없음. Side Effects: 없음.
 */
const selectedEntity = computed(function selectEntity() {
  if (!master.value || !organization.value) return master.value
  for (const item of master.value.organizations) if (String(item.organizationId) === organization.value) return item
  return null
})

/** 선택 기간에 대응하는 서버 합계를 반환한다.
 * Args: entity 전사/조직 DTO. Returns: 연간 또는 월 합계/null. Raises: 없음. Side Effects: 없음.
 */
function periodValues(entity) {
  if (!entity) return null
  if (period.value === 'year') return entity.totals
  for (const item of entity.months) if (item.month === month.value) return item
  return null
}

/** KPI와 요약에 동일한 선택 합계를 제공한다.
 * Args: 없음. Returns: 선택 합계/null. Raises: 없음. Side Effects: 없음.
 */
const selectedValues = computed(function selectValues() { return periodValues(selectedEntity.value) })

/** 현재 조직·연간/월간 계획 범위를 공통 표시한다.
 * Args: 없음. Returns: 설명 문자열. Raises: 없음. Side Effects: 없음.
 */
const selectionLabel = computed(function describeSelection() {
  return `${organization.value ? selectedEntity.value?.organizationName || '선택 조직' : '전사'} · ${master.value?.year || requestDetail.value?.year || ''}년 ${period.value === 'year' ? '연간' : `${month.value}월`} 계획`
})

/** 선택 금액의 매출·비용·영업이익 KPI를 구성한다.
 * Args: 없음. Returns: KPI 배열. Raises: 없음. Side Effects: 없음.
 */
const kpis = computed(function makeKpis() {
  return [{ label: '매출', amount: selectedValues.value?.revenue }, { label: '영업비용', amount: selectedValues.value?.cost }, { label: '영업이익', amount: selectedValues.value?.operatingProfit, margin: selectedValues.value?.operatingMargin }]
})

/** 조직과 기간 선택을 함께 적용한 사업부 표를 구성한다.
 * Args: 없음. Returns: 조직·기간 합계 배열. Raises: 없음. Side Effects: 없음.
 */
const visibleDepartments = computed(function selectDepartments() {
  const result = []
  for (const item of master.value?.organizations || []) if (!organization.value || String(item.organizationId) === organization.value) result.push({ ...item, values: periodValues(item) })
  return result
})

/** 최신 최종 제출 상태에서 실제 할 일 개수만 센다.
 * Args: 없음. Returns: 미제출·검토대기·반려 개수. Raises: 없음. Side Effects: 없음.
 */
const tasks = computed(function countTasks() {
  const counts = { missing: 0, pending: 0, rejected: 0 }
  for (const item of requestDetail.value?.targets || []) {
    if (item.latestStatus === 'PENDING') counts.pending++
    else if (item.latestStatus === 'REJECTED') counts.rejected++
    else if (item.latestStatus !== 'APPROVED') counts.missing++
  }
  return counts
})

/** 수집·승인·발행 단계 표시를 현재 요청으로 구성한다.
 * Args: 없음. Returns: 단계 배열. Raises: 없음. Side Effects: 없음.
 */
const stages = computed(function makeStages() {
  const detail = requestDetail.value
  if (!detail || !publication.value) return []
  return [
    { label: '제출 요청', value: dateTime(detail.requestedAt), complete: true },
    { label: '조직 제출', value: `${detail.submittedCount}/${detail.targetCount}곳`, complete: detail.submittedCount === detail.targetCount },
    { label: '조직 승인', value: `${publication.value.approvedOrganizationCount}/${publication.value.requiredOrganizationCount}곳`, complete: publication.value.approvedOrganizationCount === publication.value.requiredOrganizationCount },
    { label: '마스터 발행', value: publication.value.publicationState === 'PUBLISHED' ? '발행 완료' : '첫 발행 대기', complete: publication.value.publicationState === 'PUBLISHED' },
  ]
})

/** 선택 범위의 12개월 계획값으로 부호를 보존한 차트 좌표를 만든다.
 * Args: 없음. Returns: 0선·월별 금액/좌표. Raises: 없음. Side Effects: 없음; 금액 Number 변환 없음.
 */
const trend = computed(function makeTrend() {
  const months = selectedEntity.value?.months || []
  const values = []
  for (const item of months) values.push(item[metric.value])
  const geometry = chartGeometry(values)
  const bars = []
  for (let index = 0; index < months.length; index++) bars.push({ month: months[index].month, amount: values[index], top: Math.min(geometry.positions[index], geometry.zero), height: Math.abs(geometry.positions[index] - geometry.zero), negative: geometry.positions[index] > geometry.zero })
  return { zero: geometry.zero, bars }
})

/** 매출에서 비용을 차감해 영업이익으로 이어지는 세 단계 좌표를 만든다.
 * Args: 없음. Returns: 0선·단계 배열. Raises: 없음. Side Effects: 없음; 합계는 서버값을 사용한다.
 */
const waterfall = computed(function makeWaterfall() {
  const values = selectedValues.value
  if (!values) return { zero: 50, bars: [] }
  const geometry = chartGeometry([values.revenue, values.operatingProfit])
  const points = [[geometry.zero, geometry.positions[0]], [geometry.positions[0], geometry.positions[1]], [geometry.zero, geometry.positions[1]]]
  const labels = ['매출', '영업비용 차감', '영업이익']
  const amounts = [values.revenue, values.cost, values.operatingProfit]
  const bars = []
  for (let index = 0; index < points.length; index++) bars.push({ label: labels[index], amount: amounts[index], top: Math.min(...points[index]), height: Math.abs(points[index][0] - points[index][1]), kind: index === 1 ? 'deduction' : 'profit' })
  return { zero: geometry.zero, bars }
})

/** 월 선택을 KPI·요약·사업부표·차트 강조에 함께 반영한다.
 * Args: value 월 번호. Returns: 없음. Raises: 없음. Side Effects: 월·기간 변경.
 */
function chooseMonth(value) { month.value = value; period.value = 'month' }

/** 조직·기간·단위를 최초 연간 전사 기준으로 되돌린다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 선택 초기화, 서버 변경 없음.
 */
function resetSelection() { organization.value = ''; period.value = 'year'; month.value = 1; metric.value = 'operatingProfit'; unit.value = 'KRW' }

/** 선택 단위로 정확한 금액을 표시한다.
 * Args: value 서버 십진 금액. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function money(value) { return formatMoney(value, unit.value) }

/** 서버 비율을 표시하고 분모가 0인 경우는 비워 둔다.
 * Args: value 비율 문자열/null. Returns: % 문자열/—. Raises: 없음. Side Effects: 없음.
 */
function percent(value) { return value === null || value === undefined ? '—' : `${value}%` }

/** 고정 조직 승인본의 적재·원본으로 이동할 경로를 만든다.
 * Args: item 조직 DTO. Returns: 라우트. Raises: 없음. Side Effects: 없음.
 */
function sourceLink(item) { return { path: `/submissions/${item.submissionId}`, query: { from: 'annual-master', masterId: master.value.id, requestId: master.value.requestId, organizationId: item.organizationId } } }

/** 미승인 조직의 서버 상태를 화면에 표시한다.
 * Args: status 서버 상태. Returns: 표시 이름. Raises: 없음. Side Effects: 없음.
 */
function statusLabel(status) { return ({ PENDING: '검토 대기', REJECTED: '반려', NOT_SUBMITTED: '미제출', DRAFT: '초안' })[status] || status }

/** 이탈 시 모든 조회와 늦은 응답을 무효화한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 무효화.
 */
function dispose() { listGeneration++; generation++; listController.abort(); controller.abort() }

onMounted(loadRequests)
onBeforeUnmount(dispose)
</script>
<template>
  <div class="planner-dashboard">
    <header class="dashboard-heading">
      <div><p class="dashboard-eyebrow">경영기획팀 · 승인된 손익</p><h1>전사 손익 현황</h1><p class="data-caption">{{ viewMode === 'ACTUAL' ? '월간 ACTUAL · 확정 연간 계획과 비교' : master ? `${master.versionLabel} · ${dateTime(master.createdAt)} 발행` : '연간 계획 마스터 기준' }}</p></div>
      <div class="dashboard-filters">
        <label class="filter-control"><span>연도</span><select v-model="selectedRequestId" :disabled="listLoading || !requests.length" @change="loadSelection"><option v-for="item in requests" :key="item.id" :value="String(item.id)">{{ item.year }}년</option></select></label>
        <label v-if="viewMode === 'PLAN'" class="filter-control"><span>범위</span><select v-model="organization" :disabled="!master"><option value="">전사</option><option v-for="item in master?.organizations || []" :key="item.organizationId" :value="String(item.organizationId)">{{ item.organizationName }} · {{ item.organizationCode }}</option></select></label>
        <label v-if="viewMode === 'PLAN'" class="filter-control"><span>월</span><select v-model.number="month" :disabled="!master" @change="period = 'month'"><option v-for="value in 12" :key="value" :value="value">{{ value }}월</option></select></label>
        <button class="refresh-button" :disabled="loading || listLoading" @click="loadRequests">새로고침</button>
        <RouterLink v-if="master && viewMode === 'PLAN'" :to="`/planning/annual-masters/${master.id}`" class="report-button">마스터 확인 →</RouterLink>
      </div>
    </header>
    <div class="period-buttons" role="group" aria-label="계획·실적 보기"><button :class="{ selected: viewMode === 'PLAN' }" :aria-pressed="viewMode === 'PLAN'" @click="viewMode = 'PLAN'">연간 계획 PLAN</button><button :class="{ selected: viewMode === 'ACTUAL' }" :aria-pressed="viewMode === 'ACTUAL'" @click="viewMode = 'ACTUAL'">월간 실적 ACTUAL</button></div>
    <MonthlyOverview v-if="viewMode === 'ACTUAL' && requestDetail && !listLoading && !loading" :key="requestDetail.year" :year="requestDetail.year" />
    <template v-if="viewMode === 'PLAN' || !requestDetail">
    <div v-if="error" class="alert error" role="alert">{{ error }} <button class="text-button" :disabled="loading || listLoading" @click="loadRequests">다시 조회</button></div>
    <p v-if="loading || listLoading" class="dashboard-card empty" role="status">연간 계획과 마스터를 불러오고 있습니다…</p>
    <div v-else-if="!requests.length && !error" class="dashboard-card empty"><p>아직 연간 계획 요청이 없습니다.</p><RouterLink to="/planning/annual-plan" class="text-button">연간 계획 요청 작성 →</RouterLink></div>

    <section v-if="requestDetail && publication && !loading && !listLoading" class="dashboard-card closing-card" aria-label="연간 계획 수집 및 검토 현황" data-figma-node="2:685">
      <div class="closing-status"><div class="card-heading"><h2>{{ requestDetail.year }}년 계획 수집 현황</h2><span class="deadline-pill">{{ requestDetail.status === 'OPEN' ? '수집 중' : '요청 마감' }}</span></div><p class="card-description">{{ requestDetail.dueAt ? `제출 기한 ${dateTime(requestDetail.dueAt)}` : '제출 기한 미지정' }}</p>
        <ol class="closing-steps"><li v-for="stage in stages" :key="stage.label" :class="{ complete: stage.complete }"><RouterLink :to="{ path: '/planning/requests', query: { requestId: selectedRequestId } }"><span class="stage-circle" aria-hidden="true">{{ stage.complete ? '✓' : '' }}</span><strong>{{ stage.label }}</strong><small>{{ stage.value }}</small></RouterLink></li></ol>
        <progress class="closing-progress" :value="publication.approvedOrganizationCount" :max="publication.requiredOrganizationCount || 1" aria-label="연간 계획 승인 조직 수"></progress>
      </div>
      <div class="closing-divider" aria-hidden="true"></div>
      <div class="todo-section"><h2>확인할 일</h2><div class="todo-columns"><section><h3>조직 제출·검토</h3><ul><li><span class="task-dot"></span><span class="task-label">미제출 조직</span><strong>{{ tasks.missing }}곳</strong></li><li><span class="task-dot"></span><span class="task-label">검토 대기</span><strong>{{ tasks.pending }}곳</strong></li><li><span class="task-dot"></span><span class="task-label">반려 후 재제출</span><strong>{{ tasks.rejected }}곳</strong></li></ul></section><section><h3>마스터·근거 확인</h3><p class="card-description">{{ publication.publicationState === 'PUBLISHED' ? '최신 발행본의 합계와 조직별 원천을 확인하세요.' : '모든 대상 조직의 승인 완료 시 첫 마스터를 자동 발행합니다.' }}</p><RouterLink :to="{ path: '/planning/requests', query: { requestId: selectedRequestId } }" class="task-link">요청·검토 열기 →</RouterLink></section></div></div>
    </section>

    <section v-if="publication?.publicationState === 'WAITING_INITIAL' && !loading && !listLoading && !error" class="dashboard-card awaiting-card" aria-labelledby="awaiting-title"><h2 id="awaiting-title">첫 마스터 발행 대기 · 승인 {{ publication.approvedOrganizationCount }}/{{ publication.requiredOrganizationCount }}곳</h2><p class="card-description">전 조직의 최종 제출·승인을 완료하면 마스터가 자동 발행됩니다.</p><ul><li v-for="item in publication.missingOrganizations" :key="item.id"><strong>{{ item.name }}</strong> <span>{{ item.code }} · {{ statusLabel(item.status) }}</span></li></ul></section>

    <template v-if="master && !loading && !listLoading && !error">
      <div class="period-toolbar"><p>{{ selectionLabel }} <span>PLAN · 단위 {{ unit === 'KRW' ? '원' : '억원' }}</span></p><div class="period-buttons" role="group" aria-label="손익 기간·단위 선택"><button :class="{ selected: period === 'year' }" :aria-pressed="period === 'year'" @click="period = 'year'">연간</button><button :class="{ selected: period === 'month' }" :aria-pressed="period === 'month'" @click="period = 'month'">{{ month }}월</button><select v-model="unit" aria-label="금액 표시 단위"><option value="KRW">원</option><option value="HUNDRED_MILLION">억원</option></select><button class="reset-selection" @click="resetSelection">선택 해제</button></div></div>
      <section class="kpi-grid" :aria-label="`${selectionLabel} 핵심 손익`" data-figma-node="167:242"><article v-for="kpi in kpis" :key="kpi.label" class="dashboard-card kpi-card"><div class="kpi-heading"><h2>{{ kpi.label }}</h2><span v-if="kpi.label === '영업이익'" class="margin-pill">이익률 {{ percent(kpi.margin) }}</span></div><p class="kpi-amount"><strong>{{ money(kpi.amount) }}</strong><span>{{ unit === 'KRW' ? '원' : '억원' }}</span></p><p class="kpi-caption">승인된 계획 · {{ period === 'year' ? '12개월 합계' : `${month}월` }}</p></article></section>
      <div class="summary-driver-grid">
        <section class="dashboard-card summary-card" aria-labelledby="summary-title" data-figma-node="168:2031"><div class="card-heading"><div><h2 id="summary-title">손익 요약</h2><p class="card-description">{{ selectionLabel }}</p></div><span class="unit-label">단위 {{ unit === 'KRW' ? '원' : '억원' }}</span></div><div class="dashboard-table-scroll"><table class="summary-table"><caption class="visually-hidden">선택 범위의 승인 계획 손익</caption><thead><tr><th scope="col">손익 항목</th><th scope="col">계획 금액</th></tr></thead><tbody><tr v-for="kpi in kpis" :key="kpi.label" :class="{ total: kpi.label === '영업이익' }"><th scope="row">{{ kpi.label }}</th><td>{{ money(kpi.amount) }}</td></tr><tr><th scope="row">영업이익률</th><td>{{ percent(selectedValues?.operatingMargin) }}</td></tr></tbody></table></div><p class="card-description">영업이익 = 매출 − 영업비용. 매출이 0이면 이익률은 —로 표시합니다.</p></section>
        <section class="dashboard-card evidence-card" aria-labelledby="evidence-title" data-figma-node="167:372"><h2 id="evidence-title">집계 근거</h2><dl class="evidence-list"><div><dt>발행 버전</dt><dd>{{ master.versionLabel }}</dd></div><div><dt>발행 시각</dt><dd>{{ dateTime(master.createdAt) }}</dd></div><div><dt>포함 승인본</dt><dd>{{ organization ? '선택 조직 1곳' : `전 조직 ${master.sources.length}곳` }}</dd></div><div><dt>집계 방식</dt><dd>원본 상세 합계 기준</dd></div></dl><p class="evidence-note">엑셀 상단 소계의 수식 범위에 따라 표시값이 다를 수 있습니다. 원본 상세 전체를 반영한 승인본의 금액을 사용합니다.</p><p class="evidence-note">이 보기는 승인된 연간 PLAN입니다. 확정 계획 대비 실적은 월간 실적 ACTUAL 보기에서 확인할 수 있습니다. Contingency를 입력하는 연간 계획 확정은 별도입니다.</p><RouterLink v-if="organization && selectedEntity" :to="sourceLink(selectedEntity)" class="task-link">선택 조직 적재·원본 확인 →</RouterLink><RouterLink v-else :to="`/planning/annual-masters/${master.id}`" class="task-link">마스터 항목·원천 확인 →</RouterLink></section>
      </div>

      <section class="dashboard-card division-card" aria-labelledby="division-title" data-figma-node="167:439"><div class="card-heading"><div><h2 id="division-title">사업부별 계획 손익</h2><p class="card-description">{{ selectionLabel }} · 조직명을 누르면 해당 조직으로 전체 범위를 전환합니다.</p></div><span class="unit-label">단위 {{ unit === 'KRW' ? '원' : '억원' }}</span></div><div class="dashboard-table-scroll"><table class="division-table"><caption class="visually-hidden">선택 기간의 조직별 계획 금액과 승인 원천</caption><thead><tr><th scope="col">사업부</th><th scope="col">매출</th><th scope="col">영업비용</th><th scope="col">영업이익</th><th scope="col">전사 영업이익 기여도</th><th scope="col">이익률</th><th scope="col">승인 원천</th></tr></thead><tbody><tr v-for="item in visibleDepartments" :key="item.organizationId"><th scope="row"><button class="organization-name" @click="organization = String(item.organizationId)">{{ item.organizationName }}</button><small>{{ item.organizationCode }}</small></th><td>{{ money(item.values?.revenue) }}</td><td>{{ money(item.values?.cost) }}</td><td><strong>{{ money(item.values?.operatingProfit) }}</strong></td><td>{{ percent(item.values?.operatingProfitShare) }}</td><td>{{ percent(item.values?.operatingMargin) }}</td><td><RouterLink :to="sourceLink(item)" class="text-button">적재·원본 →</RouterLink></td></tr></tbody><tfoot><tr><th scope="row">{{ organization ? '선택 조직 합계' : '전사 합계' }}</th><td>{{ money(selectedValues?.revenue) }}</td><td>{{ money(selectedValues?.cost) }}</td><td>{{ money(selectedValues?.operatingProfit) }}</td><td>—</td><td>{{ percent(selectedValues?.operatingMargin) }}</td><td></td></tr></tfoot></table></div><p class="card-description">기여도는 같은 기간 전사 영업이익 대비 비율입니다. 전사 영업이익이 0이면 —로 표시합니다.</p></section>

      <section class="dashboard-card waterfall-card" aria-labelledby="waterfall-title" data-figma-node="167:318"><h2 id="waterfall-title">손익 흐름</h2><p class="card-description">{{ selectionLabel }} · 매출 − 영업비용 = 영업이익</p><div class="waterfall-scroll"><div class="waterfall-plot"><div class="chart-baseline" :style="{ top: `${waterfall.zero}%` }" aria-hidden="true"></div><div v-for="item in waterfall.bars" :key="item.label" :class="['waterfall-item', item.kind]"><span class="waterfall-bar" :style="{ top: `${item.top}%`, height: `${item.height}%` }" aria-hidden="true"></span></div></div><ol class="waterfall-labels"><li v-for="item in waterfall.bars" :key="item.label"><strong>{{ item.label }}</strong><span>{{ money(item.amount) }} {{ unit === 'KRW' ? '원' : '억원' }}</span></li></ol></div><ul class="chart-legend"><li><i class="profit-key"></i>매출·영업이익</li><li><i class="deduction-key"></i>비용 차감</li></ul></section>

      <section class="dashboard-card trend-card" aria-labelledby="trend-title" data-figma-node="168:2097"><div class="trend-heading"><div><h2 id="trend-title">월별 {{ metric === 'operatingProfit' ? '영업이익' : '매출' }} 계획 추이</h2><p class="card-description">{{ organization ? selectedEntity?.organizationName : '전사' }} · {{ master.year }}년 PLAN 12개월{{ period === 'month' ? ` · ${month}월 선택` : '' }}</p></div><div class="metric-toggle" role="group" aria-label="추이 항목"><button :class="{ active: metric === 'operatingProfit' }" :aria-pressed="metric === 'operatingProfit'" @click="metric = 'operatingProfit'">영업이익</button><button :class="{ active: metric === 'revenue' }" :aria-pressed="metric === 'revenue'" @click="metric = 'revenue'">매출</button></div></div><div class="chart-scroll"><div class="trend-plot"><div class="chart-baseline" :style="{ top: `${trend.zero}%` }" aria-hidden="true"></div><div v-for="bar in trend.bars" :key="bar.month" class="trend-month"><span :class="['trend-bar', { selected: period === 'month' && month === bar.month, negative: bar.negative }]" :style="{ top: `${bar.top}%`, height: `${bar.height}%` }" aria-hidden="true"></span></div></div><div class="trend-labels"><button v-for="bar in trend.bars" :key="bar.month" :class="{ selected: period === 'month' && month === bar.month }" :aria-pressed="period === 'month' && month === bar.month" :aria-label="`${bar.month}월 계획 ${money(bar.amount)} ${unit === 'KRW' ? '원' : '억원'} 선택`" @click="chooseMonth(bar.month)">{{ bar.month }}월</button></div></div><details class="trend-values"><summary>12개월 정확한 금액 보기</summary><div class="dashboard-table-scroll"><table class="division-table"><caption class="visually-hidden">선택 조직 범위의 월별 계획, 단위 {{ unit === 'KRW' ? '원' : '억원' }}</caption><thead><tr><th scope="col">월</th><th scope="col">매출</th><th scope="col">영업비용</th><th scope="col">영업이익</th><th scope="col">이익률</th></tr></thead><tbody><tr v-for="item in selectedEntity?.months || []" :key="item.month" :class="{ 'selected-month': period === 'month' && item.month === month }"><th scope="row">{{ item.month }}월</th><td>{{ money(item.revenue) }}</td><td>{{ money(item.cost) }}</td><td>{{ money(item.operatingProfit) }}</td><td>{{ percent(item.operatingMargin) }}</td></tr></tbody></table></div></details></section>
    </template>
    </template>
  </div>
</template>

<style scoped>
.planner-dashboard { display:flex; flex-direction:column; gap:20px; color:var(--ink); }.dashboard-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; margin-bottom:6px; }.dashboard-eyebrow { color:var(--muted); font-size:11px; margin-bottom:4px; }.data-caption { margin-top:8px; color:#837c70; font-size:11px; }.dashboard-filters { display:flex; flex-wrap:wrap; align-items:center; justify-content:flex-end; gap:8px; padding-top:8px; }.filter-control { display:flex; gap:7px; align-items:center; height:37px; padding:0 10px; border:1px solid var(--line); border-radius:8px; background:#fff; white-space:nowrap; font-size:12px; }.filter-control>span { color:#9ea2a9; }.filter-control select { width:auto; min-width:44px; max-width:150px; height:100%; min-height:0; margin:0; border:0; border-radius:0; color:var(--ink); padding:0 13px 0 0; font-size:12px; background:transparent; }.report-button,.refresh-button { display:flex; align-items:center; justify-content:center; min-height:37px; padding:0 14px; border-radius:8px; color:#fff; background:var(--sidebar); font-size:12px; }.refresh-button { background:#eeece6; color:var(--ink); }.dashboard-card { min-width:0; background:#fff; border:1px solid var(--line); border-radius:14px; }.card-heading,.kpi-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; }.card-description { margin-top:8px; color:#77746e; font-size:12px; line-height:1.7; }.unit-label { color:#77746e; font-size:11px; white-space:nowrap; }
.closing-card { display:grid; grid-template-columns:minmax(330px,.9fr) 1px minmax(420px,1.3fr); gap:24px; padding:22px 25px; }.closing-status { min-width:0; }.deadline-pill { padding:4px 10px; border-radius:20px; background:#f0eeea; color:#75552f; font-size:10px; white-space:nowrap; }.closing-steps { list-style:none; margin:26px 0 20px; padding:0; display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:9px; }.closing-steps a { display:flex; flex-direction:column; align-items:center; text-align:center; gap:8px; }.stage-circle { width:24px; height:24px; border:1px solid #ccc9c1; border-radius:50%; display:grid; place-items:center; color:#fff; font-size:13px; }.closing-steps strong { font-size:11px; font-weight:400; white-space:nowrap; }.closing-steps small { color:#77746e; font-size:10px; line-height:1.5; }.complete .stage-circle { border-color:var(--sidebar); background:var(--sidebar); }.closing-progress { width:100%; height:6px; border:0; border-radius:10px; overflow:hidden; appearance:none; background:#efeeea; }.closing-progress::-webkit-progress-bar { background:#efeeea; }.closing-progress::-webkit-progress-value { background:var(--gold); }.closing-progress::-moz-progress-bar { background:var(--gold); }.closing-divider { width:1px; background:var(--line); }.todo-columns { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:26px; margin-top:18px; }.todo-columns h3 { margin-bottom:15px; }.todo-columns ul { list-style:none; margin:0; padding:0; display:flex; flex-direction:column; gap:14px; }.todo-columns li { display:flex; align-items:center; gap:8px; min-height:27px; font-size:12px; }.task-dot { flex:none; width:5px; height:5px; border-radius:50%; background:#c6821c; }.task-label { flex:1; }.task-link { display:inline-block; margin-top:16px; color:var(--gold-ink); font-size:12px; }.task-link:hover { text-decoration:underline; }.awaiting-card { padding:24px; }.awaiting-card ul { display:flex; flex-wrap:wrap; gap:12px 24px; margin:20px 0 0; padding:0; list-style:none; font-size:12px; }.awaiting-card li span { color:var(--muted); }
.period-toolbar { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:12px; }.period-toolbar>p { color:var(--muted); font-size:12px; }.period-toolbar>p>span { margin-left:12px; color:#77746e; font-size:11px; }.period-buttons { display:flex; flex-wrap:wrap; align-items:center; gap:8px; }.period-buttons button,.period-buttons select { width:auto; min-width:65px; min-height:37px; margin:0; padding:8px 12px; border:1px solid var(--line); border-radius:8px; background:#fff; font-size:12px; }.period-buttons .selected { background:#f3b9b9; }.period-buttons .reset-selection { color:var(--muted); background:transparent; }.kpi-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:14px; }.kpi-card { padding:20px 22px; }.kpi-heading h2 { font-size:14px; color:#4a4844; }.margin-pill { padding:2px 9px; border-radius:20px; font-size:11px; background:#f0eeea; white-space:nowrap; }.kpi-amount { display:flex; flex-wrap:wrap; align-items:baseline; gap:4px; margin:14px 0; }.kpi-amount strong { font-size:clamp(18px,2.2vw,32px); letter-spacing:-.6px; line-height:1.4; font-variant-numeric:tabular-nums; overflow-wrap:anywhere; min-width:0; }.kpi-amount>span { font-size:12px; color:#6b6964; }.kpi-caption { padding-top:10px; border-top:1px solid #eeebe4; color:var(--muted); font-size:11px; }
.summary-driver-grid { display:grid; grid-template-columns:minmax(0,1.15fr) minmax(290px,1fr); gap:18px; align-items:start; }.division-card,.evidence-card,.waterfall-card { padding:22px 24px; }.summary-card,.trend-card { padding:20px; }.dashboard-table-scroll { width:100%; overflow-x:auto; }.division-table,.summary-table { width:100%; border-spacing:0; border-collapse:separate; margin-top:14px; border:1px solid var(--line); border-radius:10px; overflow:hidden; font-size:12px; }.division-table { min-width:850px; }.summary-table { min-width:340px; }.planner-dashboard table th,.planner-dashboard table td { border:0; border-bottom:1px solid var(--line); background:transparent; padding:12px 10px; text-align:right; white-space:nowrap; font-variant-numeric:tabular-nums; }.planner-dashboard table th:first-child { text-align:left; }.planner-dashboard table thead th { background:#f8f6f1; font-size:11px; color:#4a4844; font-weight:400; }.planner-dashboard table tbody th { color:var(--ink); font-weight:500; }.planner-dashboard table tfoot th,.planner-dashboard table tfoot td,.summary-table .total>* { background:#faf7f0; font-weight:700; }.planner-dashboard table tr:last-child>* { border-bottom:0; }.organization-name { padding:0; color:var(--ink); background:transparent; font-weight:600; }.organization-name:hover { text-decoration:underline; }.division-table th small { display:block; margin-top:4px; color:var(--muted); font-weight:400; }.evidence-list { margin:18px 0; display:grid; gap:12px; font-size:12px; }.evidence-list>div { display:flex; justify-content:space-between; align-items:baseline; gap:16px; }.evidence-list dt { color:var(--muted); }.evidence-list dd { margin:0; text-align:right; overflow-wrap:anywhere; }.evidence-note { margin-top:12px; color:#6b6964; font-size:12px; line-height:1.8; }
.chart-baseline { position:absolute; left:0; width:100%; height:1px; background:#ccc7bd; }.waterfall-scroll { overflow-x:auto; margin:24px 0 20px; }.waterfall-plot { display:grid; grid-template-columns:repeat(3,1fr); position:relative; min-width:540px; height:180px; margin:12px 0; }.waterfall-item { position:relative; height:100%; }.waterfall-bar { position:absolute; left:calc(50% - 38px); width:76px; min-height:1px; background:#17181c; border-radius:3px; }.deduction .waterfall-bar { background:#c9a57a; }.waterfall-labels { display:grid; grid-template-columns:repeat(3,1fr); min-width:540px; margin:18px 0 0; padding:0; list-style:none; text-align:center; gap:12px; }.waterfall-labels strong { display:block; font-size:12px; }.waterfall-labels span { display:block; margin-top:8px; font-size:12px; font-variant-numeric:tabular-nums; overflow-wrap:anywhere; }.chart-legend { list-style:none; margin:0; padding:0; display:flex; gap:16px; }.chart-legend li { display:flex; align-items:center; gap:5px; color:var(--muted); font-size:11px; }.chart-legend i { width:10px; height:10px; }.profit-key { background:#17181c; }.deduction-key { background:#c9a57a; }.trend-heading { display:flex; justify-content:space-between; align-items:center; gap:12px; }.metric-toggle { display:flex; flex-shrink:0; padding:3px; gap:2px; border-radius:8px; background:#efeeea; }.metric-toggle button { border-radius:6px; padding:6px 10px; background:transparent; color:#77746e; font-size:11px; }.metric-toggle .active { background:#fff; color:var(--ink); }.chart-scroll { overflow-x:auto; margin-top:30px; padding-top:8px; }.trend-plot { position:relative; display:grid; grid-template-columns:repeat(12,1fr); min-width:720px; height:170px; margin:0; }.trend-month { position:relative; height:100%; }.trend-bar { position:absolute; left:calc(50% - 13px); width:26px; min-height:1px; border-radius:3px; background:#c9ccd3; }.trend-bar.selected { background:var(--sidebar); }.trend-bar.negative { background:#a9875b; }.trend-bar.negative.selected { background:#75552f; }.trend-labels { display:grid; grid-template-columns:repeat(12,1fr); min-width:720px; margin:12px 0 0; }.trend-labels button { padding:6px 0; background:transparent; color:#77746e; font-size:11px; text-align:center; }.trend-labels .selected { color:var(--ink); font-weight:700; background:#f0eeea; border-radius:5px; }.trend-values { margin-top:20px; color:var(--muted); font-size:12px; }.trend-values summary { cursor:pointer; }.trend-values .division-table { min-width:700px; }.planner-dashboard table .selected-month>* { background:#faf7f0; }
@media(max-width:1300px) { .dashboard-heading { flex-wrap:wrap; }.dashboard-filters { padding-top:0; justify-content:flex-start; }.closing-card { grid-template-columns:minmax(0,1fr); gap:24px; }.closing-divider { display:none; }.closing-status { max-width:600px; }.todo-section { border-top:1px solid var(--line); padding-top:20px; }.summary-driver-grid { grid-template-columns:minmax(0,1fr) minmax(280px,.85fr); } }
@media(max-width:900px) { .summary-driver-grid { grid-template-columns:minmax(0,1fr); }.kpi-grid { grid-template-columns:minmax(0,1fr); }.kpi-amount strong { font-size:28px; } }
@media(max-width:760px) { .kpi-card,.closing-card { padding:20px; }.todo-columns { grid-template-columns:minmax(0,1fr); gap:24px; }.period-toolbar>p>span { display:block; margin:4px 0 0; }.division-card,.evidence-card,.summary-card,.trend-card,.waterfall-card { padding:18px; }.trend-heading { align-items:flex-start; flex-wrap:wrap; }.dashboard-filters { width:100%; }.filter-control select { max-width:130px; } }
</style>
