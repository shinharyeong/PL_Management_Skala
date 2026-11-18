import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './PrototypeApp.vue'
import './prototype.css'

const pages = ['login', 'home', 'notifications', 'monthly', 'monthly-request', 'calculator', 'workspace', 'cell', 'annual', 'annual-request', 'organizations', 'reports', 'org-requests', 'org-upload', 'org-explanation', 'org-history']
const router = createRouter({ history: createWebHashHistory(), routes: [
  ...pages.map(page => ({ path: `/${page}`, name: page, component: { render: () => null } })),
  { path: '/:pathMatch(.*)*', redirect: '/login' },
] })
createApp(App).use(createPinia()).use(router).mount('#app')
