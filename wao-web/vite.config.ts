import { fileURLToPath, URL } from 'node:url'
import { readFileSync, existsSync } from 'node:fs'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { webUpdateNotice } from '@plugin-web-update-notification/vite'
import vueDevTools from 'vite-plugin-vue-devtools'
// @ts-expect-error Build-only JavaScript helper, exercised through node:test.
import { edgeBuildPlugin } from './scripts/edge-build.mjs'
// @ts-expect-error Build-only JavaScript helper, exercised through node:test.
import { releaseHighlightsPlugin } from './scripts/release-highlights.mjs'
// @ts-expect-error Build-only helper, covered by real Git fixture tests.
import { readGitRelease, gitReleasePlugin, loadReleaseChanges } from './scripts/git-release.mjs'
import releaseHistory from './src/data/releaseHistory.json'
import curatedRelease from './src/data/currentRelease.json'
// @ts-expect-error Development-only JavaScript helper; serves an independently built game.
import { gameAssetsDevPlugin } from './scripts/game-dev-assets.mjs'

const buildCommitSha = process.env.WAO_GIT_SHA?.trim()
const buildVersion = buildCommitSha ? buildCommitSha.slice(0, 7) : undefined
if (buildCommitSha && !/^[0-9a-f]{40}$/.test(buildCommitSha))
  throw new Error('WAO_GIT_SHA must be a full Git SHA')

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url))
const actionRegistryPath = fileURLToPath(new URL('./src/data/releaseActions.json', import.meta.url))
const changeGuides = loadReleaseChanges(repositoryRoot)
const legacyGuides = [curatedRelease, ...releaseHistory].filter(item => item.sourceSubject && !changeGuides.some((guide: { sourceSubject: string }) => guide.sourceSubject === item.sourceSubject))
const allGuides = [...changeGuides, ...legacyGuides].filter((item, index, all) => all.findIndex(other => other.id === item.id) === index)
const gitRelease = readGitRelease(repositoryRoot, buildCommitSha, process.env.WAO_GIT_HISTORY, undefined, {
  baseSha: process.env.WAO_CHANGE_BASE,
  rangeFile: process.env.WAO_GIT_RELEASE_RANGE,
  guides: allGuides,
  actions: existsSync(actionRegistryPath) ? JSON.parse(readFileSync(actionRegistryPath, 'utf8')) : [],
})
// 档案只展示构建 HEAD 已包含的变化；新说明无需重复手填两份 JSON。
const mergedSubjects = new Map<string, number>(gitRelease.history.commits.map((item: { subject: string }, index: number) => [item.subject.replace(/\s*\(#\d+\)$/, '').trim(), index]))
const confirmedGuides = allGuides.filter((item: { sourceSubject: string }) => mergedSubjects.has(item.sourceSubject.trim()))
confirmedGuides.sort((a: { sourceSubject: string }, b: { sourceSubject: string }) => mergedSubjects.get(a.sourceSubject.trim())! - mergedSubjects.get(b.sourceSubject.trim())!)
const archive = [...confirmedGuides, ...releaseHistory].filter((item, index, all) => all.findIndex(other => other.id === item.id) === index)

// https://vite.dev/config/
export default defineConfig({
  base: buildCommitSha ? `/releases/${buildCommitSha}/` : '/',
  plugins: [
    vue(),
    gameAssetsDevPlugin(),
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
    gitReleasePlugin(gitRelease),
    releaseHighlightsPlugin(gitRelease.edition, [gitRelease.edition, ...archive.filter(item => item.id !== gitRelease.edition.id)]),
  ],
  // Excalidraw 用该常量在 React / Preact 运行时间选择；WAO 使用 React 岛。
  define: {
    'process.env.IS_PREACT': false,
    __WAO_BUILD_RELEASE__: JSON.stringify(gitRelease.edition),
    __WAO_RELEASE_HISTORY__: JSON.stringify(archive),
  },
  resolve: {
    dedupe: ['vue'],
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
