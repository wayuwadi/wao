<script setup lang="ts">
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  createDrawing,
  deleteDrawing,
  fetchDrawing,
  fetchDrawingVersions,
  fetchDrawings,
  renameDrawing,
  restoreDrawingVersion,
  saveDrawing,
  type DrawingFile,
  type DrawingFileSummary,
  type DrawingFileVersion,
} from '@/api/drawings'
const DrawingCanvas = defineAsyncComponent(() => import('@/components/drawings/DrawingCanvas.vue').then((module) => module.default))
import { readDrawingDraft, removeDrawingDraft, writeDrawingDraft } from '@/utils/drawingDrafts'

type SyncState = 'loading' | 'dirty' | 'saving' | 'saved' | 'error' | 'conflict'

const props = defineProps<{ drawingId?: string; embedded?: boolean }>()
const files = ref<DrawingFileSummary[]>([])
const activeId = ref('')
const current = ref<DrawingFile | null>(null)
const sceneJson = ref('')
const revision = ref(1)
const canvasKey = ref('empty')
const loadingList = ref(true)
const loadingFile = ref(false)
const creating = ref(false)
const sidebarOpen = ref(!props.embedded)
const syncState = ref<SyncState>('loading')
const error = ref('')
const dirty = ref(false)
const page = ref<HTMLElement | null>(null)
const titleInput = ref<HTMLInputElement | null>(null)
const editingTitle = ref(false)
const titleDraft = ref('')
const renaming = ref(false)
const isFullscreen = ref(false)
const versions = ref<DrawingFileVersion[]>([])
const loadingVersions = ref(false)
const restoringVersion = ref<number | null>(null)
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
    if (props.embedded && props.drawingId) { await openFile(props.drawingId); return }
    files.value = await fetchDrawings()
    const requested = new URLSearchParams(window.location.search).get('drawing') ?? ''
    const first = files.value.find((item) => item.id === requested)
    if (first) await openFile(first.id)
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
    titleDraft.value = remote.title
    sceneJson.value = draft?.sceneJson ?? remote.sceneJson
    revision.value = draft?.revision ?? remote.revision
    dirty.value = !!draft && draft.sceneJson !== remote.sceneJson
    canvasKey.value = `${id}:${++canvasGeneration}`
    updateSummary(remote)
    void loadVersions(id)

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

async function loadVersions(id = activeId.value) {
  if (!id) return
  loadingVersions.value = true
  try {
    const loaded = await fetchDrawingVersions(id)
    if (activeId.value === id) versions.value = loaded
  } catch (cause) {
    if (activeId.value === id) error.value = message(cause, '草图版本读取失败')
  } finally {
    if (activeId.value === id) loadingVersions.value = false
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
        await loadVersions(id)
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

async function startTitleEdit() {
  if (!current.value || renaming.value) return
  titleDraft.value = current.value.title
  editingTitle.value = true
  await nextTick()
  titleInput.value?.focus()
  titleInput.value?.select()
}

function cancelTitleEdit() {
  titleDraft.value = current.value?.title ?? ''
  editingTitle.value = false
}

async function saveTitle() {
  if (!current.value || renaming.value) return
  const title = titleDraft.value.trim()
  if (!title) {
    error.value = '草图名称不能为空'
    titleInput.value?.focus()
    return
  }
  if (title === current.value.title) {
    editingTitle.value = false
    return
  }
  renaming.value = true
  error.value = ''
  try {
    const renamed = await renameDrawing(current.value.id, title)
    updateSummary(renamed)
    if (current.value?.id === renamed.id) {
      current.value = { ...current.value, title: renamed.title, updatedAt: renamed.updatedAt }
      titleDraft.value = renamed.title
      editingTitle.value = false
    }
  } catch (cause) {
    error.value = message(cause, '草图重命名失败')
    titleInput.value?.focus()
  } finally {
    renaming.value = false
  }
}

async function restoreVersion(version: DrawingFileVersion) {
  if (!current.value || version.revision === revision.value || restoringVersion.value !== null)
    return
  if (dirty.value) {
    error.value = '请先保存当前修改，再恢复历史版本'
    return
  }
  if (!window.confirm(`恢复版本 ${version.revision} 吗？当前内容会作为历史版本保留。`)) return
  restoringVersion.value = version.revision
  error.value = ''
  try {
    const restored = await restoreDrawingVersion(current.value.id, version.revision, revision.value)
    current.value = restored
    sceneJson.value = restored.sceneJson
    revision.value = restored.revision
    canvasKey.value = `${restored.id}:${++canvasGeneration}`
    updateSummary(restored)
    syncState.value = 'saved'
    await loadVersions(restored.id)
  } catch (cause) {
    error.value = message(cause, '草图版本恢复失败')
  } finally {
    restoringVersion.value = null
  }
}

async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await page.value?.requestFullscreen()
  } catch (cause) {
    error.value = message(cause, '无法进入全屏模式')
  }
}

function fullscreenChanged() {
  isFullscreen.value = document.fullscreenElement === page.value
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
      syncState.value = 'saved'
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
  document.addEventListener('fullscreenchange', fullscreenChanged)
  void loadList()
})

onBeforeUnmount(() => {
  clearTimeout(saveTimer)
  clearTimeout(draftTimer)
  if (dirty.value && current.value) {
    void writeDrawingDraft({ id: current.value.id, sceneJson: sceneJson.value, revision: revision.value, updatedAt: Date.now() }).catch(() => undefined)
    if (syncState.value !== 'conflict') void saveNow()
  }
  window.removeEventListener('beforeunload', beforeUnload)
  document.removeEventListener('fullscreenchange', fullscreenChanged)
})
useSaveBeforeRefresh(async () => { if (dirty.value) await saveNow() })
</script>

<template>
  <section ref="page" class="drawings-page" :class="{ 'wao-page-frame': !embedded, 'sidebar-open': sidebarOpen, embedded }">
    <aside v-if="!embedded" class="file-sidebar">
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
            <a v-if="file.docId && file.kbId" :href="`/kb/${file.kbId}?doc=${file.docId}`" title="在知识库管理">知识库</a>
            <button v-if="!file.docId" type="button" title="重命名" @click="renameFile(file)">
              <i class="ri-pencil-line"></i>
            </button>
            <button v-if="!file.docId" type="button" title="删除" @click="removeFile(file)">
              <i class="ri-delete-bin-line"></i>
            </button>
          </span>
        </div>
      </div>
    </aside>

    <main class="drawing-workspace">
      <header class="drawing-toolbar">
        <button
          v-if="!sidebarOpen && !embedded"
          class="icon-button"
          title="展开文件列表"
          @click="sidebarOpen = true"
        >
          <i class="ri-menu-line"></i>
        </button>
        <div v-if="!embedded" class="title-block">
          <input
            v-if="current && editingTitle"
            ref="titleInput"
            v-model="titleDraft"
            maxlength="128"
            aria-label="草图名称"
            :disabled="renaming"
            @blur="saveTitle"
            @keydown.enter.prevent="saveTitle"
            @keydown.esc.prevent="cancelTitleEdit"
          />
          <button
            v-else-if="current && !current.docId"
            type="button"
            class="title-trigger"
            title="点击修改草图名称"
            @click="startTitleEdit"
          >
            <strong>{{ current.title }}</strong
            ><i class="ri-pencil-line"></i>
          </button>
          <strong v-else>{{ current?.title || '个人草图' }}</strong>
          <span v-if="current">{{ renaming ? '正在保存名称' : `版本 ${revision}` }}</span>
        </div>
        <details v-if="current" class="version-menu">
          <summary :title="loadingVersions ? '正在读取版本' : '查看最近三个版本'">
            <i :class="loadingVersions ? 'ri-loader-4-line spin' : 'ri-history-line'"></i>
            <span>版本</span>
          </summary>
          <div class="version-popover">
            <strong>最近三个有效版本</strong>
            <button
              v-for="version in versions"
              :key="version.revision"
              type="button"
              :disabled="version.revision === revision || restoringVersion !== null"
              @click="restoreVersion(version)"
            >
              <span>版本 {{ version.revision }}</span>
              <small>{{ new Date(version.createdAt).toLocaleString('zh-CN') }}</small>
              <i v-if="restoringVersion === version.revision" class="ri-loader-4-line spin"></i>
              <em v-else>{{ version.revision === revision ? '当前' : '恢复' }}</em>
            </button>
          </div>
        </details>
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
        <button
          v-if="current"
          type="button"
          class="icon-button fullscreen-button"
          :title="isFullscreen ? '退出全屏' : '全屏演示'"
          @click="toggleFullscreen"
        >
          <i :class="isFullscreen ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'"></i>
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
        <div v-else-if="!embedded" class="canvas-empty">
          <span><i class="ri-draft-line"></i></span>
          <h1>把想法画出来</h1>
          <p>选择左侧草图继续创作，或新建一张。打开文件后才会加载画布。</p>
          <button :disabled="creating" @click="newFile">
            <i class="ri-add-line"></i>新建草图
          </button>
        </div>
      </div>
    </main>
  </section>
</template>

<style scoped>
.drawings-page.embedded { flex: 1; height: 100%; min-height: 0; margin: 0; padding: 0; width: 100%; }
.drawings-page {
  height: 100%;
  min-height: 0;
  display: flex;
  overflow: hidden;
  background: var(--wao-bg-page);
}
.file-sidebar {
  min-height: 0;
  overflow: hidden;
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
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
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
  min-height: 0;
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--wao-bg-card);
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
.title-block > strong,
.title-trigger strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.title-trigger {
  min-width: 0;
  width: fit-content;
  max-width: 100%;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--wao-text);
  cursor: text;
}
.title-trigger i {
  flex: 0 0 auto;
  color: var(--wao-text-weak);
  font-size: 11px;
  opacity: 0;
}
.title-trigger:hover i,
.title-trigger:focus-visible i {
  opacity: 1;
}
.title-block input {
  box-sizing: border-box;
  width: min(360px, 100%);
  height: 27px;
  padding: 0 7px;
  border: 1px solid var(--wao-primary);
  border-radius: 6px;
  outline: 2px solid var(--wao-primary-soft);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  font-size: 13px;
  font-weight: 650;
}
.version-menu {
  position: relative;
}
.version-menu summary {
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 9px;
  border-radius: 8px;
  color: var(--wao-text-2);
  font-size: 10px;
  cursor: pointer;
  list-style: none;
}
.version-menu summary::-webkit-details-marker {
  display: none;
}
.version-menu summary:hover,
.version-menu[open] summary {
  background: var(--wao-bg-hover-2);
}
.version-popover {
  position: absolute;
  z-index: 30;
  top: calc(100% + 9px);
  right: 0;
  width: 230px;
  display: grid;
  gap: 5px;
  padding: 10px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-pop-2);
}
.version-popover > strong {
  padding: 2px 4px 6px;
  font-size: 11px;
}
.version-popover button {
  min-width: 0;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 2px 8px;
  padding: 8px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text);
  text-align: left;
  cursor: pointer;
}
.version-popover button:hover:not(:disabled) {
  background: var(--wao-bg-hover-2);
}
.version-popover button:disabled {
  cursor: default;
}
.version-popover button span {
  font-size: 11px;
  font-weight: 650;
}
.version-popover button small {
  grid-column: 1;
  color: var(--wao-text-weak);
  font-size: 9px;
}
.version-popover button i,
.version-popover button em {
  grid-column: 2;
  grid-row: 1 / span 2;
  align-self: center;
  color: var(--wao-primary-text);
  font-size: 9px;
  font-style: normal;
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
.fullscreen-button {
  flex: 0 0 32px;
  font-size: 16px;
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
.drawings-page:fullscreen {
  width: 100vw;
  height: 100dvh;
  min-height: 0;
  background: var(--wao-bg-page);
}
.drawings-page:fullscreen .file-sidebar {
  display: none;
}
.drawings-page:fullscreen .drawing-toolbar {
  position: absolute;
  z-index: 25;
  top: 10px;
  right: 10px;
  width: auto;
  height: 40px;
  padding: 0 4px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop-2);
}
.drawings-page:fullscreen .drawing-toolbar > :not(.fullscreen-button) {
  display: none;
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
    height: 100%;
    min-height: 0;
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
  .version-menu summary span {
    display: none;
  }
  .version-menu summary {
    width: 30px;
    justify-content: center;
    padding: 0;
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
