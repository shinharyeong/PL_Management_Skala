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
import {refreshSharedCopy,startSharedCopySync} from './copy-text.js'

const pinia = createPinia()
const app = installPrimeVue(createApp(App)).use(pinia)
const store = usePrototypeStore(pinia)
// Confirm authentication before the first route guard can redirect to login.
async function start() {
  try { await store.restoreLogin() }
  catch { store.error = '로그인 상태를 확인하지 못했습니다. 서버 연결을 확인하고 새로고침해 주세요. 저장된 로그인 정보는 유지됩니다.' }
  try {await refreshSharedCopy()} catch {store.notify('공통 문구를 불러오지 못해 기본 문구로 표시합니다.','error')}
  const router = createPrototypeRouter(createWebHashHistory(), store)
  app.use(router)
  await router.isReady()
  app.mount('#app')
  const stopCopySync=startSharedCopySync()
  if(import.meta.hot) import.meta.hot.dispose(stopCopySync)
}
start()
