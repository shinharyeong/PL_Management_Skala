<script setup>
import { ref, computed, onBeforeUnmount, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { errorText } from '../lib/display'
import { roleDestination } from '../lib/role-route'

const session = useSession()
const router = useRouter()
const email = ref('')
const code = ref('')
const challenge = ref(null)
const error = ref('')
const busy = ref(false)
const now = ref(Date.now())
const codeInput = ref(null)
const organizationCode = ref('F10')
const controller = new AbortController()
const remaining = computed(() => Math.max(0, Math.ceil((new Date(challenge.value?.expiresAt).getTime() - now.value) / 1000)) || 0)
const countdown = computed(() => `${String(Math.floor(remaining.value / 60)).padStart(2, '0')}:${String(remaining.value % 60).padStart(2, '0')}`)
const canResend = computed(() => challenge.value && now.value >= new Date(challenge.value.resendAt).getTime())
let timer

/** 입력한 등록 이메일의 인증코드를 요청한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: API 호출·코드 단계 이동.
 */
async function requestCode() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    challenge.value = await session.request('/api/v1/auth/codes', { method: 'POST', body: JSON.stringify({ email: email.value }), signal: controller.signal })
    code.value = ''; now.value = Date.now(); await nextTick(); codeInput.value?.focus()
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = false }
}

/** 서버가 확인한 역할의 홈으로 이동한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 코드 소비·세션·라우트 갱신.
 */
async function verify() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const result = await session.request('/api/v1/auth/sessions', { method: 'POST', body: JSON.stringify({ challengeId: challenge.value.challengeId, code: code.value }), signal: controller.signal })
    const destination = roleDestination(router.currentRoute.value, result.actor.role)
    session.accept(result); await router.replace(destination)
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = false }
}

/** 로컬 확인용 계정으로 입장한다.
 * Args: role 개발 역할. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 개발 API·라우트 갱신.
 */
async function enterDemo(role) {
  busy.value = true; error.value = ''
  try { const path = roleDestination(router.currentRoute.value, role); await session.enter(role, controller.signal, role === 'ORG_MANAGER' ? organizationCode.value : undefined); await router.replace(path) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = false }
}

/** 이메일 입력 단계로 돌아간다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 화면 입력 초기화. */
function changeEmail() { challenge.value = null; code.value = ''; error.value = '' }
/** 카운트다운을 시작한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 타이머 등록. */
function initialize() { timer = setInterval(() => { now.value = Date.now() }, 1000) }
/** 화면의 비동기 자원을 해제한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 요청·타이머 해제. */
function dispose() { controller.abort(); clearInterval(timer) }
onMounted(initialize)
onBeforeUnmount(dispose)
</script>

<template>
  <main class="login-page">
    <section class="login-intro">
      <div class="entry-brand"><span class="brand-mark"></span>손익관리 플랫폼</div>
      <div class="login-promise"><h1>월간 손익 보고,<br />2주에서 하루로.</h1><p>엑셀 취합 · Gap 소명 · 버전 관리<br />한 곳에서 끝내는 경영기획 워크스페이스</p></div>
      <small>경영팀을 부탁해 · 손익관리 플랫폼</small>
    </section>
    <section class="login-content">
      <div class="login-form">
        <h2>{{ challenge ? '인증코드를 입력하세요' : '로그인' }}</h2>
        <p v-if="!challenge" class="login-description">등록된 사내 이메일로 시작하세요.</p>
        <div v-if="error" class="alert error" role="alert">{{ error }}</div>
        <form v-if="!challenge" @submit.prevent="requestCode">
          <label>이메일<input v-model="email" type="email" autocomplete="email" placeholder="name@company.com" required :disabled="busy" /></label>
          <button class="button dark login-submit" :disabled="busy">{{ busy ? '확인 중…' : '인증코드 받기' }}</button>
          <p class="login-help">관리자가 등록한 소속과 역할로 연결됩니다.</p>
        </form>
        <form v-else @submit.prevent="verify">
          <div class="email-chip"><span>{{ challenge.email }}</span><button type="button" class="text-button" @click="changeEmail">변경</button></div>
          <label for="login-code">인증코드 6자리</label>
          <input id="login-code" ref="codeInput" v-model="code" class="code-input" :class="{ invalid: error }" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required aria-describedby="code-time" :disabled="busy" />
          <div class="code-meta"><span id="code-time">{{ remaining ? `유효시간 ${countdown}` : '유효시간이 경과되어, 코드 재전송을 요청해주세요' }}</span><button type="button" class="text-button" :disabled="!canResend || busy" @click="requestCode">코드 재전송</button></div>
          <button class="button dark login-submit" :disabled="busy || !remaining || code.length !== 6">{{ busy ? '확인 중…' : '로그인' }}</button>
          <div v-if="challenge.delivery === 'LOCAL_PREVIEW'" class="local-mailbox"><strong>로컬 인증 수신함</strong><p>현재 실행 환경에서는 메일을 발송하지 않습니다.</p><code>{{ challenge.localCode }}</code></div>
        </form>
        <details class="local-entry"><summary>로컬 체험 계정으로 확인</summary><p>현재 적재된 데이터로 기능을 확인합니다.</p><label>사업조직 코드<input v-model="organizationCode" placeholder="F10" :disabled="busy" /></label><div class="button-row"><button class="button" :disabled="busy" @click="enterDemo('PLANNER')">경영기획팀</button><button class="button" :disabled="busy" @click="enterDemo('ORG_MANAGER')">조직 담당자</button></div></details>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page{min-height:100vh;display:grid;grid-template-columns:44.45% 1fr;background:#fafafa}.login-intro{min-height:100vh;padding:56px 64px;display:flex;flex-direction:column;justify-content:space-between;background:#14171e;color:#fff}.login-intro .entry-brand{font-size:16px}.login-promise h1{font-size:44px;line-height:1.3;letter-spacing:-1.3px;color:white;margin-bottom:28px}.login-promise p{color:#adb0b8;font-size:15px;line-height:1.9}.login-intro>small{font-size:11px;color:#7c808a}.login-content{display:flex;align-items:center;justify-content:center;padding:40px}.login-form{width:420px;max-width:100%}.login-form h2{font-size:28px;margin-bottom:24px;letter-spacing:-.5px}.login-description{font-size:14px;color:var(--muted);margin:-10px 0 28px}.login-form input{min-height:46px;margin:8px 0 20px}.login-submit{width:100%;min-height:48px;margin:6px 0 12px}.login-help{font-size:11px;text-align:center;color:#858992}.email-chip{background:#f5f4f0;border-radius:8px;padding:12px 14px;display:flex;justify-content:space-between;gap:12px;margin:18px 0 24px;font-size:13px}.code-input{text-align:center;letter-spacing:22px;font-size:24px;font-weight:700;padding-left:30px;height:60px;border-color:#333!important}.code-input.invalid{border-color:#bd453c!important;background:#fff7f6}.code-meta{display:flex;justify-content:space-between;align-items:center;font-size:11px;color:#81868f;margin:-9px 0 20px;gap:10px}.local-entry{border-top:1px solid var(--line);margin-top:32px;padding-top:18px}.local-entry summary{font-size:12px;color:#777}.local-entry p{font-size:11px;color:#888;margin:10px 0}.local-entry input{min-height:36px;margin-bottom:12px}.local-mailbox{background:#f5f0e8;border:1px solid #e6d5b8;border-radius:8px;padding:12px;margin-top:16px;font-size:11px;color:#70512e}.local-mailbox p{margin:5px 0}.local-mailbox code{font-size:22px;letter-spacing:5px}@media(max-width:900px){.login-intro{padding:32px}.login-promise h1{font-size:32px}.login-content{padding:28px}}@media(max-width:650px){.login-page{display:block}.login-intro{min-height:auto;padding:24px;gap:30px}.login-promise h1{font-size:28px;margin-bottom:10px}.login-promise p,.login-intro>small{display:none}.login-content{padding:36px 24px}.login-form h2{font-size:24px}}
</style>
