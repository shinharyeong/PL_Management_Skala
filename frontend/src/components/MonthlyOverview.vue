<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSession } from '../stores/session'
import { errorText } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
import MonthlyMasterData from './MonthlyMasterData.vue'

const props = defineProps({ year: { type: Number, required: true } })
const session = useSession()
const periods = ref(null)
const month = ref(1)
const master = ref(null)
const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
let controller = new AbortController()
let detailController = new AbortController()
let generation = 0
let detailGeneration = 0

/** 선택 월의 발행 현황을 찾는다.
 * Args: 없음. Returns: 월 현황/null. Raises: 없음. Side Effects: 없음.
 */
const selectedPeriod = computed(function selectPeriod() { for (const item of periods.value?.items || []) if (item.month === month.value) return item; return null })

/** 연도별 12개월 상태를 읽고 최초에는 가장 최근 발행 월을 선택한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 이전 조회 취소·GET·마스터 선택.
 */
async function load() {
  controller.abort(); controller = new AbortController(); detailController.abort(); detailGeneration++
  const current = ++generation
  loading.value = true; error.value = ''; periods.value = null; master.value = null
  try {
    const result = await session.request(`/api/v1/master-periods?year=${props.year}`, { signal: controller.signal })
    if (current !== generation) return
    periods.value = result; month.value = 1
    for (const item of result.items) if (item.publicationState === 'PUBLISHED') month.value = Math.max(month.value, item.month)
    await loadMaster()
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}

/** 선택 월이 발행된 경우에만 해당 고정 마스터를 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 이전 상세 취소·GET·금액 초기화.
 */
async function loadMaster() {
  detailController.abort(); detailController = new AbortController()
  const current = ++detailGeneration
  master.value = null; error.value = ''; detailLoading.value = true
  try {
    if (selectedPeriod.value?.publicationState !== 'PUBLISHED') return
    const result = await session.request(`/api/v1/masters/${selectedPeriod.value.latestMasterId}`, { signal: detailController.signal })
    if (current === detailGeneration) master.value = result
  } catch (cause) { if (current === detailGeneration && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === detailGeneration) detailLoading.value = false }
}

/** 월 선택을 바꾸고 해당 발행 상태를 조회한다.
 * Args: value 월 번호. Returns: 없음. Raises: 없음. Side Effects: 월 변경·조회 시작.
 */
function chooseMonth(value) { month.value = value; loadMaster() }

/** watch에 현재 연도를 제공한다.
 * Args: 없음. Returns: 연도. Raises: 없음. Side Effects: 없음.
 */
function selectedYear() { return props.year }

/** 모든 읽기 요청을 취소하고 이전 응답을 무효화한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 증가.
 */
function dispose() { generation++; detailGeneration++; controller.abort(); detailController.abort() }
watch(selectedYear, load, { immediate: true })
onBeforeUnmount(dispose)
</script>

<template>
  <section class="monthly-overview">
    <div class="section-heading"><div><h2>{{ year }}년 월별 실적</h2><p class="muted">발행한 월간 마스터만 표시합니다. 미발행 월의 금액은 비어 있습니다.</p></div><button class="button" :disabled="loading || detailLoading" @click="load">새로고침</button></div>
    <div v-if="error" class="alert error" role="alert">{{ error }}</div><p v-if="loading" class="panel empty" role="status">월별 발행 현황을 불러오는 중…</p>
    <div v-if="periods" class="month-periods"><button v-for="item in periods.items" :key="item.month" :class="{ selected: month === item.month }" :aria-pressed="month === item.month" @click="chooseMonth(item.month)"><strong>{{ item.month }}월</strong><span>{{ ({ PUBLISHED: '발행 완료', WAITING_INITIAL: '승인 대기', NOT_REQUESTED: '요청 전' })[item.publicationState] }}</span></button></div>
    <p v-if="periods?.annualContingency" class="notice">전사 연간 Contingency {{ formatMoney(periods.annualContingency.amount) }}원 · 월별 배분이나 실적 영업이익에서 자동 차감하지 않습니다.</p>
    <p v-if="detailLoading && !loading" class="panel empty" role="status">선택 월 마스터를 불러오는 중…</p>
    <MonthlyMasterData v-if="master && !loading && !detailLoading && !error" :key="master.id" :master="master" />
    <section v-else-if="selectedPeriod && !loading && !detailLoading && !error" class="panel"><h3>{{ month }}월 {{ selectedPeriod.publicationState === 'NOT_REQUESTED' ? '실적 요청 전' : '첫 마스터 발행 대기' }}</h3><p class="notice">승인 {{ selectedPeriod.approvedOrganizationCount }}/{{ selectedPeriod.requiredOrganizationCount }}곳 · 실적 금액은 아직 발행되지 않았습니다.</p><ul v-if="selectedPeriod.missingOrganizations?.length"><li v-for="item in selectedPeriod.missingOrganizations" :key="item.id">{{ item.name }} · {{ item.code }} · {{ item.status }}</li></ul><RouterLink :to="{ path: '/planning/gap', query: selectedPeriod.requestId ? { requestId: selectedPeriod.requestId } : { year, month } }" class="button">{{ selectedPeriod.requestId ? '요청·소명 검토' : '월간 요청 생성' }} →</RouterLink></section>
  </section>
</template>

<style scoped>
.month-periods { display:grid; grid-template-columns:repeat(6,minmax(0,1fr)); gap:9px; margin:20px 0; }.month-periods button { background:white; border:1px solid var(--line); border-radius:9px; padding:14px; text-align:left; }.month-periods .selected { border-color:var(--gold); background:#faf7f0; }.month-periods strong { display:block; font-size:13px; }.month-periods span { display:block; margin-top:6px; color:var(--muted); font-size:11px; }@media(max-width:760px) { .month-periods { grid-template-columns:repeat(3,minmax(0,1fr)); } }
</style>
