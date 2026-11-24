<script setup>
import copy from './ui-copy.json'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePrototypeStore } from './live-store.js'
import HomePanel from './LiveHomePanel.vue'
import MonthlyPanel from './MonthlyPanel.vue'
import OrgPanel from './LiveOrgPanel.vue'
import ManagementPanels from './LiveManagementPanels.vue'
import ReportPanel from './LiveReportPanel.vue'
import { clockText } from './auth-demo.js'
import { pageTitles } from './router.js'
import NavIcon from './NavIcon.vue'
import FeedbackToast from './FeedbackToast.vue'

const store = usePrototypeStore()
const route = useRoute(), router = useRouter()
watch(()=>store.error,value=>{if(value&&store.toast?.message!==value)store.notify(value,'error')})
const page = computed(() => String(route.name || 'login'))
const email = ref(''), code = ref(''), authStep = ref('email'), authError = ref('')
const logoutDialog = ref(null)
const sentAt = ref(null), now = ref(Date.now())
const authResponse = ref(null)
const challenge = computed(() => ({remaining: Math.max(0,Math.ceil((Date.parse(authResponse.value?.expiresAt)-now.value)/1000))||0, resendIn:Math.max(0,Math.ceil((Date.parse(authResponse.value?.resendAvailableAt)-now.value)/1000))||0, expired:authResponse.value && now.value >= Date.parse(authResponse.value.expiresAt)}))
const authNotice = ref('')
const localDevelopment = import.meta.env.DEV && ['localhost','127.0.0.1','[::1]'].includes(window.location.hostname)
const localAccounts = import.meta.env.DEV ? [{email:'kim.plan@sk.com',name:'경영기획팀',hint:'김기획 매니저'}, ...['F10','F12','F20','F40','F39','F50','F16','F13','F25','F43','F42','F44','F73','F38','F66','F36','F37'].map((code,index)=>({email:code.toLowerCase()+'.test@sk.com',name:`사업부${index+1}`,hint:code+' · 조직 담당자'}))] : []
async function localSignIn(account) {
  if (!localDevelopment || store.busy) return
  const ok = await store.run(async()=>{
    const issued = await store.requestCode(account.email)
    const response = await fetch('/__local/auth-code',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(issued),cache:'no-store'})
    if (!response.ok) throw new Error('로컬 간편 접속을 사용할 수 없습니다. 로컬 인증코드 연결 설정을 확인해 주세요.')
    const {code:issuedCode} = await response.json()
    await store.login(account.email,issuedCode)
  })
  if (ok) navigate(store.actor.role==='planner'?'home':'org-requests')
}
const localAuthCode = ref(''), localCodeNotice = ref('')
async function loadLocalCode(response) {
  if (!localDevelopment) return
  try {
    const result = await fetch('/__local/auth-code', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({email:response.email, expiresAt:response.expiresAt}), cache:'no-store'})
    if (!result.ok) throw new Error()
    const data = await result.json()
    if (authResponse.value === response && /^\d{6}$/.test(data.code)) localAuthCode.value = data.code
  } catch { localCodeNotice.value = '인증코드를 표시하지 못했습니다. 로컬 서버 설정을 확인해 주세요.' }
}
let timer
onMounted(() => { timer = window.setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => window.clearInterval(timer))
const plannerMenus = [{ id: 'notifications', label: '알림 모아보기' }, { id: 'home', label: '전사 손익 현황' }, { id: 'monthly', label: '월간 시트 관리' }, { id: 'annual', label: '연간 계획 관리' }, { id: 'organizations', label: '조직별 현황' }, { id: 'reports', label: '보고서 생성' }]
const orgMenus = [{ id: 'org-requests', label: '요청함', icon: 'inbox' }, { id: 'org-history', label: '제출 이력 관리', icon: 'history' }]
const menus = computed(() => store.actor?.role === 'org' ? orgMenus : plannerMenus)
const monthlyPages = ['monthly', 'monthly-request', 'calculator', 'workspace', 'cell']
const menuSelected = id => id === 'monthly' ? monthlyPages.includes(page.value) : id === 'annual' ? page.value.startsWith('annual') : id === 'org-requests' ? ['org-requests', 'org-upload', 'org-explanation'].includes(page.value) : page.value === id
function navigate(id) { router.push({ name: id }) }
function focusMain() { document.getElementById('main-content')?.focus() }
watch(page, async value => {
  document.title = `${pageTitles[value] || '로그인'} · 경영팀을 부탁해`
  await nextTick()
  window.scrollTo({ top: 0 })
  const heading = document.querySelector('main h1')
  heading?.setAttribute('tabindex', '-1')
  heading?.focus({ preventScroll: true })
}, { immediate: true })
async function requestCode() {
  if(store.busy)return
  store.busy=true; authError.value=''; localAuthCode.value=''; localCodeNotice.value=''
  try {authResponse.value=await store.requestCode(email.value.trim());now.value=Date.now();code.value='';authStep.value='code';await loadLocalCode(authResponse.value);authNotice.value='등록된 이메일로 발송한 인증코드를 입력해 주세요.';await nextTick();document.querySelector('#auth-code')?.focus()}
  catch(e){authError.value=e.message}finally{store.busy=false}
}
async function resendCode(){if(!challenge.value.resendIn)await requestCode()}
function changeEmail(){authStep.value='email';code.value='';authResponse.value=null;localAuthCode.value='';localCodeNotice.value='';authError.value='';authNotice.value=''}
async function signIn(){if(await store.run(()=>store.login(authResponse.value.email,code.value)))navigate(store.actor.role==='planner'?'home':'org-requests');else authError.value=store.error}
async function logout(){if(await store.run(()=>store.logout())){changeEmail();email.value='';logoutDialog.value.close();router.replace({name:'login'})}}
watch(()=>store.actor,actor=>{if(!actor)router.replace({name:'login'})})
watch(page,async value=>{
  if(!store.actor)return
  await store.run(async()=>{
    await store.refreshBadge()
    if(['monthly','monthly-request'].includes(value))await store.loadMonthly()
  })
})
</script>

<template>
  <FeedbackToast />
  <div class="prototype-banner">경영팀을 부탁해 <span>서버 연결본 · 로컬 검증 환경</span></div>
  <div v-if="page === 'login'" class="auth-page">
    <a class="brand auth-brand" href="#/login">경영팀을 부탁해</a>
    <main class="auth-main">
      <section v-if="localDevelopment" class="auth-form local-account-form"><h1>로컬 계정 선택</h1><p class="muted">접속할 계정을 선택해 주세요.</p><div class="local-account-list"><button v-for="account in localAccounts" :key="account.email" class="local-account-button" :disabled="store.busy" @click="localSignIn(account)"><strong>{{account.name}}</strong><span>{{account.hint}}</span></button></div><p v-if="store.busy" class="muted" role="status">선택한 계정으로 접속 중입니다.</p><p v-if="store.error" class="error" role="alert">{{store.error}}</p></section>
      <form v-else class="auth-form" @submit.prevent="authStep === 'email' ? requestCode() : signIn()">
        <h1>{{ authStep === 'email' ? '로그인' : '인증코드 입력' }}</h1>
        <p class="muted auth-description">{{ authStep === 'email' ? '회사 이메일로 인증 메일을 받아\n로그인해 주세요.' : `${email}\n인증코드 6자리를 입력해 주세요.` }}</p>
        <div v-if="authStep === 'email'" class="field">
          <label for="company-email">{{ copy.login.emailLabel }}</label>
          <input id="company-email" v-model="email" type="email" autocomplete="email" required placeholder="kim.plan@sk.com" :aria-invalid="!!authError" aria-describedby="auth-help auth-error" @input="authError = ''" />
          <small id="auth-help" class="muted">{{ copy.login.emailHelp }}</small>
        </div>
        <div v-else class="field">
          <div class="row-between"><label for="auth-code">인증코드</label><span class="muted">남은 시간 {{ clockText(challenge.remaining) }}</span></div>
          <input id="auth-code" v-model="code" inputmode="numeric" maxlength="6" pattern="[0-9]{6}" autocomplete="one-time-code" placeholder="6자리 인증코드" required :aria-invalid="!!authError" aria-describedby="auth-error auth-code-help" @input="authError = ''" />
          <small id="auth-code-help" class="muted">{{ copy.login.codeHelp }}</small>
          <p v-if="challenge.expired" class="error" role="status">유효시간 3분이 지났습니다. 코드를 다시 받아 주세요.</p>
          <div class="row-between"><button type="button" class="text-button" :disabled="challenge.resendIn > 0" @click="resendCode">{{ challenge.resendIn ? `${clockText(challenge.resendIn)} 후 재전송` : '인증코드 다시 받기' }}</button></div>
        </div>
        <p v-if="authError" id="auth-error" class="error" role="alert">{{ authError }}</p>
        <p v-if="authNotice && authStep === 'code'" class="muted" role="status">{{ authNotice }}</p>
        <button class="btn primary auth-cta" type="submit" :disabled="store.busy || (authStep === 'code' && challenge.expired)">{{ authStep === 'email' ? '인증 메일 받기' : '로그인' }}</button>
        <p class="muted">{{ copy.login.loginHelp }}</p><aside v-if="localDevelopment" class="auth-demo"><strong>로컬 검증 환경</strong><p v-if="authStep === 'email'">인증 메일 받기를 누르면 발급된 인증코드가 여기에 표시됩니다.</p><p v-else-if="challenge.expired" role="status">인증코드가 만료되었습니다. 코드를 다시 받아 주세요.</p><p v-else-if="localAuthCode" role="status">발급된 인증코드 <strong class="local-auth-code">{{ localAuthCode }}</strong></p><p v-else role="status">{{ localCodeNotice || '인증코드를 확인하는 중입니다.' }}</p><p>경영기획팀 <code>kim.plan@sk.com</code></p><p>사업부15 담당자 <code>f66.test@sk.com</code></p></aside>

      </form>
    </main>
    <footer class="muted">{{ copy.login.footer }}</footer>
  </div>
  <div v-else-if="store.actor" class="app-layout">
    <a class="skip-link" href="#main-content" @click.prevent="focusMain">본문으로 건너뛰기</a>
    <aside class="sidebar">
      <a class="brand" :href="store.actor.role === 'org' ? '#/org-requests' : '#/home'"><span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>경영팀을 부탁해</a>
      <p class="role-caption">{{ store.actor.role === 'org' ? '조직 담당자' : '경영기획팀' }}</p>
      <nav aria-label="주 메뉴">
        <template v-for="menu in menus" :key="menu.id">
          <a :href="`#/${menu.id}`" class="nav-link" :class="{ active: menuSelected(menu.id) }" :aria-current="menuSelected(menu.id) ? 'page' : undefined">
            <img v-if="menu.icon" :src="`/assets/${menu.icon}${menuSelected(menu.id) ? '-active' : ''}.svg`" alt="" />
            <NavIcon v-else :name="menu.id" />
            <span>{{ copy.pages[menu.id] || menu.label }}</span><span v-if="menu.id === 'notifications' && store.unread" class="nav-count">{{ store.unread }}</span>
          </a>
          <div v-if="menu.id === 'monthly' && store.workspaces.length" class="workspace-nav">
            <button v-for="work in store.workspaces" :key="work.org" class="nav-link" :class="{ active: ['workspace','cell'].includes(page) && store.activeOrg === work.org }" @click="store.activeOrg = work.org; navigate('workspace')"><span>{{ work.name }}</span><span class="nav-count">{{ work.count }}</span></button>
          </div>
        </template>
      </nav>
      <div class="sidebar-user"><span class="user-avatar" aria-hidden="true">{{ store.actor.name.slice(0,1) }}</span><strong>{{ store.actor.name }}</strong><small>{{ store.actor.department }}</small><button class="text-button" @click="logoutDialog.showModal()">{{ copy.login.logout }}</button></div>
    </aside>
    <div class="workspace-shell">
    <main id="main-content" class="main-content" tabindex="-1">
      <p v-if="store.error" class="notice error" role="alert">{{ store.error }}</p><p v-if="store.busy" class="muted" role="status">처리 중…</p>
      <HomePanel v-if="page === 'home'" @navigate="navigate" />
      <MonthlyPanel v-else-if="monthlyPages.includes(page)" :page="page" @navigate="navigate" />
      <OrgPanel v-else-if="page.startsWith('org-')" :page="page" @navigate="navigate" />
      <ReportPanel v-else-if="page==='reports'" />
      <ManagementPanels v-else :page="page" @navigate="navigate" />
    </main>
    </div>
  </div>
  <dialog ref="logoutDialog" class="confirm-dialog" aria-labelledby="logout-title"><h2 id="logout-title">{{ copy.login.logoutTitle }}</h2><p class="muted">{{ copy.login.logoutHelp }}</p><div class="actions"><button class="btn" @click="logoutDialog.close()">{{ copy.login.cancel }}</button><button class="btn primary" @click="logout">{{ copy.login.logout }}</button></div></dialog>
</template>
