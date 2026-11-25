<script setup>
import copy from './copy-text.js'
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
import { clockText } from './auth-demo.js'
import { pageTitles } from './router.js'
import NavIcon from './NavIcon.vue'
import FeedbackToast from './FeedbackToast.vue'

const CopyEditor = import.meta.env.DEV ? defineAsyncComponent(() => import('./CopyEditor.vue')) : null
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
const localDevelopment = import.meta.env.DEV && ['localhost','127.0.0.1','[::1]'].includes(window.location.hostname)
const localAccounts = import.meta.env.DEV ? [{email:'kim.plan@sk.com',name:'경영기획팀',hint:'김기획 매니저'}, ...['F10','F12','F20','F40','F39','F50','F16','F13','F25','F43','F42','F44','F73','F38','F66','F36','F37'].map((code,index)=>({email:code.toLowerCase()+'.test@sk.com',name:`사업부${index+1}`,hint:code+' · 조직 담당자'}))] : []
/** 로컬 인증 서버에서 선택 계정의 세션을 발급한다.
 * Args: account 로컬 계정. Returns: Promise<void>.
 * Raises: 서버 오류는 store.run이 표시한다. Side Effects: 인증 요청과 화면 이동.
 */
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
/** 개발 환경에서 발급한 인증코드를 조회한다.
 * Args: response 발급 응답. Returns: Promise<void>.
 * Raises: 없음; 실패 안내로 변환한다. Side Effects: 로컬 HTTP 조회와 안내 상태 변경.
 */
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
async function openWorkspace(org){store.activeOrg=org;if(await store.run(()=>store.loadGap(org,store.monthlySession(org).applied?.type)))navigate('workspace')}
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
  store.busy=true; authError.value=''; localAuthCode.value=''; localCodeNotice.value=''
  try {authResponse.value=await store.requestCode(email.value.trim());now.value=Date.now();code.value='';authStep.value='code';await loadLocalCode(authResponse.value);authNotice.value='등록된 이메일로 발송한 인증코드를 입력해 주세요.';await nextTick();document.querySelector('#auth-code')?.focus()}
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
function changeEmail(){authStep.value='email';code.value='';authResponse.value=null;localAuthCode.value='';localCodeNotice.value='';authError.value='';authNotice.value=''}
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
watch(page,async value=>{
  if(!store.actor)return
  await store.run(async()=>{
    await store.refreshBadge()
    if(['monthly','monthly-request'].includes(value))await store.loadMonthly()
  })
}, { immediate: true })
</script>

<template>
  <CopyEditor v-if="localDevelopment" />
  <FeedbackToast />
  <div class="prototype-banner"><span data-copy-key="shellText.text001">{{ copy.shellText.text001 }}</span> <span><span data-copy-key="shellText.text002">{{ copy.shellText.text002 }}</span></span></div>
  <div v-if="page === 'login'" class="auth-page">
    <a class="brand auth-brand" href="#/login"><span data-copy-key="shellText.text001">{{ copy.shellText.text001 }}</span></a>
    <main class="auth-main">
      <section v-if="localDevelopment" class="auth-form local-account-form"><h1><span data-copy-key="shellText.text003">{{ copy.shellText.text003 }}</span></h1><p class="muted"><span data-copy-key="shellText.text004">{{ copy.shellText.text004 }}</span></p><div class="local-account-list"><button v-for="account in localAccounts" :key="account.email" class="local-account-button" :disabled="store.busy" @click="localSignIn(account)"><strong>{{account.name}}</strong><span>{{account.hint}}</span></button></div><p v-if="store.busy" class="muted" role="status"><span data-copy-key="shellText.text005">{{ copy.shellText.text005 }}</span></p><p v-if="store.error" class="error" role="alert">{{store.error}}</p></section>
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
        <p class="muted"><span data-copy-key="login.loginHelp">{{ copy.login.loginHelp }}</span></p><aside v-if="localDevelopment" class="auth-demo"><strong><span data-copy-key="shellText.text009">{{ copy.shellText.text009 }}</span></strong><p v-if="authStep === 'email'"><span data-copy-key="shellText.text010">{{ copy.shellText.text010 }}</span></p><p v-else-if="challenge.expired" role="status"><span data-copy-key="shellText.text011">{{ copy.shellText.text011 }}</span></p><p v-else-if="localAuthCode" role="status"><span data-copy-key="shellText.text012">{{ copy.shellText.text012 }}</span> <strong class="local-auth-code">{{ localAuthCode }}</strong></p><p v-else role="status">{{ localCodeNotice || '인증코드를 확인하는 중입니다.' }}</p><p><span data-copy-key="shellText.text013">{{ copy.shellText.text013 }}</span> <code>kim.plan@sk.com</code></p><p><span data-copy-key="shellText.text014">{{ copy.shellText.text014 }}</span> <code>f66.test@sk.com</code></p></aside>

      </form>
    </main>
    <footer class="muted"><span data-copy-key="login.footer">{{ copy.login.footer }}</span></footer>
  </div>
  <div v-else-if="store.actor" class="app-layout">
    <a class="skip-link" href="#main-content" @click.prevent="focusMain"><span data-copy-key="shellText.text015">{{ copy.shellText.text015 }}</span></a>
    <aside class="sidebar">
      <a class="brand" :href="store.actor.role === 'org' ? '#/org-requests' : '#/home'"><span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span><span data-copy-key="shellText.text001">{{ copy.shellText.text001 }}</span></a>
      <p class="role-caption">{{ store.actor.role === 'org' ? '조직 담당자' : '경영기획팀' }}</p>
      <nav aria-label="주 메뉴">
        <template v-for="menu in menus" :key="menu.id">
          <a :href="`#/${menu.id}`" class="nav-link" :class="{ active: menuSelected(menu.id) }" :aria-current="menuSelected(menu.id) ? 'page' : undefined">
            <img v-if="menu.icon" :src="`/assets/${menu.icon}${menuSelected(menu.id) ? '-active' : ''}.svg`" alt="" />
            <NavIcon v-else :name="menu.id" />
            <span><span :data-copy-key="'pages.'+menu.id">{{ copy.pages[menu.id] || menu.label }}</span></span><span v-if="menu.id === 'notifications' && store.unread" class="nav-count">{{ store.unread }}</span>
          </a>
          <div v-if="menu.id === 'monthly' && store.workspaces.length" class="workspace-nav">
            <button v-for="work in store.workspaces" :key="work.org" class="nav-link" :class="{ active: ['workspace','cell'].includes(page) && store.activeOrg === work.org }" @click="openWorkspace(work.org)"><span>{{ work.name }}</span><span class="nav-count">{{ work.count }}</span></button>
          </div>
        </template>
      </nav>
      <div class="sidebar-user"><span class="user-avatar" aria-hidden="true">{{ store.actor.name.slice(0,1) }}</span><strong>{{ store.actor.name }}</strong><small>{{ store.actor.department }}</small><button class="text-button" @click="logoutDialog.showModal()"><span data-copy-key="login.logout">{{ copy.login.logout }}</span></button></div>
    </aside>
    <div class="workspace-shell">
    <main id="main-content" class="main-content" tabindex="-1">
      <p v-if="store.error" class="notice error" role="alert">{{ store.error }}</p><p v-if="store.busy" class="muted" role="status"><span data-copy-key="shellText.text016">{{ copy.shellText.text016 }}</span></p>
      <HomePanel v-if="page === 'home'" @navigate="navigate" />
      <MonthlyPanel v-else-if="monthlyPages.includes(page)" :page="page" @navigate="navigate" />
      <OrgPanel v-else-if="page.startsWith('org-')" :page="page" @navigate="navigate" />
      <ReportPanel v-else-if="page==='reports'" /><ManagementPanels v-else :page="page" @navigate="navigate" />
    </main>
    </div>
  </div>
  <dialog ref="logoutDialog" class="confirm-dialog" aria-labelledby="logout-title"><h2 id="logout-title"><span data-copy-key="login.logoutTitle">{{ copy.login.logoutTitle }}</span></h2><p class="muted"><span data-copy-key="login.logoutHelp">{{ copy.login.logoutHelp }}</span></p><div class="actions"><button class="btn" @click="logoutDialog.close()"><span data-copy-key="login.cancel">{{ copy.login.cancel }}</span></button><button class="btn primary" @click="logout"><span data-copy-key="login.logout">{{ copy.login.logout }}</span></button></div></dialog>
</template>
