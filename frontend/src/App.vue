<script setup>
import { RouterView, useRoute, useRouter } from 'vue-router'
import { computed, provide, ref, watch } from 'vue'
import { useSession } from './stores/session'
import HomeView from './views/HomeView.vue'
import ProfileConfirmation from './components/ProfileConfirmation.vue'
import { routeAllowed } from './lib/role-route'

const session = useSession()
const route = useRoute()
const router = useRouter()
const logoutDialog = ref(null)
const logoutError = ref('')
const loggingOut = ref(false)
const organizationSubmissionContext = ref(null)
provide('organizationSubmissionContext', organizationSubmissionContext)
const plannerNavigation = [
  { path: '/planning/notifications', label: '알림 모아보기', name: 'planning-notifications' },
  { path: '/planning', label: '전사 손익 현황', name: 'planning' },
  { path: '/planning/gap', label: '월간 시트 관리', name: 'planning-gap' },
  { path: '/planning/annual-plan', label: '연간 계획 관리', name: 'planning-annual' },
  { path: '/planning/organizations', label: '조직별 현황', name: 'planning-organizations' },
  { path: '/planning/reports', label: '보고서 생성', name: 'planning-reports' },
]

/** 역할 변경 시 요청을 중단하고 입장 화면으로 돌아간다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 인증 초기화, 라우트 이동.
 */
async function changeRole() {
  loggingOut.value = true; logoutError.value = ''
  try { await session.logout(); await router.replace('/') }
  catch (cause) { logoutError.value = cause.message }
  finally { loggingOut.value = false }
}

/** 로그아웃 확인창을 연다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 모달 표시. */
function openLogout() { logoutError.value = ''; logoutDialog.value?.showModal() }
/** 배경 클릭을 취소로 처리한다. Args: event 포인터 이벤트. Returns: 없음. Raises: 없음. Side Effects: 모달 닫기. */
function cancelBackdrop(event) { if (event.target === logoutDialog.value && !loggingOut.value) logoutDialog.value.close() }
/** 사용자 또는 화면 이동 후 현재 소속·역할과 공통 알림 배지를 갱신한다. Args: 없음. Returns: Promise<void>. Raises: 없음. Side Effects: GET. */
async function refreshNavigation() { await session.refreshProfile(); await session.refreshUnread() }
/** 프로필 객체가 교체되어도 같은 사용자는 동일한 감시 값으로 유지한다. Args: 없음. Returns: 사용자 ID 또는 undefined. Raises: 없음. Side Effects: 없음. */
function navigationActorId() { return session.actor?.id }
/** 실제 화면 이동을 감시할 경로를 읽는다. Args: 없음. Returns: 전체 라우트 경로. Raises: 없음. Side Effects: 없음. */
function navigationPath() { return route.fullPath }
watch([navigationActorId, navigationPath], refreshNavigation, { immediate: true })

/** 초안·소명 보완의 실제 서버 상태로 조직 요청함 메뉴를 활성화한다.
 * Args: 없음. Returns: 요청함 활성 여부. Raises: 없음. Side Effects: 없음; 상태 조회 전에는 명시적 업로드 진입 문맥만 사용한다.
 */
const organizationInboxActive = computed(function isOrganizationInboxActive() {
  if (['requests', 'upload'].includes(route.name)) return true
  if (route.name !== 'submission') return false
  const context = organizationSubmissionContext.value
  if (context?.id === String(route.params.id) && context?.actorId === session.actor?.id) return context.status === 'DRAFT' || (context.status === 'PENDING' && context.unansweredCount > 0)
  return route.query.validate === '1' || route.query.from === 'requests'
})

/** 공용 이력·상세에서도 들어온 기획 메뉴를 활성 상태로 유지한다.
 * Args: name 기획 메뉴의 라우트 이름. Returns: 활성 여부. Raises: 없음. Side Effects: 없음.
 */
function plannerMenuActive(name) {
  if (route.name === name) return true
  if (['planning-annual-master', 'planning-requests'].includes(route.name) || ['annual-master','requests'].includes(route.query.from)) return name === 'planning-annual'
  if (['planning-monthly-master'].includes(route.name) || ['gap', 'monthly-master'].includes(route.query.from)) return name === 'planning-gap'
  if (!['history', 'submission'].includes(route.name)) return false
  return name === (route.query.from === 'requests' ? 'planning-annual' : 'planning-organizations')
}

/** 조직 검토 탭을 닫고 현재 탭이면 월간 전체 화면으로 돌아간다.
 * Args: key 작업공간 키. Returns: Promise<void>. Raises: 라우트 전환 오류. Side Effects: 메모리 탭 제거·경로 변경.
 */
async function closeWorkspace(key) { session.closeReviewWorkspace(key); if (route.query.workspace === key) await router.push({ path: '/planning/gap', query: { requestId: route.query.requestId } }) }
</script>

<template>
  <HomeView v-if="!session.actor" />
  <ProfileConfirmation v-else-if="!session.profileConfirmed" />
  <div v-else :class="['app-shell', { 'planner-shell': session.actor.role === 'PLANNER' }]">
    <a class="skip-link" href="#main-content">본문으로 건너뛰기</a>
    <aside class="sidebar">
      <div>
        <RouterLink :to="session.actor.role === 'PLANNER' ? '/planning' : '/requests'" class="brand"><span class="brand-mark"></span>손익관리</RouterLink>
        <p class="nav-label">{{ session.actor.role === 'PLANNER' ? '경영기획팀' : '조직 담당자' }}</p>
        <nav v-if="session.actor.role === 'PLANNER'" aria-label="경영기획팀 주 메뉴">
          <template v-for="item in plannerNavigation" :key="item.name"><RouterLink :to="item.path" :class="['nav-item', { active: plannerMenuActive(item.name) }]">
            <span class="nav-icon" aria-hidden="true"></span><span>{{ item.label }}</span><span v-if="item.name === 'planning-notifications' && session.unreadCount" class="nav-count">{{ session.unreadCount }}</span>
          </RouterLink><div v-if="item.name === 'planning-gap'" class="review-subnav"><div v-for="workspace in session.reviewWorkspaces" :key="workspace.key" :class="{ selected: route.query.workspace === workspace.key }"><RouterLink :to="{ path: '/planning/gap', query: { requestId: workspace.requestId, organizationId: workspace.organizationId, workspace: workspace.key } }">{{ workspace.organizationName }} <small>{{ workspace.reviewItemIds.length }}</small></RouterLink><button :aria-label="workspace.organizationName + ' 검토 탭 닫기'" @click="closeWorkspace(workspace.key)">×</button></div></div></template>
        </nav>
        <nav v-else aria-label="주 메뉴">
          <RouterLink to="/requests" :class="['nav-item', { active: organizationInboxActive }]">
            <span class="nav-icon"><img :src="organizationInboxActive ? '/assets/inbox-active.svg' : '/assets/inbox.svg'" alt="" /></span>
            {{ session.actor.role === 'PLANNER' ? '요청 관리' : '요청함' }}
          </RouterLink>
          <RouterLink to="/history" :class="['nav-item', { active: !organizationInboxActive && ['history', 'submission'].includes(route.name) }]">
            <span class="nav-icon"><img :src="!organizationInboxActive && ['history', 'submission'].includes(route.name) ? '/assets/history-active.svg' : '/assets/history.svg'" alt="" /></span>제출 이력 관리
          </RouterLink>
        </nav>
      </div>
      <div>
      <RouterLink v-if="session.actor.role === 'PLANNER'" to="/planning/settings" class="nav-item" :class="{ active: route.name === 'planning-settings' }">설정 · 조직·담당자·요청 템플릿</RouterLink>
      <div class="profile"><span class="avatar" aria-hidden="true"></span><div><strong>{{ session.actor.name.replace(' (데모)', '') }}</strong><small>{{ session.actor.organization?.name || '경영기획팀' }}</small></div><button class="logout-button" @click="openLogout">로그아웃</button></div>
      </div>
    </aside>
    <div class="workspace">
      <main id="main-content" class="main-content">
        <p v-if="!route.meta.plannerCanvas" class="breadcrumb">{{ session.actor.role === 'PLANNER' ? '경영기획팀' : '손익관리' }} <span>/</span> {{ route.meta.title || '요청함' }}</p>
        <RouterView v-if="routeAllowed(route, session.actor.role)" :key="`${session.actor.id}:${session.actor.organization?.id || 'planner'}:${route.fullPath}`" />
      </main>
    </div>
    <dialog ref="logoutDialog" class="request-dialog logout-dialog" aria-labelledby="logout-title" @click="cancelBackdrop">
      <h2 id="logout-title">로그아웃하시겠습니까?</h2><p>다시 이용하려면 로그인이 필요합니다.</p><p v-if="logoutError" class="alert error" role="alert">{{ logoutError }}</p><div class="button-row"><button class="button" :disabled="loggingOut" @click="logoutDialog.close()">취소</button><button class="button dark" :disabled="loggingOut" @click="changeRole">{{ loggingOut ? '로그아웃 중…' : '로그아웃' }}</button></div>
    </dialog>
  </div>
</template>

<style scoped>
.review-subnav{padding-left:20px}.review-subnav>div{display:flex;align-items:center;border-radius:7px;margin:3px 0}.review-subnav .selected{background:#eee8db}.review-subnav a{flex:1;padding:8px;color:inherit;font-size:12px;text-decoration:none}.review-subnav small{margin-left:5px;color:var(--muted)}.review-subnav button{background:transparent;padding:6px 10px;font-size:17px;color:var(--muted)}
</style>
