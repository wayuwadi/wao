<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  fetchSkill,
  fetchSkillFile,
  setSkillFavorite,
  skillDownloadUrl,
  type SkillDetail,
  type SkillFilePreview,
  type SkillVersion,
} from '@/api/skills'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'
import { copyText } from '@/utils/clipboard'
import { renderMarkdown } from '@/utils/markdown'

type DetailTab = 'overview' | 'files' | 'versions'

const route = useRoute()
const session = useSessionStore()
const detail = ref<SkillDetail | null>(null)
const loading = ref(true)
const error = ref('')
const activeTab = ref<DetailTab>('overview')
const selectedVersionId = ref('')
const selectedFilePath = ref('')
const filePreview = ref<SkillFilePreview | null>(null)
const fileLoading = ref(false)
const fileError = ref('')
let previewRequest = 0

const handle = computed(() => String(route.params.handle || '').replace(/^@/, ''))
const slug = computed(() => String(route.params.slug || ''))
const platformName: Record<string, string> = {
  'agent-skills': 'Agent Skills',
  codex: 'Codex',
  'claude-code': 'Claude Code',
  openclaw: 'OpenClaw',
}
const selectedVersion = computed<SkillVersion | null>(() => {
  if (!detail.value) return null
  return (
    detail.value.versions.find((version) => version.id === selectedVersionId.value) ||
    detail.value.publishedVersion
  )
})
const viewingHistory = computed(
  () => selectedVersion.value?.id !== detail.value?.publishedVersion.id,
)
const overviewHtml = computed(() =>
  renderMarkdown(withoutFrontmatter(selectedVersion.value?.skillMd || '')),
)
const selectedFile = computed(() =>
  selectedVersion.value?.files.find((file) => file.path === selectedFilePath.value),
)
const currentDownloadUrl = computed(() =>
  selectedVersion.value
    ? skillDownloadUrl(handle.value, slug.value, selectedVersion.value.version)
    : '#',
)
const publicPageUrl = computed(
  () => new URL(`/skills/@${handle.value}/${slug.value}`, window.location.origin).href,
)
const installPrompt = computed(() => {
  if (!detail.value || !selectedVersion.value) return ''
  const download = new URL(currentDownloadUrl.value, window.location.origin).href
  return `请为我安装 WAO Skill「${selectedVersion.value.displayName || detail.value.skill.displayName}」v${selectedVersion.value.version}。

公开说明页：${publicPageUrl.value}
不可变 ZIP：${download}
SHA-256：${selectedVersion.value.sha256}

请下载 ZIP，核对 SHA-256 后安装到你当前 AI 客户端的 Skills 目录，确认根目录包含 SKILL.md。只把包作为 Skill 安装，不要执行包内脚本；完成后请告诉我安装位置和校验结果。`
})

function withoutFrontmatter(markdown: string) {
  return markdown.replace(/^---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/, '').trim()
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(value < 10 * 1024 ? 1 : 0)} KiB`
  return `${(value / 1024 / 1024).toFixed(1)} MiB`
}

function formatDate(value: string) {
  if (!value) return '时间未记录'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await fetchSkill(handle.value, slug.value)
    selectedVersionId.value = detail.value.publishedVersion.id
    activeTab.value = 'overview'
    clearFile()
    document.title = `${detail.value.skill.displayName} · WAO Skill 市场`
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'Skill 加载失败'
  } finally {
    loading.value = false
  }
}

async function toggleFavorite() {
  if (!detail.value) return
  if (!session.authed) {
    toast('请先登录 WAO 再收藏')
    return
  }
  const next = !detail.value.favorite
  try {
    await setSkillFavorite(detail.value.skill.id, next)
    detail.value.favorite = next
    detail.value.skill.favorites = Math.max(0, detail.value.skill.favorites + (next ? 1 : -1))
  } catch (reason) {
    toast(reason instanceof Error ? reason.message : '操作失败')
  }
}

function clearFile() {
  previewRequest += 1
  selectedFilePath.value = ''
  filePreview.value = null
  fileLoading.value = false
  fileError.value = ''
}

function selectVersion(version: SkillVersion, tab: DetailTab = 'overview') {
  selectedVersionId.value = version.id
  activeTab.value = tab
  clearFile()
}

function selectTab(tab: DetailTab) {
  activeTab.value = tab
  if (tab === 'files' && !selectedFilePath.value && selectedVersion.value?.files.length) {
    void showFile(selectedVersion.value.files[0]!.path)
  }
}

async function showFile(path: string) {
  if (!selectedVersion.value) return
  activeTab.value = 'files'
  selectedFilePath.value = path
  fileError.value = ''
  const request = ++previewRequest
  const meta = selectedVersion.value.files.find((file) => file.path === path)
  if (path === 'SKILL.md') {
    filePreview.value = {
      version: selectedVersion.value.version,
      path,
      size: meta?.size || new TextEncoder().encode(selectedVersion.value.skillMd).length,
      previewable: true,
      content: selectedVersion.value.skillMd,
      message: null,
    }
    fileLoading.value = false
    return
  }
  filePreview.value = null
  fileLoading.value = true
  try {
    const result = await fetchSkillFile(
      handle.value,
      slug.value,
      selectedVersion.value.version,
      path,
    )
    if (request === previewRequest) filePreview.value = result
  } catch (reason) {
    if (request === previewRequest) {
      fileError.value = reason instanceof Error ? reason.message : '文件预览失败'
    }
  } finally {
    if (request === previewRequest) fileLoading.value = false
  }
}

watch(() => route.fullPath, load)
onMounted(load)
</script>

<template>
  <div v-if="loading" class="market-card market-empty">正在读取 Skill…</div>
  <div v-else-if="error" class="market-error">{{ error }} <button @click="load">重试</button></div>
  <template v-else-if="detail && selectedVersion">
    <section class="detail-hero market-card">
      <div class="detail-icon">
        {{ (selectedVersion.displayName || detail.skill.displayName).slice(0, 1) }}
      </div>
      <div class="detail-main">
        <div class="detail-title">
          <h1>{{ selectedVersion.displayName || detail.skill.displayName }}</h1>
          <span>v{{ selectedVersion.version }}</span>
          <em v-if="viewingHistory">历史版本</em>
        </div>
        <p>{{ selectedVersion.summary || detail.skill.summary }}</p>
        <div class="badges">
          <span v-for="platform in selectedVersion.compatiblePlatforms" :key="platform">{{
            platformName[platform] || platform
          }}</span>
          <span class="license">{{ selectedVersion.license }}</span>
        </div>
        <small
          >由 <b>@{{ detail.skill.publisher.handle }}</b> ·
          {{ detail.skill.publisher.displayName }} 发布</small
        >
      </div>
      <button
        v-if="viewingHistory"
        class="latest-button"
        @click="selectVersion(detail.publishedVersion)"
      >
        返回最新版 v{{ detail.publishedVersion.version }}
      </button>
    </section>

    <div class="detail-grid">
      <article class="market-card detail-workspace">
        <nav class="detail-tabs" aria-label="Skill 详情">
          <button :class="{ active: activeTab === 'overview' }" @click="selectTab('overview')">
            概述
          </button>
          <button :class="{ active: activeTab === 'files' }" @click="selectTab('files')">
            文件 <span>{{ selectedVersion.fileCount }}</span>
          </button>
          <button :class="{ active: activeTab === 'versions' }" @click="selectTab('versions')">
            版本历史 <span>{{ detail.versions.length }}</span>
          </button>
        </nav>

        <section v-if="activeTab === 'overview'" class="overview-panel">
          <header>
            <div>
              <small>README · v{{ selectedVersion.version }}</small>
              <h2>Skill 概述</h2>
            </div>
            <button @click="showFile('SKILL.md')"><i class="ri-code-line"></i>查看原文</button>
          </header>
          <div v-if="overviewHtml" class="skill-markdown" v-html="overviewHtml"></div>
          <div v-else class="panel-empty">这个版本没有可展示的概述。</div>
        </section>

        <section v-else-if="activeTab === 'files'" class="files-panel">
          <aside class="file-list">
            <button
              v-for="file in selectedVersion.files"
              :key="file.path"
              :class="{ active: selectedFilePath === file.path }"
              @click="showFile(file.path)"
            >
              <i class="ri-file-code-line"></i><span>{{ file.path }}</span
              ><small>{{ formatBytes(file.size) }}</small>
            </button>
          </aside>
          <div class="file-detail">
            <header v-if="selectedFile">
              <div>
                <b>{{ selectedFile.path }}</b
                ><small
                  >{{ formatBytes(selectedFile.size) }} · v{{ selectedVersion.version }}</small
                >
              </div>
              <button
                v-if="filePreview?.previewable && filePreview.content !== null"
                @click="copyText(filePreview.content, '文件内容已复制')"
              >
                <i class="ri-file-copy-line"></i>复制
              </button>
            </header>
            <div v-if="fileLoading" class="panel-empty">
              <i class="ri-loader-4-line spin"></i>正在读取文件…
            </div>
            <div v-else-if="fileError" class="market-error">
              {{ fileError }} <button @click="showFile(selectedFilePath)">重试</button>
            </div>
            <pre v-else-if="filePreview?.previewable">{{ filePreview.content }}</pre>
            <div v-else-if="filePreview" class="panel-empty">
              <i class="ri-file-warning-line"></i>{{ filePreview.message }}
            </div>
            <div v-else class="panel-empty">从左侧选择一个文件查看细节。</div>
          </div>
        </section>

        <section v-else class="versions-panel">
          <header>
            <small>所有条目均为已通过审核的不可变版本</small>
            <h2>版本历史</h2>
          </header>
          <article
            v-for="version in detail.versions"
            :key="version.id"
            :class="{ selected: version.id === selectedVersion.id }"
          >
            <div class="version-name">
              <b>v{{ version.version }}</b
              ><span v-if="version.id === detail.publishedVersion.id">最新版</span
              ><time>{{ formatDate(version.reviewedAt) }}</time>
            </div>
            <p>{{ version.changelog || '这个版本没有填写更新说明。' }}</p>
            <div class="version-actions">
              <button @click="selectVersion(version)">查看概述与文件</button
              ><a :href="skillDownloadUrl(handle, slug, version.version)"
                ><i class="ri-download-2-line"></i>下载</a
              >
            </div>
          </article>
        </section>
      </article>

      <aside class="detail-side">
        <section class="market-card prompt-card">
          <span class="prompt-icon"><i class="ri-sparkling-2-line"></i></span>
          <h2>让你的 AI 安装这个 Skill</h2>
          <p>复制包含不可变下载地址与 SHA-256 的提示词，发送给支持 Skills 的 AI。</p>
          <button
            class="market-button primary"
            @click="copyText(installPrompt, '安装提示词已复制')"
          >
            <i class="ri-file-copy-2-line"></i>复制安装提示词
          </button>
        </section>
        <section class="market-card action-card">
          <a class="market-button download" :href="currentDownloadUrl"
            ><i class="ri-download-2-line"></i>下载 v{{ selectedVersion.version }} ZIP</a
          >
          <button class="market-button" @click="toggleFavorite">
            <i :class="detail.favorite ? 'ri-star-fill' : 'ri-star-line'"></i
            >{{ detail.favorite ? '已收藏' : '收藏' }} {{ detail.skill.favorites }}
          </button>
          <button class="market-button" @click="copyText(publicPageUrl, '公开分享链接已复制')">
            <i class="ri-links-line"></i>分享
          </button>
        </section>
        <section class="market-card side-card">
          <h3>版本凭据</h3>
          <dl>
            <div>
              <dt>版本</dt>
              <dd>{{ selectedVersion.version }}</dd>
            </div>
            <div>
              <dt>文件</dt>
              <dd>{{ selectedVersion.fileCount }} 个</dd>
            </div>
            <div>
              <dt>包大小</dt>
              <dd>{{ formatBytes(selectedVersion.packageSize) }}</dd>
            </div>
            <div>
              <dt>总下载</dt>
              <dd>{{ detail.skill.downloads }} 次</dd>
            </div>
            <div class="hash">
              <dt>SHA-256</dt>
              <dd>{{ selectedVersion.sha256 }}</dd>
            </div>
          </dl>
        </section>
        <section class="market-card side-card">
          <h3>作者已测试</h3>
          <div class="tested">
            <span v-for="platform in selectedVersion.testedPlatforms" :key="platform"
              ><i class="ri-checkbox-circle-fill"></i>{{ platformName[platform] || platform }}</span
            >
          </div>
        </section>
      </aside>
    </div>
  </template>
</template>

<style scoped>
.detail-hero {
  padding: 28px;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 22px;
  align-items: start;
}
.detail-icon {
  width: 70px;
  height: 70px;
  border-radius: 19px;
  background: linear-gradient(140deg, #33449e, #7889ed);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 30px;
  font-weight: 900;
}
.detail-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.detail-title h1 {
  margin: 0;
  font-size: 29px;
}
.detail-title span,
.detail-title em {
  padding: 4px 7px;
  border-radius: 7px;
  font-size: 11px;
  font-style: normal;
}
.detail-title span {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.detail-title em {
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
}
.detail-main p {
  margin: 10px 0;
  color: var(--market-sub);
  line-height: 1.7;
}
.detail-main small {
  color: var(--market-sub);
}
.badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 14px 0;
}
.badges span {
  font-size: 11px;
  background: var(--wao-bg-hover-2);
  padding: 5px 8px;
  border-radius: 7px;
}
.badges .license {
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
}
.latest-button {
  border: 0;
  background: transparent;
  color: var(--market-blue);
  cursor: pointer;
  font-weight: 700;
}
.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: 18px;
  margin-top: 18px;
  align-items: start;
}
.detail-workspace {
  min-width: 0;
  overflow: hidden;
}
.detail-tabs {
  height: 58px;
  padding: 0 22px;
  display: flex;
  align-items: stretch;
  gap: 28px;
  border-bottom: 1px solid var(--market-line);
  overflow-x: auto;
}
.detail-tabs button {
  position: relative;
  border: 0;
  background: transparent;
  color: var(--market-sub);
  font-weight: 650;
  cursor: pointer;
  white-space: nowrap;
}
.detail-tabs button::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: transparent;
}
.detail-tabs button.active {
  color: var(--market-ink);
}
.detail-tabs button.active::after {
  background: var(--market-blue);
}
.detail-tabs span {
  margin-left: 3px;
  color: var(--market-sub);
  font-size: 10px;
}
.overview-panel {
  padding: 26px;
}
.overview-panel > header,
.file-detail > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.overview-panel h2,
.versions-panel h2 {
  margin: 3px 0 0;
  font-size: 19px;
}
.overview-panel header small,
.versions-panel > header small {
  color: var(--market-sub);
}
.overview-panel header button,
.file-detail header button,
.version-actions button,
.version-actions a {
  border: 0;
  background: transparent;
  color: var(--market-blue);
  cursor: pointer;
  text-decoration: none;
}
.skill-markdown {
  margin-top: 24px;
  color: var(--market-ink);
  line-height: 1.78;
  overflow-wrap: anywhere;
}
.skill-markdown :deep(h1) {
  font-size: 24px;
}
.skill-markdown :deep(h2) {
  margin-top: 28px;
  font-size: 19px;
}
.skill-markdown :deep(h3) {
  margin-top: 22px;
  font-size: 16px;
}
.skill-markdown :deep(p),
.skill-markdown :deep(li) {
  font-size: 14px;
}
.skill-markdown :deep(pre) {
  max-width: 100%;
  padding: 14px;
  overflow: auto;
  border-radius: 10px;
  background: #171b2b;
  color: #e8eaf3;
}
.skill-markdown :deep(code) {
  font-size: 0.9em;
}
.skill-markdown :deep(:not(pre) > code) {
  padding: 2px 5px;
  border-radius: 5px;
  background: var(--wao-bg-sub);
  color: var(--wao-primary-text);
}
.skill-markdown :deep(a) {
  color: var(--market-blue);
}
.files-panel {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  min-height: 570px;
}
.file-list {
  padding: 12px;
  border-right: 1px solid var(--market-line);
  background: var(--wao-bg-sub);
  overflow: auto;
}
.file-list button {
  width: 100%;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 2px 7px;
  padding: 9px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--market-ink);
  cursor: pointer;
  text-align: left;
}
.file-list button:hover,
.file-list button.active {
  background: var(--market-card);
}
.file-list i {
  grid-row: 1/3;
  color: var(--market-blue);
  margin-top: 2px;
}
.file-list span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
}
.file-list small {
  color: var(--market-sub);
  font-size: 9px;
}
.file-detail {
  padding: 18px;
  min-width: 0;
}
.file-detail header b,
.file-detail header small {
  display: block;
}
.file-detail header b {
  overflow-wrap: anywhere;
  font-size: 12px;
}
.file-detail header small {
  margin-top: 3px;
  color: var(--market-sub);
  font-size: 9px;
}
.file-detail pre {
  min-height: 475px;
  max-height: 680px;
  margin: 16px 0 0;
  padding: 18px;
  overflow: auto;
  border-radius: 12px;
  background: #171b2b;
  color: #e8eaf3;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 11px;
  line-height: 1.65;
}
.panel-empty {
  min-height: 420px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  color: var(--market-sub);
  text-align: center;
}
.market-error button {
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.versions-panel {
  padding: 25px;
}
.versions-panel > article {
  margin-top: 14px;
  padding: 18px;
  border: 1px solid var(--market-line);
  border-radius: 12px;
}
.versions-panel > article.selected {
  border-color: var(--wao-primary-line);
  background: var(--market-soft);
}
.version-name {
  display: flex;
  align-items: center;
  gap: 8px;
}
.version-name b {
  font-size: 15px;
}
.version-name span {
  padding: 3px 6px;
  border-radius: 6px;
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
  font-size: 9px;
}
.version-name time {
  margin-left: auto;
  color: var(--market-sub);
  font-size: 10px;
}
.versions-panel article p {
  max-height: 7em;
  overflow: auto;
  margin: 12px 0;
  color: var(--market-sub);
  white-space: pre-wrap;
  font-size: 12px;
  line-height: 1.6;
}
.version-actions {
  display: flex;
  gap: 16px;
  font-size: 11px;
}
.detail-side {
  display: grid;
  gap: 14px;
}
.prompt-card,
.action-card,
.side-card {
  padding: 19px;
}
.prompt-card {
  text-align: center;
}
.prompt-icon {
  width: 42px;
  height: 42px;
  margin: 0 auto 11px;
  border-radius: 13px;
  display: grid;
  place-items: center;
  background: var(--market-soft);
  color: var(--market-blue);
  font-size: 21px;
}
.prompt-card h2 {
  margin: 0;
  font-size: 15px;
}
.prompt-card p {
  margin: 9px 0 15px;
  color: var(--market-sub);
  font-size: 11px;
  line-height: 1.6;
}
.prompt-card .market-button {
  width: 100%;
}
.action-card {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.action-card .download {
  grid-column: 1/-1;
  background: var(--market-blue);
  border-color: var(--market-blue);
  color: #fff;
}
.side-card h3 {
  margin: 0 0 13px;
  font-size: 14px;
}
.side-card dl {
  margin: 0;
  display: grid;
  gap: 10px;
}
.side-card dl div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
}
.side-card dt {
  color: var(--market-sub);
}
.side-card dd {
  margin: 0;
  font-weight: 650;
}
.side-card .hash {
  display: block;
}
.hash dd {
  margin-top: 6px;
  padding: 8px;
  border-radius: 7px;
  background: var(--wao-bg-sub);
  font-family: monospace;
  font-size: 10px;
  word-break: break-all;
}
.tested {
  display: grid;
  gap: 8px;
}
.tested span {
  font-size: 12px;
}
.tested i {
  margin-right: 6px;
  color: var(--wao-success);
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 900px) {
  .detail-hero {
    grid-template-columns: auto 1fr;
  }
  .latest-button {
    grid-column: 1/-1;
    justify-self: start;
  }
  .detail-grid {
    grid-template-columns: 1fr;
  }
  .detail-side {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .prompt-card,
  .action-card {
    align-self: start;
  }
}
@media (max-width: 650px) {
  .detail-hero {
    grid-template-columns: 1fr;
    padding: 19px;
  }
  .detail-icon {
    width: 56px;
    height: 56px;
  }
  .detail-title h1 {
    font-size: 23px;
  }
  .detail-tabs {
    padding: 0 16px;
    gap: 20px;
  }
  .overview-panel,
  .versions-panel {
    padding: 18px;
  }
  .files-panel {
    grid-template-columns: 1fr;
  }
  .file-list {
    max-height: 210px;
    border-right: 0;
    border-bottom: 1px solid var(--market-line);
  }
  .file-detail pre {
    min-height: 360px;
  }
  .detail-side {
    grid-template-columns: 1fr;
  }
  .panel-empty {
    min-height: 280px;
  }
}
</style>
