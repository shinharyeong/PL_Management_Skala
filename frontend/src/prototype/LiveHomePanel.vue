<script setup>
import copy from './ui-copy.json'
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { seoulPeriod } from './domain.js'
import { formatMoney } from '../lib/decimal-display.js'
import { compare } from './exact-money.js'
import PButton from 'primevue/button'
import PCard from 'primevue/card'
import PChart from 'primevue/chart'
import PColumn from 'primevue/column'
import PColumnGroup from 'primevue/columngroup'
import PDataTable from 'primevue/datatable'
import PRow from 'primevue/row'
import PTag from 'primevue/tag'
import './home.css'
import './home-live.css'

const emit = defineEmits(['navigate'])
const store = usePrototypeStore(), period = ref('MONTH'), tab = ref('MONTHLY')
const { year, month } = seoulPeriod()
const pnl = ref(null), closing = ref(null), todos = ref([]), drivers = ref(null), divisions = ref(null), waterfall = ref(null), loading = ref(false)
let generation = 0
onBeforeUnmount(() => generation++)
const money = value => formatMoney(value, 'HUNDRED_MILLION')
const pct = value => value == null ? '—' : value + '%'
const point = value => value == null ? '—' : value + '%p'
const direction = value => value == null ? 0 : compare(value, '0')
const tone = value => value == null ? '' : direction(value) < 0 ? 'hp-decrease' : direction(value) > 0 ? 'hp-increase' : 'muted'
const signedMoney = value => value == null ? '—' : (direction(value) > 0 ? '+' : '') + money(value)
const state = computed(() => ({ CONFIRMED: '확정', PROVISIONAL: '잠정', NONE: '데이터 없음' })[pnl.value?.dataStatus] || '조회 중')
const periodName = computed(() => period.value === 'YEAR' ? `${year}년` : `${month}월`)
const dateRange = computed(() => {
  const pad = value => String(value).padStart(2, '0')
  const last = new Date(Date.UTC(year, month, 0)).getUTCDate()
  return `${year}.${period.value === 'YEAR' ? '01' : pad(month)}.01 – ${year}.${period.value === 'YEAR' ? '12.31' : pad(month)+'.'+last}`
})
const deadline = computed(() => {
  if (!closing.value?.dueAt) return '기한 미설정'
  const date = new Date(closing.value.dueAt)
  return Number.isNaN(date.getTime()) ? '기한 미설정' : '기한 ' + new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date)
})
const activeStep = computed(() => closing.value?.steps.find(step => step.total > 0 && step.done < step.total)?.key)
function effect(kpi) {
  const sign = direction(kpi.vsPlanAmount)
  if (kpi.vsPlanAmount == null) return { label: '계획 대비 비교 데이터 없음', tone: 'muted' }
  if (!sign) return { label: '계획 대비 차이 없음', tone: 'muted' }
  if (kpi.key === 'COST') return { label: sign > 0 ? '비용 증가 · 이익 감소' : '비용 감소 · 이익 증가', tone: sign > 0 ? 'bad' : 'good' }
  return { label: `${kpi.key === 'REVENUE' ? '매출' : '이익'} ${sign > 0 ? '증가' : '감소'}${kpi.key === 'REVENUE' ? ` · 이익 ${sign > 0 ? '증가' : '감소'}` : ''}`, tone: sign > 0 ? 'good' : 'bad' }
}
watch([period, tab], async () => {
  const current = ++generation
  loading.value = true; pnl.value = null; drivers.value = null; divisions.value = null; waterfall.value = null; closing.value = null; todos.value = []
  try {
    const query = `year=${year}&month=${month}&period=${period.value}`
    const results = await Promise.all(['pnl', 'status', 'op-drivers', 'divisions', 'waterfall'].map(path => store.api('/api/v1/home/' + path + '?' + query)))
    const todo = await store.api(`/api/v1/home/todos?tab=${tab.value}&year=${year}&month=${month}`)
    if (current !== generation) return
    ;[pnl.value, closing.value, drivers.value, divisions.value, waterfall.value] = results
    todos.value = todo.items
  } catch (error) { if (current === generation) store.error = error.message }
  finally { if (current === generation) loading.value = false }
}, { immediate: true })
function go(todo) { emit('navigate', todo.key === 'REQUEST_NEEDED' ? (tab.value === 'ANNUAL' ? 'annual-request' : 'monthly-request') : (tab.value === 'ANNUAL' ? 'annual' : 'monthly')) }
const waterfallChart = computed(buildWaterfallChart)
const driverChart = computed(buildDriverChart)
const waterfallOptions = { maintainAspectRatio: false, plugins: { legend: { display: false }, tooltip: { callbacks: { label: waterfallTooltip } } }, scales: { y: { ticks: { callback: chartTick }, title: { display: true, text: '억원' } }, x: { grid: { display: false } } } }
const driverOptions = { maintainAspectRatio: false, indexAxis: 'y', plugins: { legend: { display: false }, tooltip: { callbacks: { label: driverTooltip } } }, scales: { x: { ticks: { callback: chartTick }, title: { display: true, text: '억원' } }, y: { grid: { display: false } } } }
const financialRows = computed(buildFinancialRows)

/** 손익 행 끝에 이익률 표시 행을 붙이고 원문 금액을 보존한다.
 * Args: 없음. Returns: 안정된 rowKey를 가진 표시 행 목록.
 * Raises: 없음. Side Effects: 없음. API 응답을 변경하거나 금액을 계산하지 않는다.
 */
function buildFinancialRows() {
  const rows = (pnl.value?.rows || []).map((row, index) => ({ ...row, rowKey: row.reportItemId || `${row.kind}:${index}` }))
  const margin = pnl.value?.opMargin
  if (margin) rows.push({ rowKey: 'op-margin', name: '영업이익률', level: 0, isMargin: true, actual: margin.actualRate, vsPlanAmount: margin.vsPlanPoint, vsPrevEstimateAmount: margin.vsPrevEstimatePoint })
  return rows
}

/** 손익 표의 계층·강조 표시를 PrimeVue 행에 적용한다.
 * Args: row 원문 금액과 표시 계층을 가진 행.
 * Returns: 기존 손익 스타일의 클래스 문자열.
 * Raises: 없음. Side Effects: 없음. 원 금액을 변환하지 않는다.
 */
function financialRowClass(row) {
  return [row.kind === 'OP' ? 'hp-profit-row' : '', row.level > 1 ? 'hp-subrow' : 'hp-emphasis-row'].filter(Boolean).join(' ')
}

/** 원문 금액을 유지하고 차트 좌표용 부동 막대 범위를 구성한다.
 * Args: 없음. Returns: PrimeVue Chart의 labels·datasets.
 * Raises: 없음. Side Effects: 없음. Number는 그래프 좌표에만 사용한다.
 */
function buildWaterfallChart() {
  const steps = waterfall.value?.steps || []
  let previous = 0
  const ranges = steps.map(step => {
    const value = Number(step.amount) || 0
    const end = step.kind === 'DEDUCTION' ? previous - Math.abs(value) : value
    const start = step.kind === 'DEDUCTION' ? previous : 0
    previous = end
    return [start, end]
  })
  return { labels: steps.map(step => step.name), datasets: [{ label: '손익 흐름', data: ranges, backgroundColor: steps.map(step => step.kind === 'DEDUCTION' ? '#b42336' : step.key === 'OP' ? '#0d7255' : '#245fdb'), borderRadius: 4 }] }
}

/** 손익 변동 원인의 좌표와 색상만 차트용으로 구성한다.
 * Args: 없음. Returns: 조직 이름과 차액 좌표를 가진 Chart 데이터.
 * Raises: 없음. Side Effects: 없음. 툴팁·본문은 서버 금액 문자열을 별도로 표시한다.
 */
function buildDriverChart() {
  const items = drivers.value?.items || []
  return { labels: items.map(item => item.organization.name), datasets: [{ label: '영업이익 계획 대비', data: items.map(item => Number(item.opDiff) || 0), backgroundColor: items.map(item => direction(item.opDiff) < 0 ? '#245fdb' : '#c73535'), borderRadius: 4 }] }
}

/** 차트 좌표 축의 눈금을 억원 단위로 표시한다.
 * Args: value Chart.js가 계산한 축 눈금.
 * Returns: 최대 소수 6자리의 억원 좌표 눈금. Raises: 없음. Side Effects: 없음. 원 데이터 표시에는 사용하지 않는다.
 */
function chartTick(value) {
  const coordinate = Number(value) / 100000000
  return Number.isFinite(coordinate) ? new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 6 }).format(coordinate) : '—'
}

/** 손익 차트 툴팁은 좌표 근삿값 대신 서버의 원문 금액을 표시한다.
 * Args: context Chart.js 데이터 인덱스.
 * Returns: 정확한 금액 문자열. Raises: 없음. Side Effects: 없음.
 */
function waterfallTooltip(context) {
  const item = waterfall.value?.steps?.[context.dataIndex]
  return item ? `${item.kind === 'DEDUCTION' ? '−' : ''}${money(item.amount)} 억원` : '데이터 없음'
}

/** 원인 차트 툴팁은 서버의 차액 문자열을 사용한다.
 * Args: context Chart.js 데이터 인덱스.
 * Returns: 부호가 포함된 정확한 차액. Raises: 없음. Side Effects: 없음.
 */
function driverTooltip(context) { return `${signedMoney(drivers.value?.items?.[context.dataIndex]?.opDiff)} 억원` }
</script>

<template>
  <div class="home-panel home-live">
    <header class="hp-header"><h1>{{ copy.pages.home }}</h1></header>
    <div class="hp-period-bar">
      <div class="hp-period-switch" aria-label="손익 조회 기간">
        <PButton type="button" :severity="period === 'MONTH' ? undefined : 'secondary'" :outlined="period !== 'MONTH'" :class="{ active: period === 'MONTH' }" :aria-pressed="period === 'MONTH'" @click="period = 'MONTH'">{{ month }}월 · 이번 달</PButton>
        <PButton type="button" :severity="period === 'YEAR' ? undefined : 'secondary'" :outlined="period !== 'YEAR'" :class="{ active: period === 'YEAR' }" :aria-pressed="period === 'YEAR'" @click="period = 'YEAR'">{{ year }}년 · 올해</PButton>
      </div>
      <span class="hp-caption muted">{{ dateRange }} · {{ state }} · 단위 억원{{ period === 'YEAR' ? ' · 연간 전망 (실적 + 추정)'  : '' }}</span>
    </div>
    <p v-if="loading" class="muted hp-caption" role="status">손익 데이터를 불러오는 중…</p>
    <p v-else-if="pnl?.dataStatus === 'NONE'" class="notice">선택한 기간에 조회 가능한 승인 데이터가 없습니다.</p>

    <div class="hp-kpis">
      <PCard v-for="kpi in pnl?.kpis" :key="kpi.key" class="card hp-kpi"><template #content>
        <p class="hp-kpi-label">{{ { REVENUE: '매출', COST: '영업비용', OP: '영업이익' }[kpi.key] }}<span>억원</span></p>
        <strong>{{ money(kpi.actual) }}</strong>
        <p class="hp-caption" :class="tone(kpi.vsPlanAmount)">계획 대비 {{ signedMoney(kpi.vsPlanAmount) }} ({{ pct(kpi.vsPlanRate) }})</p>
        <p class="hp-caption" :class="'hp-' + effect(kpi).tone">{{ effect(kpi).label }}</p>
      </template></PCard>
    </div>

    <div class="hp-top-grid">
      <PCard class="card hp-close-card" aria-labelledby="close-title"><template #content>
        <div class="hp-section-heading"><h2 id="close-title">{{ month }}월 마감 진행</h2><PTag class="badge" severity="secondary" :value="deadline" /></div>
        <ol class="hp-close-steps"><li v-for="(step, index) in closing?.steps" :key="step.key" :class="{ active: step.key === activeStep }"><span>{{ index + 1 }} {{ step.label }}</span><b>{{ step.done }} / {{ step.total }}</b></li></ol>
        <p class="hp-caption muted">{{ closing?.dDay == null ? '마감 기한 미등록' : closing.dDay < 0 ? '마감 기한 ' + Math.abs(closing.dDay) + '일 경과' : '마감까지 ' + closing.dDay + '일' }} · {{ closing?.dataStatus === 'CONFIRMED' ? 'master 확정' : '조직별 진행 현황 기준' }}</p>
        <p class="hp-caption hp-good">소명 승인 후에도 master 숫자는 자동 변경되지 않습니다.</p>
      </template></PCard>
      <PCard class="card hp-todos" aria-labelledby="todo-title"><template #content>
        <div class="hp-section-heading"><h2 id="todo-title">오늘 할 일</h2><div class="hp-tabs" aria-label="할 일 구분"><PButton type="button" :severity="tab === 'ANNUAL' ? undefined : 'secondary'" :outlined="tab !== 'ANNUAL'" :class="{ active: tab === 'ANNUAL' }" :aria-pressed="tab === 'ANNUAL'" @click="tab = 'ANNUAL'">연간</PButton><PButton type="button" :severity="tab === 'MONTHLY' ? undefined : 'secondary'" :outlined="tab !== 'MONTHLY'" :class="{ active: tab === 'MONTHLY' }" :aria-pressed="tab === 'MONTHLY'" @click="tab = 'MONTHLY'">월간</PButton></div></div>
        <div v-for="todo in todos" :key="todo.key" class="hp-todo-row"><span :class="{ 'hp-review': ['REVIEW_NEEDED', 'EXPLANATION_REVIEW_NEEDED'].includes(todo.key) }">{{ todo.label }}</span><span>{{ todo.count }}{{ todo.key === 'REQUEST_NEEDED' ? '건' : '곳' }}</span><PButton class="btn" type="button" @click="go(todo)">{{ todo.key === 'REQUEST_NEEDED' ? '요청하러 가기' : '확인하러 가기' }}</PButton></div>
        <div class="hp-todo-row"><span>보고서 작성</span><span>—건</span><PButton class="btn" type="button" @click="emit('navigate', 'reports')">확인하러 가기</PButton></div>
      </template></PCard>
    </div>

    <section class="hp-summary" aria-labelledby="summary-title">
      <div class="hp-section-heading"><h2 id="summary-title">{{ periodName }} 전사 요약 손익</h2><span class="hp-caption muted">{{ state }} · 단위 억원 · 이익률 %</span></div>
      <div class="hp-table-scroll" tabindex="0" role="region" aria-labelledby="summary-title">
        <PDataTable :value="financialRows" dataKey="rowKey" tableClass="hp-financial-table" :rowClass="financialRowClass">
          <PColumnGroup type="header"><PRow><PColumn header="손익 항목" :rowspan="2" /><PColumn :header="period === 'YEAR' ? '연간 전망 (실적 + 추정)' : month + '월 실적'" /><PColumn header="계획 대비" :colspan="2" /><PColumn header="전월 추정 대비" :colspan="2" /></PRow><PRow><PColumn header="금액" /><PColumn header="차액" /><PColumn header="차이율" /><PColumn header="차액" /><PColumn header="차이율" /></PRow></PColumnGroup>
          <PColumn field="name" header="손익 항목"><template #body="{ data }"><span :style="{ paddingLeft: (data.level || 0) * 15 + 'px' }">{{ data.name }}</span></template></PColumn>
          <PColumn field="actual" header="금액"><template #body="{ data }">{{ data.isMargin ? pct(data.actual) : money(data.actual) }}</template></PColumn>
          <PColumn header="계획 대비 차액"><template #body="{ data }"><span :class="data.isMargin ? '' : tone(data.vsPlanAmount)">{{ data.isMargin ? point(data.vsPlanAmount) : signedMoney(data.vsPlanAmount) }}</span></template></PColumn>
          <PColumn header="계획 대비 차이율"><template #body="{ data }"><span :class="data.isMargin ? '' : tone(data.vsPlanAmount)">{{ data.isMargin ? '—' : pct(data.vsPlanRate) }}</span></template></PColumn>
          <PColumn header="전월 추정 대비 차액"><template #body="{ data }"><span :class="data.isMargin ? '' : tone(data.vsPrevEstimateAmount)">{{ data.isMargin ? point(data.vsPrevEstimateAmount) : signedMoney(data.vsPrevEstimateAmount) }}</span></template></PColumn>
          <PColumn header="전월 추정 대비 차이율"><template #body="{ data }"><span :class="data.isMargin ? '' : tone(data.vsPrevEstimateAmount)">{{ data.isMargin ? '—' : pct(data.vsPrevEstimateRate) }}</span></template></PColumn>
          <template #empty>조회 가능한 손익 항목이 없습니다.</template>
        </PDataTable>
      </div>
    </section>

    <PCard class="card hp-waterfall" aria-labelledby="waterfall-title"><template #content>
      <div class="hp-section-heading"><h2 id="waterfall-title">손익 흐름</h2><span class="hp-caption muted">{{ periodName }} · 단위 억원</span></div>
      <PChart v-if="waterfall?.steps?.length" class="hp-prime-chart" type="bar" :data="waterfallChart" :options="waterfallOptions" :canvasProps="{role:'img','aria-label':'매출에서 영업비용을 차감한 영업이익 흐름. 정확한 금액은 아래 목록을 확인하세요.'}" />
      <ul class="hp-chart-values"><li v-for="(item,index) in waterfall?.steps || []" :key="index"><span>{{item.name}}</span><strong>{{item.kind==='DEDUCTION'?'−':''}}{{money(item.amount)}} 억원</strong></li></ul>
      <div class="hp-waterfall-legend"><span><i class="hp-legend-total"></i>매출</span><span><i class="hp-legend-profit"></i>영업이익</span><span><i class="hp-legend-cost"></i>비용 차감 (−)</span></div>
      <p v-if="!waterfall?.steps?.length" class="muted hp-caption">조회 가능한 손익 흐름이 없습니다.</p>
    </template></PCard>

    <div class="hp-bottom-grid">
      <PCard class="card hp-causes" aria-labelledby="causes-title"><template #content>
        <div class="hp-section-heading"><h2 id="causes-title">손익 변동 원인 Top 5</h2><span class="hp-caption muted">{{ periodName }} · 억원</span></div>
        <h3 :class="tone(drivers?.total)">영업이익 계획 대비 {{ signedMoney(drivers?.total) }} 억원</h3>
        <PChart v-if="drivers?.items?.length" class="hp-prime-chart" type="bar" :data="driverChart" :options="driverOptions" :canvasProps="{role:'img','aria-label':'사업부별 영업이익 계획 대비 차액. 정확한 금액은 아래 목록을 확인하세요.'}" />
        <div v-for="(item,index) in drivers?.items" :key="item.organization.id" class="hp-cause-heading"><span>{{index+1}} {{item.organization.name}}</span><strong :class="tone(item.opDiff)">{{signedMoney(item.opDiff)}}</strong><span class="hp-caption" :class="direction(item.opDiff)>=0?'hp-good':'hp-bad'">{{direction(item.opDiff)>0?'이익 증가':direction(item.opDiff)<0?'이익 감소':'차이 없음'}}</span></div>
        <p v-if="!drivers?.items?.length && !loading" class="hp-caption muted">조회 가능한 손익 변동 원인이 없습니다.</p>
      </template></PCard>
      <PCard class="card hp-businesses" aria-labelledby="business-title"><template #content>
        <div class="hp-section-heading"><h2 id="business-title">사업부별 손익</h2><span class="hp-caption muted">{{ periodName }} · 억원</span></div>
        <div class="hp-table-scroll" tabindex="0" role="region" aria-labelledby="business-title">
          <PDataTable :value="divisions?.columns || []" dataKey="organization.id" tableClass="hp-business-table">
            <PColumn field="organization.name" header="사업부" /><PColumn header="매출"><template #body="{ data }">{{ money(data.revenue) }}</template></PColumn><PColumn header="영업이익"><template #body="{ data }">{{ money(data.op) }}</template></PColumn><PColumn header="기여도"><template #body="{ data }">{{ pct(data.opContributionRate) }}</template></PColumn><PColumn header="이익률"><template #body="{ data }">{{ pct(data.opMarginRate) }}</template></PColumn><PColumn header="계획 대비"><template #body="{ data }"><span :class="tone(data.vsPlanAmount)">{{ signedMoney(data.vsPlanAmount) }}</span></template></PColumn>
            <PColumnGroup v-if="divisions?.total" type="footer"><PRow><PColumn footer="전사 합계" /><PColumn :footer="money(divisions.total.revenue)" /><PColumn :footer="money(divisions.total.op)" /><PColumn footer="—" /><PColumn :footer="pct(divisions.total.opMarginRate)" /><PColumn :footer="signedMoney(divisions.total.vsPlanAmount)" :footerClass="tone(divisions.total.vsPlanAmount)" /></PRow></PColumnGroup>
            <template #empty>조회 가능한 사업부 손익이 없습니다.</template>
          </PDataTable>
        </div>
      </template></PCard>
    </div>
  </div>
</template>

<style scoped>
.home-live :deep(.p-card-body),.home-live :deep(.p-card-content){display:contents;padding:0}
.home-live :deep(.p-datatable){min-width:0;max-width:100%}
.home-live :deep(.p-datatable-table-container){overflow:auto}
.home-live :deep(.p-datatable-tbody>tr>td:first-child){text-align:left}
.home-live :deep(.p-datatable-column-header-content){justify-content:center}
.home-live :deep(.p-datatable-thead>tr>th:first-child .p-datatable-column-header-content){justify-content:flex-start}
.home-live :deep(.hp-profit-row>td){background:#edf3ff}
.home-live :deep(.hp-profit-row>td:first-child),.home-live :deep(.hp-emphasis-row>td:first-child){font-weight:600}
.home-live :deep(.hp-subrow>td:first-child){color:var(--muted,#667080);border-left:3px solid #e2e9f2}
.hp-close-card :deep(.p-card-content>.hp-caption){font-size:13px;line-height:1.7}
.hp-close-card :deep(.p-card-content>.hp-good){margin-top:auto;padding:13px 15px;background:#edf7f2;border:1px solid #d2e8dd;border-radius:8px;color:#246347}
.hp-todos :deep(.p-card-content>.hp-section-heading){padding-bottom:16px;border-bottom:1px solid var(--line);margin-bottom:3px}
.hp-causes :deep(.p-card-content>h3){font-size:15px;background:#fff4f2;padding:10px 12px;border-radius:8px}
.hp-prime-chart{height:300px;width:100%;min-width:0}
.hp-chart-values{display:flex;flex-wrap:wrap;gap:12px 24px;list-style:none;margin:0;padding:0}
.hp-chart-values li{display:flex;gap:8px;font-size:13px}
.hp-chart-values strong{font-variant-numeric:tabular-nums}
.home-live .hp-kpi:last-child{background:#193657;color:#fff;border-color:#193657}
</style>
