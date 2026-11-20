<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useSession } from '../stores/session'
import { errorText } from '../lib/display'

const session = useSession()
const profile = ref(null)
const loading = ref(false)
const error = ref('')
const controller = new AbortController()
const keys = new Map()

/** 등록된 소속·역할과 이전 확인 여부를 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·확인 상태 갱신.
 */
async function load() {
  loading.value = true; error.value = ''
  try { profile.value = await session.request('/api/v1/auth/profile-confirmation', { signal: controller.signal }); if (profile.value.confirmed) session.confirmProfile(profile.value) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { loading.value = false }
}

/** 사용자가 확인한 서버 소속·역할을 저장한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: POST·최초 확인 기록 갱신.
 */
async function confirm() {
  if (loading.value || !profile.value) return
  const body = JSON.stringify({ expectedStateToken: profile.value.stateToken })
  if (!keys.has(body)) keys.set(body, crypto.randomUUID())
  loading.value = true; error.value = ''
  try { session.confirmProfile(await session.request('/api/v1/auth/profile-confirmation', { method: 'POST', body, headers: { 'Idempotency-Key': keys.get(body) }, signal: controller.signal })); keys.delete(body) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { loading.value = false }
}

/** 잘못된 계정으로 들어온 사용자가 다시 로그인할 수 있게 세션을 종료한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: DELETE·메모리 인증 초기화.
 */
async function logout() { loading.value = true; error.value = ''; try { await session.logout() } catch (cause) { error.value = errorText(cause) } finally { loading.value = false } }

/** 화면을 떠날 때 조회를 취소한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소. */
function dispose() { controller.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <main class="profile-confirmation"><section class="panel"><h1>소속과 역할을 확인해 주세요</h1><p class="muted">등록된 정보로 업무와 열람 범위가 결정됩니다. 정보가 다르면 경영기획 담당자에게 수정을 요청해 주세요.</p><p v-if="error" class="alert error" role="alert">{{ error }} <button class="text-button" :disabled="loading" @click="load">다시 확인</button></p><p v-if="loading && !profile" role="status">등록 정보를 확인하는 중…</p><dl v-if="profile"><div><dt>이름</dt><dd>{{ profile.actor.name }}</dd></div><div><dt>이메일</dt><dd>{{ profile.actor.email }}</dd></div><div><dt>소속</dt><dd>{{ profile.actor.organization?.name || '경영기획팀' }}</dd></div><div><dt>역할</dt><dd>{{ profile.actor.role === 'PLANNER' ? '경영기획팀' : '조직 담당자' }}</dd></div></dl><div class="button-row"><button class="button" :disabled="loading" @click="logout">다른 계정으로 로그인</button><button class="button primary" :disabled="loading || !profile" @click="confirm">{{ loading ? '확인 중…' : '확인하고 시작하기' }}</button></div></section></main>
</template>

<style scoped>
.profile-confirmation{min-height:100vh;display:grid;place-items:center;background:var(--page,#f6f5f2);padding:24px}.panel{max-width:560px;width:100%;display:grid;gap:20px}h1{font-size:24px}.muted{line-height:1.8}dl{margin:0;display:grid;gap:12px}dl>div{display:grid;grid-template-columns:80px 1fr;gap:16px}dt{color:var(--muted)}dd{margin:0;overflow-wrap:anywhere}.button-row{justify-content:flex-end}
</style>
