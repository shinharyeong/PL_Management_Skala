<script setup>
import { computed, ref } from 'vue'
import { dateTime } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'

const props = defineProps({ master: { type: Object, required: true } })
const organization = ref('')
const unit = ref('KRW')
const metrics = [{ key: 'revenue', name: '매출' }, { key: 'cost', name: '영업비용' }, { key: 'operatingProfit', name: '영업이익' }]

/** 선택한 조직 또는 전사 마스터의 실제·확정계획 비교를 반환한다.
 * Args: 없음. Returns: 선택 DTO/null. Raises: 없음. Side Effects: 없음.
 */
const selected = computed(function selectOrganization() { if (!organization.value) return props.master; for (const item of props.master.organizations || []) if (String(item.organizationId) === organization.value) return item; return null })

/** 현재 조직 선택과 일치하는 고정 원천 목록만 표시한다.
 * Args: 없음. Returns: 조직 배열. Raises: 없음. Side Effects: 없음.
 */
const organizations = computed(function visibleOrganizations() { const result = []; for (const item of props.master.organizations || []) if (!organization.value || String(item.organizationId) === organization.value) result.push(item); return result })

/** 금액을 선택 단위의 정밀 문자열로 표시한다.
 * Args: value 서버 금액. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function money(value) { return formatMoney(value, unit.value) }

/** 분모 0인 비율을 숫자 0과 구분한다.
 * Args: value nullable 비율. Returns: % 표시/—. Raises: 없음. Side Effects: 없음.
 */
function rate(value) { return value === null || value === undefined ? '—' : `${value}%` }

/** 마스터에 고정된 실적본 또는 확정 계획본의 원본 조회 링크를 만든다.
 * Args: item 조직 DTO, plan 계획본 여부. Returns: 라우트 객체. Raises: 없음. Side Effects: 없음.
 */
function sourceLink(item, plan = false) { return { path: `/submissions/${plan ? item.planSubmissionId : item.submissionId}`, query: { from: 'monthly-master', masterId: props.master.id, requestId: props.master.requestId, organizationId: item.organizationId } } }

/** 조직·단위 선택을 전사·원으로 초기화한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 화면 선택만 변경.
 */
function reset() { organization.value = ''; unit.value = 'KRW' }
</script>

<template>
  <div class="monthly-master-data">
    <header class="page-heading"><div><p class="overline">ACTUAL · 확정 계획과 비교</p><h2>{{ master.year }}년 {{ master.month }}월 · {{ master.versionLabel }}</h2><p class="muted">{{ dateTime(master.createdAt) }} 발행 · 원본 상세 합계 기준</p></div><RouterLink :to="`/planning/masters/${master.id}`" class="button">발행 버전·항목 확인 →</RouterLink></header>
    <div class="monthly-filters"><label>범위<select v-model="organization"><option value="">전사</option><option v-for="item in master.organizations" :key="item.organizationId" :value="String(item.organizationId)">{{ item.organizationName }} · {{ item.organizationCode }}</option></select></label><label>단위<select v-model="unit"><option value="KRW">원</option><option value="HUNDRED_MILLION">억원</option></select></label><button class="button" @click="reset">선택 해제</button></div>
    <div class="actual-kpis"><article v-for="metric in metrics" :key="metric.key" class="panel"><h3>{{ metric.name }} 실적</h3><p class="actual-amount">{{ money(selected?.totals?.[metric.key]) }} <small>{{ unit === 'KRW' ? '원' : '억원' }}</small></p><dl><div><dt>확정 계획</dt><dd>{{ money(selected?.planTotals?.[metric.key]) }}</dd></div><div><dt>실적 − 계획</dt><dd>{{ money(selected?.comparisons?.[metric.key]?.gapAmount) }}</dd></div><div><dt>계획 대비 차이율</dt><dd>{{ rate(selected?.comparisons?.[metric.key]?.gapRate) }}</dd></div></dl></article></div>
    <div class="monthly-summary"><section class="panel"><div class="section-heading"><h3>선택 범위 손익 비교</h3><span class="muted">단위 {{ unit === 'KRW' ? '원' : '억원' }}</span></div><div class="table-scroll"><table><thead><tr><th>항목</th><th class="number">확정 계획</th><th class="number">실적</th><th class="number">차액</th><th>차이율</th></tr></thead><tbody><tr v-for="metric in metrics" :key="metric.key"><th scope="row">{{ metric.name }}</th><td class="number">{{ money(selected?.planTotals?.[metric.key]) }}</td><td class="number">{{ money(selected?.totals?.[metric.key]) }}</td><td class="number">{{ money(selected?.comparisons?.[metric.key]?.gapAmount) }}</td><td>{{ rate(selected?.comparisons?.[metric.key]?.gapRate) }}</td></tr><tr><th scope="row">영업이익률</th><td class="number">{{ rate(selected?.planTotals?.operatingMargin) }}</td><td class="number">{{ rate(selected?.totals?.operatingMargin) }}</td><td>—</td><td>—</td></tr></tbody></table></div></section><aside class="panel"><h3>집계 근거</h3><p class="muted">이 버전에 고정된 조직 승인본의 해당 월 ACTUAL과 최초 확정한 조직별 PLAN만 비교합니다. 후속 제출 검토 중에도 이 버전의 금액은 바뀌지 않습니다.</p><p class="muted">실적이 발행되지 않은 월은 금액을 0으로 채우지 않습니다. 계획이 0이면 차이율은 —로 표시합니다.</p><p v-if="master.annualContingency" class="muted">전사 연간 Contingency {{ formatMoney(master.annualContingency.amount) }}원 · 월별 배분 또는 손익 자동 차감 없음</p><RouterLink :to="{ path: '/planning/gap', query: { requestId: master.requestId } }" class="text-button">해당 월 제출·소명 검토 →</RouterLink></aside></div>
    <section class="panel"><div class="section-heading"><h3>사업조직별 실적·계획</h3><span class="muted">{{ organizations.length }}곳 · 단위 {{ unit === 'KRW' ? '원' : '억원' }}</span></div><div class="table-scroll"><table><thead><tr><th>조직</th><th class="number">매출 실적</th><th class="number">비용 실적</th><th class="number">영업이익 계획</th><th class="number">영업이익 실적</th><th class="number">손익 차액</th><th>고정 원천</th></tr></thead><tbody><tr v-for="item in organizations" :key="item.organizationId"><th scope="row"><button class="text-button" @click="organization = String(item.organizationId)">{{ item.organizationName }}</button><small>{{ item.organizationCode }}</small></th><td class="number">{{ money(item.totals?.revenue) }}</td><td class="number">{{ money(item.totals?.cost) }}</td><td class="number">{{ money(item.planTotals?.operatingProfit) }}</td><td class="number">{{ money(item.totals?.operatingProfit) }}</td><td class="number">{{ money(item.comparisons?.operatingProfit?.gapAmount) }}</td><td><RouterLink :to="sourceLink(item)" class="text-button">실적본</RouterLink><span> · </span><RouterLink v-if="item.planSubmissionId" :to="sourceLink(item, true)" class="text-button">확정 계획본</RouterLink></td></tr></tbody></table></div></section>
  </div>
</template>

<style scoped>
.monthly-filters { display:flex; align-items:flex-end; flex-wrap:wrap; gap:14px; margin:18px 0; }.monthly-filters label { min-width:130px; margin:0; }.monthly-filters select { margin-bottom:0; }.actual-kpis { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:15px; }.actual-kpis .panel { margin:0; }.actual-amount { font-size:clamp(18px,2vw,28px); font-weight:700; margin:18px 0; overflow-wrap:anywhere; font-variant-numeric:tabular-nums; }.actual-amount small { color:var(--muted); font-weight:400; }.actual-kpis dl { margin:0; padding-top:12px; border-top:1px solid var(--line); display:grid; gap:10px; font-size:12px; }.actual-kpis dl>div { display:flex; justify-content:space-between; gap:12px; }.actual-kpis dt { color:var(--muted); }.actual-kpis dd { margin:0; text-align:right; }.monthly-summary { display:grid; grid-template-columns:minmax(0,1.3fr) minmax(250px,.8fr); gap:16px; }.monthly-summary .panel { margin:20px 0; }.monthly-summary aside p { margin:14px 0; }.monthly-master-data th small { display:block; color:var(--muted); font-weight:400; margin-top:4px; }@media(max-width:900px) { .actual-kpis,.monthly-summary { grid-template-columns:1fr; } }
</style>
