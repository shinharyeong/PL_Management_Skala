<script setup>
import copy from './copy-text.js'
import {isDevelopmentEntry} from '../lib/development-entry.js'
import PButton from 'primevue/button'
import PInputText from 'primevue/inputtext'
import PDialog from 'primevue/dialog'
import PMenu from 'primevue/menu'
import PBadge from 'primevue/badge'
import PMessage from 'primevue/message'
import PProgressBar from 'primevue/progressbar'
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePrototypeStore } from './live-store.js'
const HomePanel = defineAsyncComponent(() => import('./LiveHomePanel.vue'))
const MonthlyPanel = defineAsyncComponent(() => import('./MonthlyPanel.vue'))
const OrgPanel = defineAsyncComponent(() => import('./LiveOrgPanel.vue'))
const ManagementPanels = defineAsyncComponent(() => import('./LiveManagementPanels.vue'))
const ReportPanel = defineAsyncComponent(() => import('./LiveReportPanel.vue'))
/** 목표 의사결정 화면을 해당 메뉴 진입 시 불러온다.
 * Args: 없음. Returns: Vue 컴포넌트 모듈 Promise.
 * Raises: 모듈 로드 오류. Side Effects: 화면 코드 지연 로드.
 */
function loadDecisionAgentPanel() { return import('./LiveDecisionAgentPanel.vue') }
const DecisionAgentPanel = defineAsyncComponent(loadDecisionAgentPanel)
/** 조직의 개선 검토·실행 화면을 진입 시 불러온다.
 * Args: 없음. Returns: Vue 모듈 Promise. Raises: 모듈 로드 오류.
 * Side Effects: 화면 코드 지연 로드.
 */
function loadOrgImprovementPanel() { return import('./OrgImprovementPanel.vue') }
const OrgImprovementPanel = defineAsyncComponent(loadOrgImprovementPanel)
import { clockText } from './auth-demo.js'
import { pageTitles } from './router.js'
import NavIcon from './NavIcon.vue'
import FeedbackToast from './FeedbackToast.vue'

const CopyEditor = defineAsyncComponent(() => import('./CopyEditor.vue'))
const store = usePrototypeStore()
const route = useRoute(), router = useRouter()
watch(()=>store.error,value=>{if(value&&store.toast?.message!==value)store.notify(value,'error')})
const page = computed(() => String(route.name || 'login'))
const email = ref(''), code = ref(''), authStep = ref('email'), authError = ref('')
const logoutVisible = ref(false)
const logoutDialog = ref(null)
const sentAt = ref(null), now = ref(Date.now())
const authResponse = ref(null)
const challenge = computed(() => ({remaining: Math.max(0,Math.ceil((Date.parse(authResponse.value?.expiresAt)-now.value)/1000))||0, resendIn:Math.max(0,Math.ceil((Date.parse(authResponse.value?.resendAvailableAt)-now.value)/1000))||0, expired:authResponse.value && now.value >= Date.parse(authResponse.value.expiresAt)}))
const authNotice = ref('')
const developmentEntry = isDevelopmentEntry(window.location.pathname)
const localAccounts=ref([]), devAccountsError=ref('')
// 배포 환경은 접근 키가 있어야 계정 목록·로그인이 열린다(로컬은 키 없이 열림). 키는 메모리에만 둔다.
const devAccessKey=ref(''), devKeyRequired=ref(false)
async function loadDevAccounts() {
  devAccountsError.value=''
  try {
    localAccounts.value=await store.developmentAccounts(devAccessKey.value.trim())
    devKeyRequired.value=false
    if(!localAccounts.value.length) devAccountsError.value='DB에 활성 계정이 없습니다.'
  } catch(e) {
    localAccounts.value=[]
    if(e.status===403) { devKeyRequired.value=true; if(devAccessKey.value) devAccountsError.value='접근 키가 맞지 않습니다.' }
    else devAccountsError.value='개발용 계정 목록을 불러오지 못했습니다. 서버의 개발 도구 활성화 설정을 확인해 주세요.'
  }
}
onMounted(()=>{ if(developmentEntry) loadDevAccounts() })
/** 로컬 인증 서버에서 선택 계정의 세션을 발급한다.
 * Args: account 로컬 계정. Returns: Promise<void>.
 * Raises: 서버 오류는 store.run이 표시한다. Side Effects: 인증 요청과 화면 이동.
 */
async function localSignIn(account) {
  if(!developmentEntry || store.busy) return
  if(await store.run(()=>store.developmentLogin(account.email,devAccessKey.value.trim()))) navigate(store.actor.role==='planner'?'home':'org-requests')
}

let timer
onMounted(() => { timer = window.setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => window.clearInterval(timer))
const plannerMenus = [{ id: 'notifications', label: '알림 모아보기' }, { id: 'home', label: '전사 손익 현황' }, { id: 'decision-agent', label: '목표 의사결정 Agent' }, { id: 'monthly', label: '월간 시트 관리' }, { id: 'annual', label: '연간 계획 관리' }, { id: 'organizations', label: '조직별 현황' }, { id: 'reports', label: '보고서 생성' }]
const orgMenus = [{ id: 'org-requests', label: '요청함', icon: 'inbox' }, { id: 'org-improvements', label: '개선 검토·실행 과제' }, { id: 'org-history', label: '제출 이력 관리', icon: 'history' }]
const menus = computed(() => store.actor?.role === 'org' ? orgMenus : plannerMenus)
const menuModel = computed(() => menus.value.flatMap(menu => [
  { ...menu, label: copy.pages[menu.id] || menu.label, count: menu.id === 'notifications' ? store.unread : null },
  ...(menu.id === 'monthly' ? store.workspaces.map(work => ({ id: 'workspace', org: work.org, label: work.name, count: work.count })) : []),
]))

/** 메인 메뉴 또는 조직 작업공간으로 이동한다.
 * Args: item 메뉴 항목과 선택적 org 코드.
 * Returns: 없음. Raises: 라우터 오류.
 * Side Effects: 활성 조직을 갱신하고 라우트를 이동한다.
 */
function selectMenu(item) {
  if (item.org) store.activeOrg = item.org
  navigate(item.id)
}

/** 메뉴의 현재 페이지 여부를 반환한다.
 * Args: item 메뉴 항목과 선택적 org 코드.
 * Returns: boolean. Raises: 없음. Side Effects: 없음.
 */
function itemSelected(item) {
  return item.org ? ['workspace', 'cell'].includes(page.value) && store.activeOrg === item.org : menuSelected(item.id)
}
const monthlyPages = ['monthly', 'monthly-request', 'calculator', 'workspace', 'cell']
/** 페이지 그룹에 속하는 메뉴인지 확인한다.
 * Args: id 메뉴 ID. Returns: boolean.
 * Raises: 없음. Side Effects: 없음.
 */
function menuSelected(id) { return id === 'monthly' ? monthlyPages.includes(page.value) : id === 'annual' ? page.value.startsWith('annual') : id === 'org-requests' ? ['org-requests', 'org-upload', 'org-explanation'].includes(page.value) : page.value === id }
/** 지정한 이름의 화면으로 이동한다.
 * Args: id 라우트 이름. Returns: 없음.
 * Raises: 라우터 오류. Side Effects: 브라우저 경로 변경.
 */
function navigate(id) { router.push({ name: id }) }
async function openWorkspace(org){store.activeOrg=org;if(await store.run(()=>store.loadGap(org,store.monthlySession(org).applied?.type)))navigate('cell')}
/** 건너뛰기 링크의 본문에 초점을 둔다.
 * Args: 없음. Returns: 없음.
 * Raises: 없음. Side Effects: DOM 초점 변경.
 */
function focusMain() { document.getElementById('main-content')?.focus() }
watch(page, async value => {
  document.title = `${pageTitles[value] || '로그인'} · 경영팀을 부탁해`
  await nextTick()
  window.scrollTo({ top: 0 })
  const heading = document.querySelector('main h1')
  heading?.setAttribute('tabindex', '-1')
  heading?.focus({ preventScroll: true })
}, { immediate: true })
/** 입력 이메일로 인증코드를 요청하고 입력 단계로 전환한다.
 * Args: 없음. Returns: Promise<void>.
 * Raises: 없음; 화면 오류로 변환한다. Side Effects: 인증 요청과 입력·로딩·초점 상태 변경.
 */
async function requestCode() {
  if(store.busy)return
  store.busy=true; authError.value=''
  try {authResponse.value=await store.requestCode(email.value.trim());now.value=Date.now();code.value='';authStep.value='code';authNotice.value='등록된 이메일로 발송한 인증코드를 입력해 주세요.';await nextTick();document.querySelector('#auth-code')?.focus()}
  catch(e){authError.value=e.message}finally{store.busy=false}
}
/** 재전송 대기 시간이 끝나면 새 코드를 요청한다.
 * Args: 없음. Returns: Promise<void>.
 * Raises: 없음. Side Effects: requestCode의 인증 요청과 상태 변경.
 */
async function resendCode(){if(!challenge.value.resendIn)await requestCode()}
/** 이메일 입력 단계로 인증 상태를 초기화한다.
 * Args: 없음. Returns: 없음.
 * Raises: 없음. Side Effects: 인증 화면 메모리 상태 변경.
 */
function changeEmail(){authStep.value='email';code.value='';authResponse.value=null;authError.value='';authNotice.value=''}
/** 이메일과 인증코드로 로그인한다.
 * Args: 없음. Returns: Promise<void>.
 * Raises: 없음; store.run이 표시한다. Side Effects: 서버 세션 요청과 권한별 화면 이동.
 */
async function signIn(){if(await store.run(()=>store.login(authResponse.value.email,code.value)))navigate(store.actor.role==='planner'?'home':'org-requests');else authError.value=store.error}
/** 서버 세션을 해제하고 로그인 화면으로 돌아간다.
 * Args: 없음. Returns: Promise<void>.
 * Raises: 없음; store.run이 표시한다. Side Effects: 로그아웃 요청과 인증 상태·대화상자·경로 변경.
 */
async function logout(){if(await store.run(()=>store.logout())){changeEmail();email.value='';logoutVisible.value=false;logoutDialog.value?.close();router.replace({name:'login'})}}
watch(()=>store.actor,actor=>{if(!actor)router.replace({name:'login'})})
let pageLoad=0
onUnmounted(()=>pageLoad++)
watch(page,async value=>{
  const current=++pageLoad
  if(!store.actor)return
  try{
    await store.refreshBadge()
    if(current===pageLoad&&['monthly','monthly-request'].includes(value))await store.loadMonthly()
  }catch(error){if(current===pageLoad&&error.name!=='AbortError')store.error=error.message}
}, { immediate: true })
</script>

<template>
  <CopyEditor v-if="developmentEntry" />
  <div v-if="developmentEntry" class="dev-entry-notice">개발용 화면 · 계정 선택 로그인 · 공통 문구 편집 <a href="/">일반 화면</a></div>
  <FeedbackToast />
  <div v-if="developmentEntry && !store.actor" class="prototype-banner"><span data-copy-key="shellText.text001">{{ copy.shellText.text001 }}</span> <span><span data-copy-key="shellText.text002">{{ copy.shellText.text002 }}</span></span></div>
  <div v-if="page === 'login'" class="auth-page">
    <a class="brand auth-brand" href="#/login"><img class="brand-logo" src="/assets/celljango-logo.svg" alt="셀장고" width="236" height="100"></a>
    <main class="auth-main">
      <section v-if="developmentEntry && authStep === 'email'" class="auth-form local-account-form"><h1><span data-copy-key="shellText.text003">{{ copy.shellText.text003 }}</span></h1><p class="muted"><span data-copy-key="shellText.text004">{{ copy.shellText.text004 }}</span></p><p class="muted">DB에 있는 계정을 선택하면 이메일 인증 없이 로그인합니다.</p><form v-if="devKeyRequired" class="dev-access-key" @submit.prevent="loadDevAccounts"><label for="dev-access-key">접근 키</label><input id="dev-access-key" v-model="devAccessKey" type="password" autocomplete="off" required><button class="btn primary" type="submit" :disabled="!devAccessKey.trim()">확인</button></form><p v-if="devAccountsError" class="error" role="alert">{{devAccountsError}}</p><p v-if="authError" class="error" role="alert">{{authError}}</p><div class="local-account-list"><button v-for="account in localAccounts" :key="account.email" class="local-account-button" :disabled="store.busy" @click="localSignIn(account)"><strong>{{account.name}}</strong><span>{{account.hint}}</span></button></div><p v-if="store.busy" class="muted" role="status"><span data-copy-key="shellText.text005">{{ copy.shellText.text005 }}</span></p><p v-if="store.error" class="error" role="alert">{{store.error}}</p></section>
      <form v-else class="auth-form" @submit.prevent="authStep === 'email' ? requestCode() : signIn()">
        <h1>{{ authStep === 'email' ? '로그인' : '인증코드 입력' }}</h1>
        <p class="muted auth-description">{{ authStep === 'email' ? '회사 이메일로 인증 메일을 받아\n로그인해 주세요.' : `${email}\n인증코드 6자리를 입력해 주세요.` }}</p>
        <div v-if="authStep === 'email'" class="field">
          <label for="company-email"><span data-copy-key="login.emailLabel">{{ copy.login.emailLabel }}</span></label>
          <input id="company-email" v-model="email" type="email" autocomplete="email" required placeholder="kim.plan@sk.com" :aria-invalid="!!authError" aria-describedby="auth-help auth-error" @input="authError = ''" />
          <small id="auth-help" class="muted"><span data-copy-key="login.emailHelp">{{ copy.login.emailHelp }}</span></small>
        </div>
        <div v-else class="field">
          <div class="row-between"><label for="auth-code"><span data-copy-key="shellText.text006">{{ copy.shellText.text006 }}</span></label><span class="muted"><span data-copy-key="shellText.text007">{{ copy.shellText.text007 }}</span> {{ clockText(challenge.remaining) }}</span></div>
          <input id="auth-code" v-model="code" inputmode="numeric" maxlength="6" pattern="[0-9]{6}" autocomplete="one-time-code" placeholder="6자리 인증코드" required :aria-invalid="!!authError" aria-describedby="auth-error auth-code-help" @input="authError = ''" />
          <small id="auth-code-help" class="muted"><span data-copy-key="login.codeHelp">{{ copy.login.codeHelp }}</span></small>
          <p v-if="challenge.expired" class="error" role="status"><span data-copy-key="shellText.text008">{{ copy.shellText.text008 }}</span></p>
          <div class="row-between"><button type="button" class="text-button" :disabled="challenge.resendIn > 0" @click="resendCode">{{ challenge.resendIn ? `${clockText(challenge.resendIn)} 후 재전송` : '인증코드 다시 받기' }}</button></div>
        </div>
        <p v-if="authError" id="auth-error" class="error" role="alert">{{ authError }}</p>
        <p v-if="authNotice && authStep === 'code'" class="muted" role="status">{{ authNotice }}</p>
        <button class="btn primary auth-cta" type="submit" :disabled="store.busy || (authStep === 'code' && challenge.expired)">{{ authStep === 'email' ? '인증 메일 받기' : '로그인' }}</button>
        <p class="muted"><span data-copy-key="login.loginHelp">{{ copy.login.loginHelp }}</span></p>

      </form>
    </main>
    <footer class="muted"><span data-copy-key="login.footer">{{ copy.login.footer }}</span></footer>
  </div>
  <div v-else-if="store.actor" class="app-layout top-layout" :class="{'development-workspace':developmentEntry}">
    <a class="skip-link" href="#main-content" @click.prevent="focusMain"><span data-copy-key="shellText.text015">{{ copy.shellText.text015 }}</span></a>
    <header class="topbar">
      <a class="brand topbar-brand" :href="store.actor.role === 'org' ? '#/org-requests' : '#/home'"><img class="brand-logo" src="/assets/celljango-logo.svg" alt="셀장고" width="236" height="100"></a>
      <nav class="top-nav" aria-label="주 메뉴">
        <a v-for="menu in menus" :key="menu.id" :href="`#/${menu.id}`" class="top-link" :class="{ active: menuSelected(menu.id) }" :aria-current="menuSelected(menu.id) ? 'page' : undefined">
          <img v-if="menu.icon" :src="`/assets/${menu.icon}.svg`" alt="" />
          <NavIcon v-else :name="menu.id" />
          <span><span :data-copy-key="'pages.'+menu.id">{{ copy.pages[menu.id] || menu.label }}</span></span><span v-if="menu.id === 'notifications' && store.unread" class="top-count">{{ store.unread }}</span>
        </a>
      </nav>
      <div class="topbar-user"><span class="user-avatar" aria-hidden="true">{{ store.actor.name.slice(0,1) }}</span><span class="topbar-user-text"><strong>{{ store.actor.name }}</strong><small>{{ store.actor.role === 'org' ? store.actor.department : '경영기획팀' }}</small></span><button class="text-button" @click="logoutDialog.showModal()"><span data-copy-key="login.logout">{{ copy.login.logout }}</span></button></div>
    </header>
    <div v-if="monthlyPages.includes(page) && store.workspaces.length" class="workspace-tabs" role="group" aria-label="검토 중인 조직">
      <span class="workspace-tabs-label">검토 중인 조직</span>
      <button v-for="work in store.workspaces" :key="work.org" class="workspace-tab" :class="{ active: ['workspace','cell'].includes(page) && store.activeOrg === work.org }" @click="openWorkspace(work.org)"><span>{{ work.name }}</span><span class="top-count">{{ work.count }}</span></button>
    </div>
    <div class="workspace-shell">
    <main id="main-content" class="main-content" :class="{ 'home-overview': page === 'home' }" tabindex="-1">
      <p v-if="store.error" class="notice error" role="alert">{{ store.error }}</p><p v-if="store.busy" class="muted" role="status"><span data-copy-key="shellText.text016">{{ copy.shellText.text016 }}</span></p>
      <HomePanel v-if="page === 'home'" @navigate="navigate" />
      <DecisionAgentPanel v-else-if="page === 'decision-agent'" />
      <MonthlyPanel v-else-if="monthlyPages.includes(page)" :page="page" @navigate="navigate" />
      <OrgImprovementPanel v-else-if="page === 'org-improvements'" />
      <OrgPanel v-else-if="page.startsWith('org-')" :page="page" @navigate="navigate" />
      <ReportPanel v-else-if="page==='reports'" /><ManagementPanels v-else :page="page" @navigate="navigate" />
    </main>
    </div>
  </div>
  <dialog ref="logoutDialog" class="confirm-dialog" aria-labelledby="logout-title"><h2 id="logout-title"><span data-copy-key="login.logoutTitle">{{ copy.login.logoutTitle }}</span></h2><p class="muted"><span data-copy-key="login.logoutHelp">{{ copy.login.logoutHelp }}</span></p><div class="actions"><button class="btn" @click="logoutDialog.close()"><span data-copy-key="login.cancel">{{ copy.login.cancel }}</span></button><button class="btn primary" @click="logout"><span data-copy-key="login.logout">{{ copy.login.logout }}</span></button></div></dialog>
</template>

<style>
.dev-entry-notice{padding:8px 20px;background:#fff2d4;color:#734b0a;font:13px/1.6 system-ui;display:flex;gap:16px;flex-wrap:wrap;align-items:center}.dev-entry-notice a{color:inherit;text-decoration:underline}
</style>
