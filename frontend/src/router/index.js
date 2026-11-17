import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from '../stores/session'
import { routeAllowed } from '../lib/role-route'

import HomeView from '@/views/HomeView.vue'
import RequestList from '@/components/RequestList.vue'
import UploadView from '@/components/UploadView.vue'
import HistoryView from '@/components/HistoryView.vue'
import SubmissionDetail from '@/components/SubmissionDetail.vue'
import PlannerRequests from '@/components/PlannerRequests.vue'
import PlannerRequestCreate from '@/components/PlannerRequestCreate.vue'
import PlannerDashboard from '@/components/PlannerDashboard.vue'
import PlannerGap from '@/components/PlannerGap.vue'
import PlannerOrganizations from '@/components/PlannerOrganizations.vue'
import PlannerReports from '@/components/PlannerReports.vue'
import PlannerNotifications from '@/components/PlannerNotifications.vue'
import AnnualMaster from '@/components/AnnualMaster.vue'
import MonthlyMaster from '@/components/MonthlyMaster.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/requests', name: 'requests', component: RequestList, meta: { title: '요청함' } },
    { path: '/requests/:id/upload', name: 'upload', component: UploadView, meta: { title: '엑셀 제출' } },
    { path: '/history', name: 'history', component: HistoryView, meta: { title: '제출 이력 관리' } },
    { path: '/submissions/:id', name: 'submission', component: SubmissionDetail, meta: { title: '제출본 상세' } },
    { path: '/planning', name: 'planning', component: PlannerDashboard, meta: { title: '전사 손익 현황', plannerCanvas: true } },
    { path: '/planning/notifications', name: 'planning-notifications', component: PlannerNotifications, meta: { title: '알림 모아보기', plannerCanvas: true } },
    { path: '/planning/gap', name: 'planning-gap', component: PlannerGap, meta: { title: '월간 시트 관리', plannerCanvas: true } },
    { path: '/planning/requests', name: 'planning-requests', component: PlannerRequests, meta: { title: '요청 · 검토', plannerCanvas: true } },
    { path: '/planning/annual-masters/:id', name: 'planning-annual-master', component: AnnualMaster, meta: { title: '연간 계획 마스터', plannerCanvas: true } },
    { path: '/planning/masters/:id', name: 'planning-monthly-master', component: MonthlyMaster, meta: { title: '월간 실적 마스터', plannerCanvas: true } },
    { path: '/planning/annual-plan', name: 'planning-annual', component: PlannerRequests, meta: { title: '연간 계획 관리', plannerCanvas: true } },
    { path: '/planning/annual-plan/new', name: 'planning-annual-create', component: PlannerRequestCreate, meta: { title: '연간 계획 요청', plannerCanvas: true } },
    { path: '/planning/organizations', name: 'planning-organizations', component: PlannerOrganizations, meta: { title: '조직별 현황', plannerCanvas: true } },
    { path: '/planning/reports', name: 'planning-reports', component: PlannerReports, meta: { title: '보고서', plannerCanvas: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

/** 로그인한 역할과 맞지 않는 업무 화면의 마운트를 막고 본인 홈으로 이동한다.
 * Args: to 대상 라우트. Returns: 허용 true 또는 역할별 기본 경로.
 * Raises: 없음. Side Effects: 경로 전환만 수행하며 서버의 조직별 권한 검사를 대체하지 않는다.
 */
function authorizeRoute(to) {
  const actor = useSession().actor
  if (!actor) return true
  if (!routeAllowed(to, actor.role)) return actor.role === 'PLANNER' ? '/planning' : '/requests'
  return true
}
router.beforeEach(authorizeRoute)

export default router
