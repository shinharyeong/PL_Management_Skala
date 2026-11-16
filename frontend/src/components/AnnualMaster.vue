<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { amount, dateTime, errorText, query } from '../lib/display'

const route = useRoute()
const router = useRouter()
const session = useSession()
const masterId = String(route.params.id)
const master = ref(null)
const loading = ref(false)
const error = ref('')
const versions = ref([])
const versionCursor = ref(null)
const versionsLoading = ref(false)
const versionsError = ref('')
const organizationId = ref('')
const month = ref('1')
const values = ref([])
const valueCursor = ref(null)
const valuesLoading = ref(false)
const valuesError = ref('')
const lifetime = new AbortController()
let controller = new AbortController()
let valuesController = new AbortController()
let generation = 0
let valuesGeneration = 0
let appliedFilters = { organizationId: '', month: '1' }

/** 현재 버전이 조회한 목록에 포함되어 중복 선택 항목이 필요 없는지 판정한다.
 * Args: 없음. Returns: 목록 포함 여부. Raises: 없음. Side Effects: 없음.
 */
const selectedVersionLoaded = computed(function hasSelectedVersion() {
  for (const version of versions.value) if (String(version.id) === masterId) return true
  return false
})

/** 경로의 고정 마스터를 읽은 뒤 같은 요청의 버전과 월별 보고 항목을 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(API 오류를 화면에 표시).
 * Side Effects: 이전 HTTP 취소·현재 마스터/목록 초기화·GET; 이전 세대 응답은 버린다.
 */
async function load() {
  controller.abort(); controller = new AbortController()
  valuesController.abort(); valuesGeneration++
  const current = ++generation
  master.value = null; error.value = ''; loading.value = true
  versions.value = []; versionCursor.value = null; versionsError.value = ''; versionsLoading.value = false
  values.value = []; valueCursor.value = null; valuesError.value = ''; valuesLoading.value = false
  try {
    const result = await session.request(`/api/v1/annual-masters/${masterId}`, { signal: controller.signal })
    if (current !== generation || lifetime.signal.aborted) return
    master.value = result
    await Promise.allSettled([loadVersions(false, current), loadValues(false)])
  } catch (cause) {
    if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause)
  } finally { if (current === generation && !lifetime.signal.aborted) loading.value = false }
}

/** 같은 연간 요청에서 발행한 버전을 cursor 순서로 조회한다.
 * Args: more 다음 페이지 여부, current 마스터 조회 세대. Returns: Promise<void>.
 * Raises: 없음(API 오류를 독립 영역에 표시). Side Effects: GET·버전 목록/오류 갱신.
 */
async function loadVersions(more = false, current = generation) {
  if (!master.value || versionsLoading.value || (more && !versionCursor.value)) return
  versionsLoading.value = true; versionsError.value = ''
  try {
    const page = await session.request(`/api/v1/annual-masters${query({ requestId: master.value.requestId, limit: 50, cursor: more ? versionCursor.value : null })}`, { signal: controller.signal })
    if (current !== generation || lifetime.signal.aborted) return
    versions.value = more ? [...versions.value, ...page.items] : page.items
    versionCursor.value = page.nextCursor
  } catch (cause) {
    if (current === generation && cause.name !== 'AbortError') versionsError.value = errorText(cause)
  } finally { if (current === generation && !lifetime.signal.aborted) versionsLoading.value = false }
}

/** 표시 중인 마스터의 조직·월 보고 항목을 조회하며 필터 변경 시 이전 응답을 무효화한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(API 오류를 화면에 표시).
 * Side Effects: 이전 보고 조회 취소·GET·필터/목록 갱신; 다음 페이지는 적용된 필터를 유지한다.
 */
async function loadValues(more = false) {
  if (!master.value || (more && (valuesLoading.value || !valueCursor.value))) return
  valuesController.abort(); valuesController = new AbortController()
  const current = ++valuesGeneration
  const masterGeneration = generation
  if (!more) {
    appliedFilters = { organizationId: organizationId.value, month: month.value }
    values.value = []; valueCursor.value = null
  }
  valuesLoading.value = true; valuesError.value = ''
  try {
    const page = await session.request(`/api/v1/annual-masters/${masterId}/report-values${query({ ...appliedFilters, limit: 100, cursor: more ? valueCursor.value : null })}`, { signal: valuesController.signal })
    if (current !== valuesGeneration || masterGeneration !== generation || lifetime.signal.aborted) return
    values.value = more ? [...values.value, ...page.items] : page.items
    valueCursor.value = page.nextCursor
  } catch (cause) {
    if (current === valuesGeneration && masterGeneration === generation && cause.name !== 'AbortError') valuesError.value = errorText(cause)
  } finally {
    if (current === valuesGeneration && masterGeneration === generation && !lifetime.signal.aborted) valuesLoading.value = false
  }
}

/** 서버가 반환한 버전 ID만 선택 이동에 사용한다.
 * Args: event 버전 select 변경 이벤트. Returns: Promise<void>. Raises: 없음(이동 오류 표시).
 * Side Effects: 선택한 마스터 경로로 이동; 현재 화면의 API 결과를 수정하지 않는다.
 */
async function changeVersion(event) {
  const id = event.target.value
  let available = id === masterId
  for (const version of versions.value) if (String(version.id) === id) available = true
  if (!available || id === masterId) return
  try { await router.push(`/planning/annual-masters/${id}`) }
  catch (cause) { versionsError.value = errorText(cause) }
}

/** 마스터에 고정된 조직 제출본의 원본·검증 내역으로 이동할 경로를 만든다.
 * Args: source 마스터 원천 DTO. Returns: Vue Router 경로 객체. Raises: 없음. Side Effects: 없음.
 */
function sourceLink(source) {
  return { path: `/submissions/${source.submissionId}`, query: { from: 'annual-master', masterId, requestId: master.value.requestId, organizationId: source.organizationId } }
}

/** 보고 항목의 서버 분류를 한국어로 표시한다.
 * Args: kind 서버 분류. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음; 알 수 없는 값은 보존한다.
 */
function kindLabel(kind) { return ({ REVENUE: '매출', COST: '비용', DERIVED: '계산 항목', OP: '영업이익' })[kind] || kind }

/** 화면 이탈 시 모든 읽기와 늦은 응답의 화면 반영을 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·조회 세대 무효화.
 */
function dispose() { generation++; valuesGeneration++; lifetime.abort(); controller.abort(); valuesController.abort() }

onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="annual-master">
    <RouterLink :to="{ path: '/planning/requests', query: master ? { requestId: master.requestId } : {} }" class="back-link">← 요청별 조직 현황</RouterLink>
    <header class="page-heading">
      <div><p class="overline">승인된 조직 계획 통합본 · PLAN</p><h1>{{ master ? `${master.year}년 연간 계획 마스터` : '연간 계획 마스터' }}</h1></div>
      <button class="button" :disabled="loading" @click="load()">새로고침</button>
    </header>
    <div v-if="error" class="alert error" role="alert">{{ error }}</div>
    <p v-if="!master && loading" class="panel empty" role="status">마스터를 불러오고 있습니다…</p>
    <template v-if="master">
      <section class="panel" aria-labelledby="master-version-title">
        <div class="section-heading"><h2 id="master-version-title">{{ master.versionLabel }}</h2><span class="badge green">발행 완료</span></div>
        <p class="muted master-description">발행 당시 승인된 {{ master.sources.length }}개 조직의 제출본을 보존한 통합본입니다. 이후 승인이 있어도 이 버전의 금액은 바뀌지 않습니다. Contingency를 입력하는 연간 계획 확정은 별도입니다.</p>
        <dl class="file-meta"><div><dt>발행 시각</dt><dd>{{ dateTime(master.createdAt) }}</dd></div><div><dt>발행 기준</dt><dd>조직 제출 승인</dd></div><div><dt>이전 버전</dt><dd><RouterLink v-if="master.previousMasterId" :to="`/planning/annual-masters/${master.previousMasterId}`" class="text-button">이전 마스터 확인 →</RouterLink><template v-else>첫 발행</template></dd></div></dl>
        <div class="version-controls">
          <label for="annual-master-version">발행 버전</label>
          <select id="annual-master-version" :value="masterId" :disabled="versionsLoading || !versions.length" @change="changeVersion">
            <option v-if="!selectedVersionLoaded" :value="masterId">{{ master.versionLabel }}</option>
            <option v-for="version in versions" :key="version.id" :value="String(version.id)">{{ version.versionLabel }} · {{ dateTime(version.createdAt) }}</option>
          </select>
          <button v-if="versionCursor" class="text-button" :disabled="versionsLoading" @click="loadVersions(true)">이전 버전 더 불러오기</button>
        </div>
        <p v-if="versionsLoading" class="muted" role="status">발행 버전을 불러오고 있습니다…</p>
        <div v-if="versionsError" class="alert error" role="alert">{{ versionsError }} <button class="text-button" :disabled="versionsLoading" @click="loadVersions(false)">버전 다시 조회</button></div>
      </section>

      <section class="panel" aria-labelledby="master-totals-title">
        <div class="section-heading"><h2 id="master-totals-title">전 조직 연간 합계</h2><span class="muted">단위: 원</span></div>
        <div class="annual-totals"><div><span>매출</span><strong>{{ amount(master.totals.revenue) }}</strong></div><div><span>비용</span><strong>{{ amount(master.totals.cost) }}</strong></div><div><span>영업이익</span><strong>{{ amount(master.totals.operatingProfit) }}</strong></div></div>
        <div class="table-scroll"><table><caption class="visually-hidden">전 조직의 월별 매출·비용·영업이익, 단위 원</caption><thead><tr><th scope="col">계획 월</th><th scope="col" class="number">매출</th><th scope="col" class="number">비용</th><th scope="col" class="number">영업이익</th></tr></thead><tbody><tr v-for="item in master.months" :key="item.month"><th scope="row">{{ item.month }}월</th><td class="number amount">{{ amount(item.revenue) }}</td><td class="number amount">{{ amount(item.cost) }}</td><td class="number amount">{{ amount(item.operatingProfit) }}</td></tr></tbody></table></div>
      </section>

      <section class="panel" aria-labelledby="master-report-title">
        <div class="section-heading"><div><h2 id="master-report-title">항목별 계획 금액</h2><p class="muted master-description">선택한 월·조직의 이 마스터에 포함된 보고 항목입니다. 합계 행과 상세 항목은 중복 합산하지 않습니다.</p></div><span class="muted">단위: 원</span></div>
        <div class="report-filters">
          <label>조직<select v-model="organizationId" @change="loadValues(false)"><option value="">전 조직 합계</option><option v-for="source in master.sources" :key="source.organizationId" :value="String(source.organizationId)">{{ source.organizationName }} · {{ source.organizationCode }}</option></select></label>
          <label>계획 월<select v-model="month" @change="loadValues(false)"><option v-for="value in 12" :key="value" :value="String(value)">{{ value }}월</option></select></label>
        </div>
        <div v-if="valuesError" class="alert error" role="alert">{{ valuesError }} <button class="text-button" :disabled="valuesLoading" @click="loadValues(false)">항목 다시 조회</button></div>
        <p v-if="valuesLoading && !values.length" class="empty compact" role="status">항목별 금액을 불러오고 있습니다…</p>
        <p v-else-if="!values.length && !valuesError" class="empty compact">선택한 조건의 보고 항목이 없습니다.</p>
        <div v-if="values.length" class="table-scroll"><table><caption class="visually-hidden">선택한 조직·월의 보고 항목과 계획 금액</caption><thead><tr><th scope="col">보고 항목</th><th scope="col">항목 코드</th><th scope="col">구분</th><th scope="col" class="number">계획 금액</th></tr></thead><tbody><tr v-for="item in values" :key="`${item.reportItemId}:${item.month}:${item.organizationId}`"><th scope="row">{{ item.reportItemName }}</th><td>{{ item.reportItemCode }}</td><td>{{ kindLabel(item.kind) }}</td><td class="number amount">{{ item.valueStatus === 'AVAILABLE' ? amount(item.amount) : '—' }}<small v-if="item.valueStatus !== 'AVAILABLE'">{{ item.valueStatus }}</small></td></tr></tbody></table></div>
        <button v-if="valueCursor" class="button load-more" :disabled="valuesLoading" @click="loadValues(true)">{{ valuesLoading ? '불러오는 중…' : '항목 더 보기' }}</button>
      </section>

      <section class="panel" aria-labelledby="master-sources-title">
        <div class="section-heading"><div><h2 id="master-sources-title">반영된 조직별 승인본</h2><p class="muted master-description">이 버전의 금액 근거입니다. 제출본을 열어 적재 내역·진단·원본 셀을 확인할 수 있습니다.</p></div><span class="muted">{{ master.sources.length }}곳</span></div>
        <div class="table-scroll"><table><caption class="visually-hidden">마스터에 반영된 조직별 승인 제출본</caption><thead><tr><th scope="col">조직</th><th scope="col">조직 코드</th><th scope="col">승인본</th><th scope="col"><span class="visually-hidden">원천 확인</span></th></tr></thead><tbody><tr v-for="source in master.sources" :key="source.organizationId"><th scope="row">{{ source.organizationName }}</th><td>{{ source.organizationCode }}</td><td>{{ source.versionLabel }}</td><td><RouterLink :to="sourceLink(source)" class="text-button">적재·원본 확인 →</RouterLink></td></tr></tbody></table></div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.master-description { margin-top:8px; max-width:850px; }.version-controls { display:flex; align-items:center; flex-wrap:wrap; gap:12px; margin-top:22px; }.version-controls label { margin:0; }.version-controls select { width:auto; min-width:270px; margin:0; }.annual-totals { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:16px; margin:20px 0 24px; }.annual-totals>div { padding:18px; border:1px solid var(--line); border-radius:10px; background:#faf9f6; }.annual-totals span { display:block; color:var(--muted); font-size:12px; }.annual-totals strong { display:block; margin-top:10px; font-size:20px; font-variant-numeric:tabular-nums; overflow-wrap:anywhere; }.report-filters { display:flex; align-items:flex-end; gap:16px; margin:20px 0; }.report-filters label { min-width:120px; margin:0; }.report-filters label:first-child { flex:1; max-width:380px; }.report-filters select { margin-bottom:0; }.annual-master th[scope="row"] { font-weight:500; background:transparent; color:var(--ink); }.annual-master table { min-width:610px; }
@media(max-width:760px) { .annual-totals { grid-template-columns:1fr; }.version-controls { align-items:flex-start; flex-direction:column; }.version-controls select { min-width:0; width:100%; }.report-filters { align-items:stretch; flex-direction:column; }.report-filters label:first-child { max-width:none; } }
</style>
