<script setup>
import { reactive, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useSession } from '../stores/session'
import { query, requestTitle, submissionStatus, dateTime, errorText } from '../lib/display'

const session = useSession()
const route = useRoute()
const items = ref([])
const nextCursor = ref(null)
const loading = ref(false)
const error = ref('')
const filters = reactive({ year: '', month: '', type: '', status: '' })
let controller = new AbortController()
let generation = 0
let appliedFilters = { ...filters }

/** 적용한 필터로 이력 페이지를 조회하며 앞선 요청의 뒤늦은 응답을 무시한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 이전 HTTP 취소·목록 갱신. 연도 없는 월 필터는 전송하지 않는다.
 */
async function load(more = false) {
  controller.abort()
  controller = new AbortController()
  const current = ++generation
  if (!more) appliedFilters = { ...filters }
  loading.value = true
  error.value = ''
  try {
    const page = await session.request(`/api/v1/submissions${query({ ...appliedFilters, submittedOnly: appliedFilters.status === 'DRAFT' ? null : true, month: appliedFilters.year ? appliedFilters.month : '', requestId: route.query.requestId, organizationId: route.query.organizationId, cursor: more ? nextCursor.value : null, limit: 50 })}`, { signal: controller.signal })
    if (current !== generation) return
    items.value = more ? [...items.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}

/** 필터를 초기화하고 첫 페이지를 다시 읽는다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 필터·목록 갱신.
 */
function reset() { Object.assign(filters, { year: '', month: '', type: '', status: '' }); load(false) }

/** 요청·조직 필터와 허용된 진입 문맥을 상세 화면에 전달한다.
 * Args: item 제출 요약. Returns: Vue Router 대상 객체. Raises: 없음. Side Effects: 없음.
 */
function detailLink(item) {
  return { path: `/submissions/${item.id}`, query: { from: route.query.from === 'requests' ? 'requests' : 'history', requestId: route.query.requestId, organizationId: route.query.organizationId } }
}

/** 화면 이탈 시 이력 조회를 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·응답 무효화.
 */
function dispose() { generation++; controller.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <RouterLink v-if="session.actor.role === 'PLANNER' && route.query.from === 'requests'" :to="{ path: '/planning/requests', query: { requestId: route.query.requestId } }" class="back-link">← 요청별 조직 현황</RouterLink>
  <header class="page-heading"><div><p class="overline">{{ session.actor.organization?.name || '전체 조직' }}<template v-if="route.query.requestId"> · 요청별 조회</template></p><h1>제출 이력 관리</h1></div></header>
  <section class="panel">
    <div class="section-heading"><h2>{{ filters.status === 'DRAFT' ? '작성 중 초안' : '제출본' }}</h2><span class="muted">조회 {{ items.length }}건{{ nextCursor ? ' 이상' : '' }}</span></div>
    <form class="filters" @submit.prevent="load(false)">
      <label>대상 연도<input v-model="filters.year" type="number" min="2000" max="9999" placeholder="전체 연도" /></label>
      <label>대상 월<select v-model="filters.month" :disabled="!filters.year"><option value="">전체 월</option><option v-for="month in 12" :key="month" :value="month">{{ month }}월</option></select></label>
      <label>제출 유형<select v-model="filters.type"><option value="">전체 유형</option><option value="ANNUAL_PLAN">연간 계획</option><option value="MONTHLY_ACTUAL">월간 실적·추정</option></select></label>
      <label>처리 상태<select v-model="filters.status"><option value="">전체 제출 상태</option><option value="PENDING">검토 대기</option><option value="APPROVED">승인</option><option value="REJECTED">재제출 요청</option><option value="DRAFT">작성 중 초안</option></select></label>
      <button class="button" type="submit" :disabled="loading">조회</button><button class="text-button" type="button" :disabled="loading" @click="reset">초기화</button>
    </form>
    <div v-if="error" class="alert error" role="alert">{{ error }}</div>
    <p v-if="loading && !items.length" class="empty" role="status">이력을 불러오고 있습니다…</p>
    <div v-else-if="!items.length && !error" class="empty"><strong>조회된 파일이 없습니다</strong><p>파일을 업로드하면 파싱 상태와 원본을 확인할 수 있습니다.</p><RouterLink v-if="session.actor.role === 'ORG_MANAGER'" to="/requests" class="text-button">요청함으로 이동 →</RouterLink></div>
    <div v-if="items.length" class="table-scroll"><table class="history-table"><caption class="visually-hidden">대상 기간별 제출본 이력</caption><thead><tr><th>제출 요청</th><th>대상 기간</th><th>제출 유형</th><th>버전 / 저장본</th><th>{{ filters.status === 'DRAFT' ? '업로드일' : '제출일' }}</th><th>상태</th><th><span class="visually-hidden">상세</span></th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td><strong>{{ requestTitle(item) }}</strong></td><td>{{ item.year }}년<template v-if="item.month"> {{ item.month }}월</template></td><td>{{ item.type === 'ANNUAL_PLAN' ? '연간 계획' : '월간 실적·추정' }}</td><td>{{ item.versionLabel || item.originalFilename }}</td><td>{{ dateTime(item.submittedAt || item.uploadedAt) }}</td><td><span :class="['badge', { green: item.status === 'APPROVED', red: item.status === 'REJECTED' || item.parsingStatus === 'FAILED' }]">{{ item.status === 'REJECTED' ? '재제출 요청' : submissionStatus(item) }}</span></td><td><RouterLink :to="detailLink(item)" class="text-button">{{ item.status === 'REJECTED' ? '재제출 요청 보기' : item.status === 'DRAFT' ? '이어 작성' : '보기' }}</RouterLink></td></tr></tbody></table></div>
    <button v-if="nextCursor" class="button load-more" :disabled="loading" @click="load(true)">{{ loading ? '불러오는 중…' : '이력 더 보기' }}</button>
  </section>
</template>

<style scoped>
.history-table { margin-top:4px; }.history-table th { padding:20px 10px; }.history-table td { padding:12px 10px; vertical-align:middle; font-size:12px; }.history-table th,.history-table td:nth-child(n+2) { white-space:nowrap; }.history-table td:first-child { min-width:170px; }.history-table td:nth-child(4) { max-width:220px; white-space:normal; overflow-wrap:anywhere; }
</style>
