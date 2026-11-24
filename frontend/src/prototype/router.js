import { createRouter } from 'vue-router'

export const pageTitles = {
  login: '로그인', home: '전사 손익 현황', notifications: '알림 모아보기',
  monthly: '월간 시트 관리', 'monthly-request': '월간 요청 작성', calculator: '월간 시트 관리 계산기',
  workspace: '선택 항목 검토', cell: '셀 단위 비교', annual: '연간 계획 관리',
  'annual-request': '연간 계획 요청 작성', organizations: '조직별 현황', reports: '보고서 생성',
  'org-requests': '요청함', 'org-upload': '엑셀 제출', 'org-explanation': '소명 작성', 'org-history': '제출 이력 관리',
}

// Demo navigation only. Server authorization is still required in the API app.
export function createPrototypeRouter(history, store) {
  const router = createRouter({ history, routes: [
    ...Object.keys(pageTitles).map(name => ({ path: `/${name}`, name, component: { render: () => null } })),
    { path: '/:pathMatch(.*)*', redirect: '/login' },
  ] })
  router.beforeEach(to => {
    if (to.name === 'login') return true
    if (!store.actor) return { name: 'login', replace: true }
    const isOrgPage = String(to.name).startsWith('org-')
    if (store.actor.role === 'org' && !isOrgPage) return { name: 'org-requests', replace: true }
    if (store.actor.role !== 'org' && isOrgPage) return { name: 'home', replace: true }
    if (['org-upload', 'org-explanation'].includes(to.name)) {
      const request = store.activeOrgRequest
      if (!request || request.status === '제출 완료' ||
        (to.name === 'org-explanation') !== (request.type === 'explanation')) {
        return { name: 'org-requests', replace: true }
      }
    }
    if (to.name === 'workspace' && !store.carts[store.activeOrg]?.length) return { name: 'monthly', replace: true }
    if (['calculator', 'cell'].includes(to.name) && !store.items[store.activeOrg]?.length) return { name: 'monthly', replace: true }
  })
  return router
}
