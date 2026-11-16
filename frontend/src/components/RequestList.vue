<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useSession } from '../stores/session'
import { query, requestTitle, dateTime, errorText } from '../lib/display'

const session = useSession()
const items = ref([])
const nextCursor = ref(null)
const loading = ref(false)
const error = ref('')
const controller = new AbortController()

/** 열린 요청의 첫 페이지 또는 다음 페이지를 조회한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: HTTP 조회, 목록·대기·오류 상태 갱신.
 */
async function load(more = false) {
  loading.value = true
  error.value = ''
  try {
    const page = await session.request(`/api/v1/submission-requests${query({ status: 'OPEN', limit: 50, cursor: more ? nextCursor.value : null })}`, { signal: controller.signal })
    if (controller.signal.aborted) return
    items.value = more ? [...items.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { loading.value = false }
}

/** 서버가 제공한 체험 조직을 선택하고 이전 조직의 목록·인증을 비운다.
 * Args: event 조직 select 변경 이벤트. Returns: Promise<void>. Raises: 없음(입장 화면에 오류 표시).
 * Side Effects: 현재 조회 취소·목록 초기화·session.enter 호출; 새 세션이 업무 화면을 새로 마운트한다.
 * 내부 isAvailable 콜백은 세션 응답에 포함된 조직 코드인지 확인하며 부작용이 없다.
 */
async function changeOrganization(event) {
  const code = event.target.value
  if (code === session.actor.organization?.code || session.entering) return
  if (!session.organizations.some(function isAvailable(organization) { return organization.code === code })) return
  items.value = []
  nextCursor.value = null
  controller.abort()
  try {
    await session.enter('ORG_MANAGER', undefined, code)
  } catch (cause) {
    if (cause.name !== 'AbortError') session.entryError = errorText(cause)
  }
}

/** 화면 이탈 시 조회를 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소.
 */
function dispose() { controller.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <header class="page-heading">
    <div><p class="overline">{{ session.actor.organization?.name }} · {{ session.actor.organization?.code }}</p><h1>요청함</h1></div>
    <label v-if="session.actor.role === 'ORG_MANAGER' && session.organizations.length > 1" class="organization-switch" for="demo-organization">
      체험 조직
      <select id="demo-organization" :value="session.actor.organization?.code" :disabled="session.entering" @change="changeOrganization">
        <option v-for="organization in session.organizations" :key="organization.id" :value="organization.code">{{ organization.name }} · {{ organization.code }}</option>
      </select>
    </label>
  </header>
  <section class="panel">
    <div class="section-heading"><h2>열린 요청</h2><span class="muted">{{ items.length }}건{{ nextCursor ? ' 이상' : '' }}</span></div>
    <div v-if="error" class="alert error" role="alert">{{ error }} <button class="text-button" :disabled="loading" @click="load(false)">다시 시도</button></div>
    <p v-if="loading && !items.length" class="empty" role="status">요청을 불러오고 있습니다…</p>
    <div v-else-if="!items.length && !error" class="empty"><strong>열린 요청이 없습니다</strong><p>경영기획팀에서 요청을 생성하면 여기에 표시됩니다.</p></div>
    <article v-for="item in items" :key="item.id" class="request-card">
      <div class="request-copy"><span class="badge green">제출 요청</span><h3>{{ requestTitle(item) }}</h3><p>{{ item.message || '지정된 양식에 전체 항목을 작성해 주세요.' }}</p><small v-if="item.dueAt">회신 기한 {{ dateTime(item.dueAt) }}</small></div>
      <RouterLink :to="`/requests/${item.id}/upload`" class="button primary">제출 시작</RouterLink>
    </article>
    <button v-if="nextCursor" class="button load-more" :disabled="loading" @click="load(true)">{{ loading ? '불러오는 중…' : '요청 더 보기' }}</button>
  </section>
</template>

<style scoped>
.organization-switch { min-width: 220px; color: #5f636b; font-size: 11px; font-weight: 500; }
.organization-switch select { font-size: 12px; }
@media (max-width: 760px) {
  .page-heading { flex-wrap: wrap; }
  .organization-switch { width: 100%; }
}
</style>
