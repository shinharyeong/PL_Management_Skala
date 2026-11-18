import { createServer } from 'vite'
import { createSSRApp } from 'vue'
import { createPinia } from 'pinia'
import { renderToString } from 'vue/server-renderer'
import assert from 'node:assert/strict'

// Render every prototype view without a browser or backend. This checks templates and shared store integration.
const server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
try {
  const pinia = createPinia()
  const { usePrototypeStore } = await server.ssrLoadModule('/src/prototype/store.js')
  const store = usePrototypeStore(pinia)
  store.actor = { role:'planner', name:'김기획 매니저' }
  const cases = [
    ['HomePanel', [undefined]],
    ['MonthlyPanel', ['monthly','monthly-request','calculator','workspace','cell']],
    ['ManagementPanels', ['notifications','annual','annual-request','organizations','reports']],
    ['OrgPanel', ['org-requests','org-upload','org-explanation','org-history']],
  ]
  let total = 0
  for (const [name,pages] of cases) {
    const { default: Component } = await server.ssrLoadModule(`/src/prototype/${name}.vue`)
    for (const page of pages) {
      if (page?.startsWith('org-')) store.actor = { role:'org',orgId:'F10',name:'최인프 매니저' }
      if (page === 'org-explanation') store.activateOrgRequest('explanation-11')
      if (page === 'cell' || page === 'workspace') store.addToCart('F10',store.items.F10.slice(0,2),{type:'실적 vs 계획',baseVersion:'v1.02',compareVersion:'v1.03',rateEnabled:false,amountEnabled:false,minimum:10000000,rate:5})
      const app = createSSRApp(Component, page ? { page } : {})
      app.use(pinia)
      const html = await renderToString(app)
      assert.ok(html.length > 200, `${name}/${page}: empty render`)
      assert.ok(!html.includes('undefined'), `${name}/${page}: undefined in output`)
      console.log(`PASS ${name}/${page || 'home'} (${html.length} chars)`)
      total++
    }
  }
  console.log(`${total} prototype views rendered successfully`)
} finally {
  await server.close()
}
