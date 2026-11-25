import { copyEditorPlugin } from './scripts/copy-editor-plugin.js'
import { localAuthCodePlugin } from './scripts/local-auth-code-plugin.js'
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
  plugins: [vue(), copyEditorPlugin(), localAuthCodePlugin({logPath: env.LOCAL_AUTH_LOG, apiTarget: env.API_PROXY_TARGET})],
  build: {
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        prototype: fileURLToPath(new URL('./prototype.html', import.meta.url)),
      },
    },
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 별도 검증 서버는 API_PROXY_TARGET으로 지정한다. 브라우저 요청은 같은 출처를 유지한다.
    proxy: {
      '/api': env.API_PROXY_TARGET || 'http://localhost:8080',
    },
  },
  }
})
