<script setup>
import { computed, ref } from 'vue'
import './home.css'

const emit = defineEmits(['navigate'])
const period = ref('month')
const todoPeriod = ref('month')
const now = new Date()
const dateParts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Seoul', year: 'numeric', month: 'numeric' }).formatToParts(now)
const year = Number(dateParts.find(part => part.type === 'year').value)
const month = Number(dateParts.find(part => part.type === 'month').value)
const yearView = computed(() => period.value === 'year')
const periodName = computed(() => yearView.value ? `${year}년` : `${month}월`)
const dateRange = computed(() => {
  const pad = value => String(value).padStart(2, '0')
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate()
  return yearView.value ? `${year}.01.01 – ${year}.12.31` : `${year}.${pad(month)}.01 – ${year}.${pad(month)}.${lastDay}`
})

// Figma 450:5060–5086: design fixtures, never treated as approved financial data.
const data = {
  month: {
    values: ['10,903.25', '9,824.68', '2,550.44', '1,073.80', '1,376.21', '2,808.39', '2,015.84', '1,078.57', '9.89%'],
    deltas: ['+111.82', '+50.39', '+13.08', '+5.51', '+7.07', '+14.40', '+10.33', '+61.43', '+0.47%p'],
    rates: ['+1.04%', '+0.52%', '+0.52%', '+0.52%', '+0.52%', '+0.52%', '+0.52%', '+6.04%', '해당 없음'],
    causes: [111.82, -14.40, -13.08, -7.07, -5.51, -10.33],
  },
  year: {
    values: ['128,700.00', '114,172.50', '28,372.50', '12,675.00', '16,770.00', '33,150.00', '23,205.00', '14,527.50', '11.29%'],
    deltas: ['+1,320.00', '+585.50', '+145.50', '+65.00', '+86.00', '+170.00', '+119.00', '+734.50', '+0.46%p'],
    rates: ['+1.04%', '+0.52%', '+0.52%', '+0.52%', '+0.52%', '+0.52%', '+0.52%', '+5.33%', '해당 없음'],
    causes: [1320, -170, -145.5, -86, -65, -119],
  },
}
const activeData = computed(() => data[period.value])
const rowNames = ['매출', '− 영업비용', '마케팅비용', '인건복리비', 'Network비용', '감가상각비', '기타비용', '영업이익', '영업이익률']
const causeNames = ['1 매출', '2 감가상각비', '3 마케팅비용', '4 Network비용', '5 인건복리비', '그 외']
const kpis = computed(() => [
  { title: '매출', value: activeData.value.values[0], change: `${activeData.value.deltas[0]} (${activeData.value.rates[0]})`, effect: `매출 증가 · 손익 ${activeData.value.deltas[0]}`, kind: 'good' },
  { title: '영업비용', value: activeData.value.values[1], change: `${activeData.value.deltas[1]} (${activeData.value.rates[1]})`, effect: `비용 증가 · 손익 −${activeData.value.deltas[1].slice(1)}`, kind: 'bad' },
  { title: '영업이익', value: activeData.value.values[7], change: `${activeData.value.deltas[7]} (${activeData.value.rates[7]})`, effect: '계획 대비 이익 증가', kind: 'muted' },
])
const todos = computed(() => {
  const annual = todoPeriod.value === 'year'
  const name = annual ? '연간 계획' : '월간 시트'
  return [
    { name: `${name} 제출 요청`, count: '—건', label: '요청하러 가기', target: annual ? 'annual-request' : 'monthly-request' },
    { name: `${name} 미제출`, count: annual ? '4곳' : '—곳', label: '확인하러 가기', target: annual ? 'annual' : 'monthly' },
    { name: `${name} 검토 필요 조직`, count: '—곳', label: '확인하러 가기', target: annual ? 'annual' : 'monthly', kind: 'review' },
    { name: `${name} 소명 검토 필요 조직`, count: annual ? '—개' : '3개', label: '확인하러 가기', target: annual ? 'annual' : 'monthly', kind: 'review' },
    { name: '보고서 작성', count: '—건', label: '확인하러 가기', target: 'reports' },
  ]
})
const waterfall = [
  { name: '매출', value: 15203, type: 'total' },
  { name: '매출원가', value: -8520, type: 'cost' },
  { name: '매출총이익', value: 6683, type: 'total' },
  { name: '인건비', value: -2050, type: 'cost' },
  { name: '마케팅비', value: -2310, type: 'cost' },
  { name: '기타 판관비', value: -841, type: 'cost' },
  { name: '영업이익', value: 1482, type: 'profit' },
  { name: '영업외·법인세', value: -415, type: 'cost' },
  { name: '순이익', value: 1067, type: 'profit' },
]
let previous = 0
const waterfallBars = waterfall.map(item => {
  const end = item.type === 'cost' ? previous + item.value : item.value
  const topValue = item.type === 'cost' ? previous : item.value
  const bar = { ...item, top: 230 - topValue * (190 / 15203), height: Math.abs(item.value) * (190 / 15203), end: 230 - end * (190 / 15203) }
  previous = end
  return bar
})
const businesses = [
  { name: 'MNO사업부', revenue: 6800, profit: 720, contribution: '49%', margin: '10.6%', delta: -41 },
  { name: '플랫폼사업부', revenue: 1000, profit: 220, contribution: '15%', margin: '22.0%', delta: 19 },
  { name: '미디어사업부', revenue: 2900, profit: 210, contribution: '14%', margin: '7.2%', delta: -58 },
  { name: '네트워크인프라부문', revenue: 2600, profit: 180, contribution: '12%', margin: '6.9%', delta: -33 },
  { name: 'B2B사업부', revenue: 1903, profit: 152, contribution: '10%', margin: '8.0%', delta: -22 },
]
const businessMultiplier = computed(() => yearView.value ? 12 : 1)
const format = (number, decimals = 0) => new Intl.NumberFormat('ko-KR', { minimumFractionDigits: decimals, maximumFractionDigits: decimals }).format(number)
const signed = (number, decimals = 2) => `${number < 0 ? '−' : '+'}${format(Math.abs(number), decimals)}`
</script>

<template>
  <div class="home-panel" data-figma-node="286:896">
    <header class="hp-header">
      <h1>전사 손익 현황</h1>
    </header>

    <div class="hp-period-bar">
      <div class="hp-period-switch" aria-label="손익 조회 기간"><button type="button" :class="{ active: period === 'month' }" :aria-pressed="period === 'month'" @click="period = 'month'">{{ month }}월 · 이번 달</button><button type="button" :class="{ active: period === 'year' }" :aria-pressed="period === 'year'" @click="period = 'year'">{{ year }}년 · 올해</button></div>
      <span class="hp-caption muted">{{ dateRange }} · 단위 억원 · 디자인 예시</span>
    </div>

    <div class="hp-kpis">
      <section v-for="kpi in kpis" :key="kpi.title" class="card hp-kpi"><p class="hp-kpi-label">{{ kpi.title }}<span>억원</span></p><strong>{{ kpi.value }}</strong><p class="hp-increase hp-caption">계획 대비 {{ kpi.change }}</p><p class="hp-caption" :class="`hp-${kpi.kind}`">{{ kpi.effect }}</p></section>
    </div>

    <div class="hp-top-grid">
      <section class="card hp-close-card" aria-labelledby="close-title">
        <div class="hp-section-heading"><h2 id="close-title">{{ month }}월 마감 진행</h2><span class="badge">기한 미설정</span></div>
        <ol class="hp-close-steps">
          <li v-for="(step, i) in ['요청', '조직 제출', 'Gap 검토', '소명 완료', 'master 생성']" :key="step" :class="{ active: i === 1 }"><span>{{ i + 1 }} {{ step }}</span><b>—</b></li>
        </ol>
        <p class="hp-caption muted">마감 기한 미등록 · 진행 상태 미연결</p>
        <p class="hp-caption hp-good">소명 승인 후에도 master 숫자는 자동 변경되지 않습니다.</p>
      </section>
      <section class="card hp-todos" aria-labelledby="todo-title">
        <div class="hp-section-heading"><h2 id="todo-title">오늘 할 일</h2><div class="hp-tabs" aria-label="할 일 구분"><button type="button" :class="{ active: todoPeriod === 'year' }" :aria-pressed="todoPeriod === 'year'" @click="todoPeriod = 'year'">연간</button><button type="button" :class="{ active: todoPeriod === 'month' }" :aria-pressed="todoPeriod === 'month'" @click="todoPeriod = 'month'">월간</button></div></div>
        <div v-for="todo in todos" :key="todo.name" class="hp-todo-row">
          <span :class="{ 'hp-review': todo.kind === 'review' }">{{ todo.name }}</span><span>{{ todo.count }}</span><button class="btn" type="button" @click="emit('navigate', todo.target)">{{ todo.label }}</button>
        </div>
      </section>
    </div>

    <section class="hp-summary" aria-labelledby="summary-title">
      <div class="hp-section-heading"><h2 id="summary-title">{{ periodName }} 전사 요약 손익</h2><span class="hp-caption muted">단위 억원 · 이익률 % · 디자인 예시</span></div>
      <div class="hp-table-scroll" tabindex="0" role="region" aria-labelledby="summary-title"><table class="hp-financial-table">
        <thead><tr><th rowspan="2" scope="col">손익 항목</th><th scope="col">{{ periodName }} 실적·추정</th><th colspan="2" scope="colgroup">계획 대비</th><th colspan="2" scope="colgroup">{{ yearView ? '이전 연간 제출본 대비' : '전월 추정 대비' }}</th></tr><tr><th scope="col">금액</th><th scope="col">차액</th><th scope="col">차이율</th><th scope="col">차액</th><th scope="col">차이율</th></tr></thead>
        <tbody><tr v-for="(name, i) in rowNames" :key="name" :class="{ 'hp-subrow': i >= 2 && i <= 6, 'hp-profit-row': i === 7, 'hp-emphasis-row': i <= 1 }"><th scope="row">{{ name }}</th><td>{{ activeData.values[i] }}</td><td class="hp-increase">{{ activeData.deltas[i] }}</td><td :class="{ 'hp-increase': i !== 8 }">{{ activeData.rates[i] }}</td><td>—</td><td>—</td></tr></tbody>
      </table></div>
    </section>

    <section class="card hp-waterfall" aria-labelledby="waterfall-title">
      <div class="hp-section-heading"><h2 id="waterfall-title">손익 흐름</h2><span class="hp-caption muted">디자인 예시 · 단위 억원</span></div>
      <div class="hp-waterfall-scroll" tabindex="0" role="region" aria-labelledby="waterfall-title"><div class="hp-waterfall-chart" role="img" aria-label="원본 와이어프레임 예시: 매출 15,203억원에서 비용 차감 후 순이익 1,067억원까지의 손익 흐름">
        <div v-for="(bar, i) in waterfallBars" :key="bar.name" class="hp-waterfall-stage" :class="`hp-waterfall-${bar.type}`"><span class="hp-waterfall-value" :style="{ top: `${bar.top - 26}px` }">{{ bar.value < 0 ? '−' : '' }}{{ format(Math.abs(bar.value)) }}</span><div class="hp-waterfall-bar" :style="{ top: `${bar.top}px`, height: `${bar.height}px` }"></div><div v-if="i < waterfallBars.length - 1" class="hp-waterfall-connector" :style="{ top: `${bar.end}px` }"></div><span class="hp-waterfall-name">{{ bar.name }}</span></div>
      </div></div>
      <div class="hp-waterfall-legend"><span><i class="hp-legend-total"></i>매출·매출총이익</span><span><i class="hp-legend-profit"></i>영업이익·순이익</span><span><i class="hp-legend-cost"></i>비용 차감 (−)</span></div>
      <p class="hp-caption muted">원본 와이어프레임의 예시 수치입니다. 위 KPI 및 월·연간 조회 데이터와는 별도입니다.</p>
    </section>

    <div class="hp-bottom-grid">
      <section class="card hp-causes" aria-labelledby="causes-title"><div class="hp-section-heading"><h2 id="causes-title">손익 변동 원인 Top 5</h2><span class="hp-caption muted">{{ periodName }} · 억원</span></div><h3 class="hp-increase">영업이익 계획 대비 {{ activeData.deltas[7] }} 억원</h3>
        <div v-for="(cause, i) in activeData.causes" :key="causeNames[i]" class="hp-cause"><div class="hp-cause-heading"><span>{{ causeNames[i] }}</span><strong :class="cause > 0 ? 'hp-increase' : 'hp-decrease'">{{ signed(cause) }}</strong><span class="hp-caption" :class="cause > 0 ? 'hp-good' : 'hp-bad'">{{ cause > 0 ? '이익 증가' : '이익 감소' }}</span></div><div class="hp-cause-track"><div :class="cause > 0 ? 'increase' : 'decrease'" :style="{ width: `${Math.abs(cause) / activeData.causes[0] * 100}%` }"></div></div></div>
      </section>
      <section class="card hp-businesses" aria-labelledby="business-title"><h2 id="business-title">사업부별 손익</h2><p class="hp-caption muted">{{ periodName }} {{ yearView ? '연간' : '' }} · 단위 억원 · 디자인 예시</p><div class="hp-table-scroll" tabindex="0" role="region" aria-labelledby="business-title"><table class="hp-business-table"><thead><tr><th scope="col">사업부</th><th scope="col">매출</th><th scope="col">영업이익</th><th scope="col">전사 영업이익<br>기여도</th><th scope="col">이익률</th><th scope="col">계획<br>대비</th></tr></thead><tbody><tr v-for="business in businesses" :key="business.name"><th scope="row">{{ business.name }}</th><td>{{ format(business.revenue * businessMultiplier) }}</td><td>{{ format(business.profit * businessMultiplier) }}</td><td>{{ business.contribution }}</td><td>{{ business.margin }}</td><td :class="business.delta > 0 ? 'hp-increase' : 'hp-decrease'">{{ signed(business.delta * businessMultiplier, 0) }}</td></tr></tbody><tfoot><tr><th scope="row">예시 합계</th><td>{{ format(15203 * businessMultiplier) }}</td><td>{{ format(1482 * businessMultiplier) }}</td><td>본사·기타<br>+{{ 44 * businessMultiplier }} 포함</td><td>9.7%</td><td class="hp-decrease">−{{ 91 * businessMultiplier }}</td></tr></tfoot></table></div><p v-if="yearView" class="hp-caption muted">연간 사업부 예시는 동일 월 금액을 12개월 합산한 디자인 예시입니다.</p></section>
    </div>
  </div>
</template>
