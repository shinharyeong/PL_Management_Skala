<script setup>
import { ref, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { errorText } from '../lib/display'

const session = useSession()
const router = useRouter()
const busy = ref('')
const error = ref('')
const controller = new AbortController()

/** 선택한 역할로 체험 세션을 시작한다.
 * Args: role 선택 역할. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 로그인 요청, 화면 이동, 대기·오류 상태 갱신.
 */
async function enter(role) {
  busy.value = role
  error.value = ''
  try {
    await session.enter(role, controller.signal)
    await router.replace(role === 'PLANNER' ? '/planning' : '/requests')
  } catch (cause) {
    if (cause.name !== 'AbortError') error.value = errorText(cause)
  } finally { busy.value = '' }
}

/** 이미 인증된 경우 기본 업무 화면으로 이동한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 라우트 이동.
 */
function restoreRoute() {
  if (session.actor) router.replace(session.actor.role === 'PLANNER' ? '/planning' : '/requests')
}

/** 화면 이탈 시 입장 요청을 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소.
 */
function dispose() { controller.abort() }
onMounted(restoreRoute)
onBeforeUnmount(dispose)
</script>

<template>
  <main class="entry-page">
    <div class="entry-brand"><span class="brand-mark"></span>손익관리</div>
    <div class="entry-content">
      <span class="eyebrow">경영팀을 부탁해</span>
      <h1>하나의 흐름으로,<br />더 명확한 손익 관리</h1>
      <p class="entry-description">요청부터 엑셀 업로드, 파싱 결과 확인까지.<br />체험할 역할을 선택해 시작하세요.</p>
      <div v-if="error || session.entryError" class="alert error" role="alert">{{ error || session.entryError }}</div>
      <p v-if="session.entering" class="entry-description" role="status">선택한 역할과 조직으로 연결하고 있습니다…</p>
      <div class="role-grid">
        <button class="role-card" :disabled="!!busy || session.entering" @click="enter('ORG_MANAGER')">
          <span class="role-symbol">01</span><strong>조직 담당자</strong><span>제출 요청을 확인하고<br />우리 조직의 엑셀을 업로드합니다.</span><b>{{ busy === 'ORG_MANAGER' ? '연결 중…' : '조직 담당자로 시작 →' }}</b>
        </button>
        <button class="role-card" :disabled="!!busy || session.entering" @click="enter('PLANNER')">
          <span class="role-symbol">02</span><strong>경영기획팀</strong><span>연간 계획을 요청하고<br />조직별 수집 현황을 확인합니다.</span><b>{{ busy === 'PLANNER' ? '연결 중…' : '경영기획팀으로 시작 →' }}</b>
        </button>
      </div>
      <p class="entry-note"><span class="status-dot"></span>로컬 체험 · 예시 데이터</p>
    </div>
  </main>
</template>
