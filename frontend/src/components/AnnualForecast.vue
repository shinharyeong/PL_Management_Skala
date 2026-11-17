<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useSession } from '../stores/session'
import { errorText } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'

const props = defineProps({ requestId: { type: String, required: true } })
const session = useSession()
const forecast = ref(null)
const loading = ref(false)
const error = ref('')
const organization = ref('')
const unit = ref('KRW')
const metrics = [{ key: 'revenue', name: '매출' }, { key: 'cost', name: '영업비용' }, { key: 'operatingProfit', name: '영업이익' }]
let controller = new AbortController()
let generation = 0

/** 전사 또는 실제 조직의 연간·월별 전망을 선택한다.
 * Args: 없음. Returns: 서버 전망 DTO/null. Raises: 없음. Side Effects: 없음.
 */
const selected = computed(function selectScope() {
  if (!organization.value) return forecast.value
  for (const item of forecast.value?.organizations || []) if (String(item.organizationId) === organization.value) return item
  return null
})

/** 선택 범위에 해당하는 실제 승인 원천만 반환한다.
 * Args: 없음. Returns: 승인 원천 배열. Raises: 없음. Side Effects: 없음.
 */
const sources = computed(function selectSources() {
  const result = []
  for (const source of forecast.value?.sources || []) if (!organization.value || String(source.organizationId) === organization.value) result.push(source)
  return result
})

/** 요청 시점의 최신 승인본으로 연간 전망을 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: GET·상태 교체; 이전 응답은 무시하며 공식 월 마스터를 변경하지 않는다.
 */
async function load() {
  if (session.actor?.role !== 'PLANNER') return
  controller.abort(); controller = new AbortController()
  const current = ++generation
  loading.value = true; error.value = ''; forecast.value = null
  try {
    const result = await session.request(`/api/v1/submission-requests/${props.requestId}/forecast`, { signal: controller.signal })
    if (current !== generation || controller.signal.aborted) return
    forecast.value = result
    if (organization.value && !selected.value) organization.value = ''
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation && !controller.signal.aborted) loading.value = false }
}

/** 서버 금액 문자열을 선택 단위로 표시하고 미집계를 0과 구분한다.
 * Args: value 금액 문자열/null. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function money(value) { return formatMoney(value, unit.value) }

/** 최신 승인 전망의 원본과 확정 계획 원본으로 이동한다.
 * Args: source 승인 원천 DTO, plan 계획 원천 여부. Returns: 상세 라우트. Raises: 없음. Side Effects: 없음.
 */
function sourceLink(source, plan = false) { return { path: `/submissions/${plan ? source.planSubmissionId : source.submissionId}`, query: { from: 'gap', requestId: props.requestId, organizationId: source.organizationId } } }

/** 이탈한 요청의 늦은 응답을 폐기한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: GET 취소·세대 무효화.
 */
function dispose() { generation++; controller.abort() }
onBeforeUnmount(dispose)
</script>

<template>
  <section v-if="session.actor?.role === 'PLANNER'" class="panel annual-forecast">
    <div class="section-heading"><h2>승인본 기준 연간 전망</h2><button class="button" :disabled="loading" @click="load">{{ loading ? '조회 중…' : forecast ? '최신 승인본 새로고침' : '연간 전망 조회' }}</button></div>
    <p class="muted">이 월간 요청에서 조직별 최신 승인본의 마감월까지 실적과 이후 추정을 합칩니다. 새 승인에 따라 바뀌는 전망이며, 발행된 실적 마스터의 고정 금액과 구분합니다.</p>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="loading" class="notice" role="status">승인 원천과 확정 계획을 읽는 중…</p>
    <template v-if="forecast">
      <p class="muted">{{ forecast.year }}년 · {{ forecast.asOfMonth }}월 마감 요청 · 승인 {{ forecast.approvedOrganizationCount }}/{{ forecast.requiredOrganizationCount }}조직</p>
      <div v-if="!forecast.ready" class="notice" role="status">전사 전망은 필수 조직 승인이 모이면 집계합니다. 미승인 조직을 0으로 더하지 않습니다.<ul><li v-for="item in forecast.missingOrganizations" :key="item.organizationId">{{ item.organizationName }} · {{ item.organizationCode }}</li></ul></div>
      <div class="filters"><label>조회 범위<select v-model="organization"><option value="">전사</option><option v-for="item in forecast.organizations" :key="item.organizationId" :value="String(item.organizationId)">{{ item.organizationName }}{{ item.ready ? '' : ' · 승인 대기' }}</option></select></label><label>금액 단위<select v-model="unit"><option value="KRW">원</option><option value="HUNDRED_MILLION">억원</option></select></label></div>
      <div class="table-scroll"><table><caption>연간 합계 · 단위 {{ unit === 'KRW' ? '원' : '억원' }}</caption><thead><tr><th>항목</th><th class="number">확정 계획</th><th class="number">실적 + 추정</th><th class="number">전망 − 계획</th></tr></thead><tbody><tr v-for="metric in metrics" :key="metric.key"><th scope="row">{{ metric.name }}</th><td class="number">{{ money(selected?.planTotals?.[metric.key]) }}</td><td class="number">{{ money(selected?.totals?.[metric.key]) }}</td><td class="number">{{ money(selected?.comparisons?.[metric.key]?.gapAmount) }}</td></tr></tbody></table></div>
      <div class="table-scroll"><table><caption>월별 구성</caption><thead><tr><th>월</th><th>구분</th><th class="number">매출</th><th class="number">영업비용</th><th class="number">영업이익</th><th class="number">계획 영업이익</th><th class="number">손익 차액</th></tr></thead><tbody><tr v-for="item in selected?.months || []" :key="item.month"><th scope="row">{{ item.month }}월</th><td>{{ item.scenario === 'ACTUAL' ? '실적' : '추정' }}</td><td class="number">{{ money(item.totals?.revenue) }}</td><td class="number">{{ money(item.totals?.cost) }}</td><td class="number">{{ money(item.totals?.operatingProfit) }}</td><td class="number">{{ money(item.planTotals?.operatingProfit) }}</td><td class="number">{{ money(item.comparisons?.operatingProfit?.gapAmount) }}</td></tr></tbody></table></div>
      <p v-if="forecast.annualContingency" class="muted">전사 연간 Contingency {{ formatMoney(forecast.annualContingency.amount) }}원 · 전망에서 자동 차감하지 않습니다.</p>
      <details class="forecast-sources"><summary>승인 원천 {{ sources.length }}건 확인</summary><ul><li v-for="source in sources" :key="source.organizationId"><span>{{ source.organizationName }} · {{ source.versionLabel }}</span><RouterLink :to="sourceLink(source)" class="text-button">승인본</RouterLink><RouterLink v-if="source.planSubmissionId" :to="sourceLink(source, true)" class="text-button">확정 계획본</RouterLink></li></ul></details>
    </template>
  </section>
</template>

<style scoped>
.annual-forecast>p { margin:14px 0; line-height:1.7; }.annual-forecast .filters { margin:20px 0; }.annual-forecast .table-scroll+.table-scroll { margin-top:20px; }.annual-forecast caption { text-align:left; padding:10px 0; font-weight:600; }.forecast-sources { margin-top:20px; }.forecast-sources summary { cursor:pointer; }.forecast-sources li { display:flex; flex-wrap:wrap; gap:14px; padding:8px 0; }.forecast-sources ul { list-style:none; padding:0; }
</style>
