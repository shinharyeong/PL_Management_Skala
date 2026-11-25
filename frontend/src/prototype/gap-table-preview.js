import { createApp } from 'vue'
import GapTablePreview from './GapTablePreview.vue'
import './prototype.css'
import './workspace.css'
import './monthly.css'
import './responsive.css'

if (import.meta.env.DEV) createApp(GapTablePreview).mount('#app')
