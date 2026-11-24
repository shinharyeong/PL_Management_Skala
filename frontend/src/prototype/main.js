import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createWebHashHistory } from 'vue-router'
import { createPrototypeRouter } from './router.js'
import { usePrototypeStore } from './live-store.js'
import App from './PrototypeApp.vue'
import './prototype.css'
import './workspace.css'
import './responsive.css'

const pinia = createPinia()
const router = createPrototypeRouter(createWebHashHistory(), usePrototypeStore(pinia))
const app = createApp(App).use(pinia).use(router)
router.isReady().then(() => app.mount('#app'))
