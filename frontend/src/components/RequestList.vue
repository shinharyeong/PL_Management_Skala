<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useSession } from '../stores/session'
import { query, requestTitle, dateTime, errorText } from '../lib/display'
import { requestAction } from '../lib/request-workflow'

const session = useSession()
const items = ref([])
const nextCursor = ref(null)
const loading = ref(false)
const error = ref('')
const statusFilter = ref('OPEN')
let controller = new AbortController()
let generation = 0

/** 접수 상태별 요청과 본인 조직의 현재 할 일을 조회한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: HTTP 조회, 목록·대기·오류 상태 갱신.
 */
async function load(more = false) {
  if (more && loading.value) return
  controller.abort(); controller = new AbortController()
  const current = ++generation
  loading.value = true
  error.value = ''
  if (!more) { items.value = []; nextCursor.value = null }
  try {
    const page = await session.request(`/api/v1/submission-requests${query({ status: statusFilter.value, limit: 50, cursor: more ? nextCursor.value : null })}`, { signal: controller.signal })
    if (current !== generation || controller.signal.aborted) return
    items.value = more ? [...items.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}

/** 본인 조직의 제출·소명 상태를 요청 카드의 배지로 표시한다.
 * Args: item 요청과 myWorkflow. Returns: 상태 문구. Raises: 없음. Side Effects: 없음.
 */
function statusLabel(item) {
  const work = item.myWorkflow || {}
  if (work.latestStatus === 'PENDING') return work.unansweredCount > 0 ? '소명 보완 요청' : '검토 중'
  if (work.latestStatus === 'REJECTED') return '재제출 요청'
  if (work.latestDraftSubmissionId) return '작성 중'
  if (work.latestStatus === 'APPROVED') return '승인 완료'
  return item.status === 'OPEN' ? '제출 요청' : '접수 마감'
}

/** 화면 이탈 시 조회를 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소.
 */
function dispose() { generation++; controller.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <header class="page-heading">
    <div><p class="overline">{{ session.actor.organization?.name }} · {{ session.actor.organization?.code }}</p><h1>요청함</h1></div>
  </header>
  <section class="panel">
    <div class="section-heading"><h2>{{ statusFilter === 'OPEN' ? '열린 요청' : statusFilter === 'CLOSED' ? '마감된 요청' : '전체 요청' }}</h2><div class="button-row"><label class="request-filter"><span class="visually-hidden">접수 상태</span><select v-model="statusFilter" :disabled="loading" @change="load(false)"><option value="OPEN">열린 요청</option><option value="CLOSED">마감된 요청</option><option value="">전체 요청</option></select></label><span class="muted">{{ items.length }}건{{ nextCursor ? ' 이상' : '' }}</span></div></div>
    <div v-if="error" class="alert error" role="alert">{{ error }} <button class="text-button" :disabled="loading" @click="load(false)">다시 시도</button></div>
    <p v-if="loading && !items.length" class="empty" role="status">요청을 불러오고 있습니다…</p>
    <div v-else-if="!items.length && !error" class="empty"><strong>해당하는 요청이 없습니다</strong><p>경영기획팀에서 요청을 생성하면 여기에 표시됩니다.</p></div>
    <article v-for="item in items" :key="item.id" class="request-card">
      <div class="request-copy"><span :class="['badge', { green: item.status === 'OPEN', red: item.myWorkflow?.latestStatus === 'REJECTED', amber: item.myWorkflow?.unansweredCount > 0 }]">{{ statusLabel(item) }}</span><h3>{{ requestTitle(item) }}</h3><p>{{ item.message || '지정된 양식에 전체 항목을 작성해 주세요.' }}</p><small v-if="item.dueAt">회신 기한 {{ dateTime(item.dueAt) }}</small><p v-if="item.myWorkflow?.latestDraftSubmissionId || item.myWorkflow?.latestStatus || item.myWorkflow?.canUpload === false" class="work-message">{{ requestAction(item).message }}</p><RouterLink v-if="item.myWorkflow?.latestStatus === 'REJECTED' && item.myWorkflow.latestSubmittedSubmissionId" :to="`/submissions/${item.myWorkflow.latestSubmittedSubmissionId}`" class="text-button">반려 사유 확인</RouterLink></div>
      <RouterLink v-if="requestAction(item).path" :to="requestAction(item).path" class="button primary">{{ requestAction(item).label }}</RouterLink><span v-else class="badge">{{ requestAction(item).label }}</span>
    </article>
    <button v-if="nextCursor" class="button load-more" :disabled="loading" @click="load(true)">{{ loading ? '불러오는 중…' : '요청 더 보기' }}</button>
  </section>
</template>

<style scoped>
.request-filter { display:flex; align-items:center; gap:8px; font-size:11px; }.request-filter select { width:auto; margin:0; min-height:28px; padding:3px 6px; border-color:transparent; font-size:11px; }.work-message { font-weight:500; color:var(--muted); }
@media (max-width: 760px) {
  .page-heading { flex-wrap: wrap; }
}
</style>
