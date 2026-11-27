<script setup>
import copy from './copy-text.js'
import { computed, ref, watch, nextTick, onBeforeUnmount } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { seoulPeriod } from './domain.js'
import {homeMoney} from './home-money.js'
import { compare, subtract } from './exact-money.js'
import {layoutHomeWaterfall, waterfallStepDescription} from './home-waterfall-layout.js'
import {annualProgress} from './home-annual-progress.js'
import {closingStep} from './home-closing.js'
import {homeTodoItems,homeTodoDestination} from './home-todos.js'
import './home.css'
import './home-live.css'

const emit = defineEmits(['navigate'])
const store = usePrototypeStore(), period = ref('MONTH')
const { year, month } = seoulPeriod()
const pnl = ref(null), closing = ref(null), todos = ref([]), drivers = ref(null), divisions = ref(null), waterfall = ref(null), loading = ref(false)
const todosExpanded = ref(false), todoSlot = ref(null), todoMore = ref(null), todoClose = ref(null)
async function expandTodos() {
  todosExpanded.value = true
  await nextTick()
  todoClose.value?.focus()
}
async function collapseTodos(restoreFocus = true) {
  todosExpanded.value = false
  if (restoreFocus) { await nextTick(); todoMore.value?.focus() }
}
function outsideTodo(event) {
  if (!todoSlot.value?.contains(event.target)) collapseTodos(false)
}
function todoEscape(event) {
  if (event.key === 'Escape') { event.preventDefault(); collapseTodos() }
}
watch(todosExpanded, open => {
  document[open ? 'addEventListener' : 'removeEventListener']('pointerdown', outsideTodo)
  document[open ? 'addEventListener' : 'removeEventListener']('keydown', todoEscape)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', outsideTodo)
  document.removeEventListener('keydown', todoEscape)
})
const annualYear = ref(year)
const annualDetail = ref(null), annualMaster = ref(null), annualError = ref('')
let generation = 0
onBeforeUnmount(() => generation++)
const summaryExpanded = ref(false), summaryPanel = ref(null), summaryExpandButton = ref(null), summaryCloseButton = ref(null)
async function expandSummary() {
  summaryExpanded.value = true
  await nextTick()
  summaryCloseButton.value?.focus()
}
async function closeSummary() {
  summaryExpanded.value = false
  await nextTick()
  summaryExpandButton.value?.focus()
}
function summaryKeys(event) {
  if (!summaryExpanded.value) return
  if (event.key === 'Escape') { event.preventDefault(); closeSummary(); return }
  if (event.key === 'Tab') {
    const nodes = [...summaryPanel.value.querySelectorAll('button, [tabindex="0"]')]
    const first = nodes[0], last = nodes.at(-1)
    if (event.shiftKey && document.activeElement === first) {event.preventDefault();last?.focus()}
    else if (!event.shiftKey && document.activeElement === last) {event.preventDefault();first?.focus()}
  }
}
watch(summaryExpanded, open => document[open ? 'addEventListener' : 'removeEventListener']('keydown', summaryKeys))
onBeforeUnmount(() => document.removeEventListener('keydown', summaryKeys))
const summaryHead = ref(null), summaryHeadHeight = ref(54)
let summaryHeadObserver
watch(summaryHead, head => {
  summaryHeadObserver?.disconnect()
  if (!head) return
  summaryHeadObserver = new ResizeObserver(() => { summaryHeadHeight.value = head.getBoundingClientRect().height })
  summaryHeadObserver.observe(head)
})
onBeforeUnmount(() => summaryHeadObserver?.disconnect())
const money = homeMoney
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
const progressGroups = computed(() => [
  {key:'annual', title:`${annualYear.value}년 연간 계획 마감 진행`, dueAt:annualDetail.value?.request?.dueAt, steps:annualProgress(annualDetail.value,annualMaster.value), error:annualError.value},
  {key:'monthly', title:`${month}월 마감 진행`, dueAt:closing.value?.dueAt, steps:closing.value?.steps || []},
].filter(group => group.key === (period.value === 'YEAR' ? 'annual' : 'monthly')))

watch(period, async () => {
  collapseTodos(false)
  const current = ++generation
  annualError.value = ''; annualDetail.value = null; annualMaster.value = null
  loading.value = true; pnl.value = null; drivers.value = null; divisions.value = null; waterfall.value = null; closing.value = null; todos.value = []
  try {
    const query = `year=${year}&month=${month}&period=${period.value}`
    const results = await Promise.all(['pnl', 'status', 'op-drivers', 'divisions', 'waterfall'].map(path => store.api('/api/v1/home/' + path + '?' + query + (path === 'op-drivers' ? '&limit=3' : ''))))
    annualError.value = ''; annualDetail.value = null; annualMaster.value = null
    try {
      const requests = await store.pages('/api/v1/submission-requests?type=ANNUAL_PLAN')
      const request = requests.find(row => row.year === year)
      const targetYear = request?.year || year
      const [detail, master] = await Promise.all([
        request ? store.api('/api/v1/submission-requests/' + request.id) : Promise.resolve(null),
        store.api('/api/v1/annual-masters?year=' + targetYear),
      ])
      if (current !== generation) return
      annualYear.value = targetYear; annualDetail.value = detail; annualMaster.value = master
    } catch { if (current === generation) annualError.value = '연간 계획 현황을 불러오지 못했습니다.' }
    const [annual,monthly] = await Promise.all(['ANNUAL','MONTHLY'].map(scope=>store.api(`/api/v1/home/todos?tab=${scope}&year=${scope === 'ANNUAL' ? annualYear.value : year}&month=${month}`)))
    if (current !== generation) return
    ;[pnl.value, closing.value, drivers.value, divisions.value, waterfall.value] = results
    todos.value = homeTodoItems(annual.items,monthly.items)
  } catch (error) { if (current === generation) store.error = error.message }
  finally { if (current === generation) loading.value = false }
}, { immediate: true })
function go(todo) { collapseTodos(false); emit('navigate',homeTodoDestination(todo)) }
async function openRecentCell(cell) {
  if(await store.run(async()=>{
    await store.api('/api/v1/review-items/' + cell.id)
    const submission = cell.submissionId ? await store.api('/api/v1/submissions/' + cell.submissionId) : null
    store.activeOrg = cell.organizationId
    store.currentSubmission = submission
    store.recentCellTarget = cell
    const session = store.monthlySession(cell.organizationId)
    session.activeCell = cell.id; session.detailSource = 'recent'
  })) emit('navigate','cell')
}
// Compact chart coordinates include negative profit without colliding with category labels.
const waterfallPlot = computed(() => layoutHomeWaterfall(waterfall.value?.steps || []))
const bars = computed(() => waterfallPlot.value.bars)
const detailRows = computed(() => (pnl.value?.rows || []).filter(row => row.kind !== 'OP'))
const parentLevels = computed(() => new Map(detailRows.value.filter((row,index) => detailRows.value[index+1]?.level > row.level).map(row => [row.reportItemId,row.level])))
const profitRows = computed(() => (pnl.value?.rows || []).filter(row => row.kind === 'OP'))
const driverGroups = computed(() => [
  { key: 'decreases', label: '감소 Top 3', items: (drivers.value?.decreases || []).slice(0,3) },
  { key: 'increases', label: '증가 Top 3', items: (drivers.value?.increases || []).slice(0,3) },
])
const driverMax = computed(() => Math.max(1, ...driverGroups.value.flatMap(group => group.items).map(item => Math.abs(Number(item.opDiff) || 0))))
</script>

<template>
  <div class="home-panel home-live">
    <header class="hp-header"><h1><span data-copy-key="pages.home">{{ copy.pages.home }}</span></h1>

    <p v-if="loading" class="muted hp-caption hp-data-notice" role="status"><span data-copy-key="homeText.text004">{{ copy.homeText.text004 }}</span></p>
    <p v-else-if="pnl?.dataStatus === 'NONE'" class="notice hp-data-notice"><span data-copy-key="homeText.text005">{{ copy.homeText.text005 }}</span></p>

    </header>

    <div class="hp-kpi-top">
    <div class="hp-period-bar">
      <div class="hp-period-switch" aria-label="손익 조회 기간">
        <button type="button" :class="{ active: period === 'MONTH' }" :aria-pressed="period === 'MONTH'" @click="period = 'MONTH'">{{ month }}<span data-copy-key="homeText.text001">{{ copy.homeText.text001 }}</span></button>
        <button type="button" :class="{ active: period === 'YEAR' }" :aria-pressed="period === 'YEAR'" @click="period = 'YEAR'">{{ year }}<span data-copy-key="homeText.text002">{{ copy.homeText.text002 }}</span></button>
      </div>
    </div>
    <section class="card hp-work-summary" aria-label="계획·마감 진행과 할 일">
      <div class="hp-progress-stack">
      <section v-for="group in progressGroups" :key="group.key" class="hp-close-card" :class="'hp-progress-'+group.key" :aria-labelledby="'close-title-'+group.key">
        <div class="hp-section-heading"><h2 :id="'close-title-'+group.key">{{group.title}}</h2></div>
        <p v-if="group.error" class="hp-caption" role="status">{{group.error}}</p>
        <ol v-else class="hp-close-steps" :aria-label="group.title" :style="{gridTemplateColumns:`repeat(${group.steps.length || 5}, minmax(0, 1fr))`}"><li v-for="step in group.steps" :key="step.key" tabindex="0" :class="'hp-step-'+closingStep(step).state" :aria-label="step.label+' · '+closingStep(step).value+' · '+closingStep(step).label" :aria-describedby="'closing-detail-'+group.key+'-'+step.key">
          <span class="hp-step-dot" aria-hidden="true"><svg class="hp-step-ring" viewBox="0 0 36 36"><template v-if="closingStep(step).state==='complete'"><circle class="ring-done" cx="18" cy="18" r="16"/><path class="ring-check" d="m11.5 18.5 4.5 4.5 8.5-9"/></template><template v-else><circle class="ring-track" cx="18" cy="18" r="15"/><circle v-if="closingStep(step).state==='progress'" class="ring-fill" cx="18" cy="18" r="15" :stroke-dasharray="`${closingStep(step).percent*0.9425} 94.25`" transform="rotate(-90 18 18)"/><text v-if="closingStep(step).state==='progress'" class="ring-text" x="18" y="21.5" text-anchor="middle">{{ Math.round(closingStep(step).percent) }}%</text><circle v-else class="ring-idle" cx="18" cy="18" r="3"/></template></svg></span>
          <span class="hp-step-name">{{step.label}}</span>
          <span :id="'closing-detail-'+group.key+'-'+step.key" class="hp-step-tooltip" role="tooltip"><strong>{{step.label}}</strong><span>{{closingStep(step).value}} · {{closingStep(step).label}}</span></span>
        </li></ol>
      </section>
      </div>

      <div ref="todoSlot" class="hp-todo-slot">
        <section class="hp-todos" aria-labelledby="todo-title" :inert="todosExpanded" :style="todosExpanded ? { visibility: 'hidden' } : undefined">
          <div class="hp-section-heading"><h2 id="todo-title"><span data-copy-key="homeText.text010">{{ copy.homeText.text010 }}</span></h2><button v-if="todos.length > 2" ref="todoMore" type="button" class="hp-todo-more" aria-expanded="false" aria-controls="todo-expanded" @click="expandTodos">더보기 <span aria-hidden="true">↗</span></button></div>
          <div v-for="todo in todos.slice(0, 2)" :key="todo.scope+todo.key" class="hp-todo-row"><button type="button" class="hp-todo-text" :class="{ 'hp-review': ['REVIEW_NEEDED', 'EXPLANATION_REVIEW_NEEDED'].includes(todo.key) }" @click="go(todo)">{{ todo.label }}</button><span class="hp-todo-pending">{{ todo.count }}{{ todo.key === 'REQUEST_NEEDED' ? '건' : '곳' }}</span></div>
        </section>
        <section v-if="todosExpanded" id="todo-expanded" class="hp-todos hp-todos-overlay" role="dialog" aria-modal="false" aria-labelledby="todo-expanded-title">
          <div class="hp-section-heading"><h2 id="todo-expanded-title">{{ copy.homeText.text010 }}</h2><button ref="todoClose" type="button" class="hp-todo-more" @click="collapseTodos()">접기 <span aria-hidden="true">⌃</span></button></div>
          <div class="hp-todos-expanded-list">
            <div v-for="todo in todos" :key="todo.scope+todo.key" class="hp-todo-row"><button type="button" class="hp-todo-text" :class="{ 'hp-review': ['REVIEW_NEEDED', 'EXPLANATION_REVIEW_NEEDED'].includes(todo.key) }" @click="go(todo)">{{ todo.label }}</button><span class="hp-todo-pending">{{ todo.count }}{{ todo.key === 'REQUEST_NEEDED' ? '건' : '곳' }}</span></div>
          </div>
        </section>
      </div>
    </section>

    <div class="hp-kpis">
      <section v-for="kpi in pnl?.kpis" :key="kpi.key" class="card hp-kpi">
        <p class="hp-kpi-label">{{ { REVENUE: '매출', COST: '영업비용', OP: '영업이익' }[kpi.key] }}</p>
        <strong>{{ money(kpi.actual) }}<span class="hp-kpi-unit" data-copy-key="homeText.text006">{{ copy.homeText.text006 }}</span></strong>
        <p class="hp-caption" :class="tone(kpi.vsPlanAmount)"><span data-copy-key="homeText.text007">{{ copy.homeText.text007 }}</span> {{ signedMoney(kpi.vsPlanAmount) }} ({{ pct(kpi.vsPlanRate) }})</p>

      </section>
    </div>
    </div>

    <div class="hp-overview-grid">
      <div class="hp-main-column">
        <Teleport to="body" :disabled="!summaryExpanded"><div class="home-live hp-summary-portal" style="display:contents">
        <div v-if="summaryExpanded" class="hp-summary-backdrop" aria-hidden="true" @click="closeSummary"></div>
        <section ref="summaryPanel" class="hp-summary" :class="{'hp-summary-expanded':summaryExpanded}" :role="summaryExpanded ? 'dialog' : undefined" :aria-modal="summaryExpanded ? 'true' : undefined" aria-labelledby="summary-title">
      <div class="hp-section-heading"><h2 id="summary-title">{{ periodName }} <span data-copy-key="homeText.text016">{{ copy.homeText.text016 }}</span></h2><span class="hp-caption muted">단위 억원</span><button v-if="!summaryExpanded" ref="summaryExpandButton" type="button" class="hp-summary-expand" aria-haspopup="dialog" :aria-expanded="false" @click="expandSummary">확대 보기 <span aria-hidden="true">⤢</span></button><button v-else ref="summaryCloseButton" type="button" class="hp-summary-expand" @click="closeSummary">닫기 <span aria-hidden="true">×</span></button></div>
      <div class="hp-table-scroll" tabindex="0" role="region" aria-labelledby="summary-title"><table class="hp-financial-table"><colgroup><col style="width:24%"><col v-for="column in 5" :key="column" style="width:15.2%"></colgroup>
        <thead ref="summaryHead"><tr><th rowspan="2" scope="col"><span data-copy-key="homeText.text018">{{ copy.homeText.text018 }}</span></th><th scope="col">{{ period === 'YEAR' ? '연간 전망 (실적 + 추정)' : month + '월 실적' }}</th><th colspan="2" scope="colgroup"><span data-copy-key="homeText.text007">{{ copy.homeText.text007 }}</span></th><th colspan="2" scope="colgroup"><span>{{ period === 'YEAR' ? '전년 실적 대비' : copy.homeText.text019 }}</span></th></tr><tr><th scope="col"><span data-copy-key="homeText.text020">{{ copy.homeText.text020 }}</span></th><th scope="col"><span data-copy-key="homeText.text021">{{ copy.homeText.text021 }}</span></th><th scope="col"><span data-copy-key="homeText.text022">{{ copy.homeText.text022 }}</span></th><th scope="col"><span data-copy-key="homeText.text021">{{ copy.homeText.text021 }}</span></th><th scope="col"><span data-copy-key="homeText.text022">{{ copy.homeText.text022 }}</span></th></tr></thead>
        <tbody>
          <tr v-for="(row, index) in detailRows" :key="row.reportItemId || row.kind + ':' + index" :class="{ 'hp-subrow': row.level > 1, 'hp-emphasis-row': row.level <= 1, 'hp-sticky-parent': parentLevels.has(row.reportItemId) }" :style="{ '--hp-parent-top': (summaryHeadHeight + (row.level - 1) * 32) + 'px' }"><th scope="row" :style="{ paddingLeft: (12 + row.level * 12) + 'px' }">{{ row.name }}</th><td>{{ money(row.actual) }}</td><td :class="tone(row.vsPlanAmount)">{{ signedMoney(row.vsPlanAmount) }}</td><td :class="tone(row.vsPlanAmount)">{{ pct(row.vsPlanRate) }}</td><td :class="tone(row.vsPrevEstimateAmount)">{{ signedMoney(row.vsPrevEstimateAmount) }}</td><td :class="tone(row.vsPrevEstimateAmount)">{{ pct(row.vsPrevEstimateRate) }}</td></tr>

        </tbody>
        <tfoot v-if="profitRows.length || pnl?.opMargin">
          <tr v-for="(row, index) in profitRows" :key="row.reportItemId || 'profit:' + index" class="hp-profit-row" :style="{ '--hp-footer-offset': ((profitRows.length - index - 1 + (pnl?.opMargin ? 1 : 0)) * 32) + 'px' }"><th scope="row">{{ row.name }}</th><td>{{ money(row.actual) }}</td><td :class="tone(row.vsPlanAmount)">{{ signedMoney(row.vsPlanAmount) }}</td><td :class="tone(row.vsPlanAmount)">{{ pct(row.vsPlanRate) }}</td><td :class="tone(row.vsPrevEstimateAmount)">{{ signedMoney(row.vsPrevEstimateAmount) }}</td><td :class="tone(row.vsPrevEstimateAmount)">{{ pct(row.vsPrevEstimateRate) }}</td></tr>
          <tr v-if="pnl?.opMargin" class="hp-emphasis-row"><th scope="row"><span data-copy-key="homeText.text023">{{ copy.homeText.text023 }}</span></th><td>{{ pct(pnl.opMargin.actualRate) }}</td><td>{{ point(pnl.opMargin.vsPlanPoint) }}</td><td>—</td><td>{{ point(pnl.opMargin.vsPrevEstimatePoint) }}</td><td>—</td></tr>
        </tfoot>
      </table></div>
    </section>
        </div></Teleport>
        <section class="card hp-businesses" aria-labelledby="business-title">
        <div class="hp-section-heading"><h2 id="business-title"><span data-copy-key="homeText.text033">{{ copy.homeText.text033 }}</span></h2><span class="hp-caption muted">단위 억원</span></div>
        <div class="hp-table-scroll" tabindex="0" role="region" aria-labelledby="business-title"><table class="hp-business-table"><colgroup><col style="width:24%"><col v-for="column in 4" :key="column" style="width:19%"></colgroup><thead><tr><th scope="col">사업부</th><th scope="col">매출</th><th scope="col">영업비용</th><th scope="col">영업이익</th><th scope="col" title="영업이익의 계획 대비 차액">계획 대비</th></tr></thead><tbody><tr v-for="item in divisions?.columns" :key="item.organization.id"><th scope="row">{{ item.organization.name }}</th><td>{{ money(item.revenue) }}</td><td>{{ money(subtract(item.revenue, item.op)) }}</td><td>{{ money(item.op) }}</td><td :class="tone(item.vsPlanAmount)">{{ signedMoney(item.vsPlanAmount) }}</td></tr><tr v-if="!divisions?.columns?.length && !loading"><td colspan="5" class="hp-table-empty"><span data-copy-key="homeText.text038">{{ copy.homeText.text038 }}</span></td></tr></tbody></table></div>
      </section>
      </div>
      <aside class="hp-side-column" aria-label="주요 손익 지표와 분석">
        <section class="card hp-recent-cells" aria-labelledby="recent-cells-title">
          <div class="hp-section-heading"><h2 id="recent-cells-title">최근 본 셀</h2></div>
          <ul v-if="store.recentCells.length" class="hp-recent-cell-list"><li v-for="cell in store.recentCells.slice(0,5)" :key="cell.id"><button type="button" :disabled="store.busy" @click="openRecentCell(cell)"><span class="hp-recent-cell-title">{{cell.name}}</span><span class="hp-recent-cell-meta">{{cell.organizationName}}<span v-if="cell.sourceCell"> · {{cell.sourceCell}}</span></span></button></li></ul>
          <p v-else class="hp-caption muted hp-recent-empty">아직 열어본 셀 항목이 없습니다.</p>
        </section>


        <section class="card hp-waterfall" :class="{ 'hp-is-empty': !bars.length }" aria-labelledby="waterfall-title">
      <div class="hp-section-heading"><h2 id="waterfall-title"><span data-copy-key="homeText.text024">{{ copy.homeText.text024 }}</span></h2><span class="hp-caption muted">단위 억원</span></div>
      <div v-if="bars.length" class="hp-waterfall-scroll" tabindex="0" role="region" aria-labelledby="waterfall-title"><div class="hp-waterfall-chart" :style="{ '--hp-stage-count': bars.length, '--hp-zero': waterfallPlot.zero + 'px' }" role="list" aria-label="매출에서 비용 분류별 금액을 차감한 영업이익 흐름"><div v-for="(bar, index) in bars" :key="bar.reportItemId || bar.key + ':' + index" class="hp-waterfall-stage" role="listitem" tabindex="0" :title="waterfallStepDescription(bar)" :aria-label="waterfallStepDescription(bar)" :class="bar.cost ? 'hp-waterfall-cost' : bar.key === 'OP' ? 'hp-waterfall-profit' : 'hp-waterfall-total'"><span class="hp-waterfall-value" :title="(bar.cost ? '−' : '') + money(bar.amount) + '억원'" :style="{ top: (bar.top - 22) + 'px' }">{{ bar.cost ? '−' : '' }}{{ money(bar.amount) }}</span><div class="hp-waterfall-bar" :style="{ top: bar.top + 'px', height: bar.height + 'px' }"></div><div v-if="index < bars.length - 1" class="hp-waterfall-connector" :style="{ top: bar.end + 'px' }"></div><span class="hp-waterfall-name">{{ bar.name }}</span></div></div></div>
      <div v-if="bars.length" class="hp-waterfall-legend"><span><i class="hp-legend-total"></i><span data-copy-key="homeText.text025">{{ copy.homeText.text025 }}</span></span><span><i class="hp-legend-profit"></i><span data-copy-key="homeText.text026">{{ copy.homeText.text026 }}</span></span><span><i class="hp-legend-cost"></i><span data-copy-key="homeText.text027">{{ copy.homeText.text027 }}</span></span></div>
      <p v-if="!bars.length && !loading" class="muted hp-caption hp-empty"><span data-copy-key="homeText.text028">{{ copy.homeText.text028 }}</span></p>
    </section>
        <section class="card hp-causes" :class="{ 'hp-is-empty': !driverGroups.some(group => group.items.length) }" aria-labelledby="causes-title">
        <div class="hp-section-heading"><h2 id="causes-title"><span data-copy-key="homeText.text029">{{ copy.homeText.text029 }}</span></h2><span class="hp-caption muted">단위 억원</span></div>
        <h3 :class="tone(drivers?.total)"><span data-copy-key="homeText.text031">{{ copy.homeText.text031 }}</span> {{ signedMoney(drivers?.total) }} <span data-copy-key="homeText.text006">{{ copy.homeText.text006 }}</span></h3>
        <section v-for="group in driverGroups" :key="group.key" class="hp-driver-group">
          <div v-for="item in group.items" :key="item.organization.id + ':' + item.reportItemId" class="hp-cause"><div class="hp-cause-heading"><span>{{ item.reportItemName }} | {{ item.organization.name }}</span><strong :class="tone(item.opDiff)">{{ signedMoney(item.opDiff) }}</strong></div><div class="hp-cause-track"><div :class="direction(item.opDiff) < 0 ? 'decrease' : 'increase'" :style="{ width: Math.abs(Number(item.opDiff)) / driverMax * 100 + '%' }"></div></div></div>
          <p v-if="!group.items.length && !loading" class="hp-caption muted">해당 항목 없음</p>
        </section>
      </section>
      </aside>
    </div>

  </div>
</template>
