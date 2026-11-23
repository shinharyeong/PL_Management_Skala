<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePrototypeStore } from './store.js'
import HomePanel from './HomePanel.vue'
import MonthlyPanel from './MonthlyPanel.vue'
import OrgPanel from './OrgPanel.vue'
import ManagementPanels from './ManagementPanels.vue'
import { challengeState, clockText } from './auth-demo.js'
import { pageTitles } from './router.js'

const store = usePrototypeStore()
const route = useRoute(), router = useRouter()
const page = computed(() => String(route.name || 'login'))
const email = ref(''), code = ref(''), authStep = ref('email'), authError = ref('')
const logoutDialog = ref(null)
const sentAt = ref(null), now = ref(Date.now())
const challenge = computed(() => challengeState(sentAt.value, now.value))
const authNotice = ref('')
let timer
onMounted(() => { timer = window.setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => window.clearInterval(timer))
const accounts = { 'kim.plan@sk.com': { role: 'planner', name: '김기획 매니저', department: 'SKT 경영기획팀' }, 'choi.infra@sk.com': { role: 'org', orgId: 'F10', name: '최인프 매니저', department: '네트워크인프라부문' } }
const plannerMenus = [{ id: 'notifications', label: '알림 모아보기' }, { id: 'home', label: '전사 손익 현황' }, { id: 'monthly', label: '월간 시트 관리' }, { id: 'annual', label: '연간 계획 관리' }, { id: 'organizations', label: '조직별 현황' }, { id: 'reports', label: '보고서 생성' }]
const orgMenus = [{ id: 'org-requests', label: '요청함', icon: 'inbox' }, { id: 'org-history', label: '제출 이력 관리', icon: 'history' }]
const menus = computed(() => store.actor?.role === 'org' ? orgMenus : plannerMenus)
const monthlyPages = ['monthly', 'monthly-request', 'calculator', 'workspace', 'cell']
const menuSelected = id => id === 'monthly' ? monthlyPages.includes(page.value) : id === 'annual' ? page.value.startsWith('annual') : id === 'org-requests' ? ['org-requests', 'org-upload', 'org-explanation'].includes(page.value) : page.value === id
function navigate(id) { router.push({ name: id }) }
function focusMain() { document.getElementById('main-content')?.focus() }
watch(page, async value => {
  document.title = `${pageTitles[value] || '로그인'} · 경영팀을 부탁해 (체험)`
  await nextTick()
  window.scrollTo({ top: 0 })
  const heading = document.querySelector('main h1')
  heading?.setAttribute('tabindex', '-1')
  heading?.focus({ preventScroll: true })
}, { immediate: true })
function issueDemoCode() {
  now.value = Date.now(); sentAt.value = now.value
  code.value = ''; authError.value = ''
  authNotice.value = '체험 코드가 준비되었습니다. 실제 이메일은 발송하지 않습니다.'
  nextTick(() => document.querySelector('#auth-code')?.focus())
}
function requestCode() {
  const key = email.value.trim().toLowerCase()
  if (!accounts[key]) { authError.value = '등록되지 않은 이메일입니다. 등록된 사내 이메일을 확인해 주세요.'; return }
  authStep.value = 'code'; issueDemoCode()
}
function resendCode() {
  now.value = Date.now()
  if (!challenge.value.resendIn) issueDemoCode()
}
function changeEmail() {
  authStep.value = 'email'; code.value = ''; sentAt.value = null; authError.value = ''; authNotice.value = ''
  nextTick(() => document.querySelector('#company-email')?.focus())
}
function signIn() {
  now.value = Date.now()
  if (challenge.value.expired) { authError.value = '인증코드가 만료되었습니다. 코드를 다시 받아 주세요.'; return }
  if (code.value !== '123456') { authError.value = '인증코드를 확인해 주세요.'; return }
  store.actor = { ...accounts[email.value.trim().toLowerCase()], email: email.value.trim() }
  authError.value = ''; navigate(store.actor.role === 'planner' ? 'home' : 'org-requests')
}
function logout() { store.actor = null; changeEmail(); email.value = ''; logoutDialog.value.close(); router.replace({ name: 'login' }) }
</script>

<template>
  <div class="prototype-banner">프론트 프로토타입 <span>예시 데이터 · 서버·이메일·AI 미연결 · 새로고침 시 체험 상태 초기화</span></div>
  <div v-if="page === 'login'" class="auth-page">
    <a class="brand auth-brand" href="#/login">경영팀을 부탁해</a>
    <main class="auth-main">
      <form class="auth-form" @submit.prevent="authStep === 'email' ? requestCode() : signIn()">
        <h1>{{ authStep === 'email' ? '로그인' : '인증코드 입력' }}</h1>
        <p class="muted auth-description">{{ authStep === 'email' ? '회사 이메일로 인증 메일을 받아\n로그인해 주세요.' : `${email}\n인증코드 6자리를 입력해 주세요.` }}</p>
        <div v-if="authStep === 'email'" class="field">
          <label for="company-email">회사 이메일</label>
          <input id="company-email" v-model="email" type="email" autocomplete="email" required placeholder="kim.plan@sk.com" :aria-invalid="!!authError" aria-describedby="auth-help auth-error" @input="authError = ''" />
          <small id="auth-help" class="muted">등록된 사내 이메일 계정으로 이용할 수 있습니다.</small>
        </div>
        <div v-else class="field">
          <div class="row-between"><label for="auth-code">인증코드</label><span class="muted">남은 시간 {{ clockText(challenge.remaining) }}</span></div>
          <input id="auth-code" v-model="code" inputmode="numeric" maxlength="6" pattern="[0-9]{6}" autocomplete="one-time-code" placeholder="6자리 인증코드" required :aria-invalid="!!authError" aria-describedby="auth-error auth-code-help" @input="authError = ''" />
          <small id="auth-code-help" class="muted">체험 인증코드: <b>123456</b> (실제 메일은 발송하지 않습니다.)</small>
          <p v-if="challenge.expired" class="error" role="status">유효시간 3분이 지났습니다. 코드를 다시 받아 주세요.</p>
          <div class="row-between"><button type="button" class="text-button" @click="changeEmail">이메일 변경</button><button type="button" class="text-button" :disabled="challenge.resendIn > 0" @click="resendCode">{{ challenge.resendIn ? `${clockText(challenge.resendIn)} 후 재전송` : '인증코드 다시 받기' }}</button></div>
        </div>
        <p v-if="authError" id="auth-error" class="error" role="alert">{{ authError }}</p>
        <p v-if="authNotice && authStep === 'code'" class="muted" role="status">{{ authNotice }}</p>
        <button class="btn primary auth-cta" type="submit" :disabled="authStep === 'code' && challenge.expired">{{ authStep === 'email' ? '인증 메일 받기' : '로그인' }}</button>
        <p class="muted">메일의 로그인 링크 또는 6자리 코드로<br />로그인할 수 있습니다.</p>
        <aside class="auth-demo"><strong>프로토타입 체험 계정</strong><p>경영기획팀 <code>kim.plan@sk.com</code></p><p>조직 담당자 <code>choi.infra@sk.com</code></p></aside>
      </form>
    </main>
    <footer class="muted">사내 계정 전용</footer>
  </div>
  <div v-else-if="store.actor" class="app-layout">
    <a class="skip-link" href="#main-content" @click.prevent="focusMain">본문으로 건너뛰기</a>
    <aside class="sidebar">
      <a class="brand" :href="store.actor.role === 'org' ? '#/org-requests' : '#/home'">경영팀을 부탁해</a>
      <p class="role-caption">{{ store.actor.role === 'org' ? '조직 담당자' : '경영기획팀' }}</p>
      <nav aria-label="주 메뉴">
        <template v-for="menu in menus" :key="menu.id">
          <a :href="`#/${menu.id}`" class="nav-link" :class="{ active: menuSelected(menu.id) }" :aria-current="menuSelected(menu.id) ? 'page' : undefined">
            <img v-if="menu.icon" :src="`/assets/${menu.icon}${menuSelected(menu.id) ? '-active' : ''}.svg`" alt="" />
            <span>{{ menu.label }}</span><span v-if="menu.id === 'notifications' && store.unread" class="nav-count">{{ store.unread }}</span>
          </a>
          <div v-if="menu.id === 'monthly' && store.workspaces.length" class="workspace-nav">
            <button v-for="work in store.workspaces" :key="work.org" class="nav-link" :class="{ active: ['workspace','cell'].includes(page) && store.activeOrg === work.org }" @click="store.activeOrg = work.org; navigate('workspace')"><span>{{ work.name }}</span><span class="nav-count">{{ work.count }}</span></button>
          </div>
        </template>
      </nav>
      <div class="sidebar-user"><strong>{{ store.actor.name }}</strong><small>{{ store.actor.department }}</small><button class="text-button" @click="logoutDialog.showModal()">로그아웃</button></div>
    </aside>
    <main id="main-content" class="main-content" tabindex="-1">
      <HomePanel v-if="page === 'home'" @navigate="navigate" />
      <MonthlyPanel v-else-if="monthlyPages.includes(page)" :page="page" @navigate="navigate" />
      <OrgPanel v-else-if="page.startsWith('org-')" :page="page" @navigate="navigate" />
      <ManagementPanels v-else :page="page" @navigate="navigate" />
    </main>
  </div>
  <dialog ref="logoutDialog" class="confirm-dialog" aria-labelledby="logout-title"><h2 id="logout-title">로그아웃할까요?</h2><p class="muted">다시 이용하려면 회사 이메일로 로그인해 주세요.</p><div class="actions"><button class="btn" @click="logoutDialog.close()">취소</button><button class="btn primary" @click="logout">로그아웃</button></div></dialog>
</template>
