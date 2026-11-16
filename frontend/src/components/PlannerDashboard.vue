<script setup>
import { computed, ref } from 'vue'

const month = ref(10)
const scope = ref('company')
const organization = ref('current')
const period = ref('month')
const metric = ref('profit')
const costsExpanded = ref(true)

// Figma 원본 월간 예시의 각 카드 수치는 서로 다른 집계 예시이므로 재계산하거나 합치지 않는다.
const kpis = [
  { label: '매출액', amount: '15,203', rate: '', plan: '−2.1%', previous: '−0.8%', lastYear: '+4.3%' },
  { label: '매출총이익', amount: '6,683', rate: '44.0%', plan: '−3.0%', previous: '−1.2%', lastYear: '+3.1%' },
  { label: '영업이익', amount: '1,482', rate: '9.7%', plan: '−5.8%', previous: '−3.4%', lastYear: '+2.0%' },
]
const stages = [
  { label: '제출 요청', value: '요청일 11.03', state: 'complete', path: '/planning/requests' },
  { label: '조직 제출', value: '18/22', state: 'active', path: '/planning/requests' },
  { label: 'Gap 검토', value: '14/41', state: 'active', path: '/planning/gap' },
  { label: '소명 완료', value: '5/22', state: 'waiting', path: '/planning/gap' },
  { label: 'master 생성', value: '', state: 'waiting', path: '/planning/reports' },
]
const taskGroups = [
  { label: '연간', tasks: [
    { label: '연간 계획 제출 요청', count: '', state: 'urgent', path: '/planning/requests' },
    { label: '연간 계획 미제출', count: '4곳', state: 'urgent', path: '/planning/requests' },
    { label: '연간 계획 검토 필요 조직', count: '3개', state: 'review', path: '/planning/requests' },
    { label: '연간 계획 소명 검토 필요 조직', count: '13개', state: 'review', path: '/planning/requests' },
  ] },
  { label: '월간', tasks: [
    { label: '월간 시트 제출 요청', count: '', state: 'urgent', path: '/planning/requests' },
    { label: '월간 시트 미제출', count: '4곳', state: 'urgent', path: '/planning/requests' },
    { label: '월간 시트 검토 필요 조직', count: '4곳', state: 'urgent', path: '/planning/gap' },
    { label: '월간 시트 소명 검토 필요 조직', count: '13개', state: 'review', path: '/planning/gap' },
    { label: '보고서 작성', count: '', state: 'review', path: '/planning/reports' },
  ] },
]
const summaryRows = [
  { label: '매출', amount: '15,203', plan: '−2.1%', previous: '−0.8%', kind: 'main' },
  { label: '영업비용', amount: '13,721', plan: '−1.7%', previous: '+0.4%', kind: 'cost' },
  { label: '인건비', amount: '4,120', plan: '+0.8%', previous: '+0.2%', kind: 'detail' },
  { label: '마케팅비', amount: '2,310', plan: '', previous: '+3.1%', kind: 'detail' },
  { label: '감가상각비', amount: '3,050', plan: '−0.4%', previous: '0.0%', kind: 'detail' },
  { label: '기타', amount: '4,241', plan: '−6.9%', previous: '−1.8%', kind: 'detail' },
  { label: '영업이익', amount: '1,482', plan: '−5.8%', previous: '−3.4%', kind: 'total' },
  { label: '영업이익률', amount: '9.7%', plan: '−0.4%p', previous: '−0.3%p', kind: 'ratio' },
]
const departments = [
  { name: 'MNO사업부', revenue: '6,800', profit: '720', contribution: '49%', width: 88, margin: '10.6%', gap: '−41' },
  { name: '플랫폼사업부', revenue: '1,000', profit: '220', contribution: '15%', width: 27, margin: '22.0%', gap: '+19' },
  { name: '미디어사업부', revenue: '2,900', profit: '210', contribution: '14%', width: 25, margin: '7.2%', gap: '−58' },
  { name: '네트워크인프라부문', revenue: '2,600', profit: '180', contribution: '12%', width: 22, margin: '6.9%', gap: '−33' },
  { name: 'B2B사업부', revenue: '1,903', profit: '152', contribution: '10%', width: 18, margin: '8.0%', gap: '−22' },
]
const drivers = [
  { label: '광고매출', organization: '미디어사업부', gap: '−58', width: 379 },
  { label: '마케팅비', organization: 'MNO사업부', gap: '−41', width: 268 },
  { label: '망 운영', organization: '네트워크인프라부문', gap: '−33', width: 216 },
  { label: '계약', organization: 'B2B사업부', gap: '−22', width: 144 },
  { label: '플랫폼 수수료', organization: '플랫폼사업부', gap: '+19', width: 124 },
  { label: '그 외', organization: '', gap: '+44', width: 287 },
]
// 막대 높이와 계획선의 점 좌표는 Figma 월별 추이 도형의 원래 좌표이며 금액 역산값이 아니다.
const trendBars = [
  { month: 1, height: 55.385, pointTop: 70.769 },
  { month: 2, height: 67.692, pointTop: 61.539 },
  { month: 3, height: 95.385, pointTop: 55.385 },
  { month: 4, height: 76.923, pointTop: 55.385 },
  { month: 5, height: 89.231, pointTop: 49.231 },
  { month: 6, height: 101.538, pointTop: 43.077 },
  { month: 7, height: 83.077, pointTop: 46.154 },
  { month: 8, height: 107.692, pointTop: 36.923 },
  { month: 9, height: 73.846, pointTop: 49.231 },
  { month: 10, height: 86.769, pointTop: 32.923 },
  { month: 11, height: 110.769, pointTop: 24.615 },
  { month: 12, height: 135.385, pointTop: 0 },
]
const waterfall = [
  { label: '매출', value: '15,203', note: '', left: 14, top: 40, height: 170, kind: 'revenue', connectorTop: 39 },
  { label: '매출원가', value: '−8,520', note: '', left: 85, top: 40, height: 95.271, kind: 'deduction', connectorTop: 134.27 },
  { label: '매출총이익', value: '6,683', note: '44.0%', left: 156, top: 135.27, height: 74.729, kind: 'profit', connectorTop: 134.27 },
  { label: '인건비', value: '−2,050', note: '판관비', left: 227, top: 135.27, height: 22.923, kind: 'deduction', connectorTop: 157.19 },
  { label: '마케팅비', value: '−2,310', note: '판관비', left: 298, top: 158.19, height: 25.83, kind: 'deduction', connectorTop: 183.02 },
  { label: '기타 판관비', value: '−841', note: '감가상각 등', left: 369, top: 184.02, height: 9.404, kind: 'deduction', connectorTop: 192.43 },
  { label: '영업이익', value: '1,482', note: '9.7%', left: 440, top: 193.43, height: 16.572, kind: 'profit', connectorTop: 192.43 },
  { label: '영업외·법인세', value: '−415', note: '', left: 511, top: 193.43, height: 4.641, kind: 'deduction', connectorTop: 197.07 },
  { label: '순이익', value: '1,067', note: '7.0%', left: 582, top: 198.07, height: 11.931, kind: 'profit', connectorTop: null },
]

/** 화면 예시의 증감 부호를 원본 파랑·빨강 표시로 구분한다.
 * Args: value 표시용 증감 문자열. Returns: negative, positive 또는 neutral 클래스명.
 * Raises: 없음. Side Effects: 없음; 재무 유불리나 실제 금액을 판단하지 않는다.
 */
function deltaTone(value) {
  return value.startsWith('−') ? 'negative' : value.startsWith('+') ? 'positive' : 'neutral'
}

/** 선택한 사업부와 원본 예시 행의 이름이 같은지 확인한다.
 * Args: department 원본 사업부 예시 행. Returns: 선택 일치 여부 boolean.
 * Raises: 없음. Side Effects: 없음.
 */
function matchesDepartment(department) { return department.name === organization.value }

/** 사업부 표에만 적용할 예시 필터 결과를 반환한다.
 * Args: 없음. Returns: 원본 예시 사업부 행 배열.
 * Raises: 없음. Side Effects: 없음; 다른 전사 카드와 실제 서버 데이터는 변경하지 않는다.
 */
function filteredDepartments() {
  return organization.value === 'current' ? departments : departments.filter(matchesDepartment)
}

/** 선택 범위와 기간이 집계값을 변경하지 않는다는 현재 표시 범위를 안내한다.
 * Args: 없음. Returns: 필터·보기 상태 설명 문자열.
 * Raises: 없음. Side Effects: 없음; 미제공 월·연간·매출 수치를 만들지 않는다.
 */
function selectionDescription() {
  const notes = []
  if (period.value === 'year') notes.push('연간 보기 선택 · 원본 예시는 2026년 10월 금액입니다.')
  if (month.value !== 10) notes.push(`${month.value}월 선택 · 차트 선택 위치만 변경되며 금액은 10월 예시입니다.`)
  if (scope.value === 'department' || organization.value !== 'current') notes.push('조직 선택은 사업부별 손익 표에만 적용됩니다.')
  return notes.join(' ')
}

const visibleDepartments = computed(filteredDepartments)
const selectionNote = computed(selectionDescription)
</script>

<template>
  <div class="planner-dashboard">
    <header class="dashboard-heading">
      <div><p class="dashboard-eyebrow">경영기획팀</p><h1>전사 손익 현황</h1><p class="design-example">디자인 예시 · 실제 집계가 아닙니다</p></div>
      <div class="dashboard-filters">
        <label class="filter-control"><span>월</span><select v-model.number="month" aria-label="차트 선택 월"><option v-for="value in 12" :key="value" :value="value">{{ value }}월</option></select></label>
        <label class="filter-control"><span>범위</span><select v-model="scope" aria-label="표시 범위"><option value="company">전사</option><option value="department">사업부별</option></select></label>
        <label class="filter-control organization-filter"><span>조직</span><select v-model="organization" aria-label="사업부별 표의 조직"><option value="current">현 조직 기준</option><option v-for="department in departments" :key="department.name" :value="department.name">{{ department.name }}</option></select></label>
        <RouterLink to="/planning/reports" class="report-button">월말 보고서 추출</RouterLink>
      </div>
    </header>

    <section class="dashboard-card closing-card" aria-label="마감 현황 및 할 일 예시" data-figma-node="2:685">
      <div class="closing-status">
        <div class="card-heading"><h2>10월 마감 현황</h2><span class="deadline-pill">마감 D-2</span></div>
        <ol class="closing-steps"><li v-for="stage in stages" :key="stage.label" :class="stage.state"><RouterLink :to="stage.path"><span class="stage-circle" aria-hidden="true">{{ stage.state === 'complete' ? '✓' : '' }}</span><strong>{{ stage.label }}</strong><small>{{ stage.value || '\u00a0' }}</small></RouterLink></li></ol>
        <div class="closing-progress" role="img" aria-label="Figma 예시 마감 진행 막대"><span></span></div>
      </div>
      <img class="closing-divider" src="/assets/planner-home-divider.svg" alt="" />
      <div class="todo-section"><h2>To Do</h2><div class="todo-columns"><section v-for="group in taskGroups" :key="group.label"><h3>{{ group.label }}</h3><ul><li v-for="task in group.tasks" :key="task.label" :class="task.state"><span class="task-dot" aria-hidden="true"></span><RouterLink :to="task.path" class="task-label">{{ task.label }}</RouterLink><strong v-if="task.count" class="task-count">{{ task.count }}</strong><RouterLink :to="task.path" class="task-arrow" :aria-label="`${task.label} 화면 열기`">→</RouterLink></li></ul></section></div></div>
    </section>

    <div class="period-toolbar"><p>전사 기준 · 2026년 10월 <span>단위 억원</span></p><div class="period-buttons" role="group" aria-label="손익 보기 기간"><button :class="{ selected: period === 'month' }" :aria-pressed="period === 'month'" @click="period = 'month'">{{ month }}월</button><button :class="{ selected: period === 'year' }" :aria-pressed="period === 'year'" @click="period = 'year'">년</button></div></div>
    <p v-if="selectionNote" class="filter-note" role="status">{{ selectionNote }}</p>

    <section class="kpi-grid" aria-label="전사 핵심 손익 예시" data-figma-node="167:242">
      <article v-for="kpi in kpis" :key="kpi.label" class="dashboard-card kpi-card">
        <div class="kpi-heading"><h2>{{ kpi.label }}</h2><span v-if="kpi.rate" class="margin-pill">이익률 {{ kpi.rate }}</span></div>
        <p class="kpi-amount"><strong>{{ kpi.amount }}</strong><span>억원</span></p>
        <dl class="kpi-comparison"><div><dt>계획 대비</dt><dd :class="deltaTone(kpi.plan)">{{ kpi.plan }}</dd></div><div><dt>전월 대비</dt><dd :class="deltaTone(kpi.previous)">{{ kpi.previous }}</dd></div><div><dt>전년 동월 대비</dt><dd :class="deltaTone(kpi.lastYear)">{{ kpi.lastYear }}</dd></div></dl>
      </article>
    </section>

    <div class="summary-driver-grid">
      <section class="dashboard-card summary-card" aria-labelledby="summary-title" data-figma-node="168:2031">
        <div class="card-heading"><div><h2 id="summary-title">전사 손익 요약</h2><p class="card-description">전사 기준 · 2026년 10월</p></div><span class="unit-label">단위 억원</span></div>
        <div class="dashboard-table-scroll"><table class="summary-table"><caption class="visually-hidden">Figma 예시 손익 항목별 당월 실적과 계획 및 전월 추정 대비</caption><thead><tr><th scope="col">손익 항목</th><th scope="col">당월 실적</th><th scope="col">계획대비</th><th scope="col">전월 추정대비</th></tr></thead><tbody><template v-for="row in summaryRows" :key="row.label"><tr v-if="row.kind !== 'detail' || costsExpanded" :class="row.kind"><th scope="row"><button v-if="row.kind === 'cost'" class="cost-toggle" :aria-expanded="costsExpanded" @click="costsExpanded = !costsExpanded"><span aria-hidden="true">{{ costsExpanded ? '▼' : '▶' }}</span> {{ row.label }}</button><template v-else>{{ row.label }}</template></th><td>{{ row.amount }}</td><td :class="deltaTone(row.plan)"><span v-if="row.plan">{{ row.plan }}</span><span v-else class="missing-example" aria-label="Figma 예시값 없음">—</span></td><td :class="deltaTone(row.previous)">{{ row.previous }}</td></tr></template></tbody></table></div>
      </section>
      <section class="dashboard-card drivers-card" aria-labelledby="driver-title" data-figma-node="167:372">
        <h2 id="driver-title">영업이익 변동 원인 Top 5</h2><p class="driver-total"><span>계획 대비</span><strong>−91</strong><small>억원</small></p>
        <ul class="driver-list"><li v-for="driver in drivers" :key="driver.label"><RouterLink to="/planning/gap" :aria-label="`${driver.label} 관련 Gap 검토 화면 열기`"><div class="driver-label"><span><strong>{{ driver.label }}</strong><small>{{ driver.organization }}</small></span><strong :class="deltaTone(driver.gap)">{{ driver.gap }}</strong></div><div class="driver-track"><span :class="deltaTone(driver.gap)" :style="{ width: `${driver.width / 390 * 100}%` }"></span></div></RouterLink></li></ul>
      </section>
    </div>

      <section class="dashboard-card division-card" aria-labelledby="division-title" data-figma-node="167:439">
        <div class="card-heading"><h2 id="division-title">사업부별 손익</h2><span v-if="organization !== 'current'" class="selected-organization">{{ organization }}</span></div>
        <div class="dashboard-table-scroll"><table class="division-table"><caption class="visually-hidden">Figma 예시 사업부별 매출, 영업이익, 전사 영업이익 기여도, 이익률, 계획 대비</caption><thead><tr><th scope="col">사업부</th><th scope="col">매출</th><th scope="col">영업이익</th><th scope="col">전사 영업이익 기여도</th><th scope="col">이익률</th><th scope="col">계획 대비</th></tr></thead><tbody><tr v-for="department in visibleDepartments" :key="department.name"><th scope="row">{{ department.name }}</th><td>{{ department.revenue }}</td><td><strong>{{ department.profit }}</strong></td><td><div class="contribution"><span class="contribution-track"><i :style="{ width: `${department.width}px` }"></i></span><span>{{ department.contribution }}</span></div></td><td>{{ department.margin }}</td><td :class="deltaTone(department.gap)"><strong>{{ department.gap }}</strong></td></tr></tbody><tfoot><tr><th scope="row">전사 합계</th><td>15,203</td><td>1,482</td><td class="head-office">본사·기타 +44 포함</td><td>9.7%</td><td class="negative">−91</td></tr></tfoot></table></div>
      </section>

    <section class="dashboard-card waterfall-card" aria-labelledby="waterfall-title" data-figma-node="167:318"><h2 id="waterfall-title">손익 흐름</h2><p class="card-description">매출에서 순이익까지 단계별로 깎이는 금액</p><div class="waterfall-scroll" tabindex="0" role="region" aria-label="매출부터 순이익까지의 손익 흐름 예시, 가로 스크롤 가능"><div class="waterfall-plot"><div class="waterfall-baseline"></div><div v-for="item in waterfall" :key="item.label" :class="['waterfall-item', item.kind]"><strong class="waterfall-value" :style="{ left: `${item.left - 13}px`, top: `${item.top - 20}px` }">{{ item.value }}</strong><span class="waterfall-bar" :style="{ left: `${item.left}px`, top: `${item.top}px`, height: `${item.height}px` }"></span><img v-if="item.connectorTop !== null" class="waterfall-connector" src="/assets/planner-waterfall-connector.svg" alt="" :style="{ left: `${item.left + 44}px`, top: `${item.connectorTop}px` }" /><span class="waterfall-label" :style="{ left: `${item.left - 13}px` }">{{ item.label }}<small>{{ item.note }}</small></span></div></div></div><ul class="chart-legend waterfall-legend"><li><i class="profit-key"></i>이익 단계</li><li><i class="deduction-key"></i>비용 차감</li></ul></section>

      <section class="dashboard-card trend-card" aria-labelledby="trend-title" data-figma-node="168:2097">
        <div class="trend-heading"><div><h2 id="trend-title">월별 {{ metric === 'profit' ? '영업이익' : '매출' }} 추이</h2><p class="card-description">실적(1~10월) · 최신 추정(11~12월) vs 연간 계획</p></div><div class="trend-controls"><ul class="chart-legend"><li><i class="actual-key"></i>실적</li><li><i class="estimate-key"></i>추정</li><li><i class="plan-key"></i>계획</li></ul><div class="metric-toggle" role="group" aria-label="추이 항목"><button :class="{ active: metric === 'profit' }" :aria-pressed="metric === 'profit'" @click="metric = 'profit'">영업이익</button><button :class="{ active: metric === 'revenue' }" :aria-pressed="metric === 'revenue'" @click="metric = 'revenue'">매출</button></div></div></div>
        <div v-if="metric === 'profit'" class="chart-scroll" tabindex="0" role="region" aria-label="월별 영업이익 예시 차트, 가로 스크롤 가능"><div class="trend-plot"><div class="trend-baseline"></div><div v-for="(bar, index) in trendBars" :key="bar.month" class="trend-month" :style="{ left: `${index * 63.5}px` }"><div :class="['trend-bar', { estimate: bar.month > 10, selected: month === bar.month }]" :style="{ height: `${bar.height}px` }"></div><button :class="['month-label', { selected: month === bar.month }]" :aria-pressed="month === bar.month" @click="month = bar.month">{{ bar.month }}월</button></div><img class="trend-plan-line" src="/assets/planner-trend-line.svg" alt="" /><img v-for="(bar, index) in trendBars" :key="`point-${bar.month}`" class="trend-plan-point" src="/assets/planner-trend-point.svg" alt="" :style="{ left: `${28.75 + index * 63.5}px`, top: `${bar.pointTop}px` }" /></div></div>
        <div v-else class="unavailable-chart" role="status"><strong>매출 추이 예시 데이터가 없습니다</strong><p>Figma에서 제공된 월별 영업이익 차트를 확인할 수 있습니다.</p><button class="chart-return" @click="metric = 'profit'">영업이익 추이 보기</button></div>
      </section>
  </div>
</template>

<style scoped>
.planner-dashboard { display:flex; flex-direction:column; gap:20px; color:var(--ink); }
.dashboard-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; margin-bottom:6px; }
.dashboard-eyebrow { color:var(--muted); font-size:11px; margin-bottom:4px; }
.design-example { margin-top:8px; color:#837c70; font-size:11px; }
.dashboard-filters { display:flex; flex-wrap:wrap; align-items:center; justify-content:flex-end; gap:8px; padding-top:8px; }
.filter-control { display:flex; gap:7px; align-items:center; height:37px; padding:0 10px; border:1px solid var(--line); border-radius:8px; background:#fff; white-space:nowrap; font-size:12px; }
.filter-control>span { color:#9ea2a9; }
.filter-control select { width:auto; min-width:44px; max-width:150px; height:100%; min-height:0; margin:0; border:0; border-radius:0; color:var(--ink); padding:0 13px 0 0; font-size:12px; background:transparent; }
.report-button { display:flex; align-items:center; justify-content:center; min-height:37px; padding:0 14px; border-radius:8px; color:#fff; background:var(--sidebar); font-size:12px; }
.dashboard-card { min-width:0; background:#fff; border:1px solid var(--line); border-radius:14px; }
.kpi-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:14px; }
.kpi-card { padding:20px 22px; }
.kpi-heading,.card-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.kpi-heading h2 { font-size:14px; color:#4a4844; }
.margin-pill { padding:2px 9px; border-radius:20px; font-size:11px; background:#f0eeea; white-space:nowrap; }
.kpi-amount { display:flex; align-items:baseline; gap:4px; margin:12px 0; }
.kpi-amount strong { font-size:32px; letter-spacing:-.6px; line-height:1.3; }
.kpi-amount>span { font-size:13px; color:#6b6964; }
.kpi-comparison { display:flex; flex-direction:column; gap:5px; border-top:1px solid #eeebe4; padding-top:10px; margin:0; font-size:12px; }
.kpi-comparison>div { display:flex; justify-content:space-between; gap:12px; }
.kpi-comparison dt { color:#6b6964; }
.kpi-comparison dd { margin:0; font-weight:700; }
.negative { color:#2457c5; }
.positive { color:#b42318; }
.neutral { color:var(--ink); }
.closing-card { display:grid; grid-template-columns:minmax(330px,.9fr) 1px minmax(480px,1.3fr); gap:24px; padding:22px 25px; }
.closing-status { min-width:0; }
.deadline-pill { padding:4px 10px; border-radius:20px; background:#fbe9e7; color:#d53a32; font-size:10px; white-space:nowrap; }
.closing-steps { list-style:none; margin:35px 0 20px; padding:0; display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:9px; }
.closing-steps a { display:flex; flex-direction:column; align-items:center; text-align:center; gap:8px; }
.stage-circle { width:24px; height:24px; border:1px solid #ccc9c1; border-radius:50%; display:grid; place-items:center; color:#fff; font-size:13px; }
.closing-steps strong { font-size:11px; font-weight:400; white-space:nowrap; }
.closing-steps small { color:#9ea2a9; font-size:10px; }
.complete .stage-circle { border-color:var(--sidebar); background:var(--sidebar); }
.active .stage-circle { border-color:var(--gold); background:var(--gold); }
.closing-steps .active small { color:var(--gold); }
.closing-steps .waiting strong { color:#9ea2a9; }
.closing-progress { height:6px; border-radius:10px; background:#efeeea; overflow:hidden; }
.closing-progress>span { display:block; width:69.767%; height:100%; border-radius:10px; background:var(--gold); }
.closing-divider { display:block; flex:none; align-self:start; }
.todo-section { min-width:0; }
.todo-columns { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:26px; margin-top:8px; }
.todo-columns h3 { margin:0 0 18px 10px; }
.todo-columns ul { list-style:none; margin:0; padding:0; display:flex; flex-direction:column; gap:14px; }
.todo-columns li { display:flex; align-items:center; gap:8px; min-height:27px; }
.task-dot { flex:none; width:5px; height:5px; border-radius:50%; background:#c6821c; }
.urgent .task-dot { background:#d9463a; }
.task-label { font-size:11px; flex:1; line-height:1.55; }
.task-label:hover { text-decoration:underline; }
.task-count { font-size:11px; color:#d53a32; white-space:nowrap; }
.task-arrow { display:grid; place-items:center; flex:none; width:33px; height:27px; border-radius:8px; background:var(--sidebar); color:#fff; font-size:15px; }
.period-toolbar { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.period-toolbar>p { color:var(--muted); font-size:12px; }
.period-toolbar>p>span { margin-left:12px; color:#9ea2a9; font-size:11px; }
.period-buttons { display:flex; gap:8px; }
.period-buttons button { min-width:79px; min-height:37px; padding:8px 18px; border:1px solid var(--line); border-radius:8px; background:#fff; font-size:13px; }
.period-buttons .selected { background:#f3b9b9; }
.filter-note { margin-top:-10px; padding:10px 12px; border-radius:8px; background:#eeece6; color:#6d6455; font-size:11px; line-height:1.7; }
.summary-driver-grid { display:grid; grid-template-columns:minmax(0,1.15fr) minmax(290px,1fr); gap:18px; align-items:start; }
.division-card,.drivers-card { padding:22px 24px; }
.selected-organization { color:var(--muted); font-size:11px; }
.dashboard-table-scroll { width:100%; overflow-x:auto; }
.division-table { width:100%; min-width:640px; border-spacing:0; border-collapse:separate; margin-top:14px; border:1px solid #eeebe4; border-radius:10px; overflow:hidden; table-layout:auto; font-size:12px; }
.planner-dashboard table th,.planner-dashboard table td { border:0; border-bottom:1px solid var(--line); background:transparent; padding:10px 7px; text-align:right; white-space:nowrap; }
.planner-dashboard table th:first-child { text-align:left; }
.planner-dashboard table thead th { background:#f8f6f1; font-size:11px; color:#4a4844; font-weight:400; }
.division-table thead th:first-child,.division-table tbody th,.division-table tfoot th { padding-left:14px; }
.division-table tbody th { font-size:12px; font-weight:700; color:var(--ink); }
.division-table tfoot th,.division-table tfoot td { border-bottom:0; background:#faf8f4; font-weight:700; }
.division-table .head-office { color:#6b6964; font-weight:400; text-align:left; }
.contribution { display:flex; gap:8px; align-items:center; justify-content:flex-start; font-size:11px; color:#4a4844; }
.contribution-track { position:relative; display:block; width:90px; height:8px; border-radius:6px; background:#f0eeea; }
.contribution-track i { display:block; height:8px; border-radius:6px; background:var(--gold); }
.driver-total { display:flex; align-items:baseline; gap:6px; margin:12px 0 16px; font-size:24px; font-weight:700; }
.driver-total>span { color:#fc0; }
.driver-total>strong { color:#2457c5; }
.driver-total>small { margin-left:2px; font-size:12px; font-weight:400; color:#6b6964; }
.driver-list { display:flex; flex-direction:column; gap:12px; margin:0; padding:0; list-style:none; }
.driver-label { display:flex; justify-content:space-between; align-items:baseline; gap:6px; font-size:12px; }
.driver-label>span { display:flex; align-items:baseline; flex-wrap:wrap; gap:6px; }
.driver-label small { color:#6b6964; }
.driver-track { height:8px; border-radius:6px; margin-top:5px; background:#f0eeea; overflow:hidden; }
.driver-track>span { display:block; height:8px; border-radius:6px; }
.driver-track>.negative { background:#2457c5; }
.driver-track>.positive { background:#b42318; }
.summary-card { padding:20px; }
.card-description { margin-top:4px; color:#9ea2a9; font-size:12px; line-height:1.55; }
.unit-label { align-self:flex-end; color:#9ea2a9; font-size:10px; white-space:nowrap; }
.summary-table { width:100%; min-width:470px; border-spacing:0; border-collapse:separate; margin-top:14px; border:1px solid var(--line); font-size:13px; }
.summary-table th:first-child { width:36%; }
.summary-table .main th,.summary-table .cost th,.summary-table .total th { color:var(--ink); font-size:13px; font-weight:700; }
.summary-table thead th { background:#faf9f6; }
.summary-table .detail th { padding-left:20px; color:var(--muted); font-weight:400; }
.summary-table .detail td,.summary-table .detail th { background:#fcfbf9; }
.summary-table .total th,.summary-table .total td { background:#faf7f0; }
.summary-table .total td:nth-child(2) { font-weight:700; }
.summary-table .ratio th { color:var(--muted); font-weight:400; }
.summary-table tr:last-child th,.summary-table tr:last-child td { border-bottom:0; }
.cost-toggle { padding:0; background:transparent; font:inherit; font-weight:700; color:var(--ink); }
.missing-example { color:#9ea2a9; }
.trend-card { padding:20px; min-height:310px; }
.trend-heading { display:flex; justify-content:space-between; align-items:center; gap:12px; }
.trend-controls { display:flex; align-items:center; flex-wrap:wrap; justify-content:flex-end; gap:14px; }
.chart-legend { list-style:none; margin:0; padding:0; display:flex; align-items:center; gap:12px; }
.chart-legend li { display:flex; align-items:center; gap:4px; color:var(--muted); font-size:11px; white-space:nowrap; }
.chart-legend i { display:inline-block; flex:none; width:10px; height:10px; border-radius:2px; }
.actual-key { background:#c9ccd3; }
.estimate-key { border:1px solid #5f636b; }
.chart-legend .plan-key { width:14px; height:2px; background:var(--gold); border-radius:0; }
.metric-toggle { display:flex; padding:3px; gap:2px; border-radius:8px; background:#efeeea; }
.metric-toggle button { border-radius:6px; padding:4px 9px; background:transparent; color:#9ea2a9; font-size:11px; }
.metric-toggle .active { background:#fff; color:var(--ink); }
.chart-scroll { overflow-x:auto; margin-top:45px; }
.trend-plot { position:relative; width:762px; height:173px; margin:0 auto; }
.trend-baseline { position:absolute; top:150.692px; left:0; width:762px; height:1px; background:var(--line); }
.trend-month { position:absolute; top:0; width:63.5px; height:173px; }
.trend-bar { position:absolute; left:18.75px; bottom:22.308px; width:26px; border-radius:4px 4px 0 0; background:#c9ccd3; }
.trend-bar.selected { background:var(--sidebar); }
.trend-bar.estimate { background:#fff; border:1.2px dashed #5f636b; }
.trend-bar.estimate.selected { border-color:var(--sidebar); background:#eeece6; }
.month-label { position:absolute; bottom:0; left:0; width:63.5px; padding:2px 0; background:transparent; color:#9ea2a9; font-size:11px; text-align:center; }
.month-label.selected { color:var(--ink); font-weight:700; }
.trend-plan-line { position:absolute; left:31.55px; top:2.057px; pointer-events:none; }
.trend-plan-point { position:absolute; pointer-events:none; }
.unavailable-chart { min-height:214px; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; color:var(--muted); text-align:center; font-size:12px; }
.unavailable-chart strong { color:var(--ink); font-size:14px; }
.chart-return { margin-top:4px; padding:8px 12px; background:#eeece6; border-radius:7px; color:var(--ink); font-size:12px; }
.waterfall-card { padding:22px 24px; }
.waterfall-card .card-description { color:#6b6964; }
.waterfall-scroll { overflow-x:auto; margin-top:14px; }
.waterfall-plot { position:relative; width:640px; height:262px; }
.waterfall-baseline { position:absolute; top:210px; left:0; width:640px; height:1px; background:#e6e2d9; }
.waterfall-value { position:absolute; width:70px; font-size:12px; text-align:center; white-space:nowrap; }
.waterfall-item.deduction .waterfall-value { font-weight:400; }
.waterfall-bar { position:absolute; width:44px; border-radius:2px; background:#17181c; }
.waterfall-item.revenue .waterfall-bar { background:#6b6964; }
.waterfall-item.deduction .waterfall-bar { background:#c9a57a; }
.waterfall-connector { position:absolute; }
.waterfall-label { position:absolute; top:218px; width:70px; text-align:center; white-space:nowrap; font-size:12px; font-weight:700; }
.waterfall-item.deduction .waterfall-label { font-size:11px; font-weight:400; }
.waterfall-label small { display:block; margin-top:6px; color:#6b6964; font-size:10px; font-weight:400; }
.waterfall-legend { margin-top:14px; }
.chart-legend .profit-key { background:#17181c; border-radius:0; }
.chart-legend .deduction-key { background:#c9a57a; border-radius:0; }
@media (max-width:1300px) {
  .dashboard-heading { flex-wrap:wrap; }
  .dashboard-filters { padding-top:0; justify-content:flex-start; }
  .closing-card { grid-template-columns:minmax(0,1fr); gap:24px; }
  .closing-divider { display:none; }
  .closing-status { max-width:560px; }
  .closing-steps { margin-top:24px; }
  .todo-section { border-top:1px solid var(--line); padding-top:20px; }
  .summary-driver-grid { grid-template-columns:minmax(0,1fr); }
  .drivers-card { max-width:520px; width:100%; }
}
@media (max-width:760px) {
  .kpi-grid { grid-template-columns:minmax(0,1fr); }
  .kpi-card { padding:18px 20px; }
  .closing-card { padding:20px; }
  .todo-columns { grid-template-columns:minmax(0,1fr); gap:24px; }
  .todo-columns h3 { margin-bottom:12px; }
  .todo-columns ul { gap:9px; }
  .period-toolbar>p>span { display:block; margin:4px 0 0; }
  .division-card,.drivers-card,.summary-card,.trend-card,.waterfall-card { padding:18px; }
  .trend-heading { align-items:flex-start; flex-wrap:wrap; }
  .trend-controls { justify-content:flex-start; }
  .chart-scroll { margin-top:30px; }
}
</style>
