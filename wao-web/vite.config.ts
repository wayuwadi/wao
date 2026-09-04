import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { webUpdateNotice } from '@plugin-web-update-notification/vite'
import vueDevTools from 'vite-plugin-vue-devtools'

const buildCommitSha = process.env.WAO_GIT_SHA?.trim()
const buildVersion = buildCommitSha ? buildCommitSha.slice(0, 7) : undefined

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    process.env.WAO_VUE_DEVTOOLS === 'false' ? undefined : vueDevTools(),
    webUpdateNotice({
      ...(buildVersion
        ? { versionType: 'custom', customVersion: buildVersion }
        : { versionType: 'git_commit_hash' }),
      checkInterval: 60_000,
      hiddenDefaultNotification: true,
      checkOnLoadFileError: false,
      logVersion: false,
    }),
  ],
  // Excalidraw 用该常量在 React / Preact 运行时间选择；WAO 使用 React 岛。
  define: {
    'process.env.IS_PREACT': false,
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:9000',
        changeOrigin: true,
      },
    },
  },
})
