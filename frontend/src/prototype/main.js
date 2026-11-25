import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createWebHashHistory } from 'vue-router'
import { createPrototypeRouter } from './router.js'
import { usePrototypeStore } from './live-store.js'
import App from './PrototypeApp.vue'
import {installPrimeVue} from './primevue.js'
import './primevue-layout.css'
import './prototype.css'
import './workspace.css'
import './responsive.css'
import './legacy-design-bridge.css'

const pinia = createPinia()
const app = installPrimeVue(createApp(App)).use(pinia)
const store = usePrototypeStore(pinia)
// Confirm authentication before the first route guard can redirect to login.
async function start() {
  try { await store.restoreLogin() }
  catch { store.error = '로그인 상태를 확인하지 못했습니다. 서버 연결을 확인하고 새로고침해 주세요. 저장된 로그인 정보는 유지됩니다.' }
  const router = createPrototypeRouter(createWebHashHistory(), store)
  app.use(router)
  await router.isReady()
  app.mount('#app')
}
start()
