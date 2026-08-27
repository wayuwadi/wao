import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { webUpdateNotice } from '@plugin-web-update-notification/vite'
import vueDevTools from 'vite-plugin-vue-devtools'

const ciBuildVersion = process.env.WAO_WEB_BUILD_VERSION?.trim()
if (ciBuildVersion && !/^[0-9a-f]{7,40}$/.test(ciBuildVersion)) {
  throw new Error('WAO_WEB_BUILD_VERSION must be a Git commit SHA')
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
    webUpdateNotice({
      versionType: ciBuildVersion ? 'custom' : 'git_commit_hash',
      ...(ciBuildVersion ? { customVersion: ciBuildVersion } : {}),
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
