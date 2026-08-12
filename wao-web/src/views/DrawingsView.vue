<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  createDrawing,
  deleteDrawing,
  fetchDrawing,
  fetchDrawings,
  renameDrawing,
  saveDrawing,
  type DrawingFile,
  type DrawingFileSummary,
} from '@/api/drawings'
import DrawingCanvas from '@/components/drawings/DrawingCanvas.vue'
import { readDrawingDraft, removeDrawingDraft, writeDrawingDraft } from '@/utils/drawingDrafts'

type SyncState = 'loading' | 'dirty' | 'saving' | 'saved' | 'error' | 'conflict'

const files = ref<DrawingFileSummary[]>([])
const activeId = ref('')
const current = ref<DrawingFile | null>(null)
const sceneJson = ref('')
const revision = ref(1)
const canvasKey = ref('empty')
const loadingList = ref(true)
const loadingFile = ref(false)
const creating = ref(false)
const sidebarOpen = ref(true)
const syncState = ref<SyncState>('loading')
const error = ref('')
const dirty = ref(false)
let loadToken = 0
let canvasGeneration = 0
let saveTimer: number | undefined
let draftTimer: number | undefined
let savePromise: Promise<void> | null = null

const syncMeta = computed(
  () =>
    ({
      loading: { text: '正在读取云端文件', icon: 'ri-loader-4-line' },
      dirty: { text: '未保存，本机草稿已记录', icon: 'ri-edit-circle-line' },
      saving: { text: '正在保存到账号', icon: 'ri-loader-4-line' },
      saved: { text: '已保存到账号', icon: 'ri-cloud-line' },
      error: { text: '保存失败，草稿仍在本机', icon: 'ri-error-warning-line' },
      conflict: { text: '云端已有新版本，未覆盖', icon: 'ri-git-merge-line' },
    })[syncState.value],
)

function updateSummary(file: DrawingFile | DrawingFileSummary) {
  const index = files.value.findIndex((item) => item.id === file.id)
  if (index >= 0) files.value.splice(index, 1, { ...file })
  else files.value.push({ ...file })
  files.value.sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
}

function message(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback
}

async function loadList() {
  loadingList.value = true
  error.value = ''
  try {
    files.value = await fetchDrawings()
    if (files.value.length) await openFile(files.value[0]!.id)
    else syncState.value = 'saved'
  } catch (cause) {
    error.value = message(cause, '草图文件列表读取失败')
    syncState.value = 'error'
  } finally {
    loadingList.value = false
  }
}

async function openFile(id: string) {
  if (activeId.value === id && current.value && !loadingFile.value) return
  clearTimeout(saveTimer)
  if (dirty.value && syncState.value !== 'conflict') await saveNow()

  const token = ++loadToken
  activeId.value = id
  loadingFile.value = true
  syncState.value = 'loading'
  error.value = ''
  try {
    const remote = await fetchDrawing(id)
    let draft = null
    try {
      draft = await readDrawingDraft(id)
    } catch {
      // IndexedDB 被浏览器禁用时仍可使用云端保存。
    }
    if (token !== loadToken) return

    current.value = remote
    sceneJson.value = draft?.sceneJson ?? remote.sceneJson
    revision.value = draft?.revision ?? remote.revision
    dirty.value = !!draft && draft.sceneJson !== remote.sceneJson
    canvasKey.value = `${id}:${++canvasGeneration}`
    updateSummary(remote)

    if (dirty.value && draft?.revision !== remote.revision) {
      syncState.value = 'conflict'
      error.value = '本机草稿基于旧版本，云端已有更新。当前先展示本机草稿，不会覆盖云端。'
    } else {
      syncState.value = dirty.value ? 'dirty' : 'saved'
    }
  } catch (cause) {
    if (token !== loadToken) return
    current.value = null
    sceneJson.value = ''
    error.value = message(cause, '草图文件读取失败')
    syncState.value = 'error'
  } finally {
    if (token === loadToken) loadingFile.value = false
  }
}

function scheduleDraft() {
  clearTimeout(draftTimer)
  const id = activeId.value
  const capturedScene = sceneJson.value
  const capturedRevision = revision.value
  draftTimer = window.setTimeout(() => {
    void writeDrawingDraft({
      id,
      sceneJson: capturedScene,
      revision: capturedRevision,
      updatedAt: Date.now(),
    }).catch(() => {
      // 云端自动保存仍会继续；失败时页面状态由服务端请求给出。
    })
  }, 180)
}

function scheduleSave() {
  clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => void saveNow(), 1_100)
}

function canvasChanged(nextScene: string) {
  if (!current.value || nextScene === sceneJson.value) return
  sceneJson.value = nextScene
  dirty.value = true
  error.value = ''
  syncState.value = 'dirty'
  scheduleDraft()
  scheduleSave()
}

function canvasReady() {
  if (dirty.value && syncState.value === 'dirty') scheduleSave()
}

async function saveNow() {
  clearTimeout(saveTimer)
  if (!current.value || !dirty.value || syncState.value === 'conflict') return
  if (savePromise) {
    await savePromise
    return
  }

  const id = current.value.id
  const capturedScene = sceneJson.value
  const capturedRevision = revision.value
  syncState.value = 'saving'
  error.value = ''
  savePromise = (async () => {
    try {
      const saved = await saveDrawing(id, capturedScene, capturedRevision)
      updateSummary(saved)
      if (activeId.value !== id || !current.value) return
      current.value = saved
      revision.value = saved.revision
      if (sceneJson.value === capturedScene) {
        dirty.value = false
        syncState.value = 'saved'
        await removeDrawingDraft(id).catch(() => undefined)
      } else {
        dirty.value = true
        syncState.value = 'dirty'
        await writeDrawingDraft({
          id,
          sceneJson: sceneJson.value,
          revision: saved.revision,
          updatedAt: Date.now(),
        }).catch(() => undefined)
        scheduleSave()
      }
    } catch (cause) {
      if (activeId.value !== id) return
      dirty.value = true
      error.value = message(cause, '草图保存失败')
      syncState.value = error.value.includes('其它页面') ? 'conflict' : 'error'
      await writeDrawingDraft({
        id,
        sceneJson: sceneJson.value,
        revision: capturedRevision,
        updatedAt: Date.now(),
      }).catch(() => undefined)
    }
  })()
  try {
    await savePromise
  } finally {
    savePromise = null
  }
}

async function newFile() {
  if (creating.value) return
  creating.value = true
  error.value = ''
  try {
    if (dirty.value && syncState.value !== 'conflict') await saveNow()
    const created = await createDrawing()
    updateSummary(created)
    activeId.value = ''
    await openFile(created.id)
    sidebarOpen.value = window.innerWidth > 760
  } catch (cause) {
    error.value = message(cause, '新建草图失败')
  } finally {
    creating.value = false
  }
}

async function renameFile(file: DrawingFileSummary) {
  const title = window.prompt('请输入新的草图名称', file.title)?.trim()
  if (!title || title === file.title) return
  try {
    const renamed = await renameDrawing(file.id, title)
    updateSummary(renamed)
    if (current.value?.id === file.id) current.value = { ...current.value, title }
  } catch (cause) {
    error.value = message(cause, '草图重命名失败')
  }
}

async function removeFile(file: DrawingFileSummary) {
  if (!window.confirm(`删除「${file.title}」吗？该操作无法撤销。`)) return
  try {
    if (activeId.value === file.id) {
      if (savePromise) await savePromise
      clearTimeout(saveTimer)
      dirty.value = false
    }
    await deleteDrawing(file.id)
    await removeDrawingDraft(file.id).catch(() => undefined)
    files.value = files.value.filter((item) => item.id !== file.id)
    if (activeId.value === file.id) {
      activeId.value = ''
      current.value = null
      sceneJson.value = ''
      if (files.value.length) await openFile(files.value[0]!.id)
    }
  } catch (cause) {
    error.value = message(cause, '草图删除失败')
  }
}

function downloadDraft() {
  if (!current.value || !sceneJson.value) return
  const blob = new Blob([sceneJson.value], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${current.value.title || '未命名草图'}.excalidraw`
  anchor.click()
  URL.revokeObjectURL(url)
}

async function useCloudVersion() {
  if (!current.value) return
  if (!window.confirm('确定放弃当前本机草稿，重新读取云端版本吗？建议先下载草稿备份。')) return
  const id = current.value.id
  await removeDrawingDraft(id).catch(() => undefined)
  dirty.value = false
  activeId.value = ''
  await openFile(id)
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  void loadList()
})

onBeforeUnmount(() => {
  clearTimeout(saveTimer)
  clearTimeout(draftTimer)
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <section class="drawings-page" :class="{ 'sidebar-open': sidebarOpen }">
    <aside class="file-sidebar">
      <div class="sidebar-head">
        <div>
          <strong>我的草图</strong>
          <span>{{ files.length }} 个文件</span>
        </div>
        <button title="收起文件列表" @click="sidebarOpen = false">
          <i class="ri-side-bar-line"></i>
        </button>
      </div>

      <button class="new-file" :disabled="creating" @click="newFile">
        <i :class="creating ? 'ri-loader-4-line spin' : 'ri-add-line'"></i>
        {{ creating ? '正在新建' : '新建草图' }}
      </button>

      <div v-if="loadingList" class="list-state">
        <i class="ri-loader-4-line spin"></i>正在读取文件
      </div>
      <div v-else-if="!files.length" class="list-state empty-list">
        <i class="ri-draft-line"></i>
        <span>还没有草图</span>
        <small>新建后会以当前账号保存</small>
      </div>
      <div v-else class="file-list">
        <div
          v-for="file in files"
          :key="file.id"
          class="file-row"
          :class="{ active: activeId === file.id }"
          role="button"
          :tabindex="loadingFile ? -1 : 0"
          :aria-disabled="loadingFile"
          @click="!loadingFile && openFile(file.id)"
          @keydown.enter="!loadingFile && openFile(file.id)"
          @keydown.space.prevent="!loadingFile && openFile(file.id)"
        >
          <i class="ri-draft-line file-icon"></i>
          <span class="file-main">
            <strong>{{ file.title }}</strong>
            <small
              >v{{ file.revision }} ·
              {{
                new Date(file.updatedAt).toLocaleString('zh-CN', {
                  month: 'numeric',
                  day: 'numeric',
                  hour: '2-digit',
                  minute: '2-digit',
                })
              }}</small
            >
          </span>
          <span class="file-actions" @click.stop>
            <button type="button" title="重命名" @click="renameFile(file)">
              <i class="ri-pencil-line"></i>
            </button>
            <button type="button" title="删除" @click="removeFile(file)">
              <i class="ri-delete-bin-line"></i>
            </button>
          </span>
        </div>
      </div>
    </aside>

    <main class="drawing-workspace">
      <header class="drawing-toolbar">
        <button
          v-if="!sidebarOpen"
          class="icon-button"
          title="展开文件列表"
          @click="sidebarOpen = true"
        >
          <i class="ri-menu-line"></i>
        </button>
        <div class="title-block">
          <strong>{{ current?.title ?? '个人草图' }}</strong>
          <span v-if="current">版本 {{ revision }}</span>
        </div>
        <div v-if="current" class="sync-pill" :class="syncState">
          <i
            :class="[syncMeta.icon, { spin: syncState === 'loading' || syncState === 'saving' }]"
          ></i>
          <span>{{ syncMeta.text }}</span>
        </div>
        <button
          v-if="dirty && syncState !== 'conflict'"
          class="save-button"
          :disabled="syncState === 'saving'"
          @click="saveNow"
        >
          <i class="ri-save-3-line"></i>立即保存
        </button>
      </header>

      <div v-if="error" class="error-banner" :class="{ conflict: syncState === 'conflict' }">
        <i class="ri-error-warning-line"></i>
        <span>{{ error }}</span>
        <template v-if="syncState === 'conflict'">
          <button @click="downloadDraft">下载本机草稿</button>
          <button @click="useCloudVersion">重新读取云端</button>
        </template>
        <button v-else-if="current && dirty" @click="saveNow">重试保存</button>
        <button v-else @click="loadList">重试读取</button>
      </div>

      <div class="canvas-shell">
        <div v-if="loadingFile" class="canvas-loading">
          <i class="ri-loader-4-line spin"></i>
          <span>正在打开草图</span>
        </div>
        <DrawingCanvas
          v-else-if="current"
          :scene-json="sceneJson"
          :scene-key="canvasKey"
          :name="current.title"
          @change="canvasChanged"
          @ready="canvasReady"
        />
        <div v-else class="canvas-empty">
          <span><i class="ri-draft-line"></i></span>
          <h1>把想法画出来</h1>
          <p>Excalidraw 画布已接入 WAO，每份文件只属于当前账号。</p>
          <button :disabled="creating" @click="newFile">
            <i class="ri-add-line"></i>新建第一张草图
          </button>
        </div>
      </div>
    </main>
  </section>
</template>

<style scoped>
.drawings-page {
  height: calc(100dvh - var(--wao-header-h) - var(--wao-tabbar-h));
  min-height: 560px;
  display: flex;
  overflow: hidden;
  background: var(--wao-bg-page);
}
.file-sidebar {
  width: 268px;
  flex: 0 0 268px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 12px;
  border-right: 1px solid var(--wao-line);
  background: var(--wao-bg-card);
}
.sidebar-head,
.drawing-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.sidebar-head > div {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 3px;
}
.sidebar-head strong {
  font-size: 14px;
}
.sidebar-head span,
.title-block span {
  color: var(--wao-text-weak);
  font-size: 10px;
}
.sidebar-head button,
.icon-button {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.sidebar-head button:hover,
.icon-button:hover {
  background: var(--wao-bg-hover-2);
}
.new-file,
.canvas-empty button {
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 0;
  border-radius: 9px;
  background: var(--wao-primary);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}
.new-file:disabled,
.canvas-empty button:disabled {
  opacity: 0.6;
  cursor: wait;
}
.file-list {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  display: grid;
  align-content: start;
  gap: 5px;
}
.file-row {
  box-sizing: border-box;
  width: 100%;
  min-height: 58px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: var(--wao-text);
  text-align: left;
  cursor: pointer;
}
.file-row:hover,
.file-row:focus-visible {
  background: var(--wao-bg-hover-2);
}
.file-row:focus-visible {
  outline: 2px solid var(--wao-primary-line);
  outline-offset: -2px;
}
.file-row.active {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-soft);
}
.file-icon {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-primary-text);
  font-size: 16px;
}
.file-main {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 4px;
}
.file-main strong {
  overflow: hidden;
  color: inherit;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-main small {
  color: var(--wao-text-weak);
  font-size: 9px;
}
.file-actions {
  display: flex;
  opacity: 0;
}
.file-row:hover .file-actions,
.file-row.active .file-actions,
.file-row:focus-within .file-actions {
  opacity: 1;
}
.file-actions button {
  width: 25px;
  height: 25px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
}
.file-actions button:hover,
.file-actions button:focus-visible {
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.file-actions button:last-child:hover,
.file-actions button:last-child:focus-visible {
  color: var(--wao-danger-strong);
}
.list-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.empty-list {
  flex-direction: column;
  text-align: center;
}
.empty-list i {
  font-size: 26px;
  color: var(--wao-primary-text);
}
.empty-list small {
  max-width: 180px;
  line-height: 1.5;
}
.drawing-workspace {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
}
.drawing-toolbar {
  height: 54px;
  flex: 0 0 54px;
  padding: 0 14px;
  border-bottom: 1px solid var(--wao-line);
  background: var(--wao-bg-card);
}
.title-block {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 2px;
}
.title-block strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sync-pill {
  max-width: 270px;
  min-height: 29px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid var(--wao-success-line);
  border-radius: 999px;
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
  font-size: 10px;
  white-space: nowrap;
}
.sync-pill.dirty,
.sync-pill.loading,
.sync-pill.saving {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.sync-pill.error,
.sync-pill.conflict {
  border-color: var(--wao-danger-line);
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.save-button,
.error-banner button {
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font-size: 10px;
  cursor: pointer;
}
.save-button i {
  margin-right: 5px;
}
.error-banner {
  min-height: 40px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 13px;
  border-bottom: 1px solid var(--wao-danger-line);
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  font-size: 10px;
}
.error-banner span {
  min-width: 0;
  flex: 1;
}
.error-banner button {
  border-color: currentColor;
  background: transparent;
  color: inherit;
  white-space: nowrap;
}
.canvas-shell {
  position: relative;
  min-height: 0;
  flex: 1;
  overflow: hidden;
}
.canvas-loading,
.canvas-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  font-size: 12px;
  z-index: 2;
}
.canvas-empty > span {
  width: 58px;
  height: 58px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 27px;
}
.canvas-empty h1 {
  margin: 5px 0 0;
  color: var(--wao-text);
  font-size: 22px;
}
.canvas-empty p {
  max-width: 430px;
  margin: 0 16px 8px;
  line-height: 1.7;
  text-align: center;
}
.canvas-empty button {
  padding: 0 15px;
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 760px) {
  .drawings-page {
    height: calc(100dvh - 48px - var(--wao-tabbar-h) - 64px);
    min-height: 420px;
  }
  .file-sidebar {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 20;
    width: min(86vw, 310px);
    transform: translateX(-102%);
    transition: transform 0.18s ease;
    box-shadow: var(--wao-shadow-pop-2);
  }
  .sidebar-open .file-sidebar {
    transform: translateX(0);
  }
  .drawing-toolbar {
    height: 48px;
    flex-basis: 48px;
    padding: 0 8px;
  }
  .sync-pill {
    max-width: none;
    padding: 0 8px;
  }
  .sync-pill span {
    display: none;
  }
  .save-button {
    width: 32px;
    padding: 0;
    font-size: 0;
  }
  .save-button i {
    margin: 0;
    font-size: 15px;
  }
  .error-banner {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .error-banner span {
    flex-basis: calc(100% - 28px);
  }
  .file-actions {
    opacity: 1;
  }
}
</style>
