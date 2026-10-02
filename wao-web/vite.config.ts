import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { webUpdateNotice } from '@plugin-web-update-notification/vite'
import vueDevTools from 'vite-plugin-vue-devtools'
// @ts-expect-error Build-only JavaScript helper, exercised through node:test.
import { edgeBuildPlugin } from './scripts/edge-build.mjs'
// @ts-expect-error Build-only JavaScript helper, exercised through node:test.
import { releaseHighlightsPlugin } from './scripts/release-highlights.mjs'
import currentRelease from './src/data/currentRelease.json'

const buildCommitSha = process.env.WAO_GIT_SHA?.trim()
const buildVersion = buildCommitSha ? buildCommitSha.slice(0, 7) : undefined
if (buildCommitSha && !/^[0-9a-f]{40}$/.test(buildCommitSha)) throw new Error('WAO_GIT_SHA must be a full Git SHA')

// https://vite.dev/config/
export default defineConfig({
  base: buildCommitSha ? `/releases/${buildCommitSha}/` : '/',
  plugins: [
    vue(),
    // 调试插件按需开启，避免真实数据页面的调试采集拖慢浏览器验收。
    process.env.WAO_VUE_DEVTOOLS === 'true' ? vueDevTools() : undefined,
    webUpdateNotice({
      injectFileBase: '/',
      ...(buildVersion
        ? { versionType: 'custom', customVersion: buildVersion }
        : { versionType: 'git_commit_hash' }),
      checkInterval: 60_000,
      hiddenDefaultNotification: true,
      checkOnLoadFileError: false,
      logVersion: false,
    }),
    edgeBuildPlugin(buildCommitSha),
    releaseHighlightsPlugin(currentRelease),
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
