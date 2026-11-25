<script setup>
import copy from './copy-text.js'
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { seoulPeriod } from './domain.js'
import { formatMoney } from '../lib/decimal-display.js'
import { compare } from './exact-money.js'
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
// Number is used only for chart geometry. Displayed financial values retain server decimal strings.
const bars = computed(() => {
  const steps = waterfall.value?.steps || [], max = Math.max(1, ...steps.map(step => Math.abs(Number(step.amount) || 0)))
  let previous = 0
  return steps.map(step => {
    const value = Number(step.amount) || 0, cost = step.kind === 'DEDUCTION', end = cost ? previous - Math.abs(value) : value, start = cost ? previous : 0
    previous = end
    return { ...step, top: 230 - Math.max(start, end) / max * 190, height: Math.max(1, Math.abs(end - start) / max * 190), end: 230 - end / max * 190, cost }
  })
})
const driverMax = computed(() => Math.max(1, ...(drivers.value?.items || []).map(item => Math.abs(Number(item.opDiff) || 0))))
</script>

<template>
  <div class="home-panel home-live">
    <header class="hp-header"><h1><span data-copy-key="pages.home">{{ copy.pages.home }}</span></h1></header>
    <div class="hp-period-bar">
      <div class="hp-period-switch" aria-label="손익 조회 기간">
        <button type="button" :class="{ active: period === 'MONTH' }" :aria-pressed="period === 'MONTH'" @click="period = 'MONTH'">{{ month }}<span data-copy-key="homeText.text001">{{ copy.homeText.text001 }}</span></button>
        <button type="button" :class="{ active: period === 'YEAR' }" :aria-pressed="period === 'YEAR'" @click="period = 'YEAR'">{{ year }}<span data-copy-key="homeText.text002">{{ copy.homeText.text002 }}</span></button>
      </div>
      <span class="hp-caption muted">{{ dateRange }} · {{ state }} <span data-copy-key="homeText.text003">{{ copy.homeText.text003 }}</span>{{ period === 'YEAR' ? ' · 연간 전망 (실적 + 추정)'  : '' }}</span>
    </div>
    <p v-if="loading" class="muted hp-caption" role="status"><span data-copy-key="homeText.text004">{{ copy.homeText.text004 }}</span></p>
    <p v-else-if="pnl?.dataStatus === 'NONE'" class="notice"><span data-copy-key="homeText.text005">{{ copy.homeText.text005 }}</span></p>

    <div class="hp-kpis">
      <section v-for="kpi in pnl?.kpis" :key="kpi.key" class="card hp-kpi">
        <p class="hp-kpi-label">{{ { REVENUE: '매출', COST: '영업비용', OP: '영업이익' }[kpi.key] }}<span><span data-copy-key="homeText.text006">{{ copy.homeText.text006 }}</span></span></p>
        <strong>{{ money(kpi.actual) }}</strong>
        <p class="hp-caption" :class="tone(kpi.vsPlanAmount)"><span data-copy-key="homeText.text007">{{ copy.homeText.text007 }}</span> {{ signedMoney(kpi.vsPlanAmount) }} ({{ pct(kpi.vsPlanRate) }})</p>
        <p class="hp-caption" :class="'hp-' + effect(kpi).tone">{{ effect(kpi).label }}</p>
      </section>
    </div>

    <div class="hp-top-grid">
      <section class="card hp-close-card" aria-labelledby="close-title">
        <div class="hp-section-heading"><h2 id="close-title">{{ month }}<span data-copy-key="homeText.text008">{{ copy.homeText.text008 }}</span></h2><span class="badge">{{ deadline }}</span></div>
        <ol class="hp-close-steps"><li v-for="(step, index) in closing?.steps" :key="step.key" :class="{ active: step.key === activeStep }"><span>{{ index + 1 }} {{ step.label }}</span><b>{{ step.done }} / {{ step.total }}</b></li></ol>
        <p class="hp-caption muted">{{ closing?.dDay == null ? '마감 기한 미등록' : closing.dDay < 0 ? '마감 기한 ' + Math.abs(closing.dDay) + '일 경과' : '마감까지 ' + closing.dDay + '일' }} · {{ closing?.dataStatus === 'CONFIRMED' ? 'master 확정' : '조직별 진행 현황 기준' }}</p>
        <p class="hp-caption hp-good"><span data-copy-key="homeText.text009">{{ copy.homeText.text009 }}</span></p>
      </section>
      <section class="card hp-todos" aria-labelledby="todo-title">
        <div class="hp-section-heading"><h2 id="todo-title"><span data-copy-key="homeText.text010">{{ copy.homeText.text010 }}</span></h2><div class="hp-tabs" aria-label="할 일 구분"><button type="button" :class="{ active: tab === 'ANNUAL' }" :aria-pressed="tab === 'ANNUAL'" @click="tab = 'ANNUAL'"><span data-copy-key="homeText.text011">{{ copy.homeText.text011 }}</span></button><button type="button" :class="{ active: tab === 'MONTHLY' }" :aria-pressed="tab === 'MONTHLY'" @click="tab = 'MONTHLY'"><span data-copy-key="homeText.text012">{{ copy.homeText.text012 }}</span></button></div></div>
        <div v-for="todo in todos" :key="todo.key" class="hp-todo-row"><span :class="{ 'hp-review': ['REVIEW_NEEDED', 'EXPLANATION_REVIEW_NEEDED'].includes(todo.key) }">{{ todo.label }}</span><span>{{ todo.count }}{{ todo.key === 'REQUEST_NEEDED' ? '건' : '곳' }}</span><button class="btn" type="button" @click="go(todo)">{{ todo.key === 'REQUEST_NEEDED' ? '요청하러 가기' : '확인하러 가기' }}</button></div>
        <div class="hp-todo-row"><span><span data-copy-key="homeText.text013">{{ copy.homeText.text013 }}</span></span><span><span data-copy-key="homeText.text014">{{ copy.homeText.text014 }}</span></span><button class="btn" type="button" @click="emit('navigate', 'reports')"><span data-copy-key="homeText.text015">{{ copy.homeText.text015 }}</span></button></div>
      </section>
    </div>

    <section class="hp-summary" aria-labelledby="summary-title">
      <div class="hp-section-heading"><h2 id="summary-title">{{ periodName }} <span data-copy-key="homeText.text016">{{ copy.homeText.text016 }}</span></h2><span class="hp-caption muted">{{ state }} <span data-copy-key="homeText.text017">{{ copy.homeText.text017 }}</span></span></div>
      <div class="hp-table-scroll" tabindex="0" role="region" aria-labelledby="summary-title"><table class="hp-financial-table">
        <thead><tr><th rowspan="2" scope="col"><span data-copy-key="homeText.text018">{{ copy.homeText.text018 }}</span></th><th scope="col">{{ period === 'YEAR' ? '연간 전망 (실적 + 추정)' : month + '월 실적' }}</th><th colspan="2" scope="colgroup"><span data-copy-key="homeText.text007">{{ copy.homeText.text007 }}</span></th><th colspan="2" scope="colgroup"><span data-copy-key="homeText.text019">{{ copy.homeText.text019 }}</span></th></tr><tr><th scope="col"><span data-copy-key="homeText.text020">{{ copy.homeText.text020 }}</span></th><th scope="col"><span data-copy-key="homeText.text021">{{ copy.homeText.text021 }}</span></th><th scope="col"><span data-copy-key="homeText.text022">{{ copy.homeText.text022 }}</span></th><th scope="col"><span data-copy-key="homeText.text021">{{ copy.homeText.text021 }}</span></th><th scope="col"><span data-copy-key="homeText.text022">{{ copy.homeText.text022 }}</span></th></tr></thead>
        <tbody>
          <tr v-for="(row, index) in pnl?.rows" :key="row.reportItemId || row.kind + ':' + index" :class="{ 'hp-profit-row': row.kind === 'OP', 'hp-subrow': row.level > 1, 'hp-emphasis-row': row.level <= 1 }"><th scope="row" :style="{ paddingLeft: (16 + row.level * 15) + 'px' }">{{ row.name }}</th><td>{{ money(row.actual) }}</td><td :class="tone(row.vsPlanAmount)">{{ signedMoney(row.vsPlanAmount) }}</td><td :class="tone(row.vsPlanAmount)">{{ pct(row.vsPlanRate) }}</td><td :class="tone(row.vsPrevEstimateAmount)">{{ signedMoney(row.vsPrevEstimateAmount) }}</td><td :class="tone(row.vsPrevEstimateAmount)">{{ pct(row.vsPrevEstimateRate) }}</td></tr>
          <tr v-if="pnl?.opMargin" class="hp-emphasis-row"><th scope="row"><span data-copy-key="homeText.text023">{{ copy.homeText.text023 }}</span></th><td>{{ pct(pnl.opMargin.actualRate) }}</td><td>{{ point(pnl.opMargin.vsPlanPoint) }}</td><td>—</td><td>{{ point(pnl.opMargin.vsPrevEstimatePoint) }}</td><td>—</td></tr>
        </tbody>
      </table></div>
    </section>

    <section class="card hp-waterfall" aria-labelledby="waterfall-title">
      <div class="hp-section-heading"><h2 id="waterfall-title"><span data-copy-key="homeText.text024">{{ copy.homeText.text024 }}</span></h2><span class="hp-caption muted">{{ periodName }} <span data-copy-key="homeText.text003">{{ copy.homeText.text003 }}</span></span></div>
      <div class="hp-waterfall-scroll" tabindex="0" role="region" aria-labelledby="waterfall-title"><div class="hp-waterfall-chart" role="img" aria-label="매출에서 영업비용을 차감한 영업이익 흐름"><div v-for="(bar, index) in bars" :key="index" class="hp-waterfall-stage" :class="bar.cost ? 'hp-waterfall-cost' : bar.key === 'OP' ? 'hp-waterfall-profit' : 'hp-waterfall-total'"><span class="hp-waterfall-value" :style="{ top: (bar.top - 26) + 'px' }">{{ bar.cost ? '−' : '' }}{{ money(bar.amount) }}</span><div class="hp-waterfall-bar" :style="{ top: bar.top + 'px', height: bar.height + 'px' }"></div><div v-if="index < bars.length - 1" class="hp-waterfall-connector" :style="{ top: bar.end + 'px' }"></div><span class="hp-waterfall-name">{{ bar.name }}</span></div></div></div>
      <div class="hp-waterfall-legend"><span><i class="hp-legend-total"></i><span data-copy-key="homeText.text025">{{ copy.homeText.text025 }}</span></span><span><i class="hp-legend-profit"></i><span data-copy-key="homeText.text026">{{ copy.homeText.text026 }}</span></span><span><i class="hp-legend-cost"></i><span data-copy-key="homeText.text027">{{ copy.homeText.text027 }}</span></span></div>
      <p v-if="!bars.length" class="muted hp-caption"><span data-copy-key="homeText.text028">{{ copy.homeText.text028 }}</span></p>
    </section>

    <div class="hp-bottom-grid">
      <section class="card hp-causes" aria-labelledby="causes-title">
        <div class="hp-section-heading"><h2 id="causes-title"><span data-copy-key="homeText.text029">{{ copy.homeText.text029 }}</span></h2><span class="hp-caption muted">{{ periodName }} <span data-copy-key="homeText.text030">{{ copy.homeText.text030 }}</span></span></div>
        <h3 :class="tone(drivers?.total)"><span data-copy-key="homeText.text031">{{ copy.homeText.text031 }}</span> {{ signedMoney(drivers?.total) }} <span data-copy-key="homeText.text006">{{ copy.homeText.text006 }}</span></h3>
        <div v-for="(item, index) in drivers?.items" :key="item.organization.id" class="hp-cause"><div class="hp-cause-heading"><span>{{ index + 1 }} {{ item.organization.name }}</span><strong :class="tone(item.opDiff)">{{ signedMoney(item.opDiff) }}</strong><span class="hp-caption" :class="direction(item.opDiff) >= 0 ? 'hp-good' : 'hp-bad'">{{ direction(item.opDiff) > 0 ? '이익 증가' : direction(item.opDiff) < 0 ? '이익 감소' : '차이 없음' }}</span></div><div class="hp-cause-track"><div :class="direction(item.opDiff) < 0 ? 'decrease' : 'increase'" :style="{ width: Math.abs(Number(item.opDiff)) / driverMax * 100 + '%' }"></div></div></div>
        <p v-if="!drivers?.items?.length && !loading" class="hp-caption muted"><span data-copy-key="homeText.text032">{{ copy.homeText.text032 }}</span></p>
      </section>
      <section class="card hp-businesses" aria-labelledby="business-title">
        <div class="hp-section-heading"><h2 id="business-title"><span data-copy-key="homeText.text033">{{ copy.homeText.text033 }}</span></h2><span class="hp-caption muted">{{ periodName }} <span data-copy-key="homeText.text030">{{ copy.homeText.text030 }}</span></span></div>
        <div class="hp-table-scroll" tabindex="0" role="region" aria-labelledby="business-title"><table class="hp-business-table"><thead><tr><th scope="col"><span data-copy-key="homeText.text034">{{ copy.homeText.text034 }}</span></th><th scope="col"><span data-copy-key="homeText.text025">{{ copy.homeText.text025 }}</span></th><th scope="col"><span data-copy-key="homeText.text026">{{ copy.homeText.text026 }}</span></th><th scope="col"><span data-copy-key="homeText.text035">{{ copy.homeText.text035 }}</span></th><th scope="col"><span data-copy-key="homeText.text036">{{ copy.homeText.text036 }}</span></th><th scope="col"><span data-copy-key="homeText.text007">{{ copy.homeText.text007 }}</span></th></tr></thead><tbody><tr v-for="item in divisions?.columns" :key="item.organization.id"><th scope="row">{{ item.organization.name }}</th><td>{{ money(item.revenue) }}</td><td>{{ money(item.op) }}</td><td>{{ pct(item.opContributionRate) }}</td><td>{{ pct(item.opMarginRate) }}</td><td :class="tone(item.vsPlanAmount)">{{ signedMoney(item.vsPlanAmount) }}</td></tr></tbody><tfoot v-if="divisions?.total"><tr><th scope="row"><span data-copy-key="homeText.text037">{{ copy.homeText.text037 }}</span></th><td>{{ money(divisions.total.revenue) }}</td><td>{{ money(divisions.total.op) }}</td><td>—</td><td>{{ pct(divisions.total.opMarginRate) }}</td><td :class="tone(divisions.total.vsPlanAmount)">{{ signedMoney(divisions.total.vsPlanAmount) }}</td></tr></tfoot></table></div>
      </section>
    </div>
  </div>
</template>
