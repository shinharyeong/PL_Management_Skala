<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useSession } from '../stores/session'
import { dateTime, errorText, query } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
import MonthlyMasterData from './MonthlyMasterData.vue'

const route = useRoute()
const session = useSession()
const master = ref(null)
const versions = ref([])
const versionCursor = ref(null)
const values = ref([])
const valueCursor = ref(null)
const organizationId = ref('')
const loading = ref(false)
const versionsLoading = ref(false)
const valuesLoading = ref(false)
const error = ref('')
const versionsError = ref('')
const valuesError = ref('')
let controller = new AbortController()
let valuesController = new AbortController()
let generation = 0
let valueGeneration = 0
let appliedOrganization = ''
const masterId = String(route.params.id)

/** 경로의 고정 실적 마스터와 같은 월의 이력·보고 항목을 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·구 조회 취소·목록 초기화.
 */
async function load() {
  controller.abort(); controller = new AbortController(); valuesController.abort(); valueGeneration++
  const current = ++generation
  loading.value = true; error.value = ''; master.value = null; versions.value = []; values.value = []; versionCursor.value = null; valueCursor.value = null
  try {
    const result = await session.request(`/api/v1/masters/${masterId}`, { signal: controller.signal })
    if (current !== generation) return
    master.value = result
    await Promise.allSettled([loadVersions(false), loadValues(false)])
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}

/** 같은 월의 발행 이력을 cursor로 추가 조회한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·이력 갱신.
 */
async function loadVersions(more = false) {
  if (!master.value || versionsLoading.value || (more && !versionCursor.value)) return
  const current = generation
  versionsLoading.value = true; versionsError.value = ''
  try {
    const page = await session.request(`/api/v1/masters${query({ year: master.value.year, month: master.value.month, limit: 50, cursor: more ? versionCursor.value : null })}`, { signal: controller.signal })
    if (current !== generation) return
    versions.value = more ? [...versions.value, ...page.items] : page.items; versionCursor.value = page.nextCursor
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') versionsError.value = errorText(cause) }
  finally { if (current === generation) versionsLoading.value = false }
}

/** 고정된 마스터 월의 ACTUAL 항목을 선택 조직 기준으로 조회한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: GET·목록/적용 필터 갱신·이전 항목 요청 취소; 없는 값을 0으로 만들지 않는다.
 */
async function loadValues(more = false) {
  if (!master.value || (more && (valuesLoading.value || !valueCursor.value))) return
  valuesController.abort(); valuesController = new AbortController()
  const current = ++valueGeneration
  if (!more) { values.value = []; valueCursor.value = null; appliedOrganization = organizationId.value }
  valuesLoading.value = true; valuesError.value = ''
  try {
    const page = await session.request(`/api/v1/masters/${masterId}/report-values${query({ organizationId: appliedOrganization, limit: 100, cursor: more ? valueCursor.value : null })}`, { signal: valuesController.signal })
    if (current !== valueGeneration) return
    values.value = more ? [...values.value, ...page.items] : page.items; valueCursor.value = page.nextCursor
  } catch (cause) { if (current === valueGeneration && cause.name !== 'AbortError') valuesError.value = errorText(cause) }
  finally { if (current === valueGeneration) valuesLoading.value = false }
}

/** 화면 이탈 시 모든 조회를 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 무효화.
 */
function dispose() { generation++; valueGeneration++; controller.abort(); valuesController.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <RouterLink :to="{ path: '/planning/gap', query: master ? { requestId: master.requestId } : {} }" class="back-link">← 월간 시트 관리</RouterLink>
  <header class="page-heading"><h1>월간 실적 마스터</h1><button class="button" :disabled="loading" @click="load">새로고침</button></header>
  <div v-if="error" class="alert error" role="alert">{{ error }}</div><p v-if="loading" class="panel empty" role="status">마스터를 불러오는 중…</p>
  <template v-if="master"><MonthlyMasterData :key="master.id" :master="master" />
    <section class="panel"><h2>같은 월의 발행 이력</h2><p class="muted">이전 버전은 발행 당시의 승인본과 금액을 보존합니다.</p><p v-if="versionsError" class="alert error" role="alert">{{ versionsError }}</p><ul class="version-list"><li v-for="version in versions" :key="version.id"><RouterLink :to="`/planning/masters/${version.id}`" :aria-current="String(version.id) === masterId ? 'page' : null">{{ version.versionLabel }} · {{ dateTime(version.createdAt) }}</RouterLink></li></ul><button v-if="versionCursor" class="button" :disabled="versionsLoading" @click="loadVersions(true)">이전 버전 더 보기</button></section>
    <section class="panel"><div class="section-heading"><div><h2>{{ master.month }}월 ACTUAL 보고 항목</h2><p class="muted">현재 버전에 고정된 해당 월의 실적만 표시합니다. 합계와 상세를 중복 합산하지 않습니다.</p></div><span class="muted">단위 원</span></div><label>조직<select v-model="organizationId" @change="loadValues(false)"><option value="">전사 합계</option><option v-for="item in master.organizations" :key="item.organizationId" :value="String(item.organizationId)">{{ item.organizationName }}</option></select></label><div v-if="valuesError" class="alert error" role="alert">{{ valuesError }} <button class="text-button" :disabled="valuesLoading" @click="loadValues(false)">다시 조회</button></div><p v-if="valuesLoading && !values.length" class="empty" role="status">보고 항목을 불러오는 중…</p><div v-if="values.length" class="table-scroll"><table><thead><tr><th>보고 항목</th><th>구분</th><th class="number">실적 금액</th><th>상태</th></tr></thead><tbody><tr v-for="item in values" :key="`${item.reportItemId}:${item.organizationId}`"><th scope="row">{{ item.reportItemName || item.reportItem?.name }}</th><td>{{ item.kind }}</td><td class="number">{{ item.valueStatus === 'AVAILABLE' ? formatMoney(item.amount) : '—' }}</td><td>{{ item.valueStatus }}</td></tr></tbody></table></div><p v-else-if="!valuesLoading && !valuesError" class="empty compact">표시할 보고 항목이 없습니다.</p><button v-if="valueCursor" class="button load-more" :disabled="valuesLoading" @click="loadValues(true)">항목 더 보기</button></section>
  </template>
</template>

<style scoped>
.version-list { list-style:none; padding:0; display:flex; flex-wrap:wrap; gap:12px; margin:18px 0; font-size:12px; }.version-list a { display:block; padding:10px; border:1px solid var(--line); border-radius:8px; }.version-list a[aria-current] { border-color:var(--gold); background:#faf7f0; }
</style>
