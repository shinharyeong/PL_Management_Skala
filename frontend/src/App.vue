<script setup>
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useSession } from './stores/session'
import HomeView from './views/HomeView.vue'

const session = useSession()
const route = useRoute()
const router = useRouter()
const plannerNavigation = [
  { path: '/planning', label: '홈 · 전사 손익', name: 'planning' },
  { path: '/planning/gap', label: '월간 시트 관리', name: 'planning-gap' },
  { path: '/planning/requests', label: '요청 · 검토', name: 'planning-requests' },
  { path: '/planning/annual-plan', label: '연간 계획', name: 'planning-annual' },
  { path: '/planning/organizations', label: '조직별 현황', name: 'planning-organizations' },
  { path: '/planning/reports', label: '보고서', name: 'planning-reports' },
]

/** 역할 변경 시 요청을 중단하고 입장 화면으로 돌아간다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 인증 초기화, 라우트 이동.
 */
function changeRole() {
  session.clear()
  router.replace('/')
}

/** 공용 이력·상세에서도 들어온 기획 메뉴를 활성 상태로 유지한다.
 * Args: name 기획 메뉴의 라우트 이름. Returns: 활성 여부. Raises: 없음. Side Effects: 없음.
 */
function plannerMenuActive(name) {
  if (route.name === name) return true
  if (!['history', 'submission'].includes(route.name)) return false
  return name === (route.query.from === 'requests' ? 'planning-requests' : 'planning-organizations')
}
</script>

<template>
  <HomeView v-if="!session.actor" />
  <div v-else :class="['app-shell', { 'planner-shell': session.actor.role === 'PLANNER' }]">
    <a class="skip-link" href="#main-content">본문으로 건너뛰기</a>
    <aside class="sidebar">
      <div>
        <RouterLink :to="session.actor.role === 'PLANNER' ? '/planning' : '/requests'" class="brand"><span class="brand-mark"></span>손익관리</RouterLink>
        <p class="nav-label">{{ session.actor.role === 'PLANNER' ? '경영기획팀' : '조직 담당자' }}</p>
        <nav v-if="session.actor.role === 'PLANNER'" aria-label="경영기획팀 주 메뉴">
          <RouterLink v-for="item in plannerNavigation" :key="item.name" :to="item.path" :class="['nav-item', { active: plannerMenuActive(item.name) }]">
            <span class="nav-icon" aria-hidden="true"></span>{{ item.label }}
          </RouterLink>
        </nav>
        <nav v-else aria-label="주 메뉴">
          <RouterLink :to="session.actor.role === 'PLANNER' ? '/planning' : '/requests'" :class="['nav-item', { active: ['requests', 'upload', 'planning'].includes(route.name) }]">
            <span class="nav-icon"><img :src="['requests', 'upload', 'planning'].includes(route.name) ? '/assets/inbox-active.svg' : '/assets/inbox.svg'" alt="" /></span>
            {{ session.actor.role === 'PLANNER' ? '요청 관리' : '요청함' }}
          </RouterLink>
          <RouterLink to="/history" :class="['nav-item', { active: ['history', 'submission'].includes(route.name) }]">
            <span class="nav-icon"><img :src="['history', 'submission'].includes(route.name) ? '/assets/history-active.svg' : '/assets/history.svg'" alt="" /></span>제출 이력 관리
          </RouterLink>
        </nav>
      </div>
      <div>
      <div v-if="session.actor.role === 'PLANNER'" class="planner-settings">
        <p class="nav-label">설정</p>
        <button class="nav-item" disabled title="설정 저장은 준비 중입니다"><span class="nav-icon" aria-hidden="true"></span>Gap 기준 · 템플릿</button>
        <button class="nav-item" disabled title="조직·권한 편집은 준비 중입니다"><span class="nav-icon" aria-hidden="true"></span>조직 · 권한 관리</button>
      </div>
      <div class="profile"><span class="avatar">{{ session.actor.role === 'PLANNER' ? '기획' : '재무' }}</span><div><strong>{{ session.actor.name }}</strong><small>{{ session.actor.organization?.name || '경영기획팀' }}</small></div></div>
      </div>
    </aside>
    <div class="workspace">
      <div class="demo-bar"><span><i></i>로컬 체험 · 예시 데이터</span><button class="text-button" @click="changeRole">역할 전환</button></div>
      <main id="main-content" class="main-content">
        <p v-if="!route.meta.plannerCanvas" class="breadcrumb">{{ session.actor.role === 'PLANNER' ? '경영기획팀' : '손익관리' }} <span>/</span> {{ route.meta.title || '요청함' }}</p>
        <RouterView :key="`${session.actor.id}:${session.actor.organization?.id || 'planner'}:${route.fullPath}`" />
      </main>
    </div>
  </div>
</template>
