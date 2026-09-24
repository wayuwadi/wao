<script setup lang="ts">
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  createCrawlerCapability,
  deleteCrawlerCapability,
  fetchCrawlerCapabilities,
  updateCrawlerCapability,
  type CrawlerCapability,
  type CrawlerLanguage,
  type SaveCrawlerCapability,
} from '@/api/crawlerCapabilities'
import { toast } from '@/composables/toast'
import { useClientPagination } from '@/composables/clientPagination'
import WaoPagination from '@/components/ui/WaoPagination.vue'

interface EditorModel extends SaveCrawlerCapability {
  id: string | null
}

const NEW_DRAFT_KEY = 'wao-crawler-capability-draft:new'
const DRAFT_PREFIX = 'wao-crawler-capability-draft:'

const capabilities = ref<CrawlerCapability[]>([])
const loading = ref(true)
const loadError = ref('')
const query = ref('')
const editor = ref<EditorModel | null>(null)
const initialEditor = ref('')
const saving = ref(false)
const saveError = ref('')
const deleting = ref(false)

const filtered = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase()
  if (!keyword) return capabilities.value
  return capabilities.value.filter((item) =>
    `${item.name}\n${item.description}\n${languageLabel(item.language)}`
      .toLocaleLowerCase()
      .includes(keyword),
  )
})
const { page: capabilityPage, pageCount: capabilityPageCount, visible: visibleCapabilities } = useClientPagination(filtered, [query])

const dirty = computed(() => !!editor.value && JSON.stringify(editor.value) !== initialEditor.value)
const canSave = computed(
  () =>
    !!editor.value?.name.trim() &&
    !!editor.value?.sourceCode.trim() &&
    editor.value.sourceCode.length <= 262_144 &&
    !saving.value,
)

function languageLabel(language: CrawlerLanguage) {
  return language === 'PYTHON' ? 'Python' : 'JavaScript'
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat('zh-CN', {
        month: 'numeric',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      }).format(date)
}

function lineCount(source: string) {
  return source ? source.split(/\r?\n/).length : 0
}

function draftKey(id: string | null) {
  return id ? `${DRAFT_PREFIX}${id}` : NEW_DRAFT_KEY
}

function parseDraft(key: string): EditorModel | null {
  try {
    const parsed = JSON.parse(localStorage.getItem(key) || 'null') as EditorModel | null
    return parsed && typeof parsed.name === 'string' && typeof parsed.sourceCode === 'string'
      ? parsed
      : null
  } catch {
    return null
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    capabilities.value = await fetchCrawlerCapabilities()
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '爬虫能力加载失败，请重试'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  const saved = parseDraft(NEW_DRAFT_KEY)
  editor.value = saved ?? {
    id: null,
    name: '',
    description: '',
    language: 'PYTHON',
    sourceCode: '',
    enabled: false,
    revision: null,
  }
  initialEditor.value = JSON.stringify(editor.value)
  saveError.value = ''
}

function openEdit(item: CrawlerCapability) {
  const current: EditorModel = {
    id: item.id,
    name: item.name,
    description: item.description,
    language: item.language,
    sourceCode: item.sourceCode,
    enabled: item.enabled,
    revision: item.revision,
  }
  const saved = parseDraft(draftKey(item.id))
  editor.value = saved?.revision === item.revision ? saved : current
  initialEditor.value = JSON.stringify(current)
  saveError.value = ''
}

function closeEditor() {
  editor.value = null
  saveError.value = ''
}

async function save() {
  if (!editor.value || !canSave.value) return
  saving.value = true
  saveError.value = ''
  const model = editor.value
  const body: SaveCrawlerCapability = {
    name: model.name.trim(),
    description: model.description.trim(),
    language: model.language,
    sourceCode: model.sourceCode,
    enabled: model.enabled,
    revision: model.revision,
  }
  try {
    const saved = model.id
      ? await updateCrawlerCapability(model.id, body)
      : await createCrawlerCapability(body)
    const index = capabilities.value.findIndex((item) => item.id === saved.id)
    if (index >= 0) capabilities.value[index] = saved
    else capabilities.value.unshift(saved)
    localStorage.removeItem(draftKey(model.id))
    editor.value = null
    toast(model.id ? '爬虫能力已保存' : '爬虫能力已创建', 'ri-check-line')
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

async function remove(item: CrawlerCapability) {
  if (!window.confirm(`确定删除“${item.name}”吗？源码将一并删除。`)) return
  deleting.value = true
  try {
    await deleteCrawlerCapability(item.id, item.revision)
    capabilities.value = capabilities.value.filter((entry) => entry.id !== item.id)
    localStorage.removeItem(draftKey(item.id))
    editor.value = null
    toast('爬虫能力已删除', 'ri-delete-bin-line')
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : '删除失败，请重试'
  } finally {
    deleting.value = false
  }
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
}

watch(
  editor,
  (value) => {
    if (value) localStorage.setItem(draftKey(value.id), JSON.stringify(value))
  },
  { deep: true },
)

onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  void load()
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
useSaveBeforeRefresh(async () => { if (dirty.value) await save() })
</script>

<template>
  <main class="crawler-page">
    <header class="hero">
      <div>
        <p class="eyebrow">AUTOMATION ASSETS</p>
        <h1>爬虫能力</h1>
        <p>集中保存可复用的采集脚本，为后续自动化任务提供明确的能力入口。</p>
      </div>
      <button class="primary" type="button" @click="openCreate">
        <i class="ri-add-line"></i>新增能力
      </button>
    </header>

    <aside class="boundary" aria-label="执行边界">
      <i class="ri-shield-check-line"></i>
      <div>
        <b>源码管理与执行隔离</b>
        <span
          >这里保存真实源码，但不会在 WAO
          服务端直接执行。启用表示允许后续受控执行器引用，不代表脚本已经运行。</span
        >
      </div>
    </aside>

    <label class="search">
      <i class="ri-search-line"></i>
      <input v-model="query" type="search" placeholder="搜索名称、说明或语言" />
    </label>

    <section v-if="loading" class="state-card" aria-live="polite">
      <i class="ri-loader-4-line spin"></i><span>正在读取爬虫能力…</span>
    </section>
    <section v-else-if="loadError" class="state-card error" role="alert">
      <i class="ri-error-warning-line"></i><span>{{ loadError }}</span>
      <button type="button" @click="load">重试</button>
    </section>
    <section v-else-if="!capabilities.length" class="state-card empty">
      <i class="ri-code-box-line"></i>
      <b>还没有爬虫能力</b>
      <span>收到真实脚本后，从这里创建第一项能力。</span>
      <button type="button" @click="openCreate">新增能力</button>
    </section>
    <section v-else-if="!filtered.length" class="state-card empty">
      <i class="ri-search-eye-line"></i><b>没有匹配的能力</b><span>换一个关键词试试。</span>
    </section>
    <section v-else class="capability-grid">
      <article v-for="item in visibleCapabilities" :key="item.id" class="capability-card">
        <header>
          <span class="language">{{ languageLabel(item.language) }}</span>
          <span class="status" :class="{ enabled: item.enabled }">
            {{ item.enabled ? '已启用' : '未启用' }}
          </span>
        </header>
        <h2>{{ item.name }}</h2>
        <p>{{ item.description || '暂无说明' }}</p>
        <div class="code-meta">
          <i class="ri-code-s-slash-line"></i>
          <span>{{ lineCount(item.sourceCode) }} 行源码</span>
          <time>{{ formatTime(item.updatedAt) }} 更新</time>
        </div>
        <footer>
          <small>版本 {{ item.revision }}</small>
          <button type="button" @click="openEdit(item)">
            <i class="ri-edit-line"></i>查看与编辑
          </button>
        </footer>
      </article>
    </section>
    <WaoPagination v-if="!loading && !loadError && capabilityPageCount > 1" v-model:page="capabilityPage" :page-count="capabilityPageCount" :total="filtered.length" aria-label="爬虫能力查询结果分页" />

    <div v-if="editor" class="mask" @click.self="closeEditor">
      <form class="editor" @submit.prevent="save">
        <header>
          <div>
            <h2>{{ editor.id ? '编辑爬虫能力' : '新增爬虫能力' }}</h2>
            <p>源码会真实保存；请通过密码本或部署配置提供密钥，不要写入源码。</p>
          </div>
          <button type="button" aria-label="关闭" @click="closeEditor">
            <i class="ri-close-line"></i>
          </button>
        </header>

        <div class="fields">
          <label class="wide">
            <span>能力名称</span>
            <input
              v-model="editor.name"
              maxlength="128"
              required
              placeholder="例如：公开页面文章采集"
            />
          </label>
          <label>
            <span>脚本语言</span>
            <select v-model="editor.language">
              <option value="PYTHON">Python</option>
              <option value="JAVASCRIPT">JavaScript</option>
            </select>
          </label>
          <label class="switch-field">
            <span>后续执行器可引用</span>
            <input v-model="editor.enabled" type="checkbox" />
          </label>
          <label class="wide">
            <span>能力说明</span>
            <textarea
              v-model="editor.description"
              maxlength="512"
              rows="3"
              placeholder="说明采集目标、输入、输出和限制"
            ></textarea>
          </label>
          <label class="wide code-field">
            <span>源码</span>
            <textarea
              v-model="editor.sourceCode"
              maxlength="262144"
              required
              rows="16"
              spellcheck="false"
              placeholder="粘贴真实脚本源码"
            ></textarea>
            <small>{{ editor.sourceCode.length.toLocaleString() }} / 262,144 字符</small>
          </label>
        </div>

        <p v-if="dirty" class="draft-hint">
          <i class="ri-save-3-line"></i>未保存修改已保存在本机草稿
        </p>
        <p v-if="saveError" class="save-error" role="alert">{{ saveError }}</p>
        <footer>
          <button
            v-if="editor.id"
            class="danger"
            type="button"
            :disabled="deleting || saving"
            @click="remove(capabilities.find((item) => item.id === editor?.id)!)"
          >
            {{ deleting ? '删除中…' : '删除能力' }}
          </button>
          <span v-else></span>
          <div>
            <button type="button" @click="closeEditor">稍后再改</button>
            <button class="primary" type="submit" :disabled="!canSave">
              {{ saving ? '保存中…' : saveError ? '重试保存' : '保存能力' }}
            </button>
          </div>
        </footer>
      </form>
    </div>
  </main>
</template>

<style scoped>
.crawler-page {
  max-width: none;
  margin: 0 auto;
  padding: 30px 26px 56px;
}
.hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}
.eyebrow {
  margin: 0;
  color: var(--wao-primary-text);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.14em;
}
.hero h1 {
  margin: 6px 0 0;
  font-size: clamp(30px, 4vw, 42px);
}
.hero p:not(.eyebrow) {
  max-width: 650px;
  margin: 8px 0 0;
  color: var(--wao-text-3);
  font-size: 13px;
  line-height: 1.7;
}
.primary {
  border: 0 !important;
  background: var(--wao-primary) !important;
  color: #fff !important;
}
.hero button {
  min-height: 40px;
  padding: 0 16px;
  border: 0;
  border-radius: 10px;
  white-space: nowrap;
}
.hero button i {
  margin-right: 5px;
}
.boundary {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  margin-top: 20px;
  padding: 12px 14px;
  border: 1px solid var(--wao-success-line);
  border-radius: 12px;
  background: var(--wao-success-bg);
}
.boundary > i {
  margin-top: 1px;
  color: var(--wao-success-strong);
  font-size: 21px;
}
.boundary div {
  display: grid;
  gap: 3px;
}
.boundary b {
  font-size: 11px;
}
.boundary span {
  color: var(--wao-text-3);
  font-size: 10px;
  line-height: 1.6;
}
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 430px;
  min-height: 40px;
  margin: 16px 0;
  padding: 0 11px;
  border: 1px solid var(--wao-line-control);
  border-radius: 10px;
  background: var(--wao-bg-card);
}
.search i {
  color: var(--wao-text-faint);
}
.search input {
  width: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--wao-text);
  font: inherit;
  font-size: 12px;
}
.state-card {
  min-height: 280px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 9px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  font-size: 12px;
}
.state-card > i {
  font-size: 28px;
}
.state-card button {
  border: 0;
  background: none;
  color: var(--wao-primary-text);
}
.state-card.error {
  color: var(--wao-danger-strong);
}
.capability-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.capability-card {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
}
.capability-card > header,
.capability-card > footer,
.code-meta,
.editor > header,
.editor > footer {
  display: flex;
  align-items: center;
}
.capability-card > header {
  gap: 7px;
}
.language,
.status {
  padding: 3px 8px;
  border-radius: 99px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
  font-size: 9px;
}
.status {
  margin-left: auto;
}
.status.enabled {
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
}
.capability-card h2 {
  margin: 12px 0 0;
  font-size: 16px;
}
.capability-card > p {
  min-height: 42px;
  margin: 7px 0 0;
  color: var(--wao-text-2);
  font-size: 11px;
  line-height: 1.75;
}
.code-meta {
  gap: 6px;
  margin-top: 12px;
  padding: 9px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  font-size: 9px;
}
.code-meta time {
  margin-left: auto;
}
.capability-card > footer {
  justify-content: space-between;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--wao-divider);
}
.capability-card footer small {
  color: var(--wao-text-faint);
  font-size: 9px;
}
.capability-card footer button {
  min-height: 30px;
  padding: 0 9px;
  border: 0;
  border-radius: 7px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 10px;
}
.capability-card footer button i {
  margin-right: 4px;
}
.mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 14px;
  background: rgba(15, 23, 42, 0.48);
}
.editor {
  box-sizing: border-box;
  width: min(760px, 100%);
  max-height: calc(100dvh - 28px);
  overflow: auto;
  padding: 20px;
  border-radius: 16px;
  background: var(--wao-bg-card);
}
.editor > header {
  justify-content: space-between;
}
.editor h2 {
  margin: 0;
  font-size: 18px;
}
.editor header p {
  margin: 4px 0 0;
  color: var(--wao-text-weak);
  font-size: 10px;
}
.editor header button {
  width: 30px;
  height: 30px;
  border: 0;
  background: transparent;
}
.fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 16px;
}
.fields label {
  display: grid;
  align-content: start;
  gap: 6px;
  font-size: 10px;
  font-weight: 650;
}
.fields .wide {
  grid-column: 1/-1;
}
.fields input:not([type='checkbox']),
.fields select,
.fields textarea {
  box-sizing: border-box;
  width: 100%;
  padding: 9px 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  outline: 0;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  font-size: 12px;
}
.fields textarea {
  resize: vertical;
}
.fields .code-field textarea {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 11px;
  line-height: 1.6;
  tab-size: 2;
}
.fields label > small {
  text-align: right;
  color: var(--wao-text-faint);
  font-size: 9px;
}
.switch-field {
  grid-template-columns: 1fr auto;
  align-items: center;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
}
.switch-field input {
  width: 18px;
  height: 18px;
}
.draft-hint,
.save-error {
  margin: 12px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  font-size: 10px;
}
.save-error {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.draft-hint i {
  margin-right: 5px;
}
.editor > footer {
  justify-content: space-between;
  gap: 10px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--wao-divider);
}
.editor footer > div {
  display: flex;
  gap: 8px;
}
.editor footer button {
  min-height: 36px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: var(--wao-bg-sub);
}
.editor footer button:disabled {
  opacity: 0.5;
}
.editor footer .danger {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 720px) {
  .crawler-page {
    padding: 20px 12px 40px;
  }
  .hero {
    align-items: stretch;
    flex-direction: column;
  }
  .hero button {
    width: 100%;
  }
  .capability-grid {
    grid-template-columns: 1fr;
  }
  .mask {
    align-items: end;
    padding: 7px;
  }
  .editor {
    max-height: calc(100dvh - 14px);
  }
  .fields {
    grid-template-columns: 1fr;
  }
  .fields .wide {
    grid-column: auto;
  }
  .editor > footer {
    align-items: stretch;
    flex-direction: column;
  }
  .editor > footer > div {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
  .editor footer .danger {
    width: 100%;
  }
}
</style>
