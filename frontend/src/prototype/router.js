import copy from './copy-text.js'
import { createRouter } from 'vue-router'

export const pageTitles = copy.pages

// Client navigation guards complement server authorization.
export function createPrototypeRouter(history, store) {
  const router = createRouter({ history, routes: [
    ...Object.keys(pageTitles).map(name => ({ path: `/${name}`, name, component: { render: () => null } })),
    { path: '/:pathMatch(.*)*', redirect: '/login' },
  ] })
  router.beforeEach(to => {
    if (to.name === 'login') return store.actor ? { name: store.actor.role === 'org' ? 'org-requests' : 'home', replace: true } : true
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
    if (to.name === 'calculator' && store.live && !store.activeOrg) return { name: 'monthly', replace: true }
    if (to.name === 'workspace' && !store.carts[store.activeOrg]?.length) return { name: 'monthly', replace: true }
    if (to.name === 'cell' && store.live && !store.carts[store.activeOrg]?.some(item => item.id === store.monthlySession(store.activeOrg).activeCell)) return { name: store.carts[store.activeOrg]?.length ? 'workspace' : 'monthly', replace: true }
    if ((to.name === 'cell' || (to.name === 'calculator' && !store.live)) && !store.items[store.activeOrg]?.length) return { name: 'monthly', replace: true }
  })
  return router
}
