<script setup lang="ts">
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
import {
  computed,
  defineAsyncComponent,
  h,
  inject,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue'
import { onBeforeRouteLeave, routerKey } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import WaoButton from '@/components/ui/WaoButton.vue'
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
const DrawingCanvas = defineAsyncComponent({
  loader: () => import('@/components/drawings/DrawingCanvas.vue').then((module) => module.default),
  loadingComponent: {
    render: () => h('div', { class: 'canvas-loading', role: 'status' }, '正在加载画布…'),
  },
  errorComponent: {
    render: () =>
      h('div', { class: 'canvas-loading', role: 'alert' }, [
        h('p', '画布加载失败，草图内容已保留。'),
        h(
          'button',
          {
            onClick: () => {
              canvasKey.value = `${activeId.value}:${++canvasGeneration}`
            },
          },
          '重试加载画布',
        ),
      ]),
  },
})
import { readDrawingDraft, removeDrawingDraft, writeDrawingDraft } from '@/utils/drawingDrafts'

type SyncState = 'loading' | 'dirty' | 'saving' | 'saved' | 'error' | 'conflict'

const props = defineProps<{ drawingId?: string; embedded?: boolean }>()
const session = useSessionStore()
const owner = session.userId
const search = ref('')
const listError = ref('')
const versionsError = ref('')
const draftError = ref('')
const draftScene = ref('')
const transitioning = ref(false)
const deletingId = ref('')
const conflict = ref(false)
const reloadingCloud = ref(false)
let disposed = false
let draftQueue: Promise<unknown> = Promise.resolve()
const sameOwner = () => !disposed && session.userId === owner
const files = ref<DrawingFileSummary[]>([])
const visibleFiles = computed(() =>
  files.value.filter((file) =>
    file.title.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase()),
  ),
)
const recentFiles = computed(() => files.value.slice(0, 3))
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
      dirty: {
        text: draftScene.value === sceneJson.value ? '待同步 · 本机草稿已记录' : '尚未保存',
        icon: 'ri-edit-circle-line',
      },
      saving: { text: '正在保存到账号', icon: 'ri-loader-4-line' },
      saved: { text: '已保存到账号', icon: 'ri-cloud-line' },
      error: {
        text:
          draftScene.value === sceneJson.value
            ? '保存失败 · 本机草稿已记录'
            : '保存失败 · 请勿关闭页面',
        icon: 'ri-error-warning-line',
      },
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
  listError.value = ''
  error.value = ''
  try {
    if (props.embedded && props.drawingId) {
      await openFile(props.drawingId)
      return
    }
    const loaded = await fetchDrawings()
    if (!sameOwner()) return
    files.value = loaded.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    const requested = new URLSearchParams(window.location.search).get('drawing') ?? ''
    if (requested) await openFile(requested)
    else syncState.value = 'saved'
  } catch (cause) {
    error.value = message(cause, '草图文件列表读取失败')
    listError.value = error.value
    syncState.value = 'error'
  } finally {
    loadingList.value = false
  }
}

async function openFile(id: string) {
  if (
    !sameOwner() ||
    transitioning.value ||
    deletingId.value ||
    restoringVersion.value !== null ||
    renaming.value
  )
    return
  if (activeId.value === id && current.value && !loadingFile.value) {
    sidebarOpen.value = window.innerWidth > 760
    return
  }
  transitioning.value = true
  clearTimeout(saveTimer)
  if (!(await settleEdits())) {
    transitioning.value = false
    return
  }

  const token = ++loadToken
  activeId.value = id
  loadingFile.value = true
  syncState.value = 'loading'
  error.value = ''
  versions.value = []
  versionsError.value = ''
  draftError.value = ''
  editingTitle.value = false
  try {
    const remote = await fetchDrawing(id)
    let draft = null
    try {
      draft = await readDrawingDraft(owner, id, !remote.docId)
    } catch {
      // IndexedDB 被浏览器禁用时仍可使用云端保存。
    }
    if (token !== loadToken || !sameOwner()) return

    current.value = remote
    titleDraft.value = remote.title
    sceneJson.value = draft?.sceneJson ?? remote.sceneJson
    dirty.value = !!draft && draft.sceneJson !== remote.sceneJson
    revision.value = dirty.value ? draft!.revision : remote.revision
    draftScene.value = draft?.sceneJson ?? ''
    canvasKey.value = `${id}:${++canvasGeneration}`
    updateSummary(remote)
    void loadVersions(id)

    conflict.value = dirty.value && draft?.revision !== remote.revision
    if (conflict.value) {
      syncState.value = 'conflict'
      error.value = '本机草稿基于旧版本，云端已有更新。当前先展示本机草稿，不会覆盖云端。'
    } else {
      syncState.value = dirty.value ? 'dirty' : 'saved'
    }
    sidebarOpen.value = window.innerWidth > 760 && sidebarOpen.value
  } catch (cause) {
    if (token !== loadToken || !sameOwner()) return
    current.value = null
    sceneJson.value = ''
    error.value = message(cause, '草图文件读取失败')
    syncState.value = 'error'
  } finally {
    if (token === loadToken) loadingFile.value = false
    transitioning.value = false
  }
}

async function loadVersions(id = activeId.value) {
  if (!id) return
  loadingVersions.value = true
  versionsError.value = ''
  try {
    const loaded = await fetchDrawingVersions(id)
    if (activeId.value === id && sameOwner()) versions.value = loaded
  } catch (cause) {
    if (activeId.value === id && sameOwner())
      versionsError.value = message(cause, '草图版本读取失败')
  } finally {
    if (activeId.value === id) loadingVersions.value = false
  }
}

function persistDraft() {
  clearTimeout(draftTimer)
  if (!current.value || !dirty.value) return Promise.resolve(true)
  const id = current.value.id
  const capturedScene = sceneJson.value
  const capturedRevision = revision.value
  const operation = draftQueue
    .then(() =>
      writeDrawingDraft({
        id,
        memberId: owner,
        sceneJson: capturedScene,
        revision: capturedRevision,
        updatedAt: Date.now(),
      }),
    )
    .then(
      () => {
        if (activeId.value === id && sceneJson.value === capturedScene) {
          draftScene.value = capturedScene
          draftError.value = ''
        }
        return true
      },
      () => {
        if (activeId.value === id)
          draftError.value = '本机草稿未能写入。请保持页面打开，保存到账号或下载备份。'
        return false
      },
    )
  draftQueue = operation
  return operation
}

async function clearDraft(id: string) {
  clearTimeout(draftTimer)
  const operation = draftQueue.then(() => removeDrawingDraft(owner, id)).catch(() => undefined)
  draftQueue = operation
  await operation
}

function scheduleDraft() {
  clearTimeout(draftTimer)
  draftTimer = window.setTimeout(() => void persistDraft(), 180)
}

function scheduleSave() {
  clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => void saveNow(), 1_100)
}

function canvasChanged(nextScene: string) {
  if (
    !sameOwner() ||
    !current.value ||
    reloadingCloud.value ||
    deletingId.value ||
    restoringVersion.value !== null ||
    nextScene === sceneJson.value
  )
    return
  sceneJson.value = nextScene
  dirty.value = true
  if (!conflict.value) {
    error.value = ''
    syncState.value = savePromise ? 'saving' : 'dirty'
  }
  scheduleDraft()
  if (!conflict.value) scheduleSave()
}

function canvasReady() {
  if (dirty.value && syncState.value === 'dirty') scheduleSave()
}

async function saveNow() {
  clearTimeout(saveTimer)
  if (!sameOwner() || !current.value || !dirty.value || conflict.value) return
  if (savePromise) {
    await savePromise
    return
  }

  const id = current.value.id
  savePromise = (async () => {
    while (sameOwner() && current.value?.id === id && dirty.value && !conflict.value) {
      const capturedScene = sceneJson.value
      const capturedRevision = revision.value
      syncState.value = 'saving'
      error.value = ''
      try {
        const saved = await saveDrawing(id, capturedScene, capturedRevision)
        if (!sameOwner() || activeId.value !== id || !current.value) return
        updateSummary(saved)
        current.value = saved
        revision.value = saved.revision
        if (sceneJson.value === capturedScene) {
          dirty.value = false
          syncState.value = 'saved'
          await clearDraft(id)
          // Edits can arrive while IndexedDB finishes removing the saved snapshot.
          if (dirty.value) await persistDraft()
          void loadVersions(id)
        } else {
          dirty.value = true
          syncState.value = 'dirty'
          await persistDraft()
        }
      } catch (cause) {
        if (!sameOwner() || activeId.value !== id) return
        dirty.value = true
        error.value = message(cause, '草图保存失败')
        const status = cause as { response?: { status?: number }; status?: number }
        conflict.value =
          status?.response?.status === 409 ||
          status?.status === 409 ||
          /其它页面|其他页面|版本冲突/.test(error.value)
        syncState.value = conflict.value ? 'conflict' : 'error'
        await persistDraft()
        return
      }
    }
  })()
  try {
    await savePromise
  } finally {
    savePromise = null
  }
}

async function settleEdits() {
  if (editingTitle.value) {
    await saveTitle()
    if (editingTitle.value) return false
  }
  if (savePromise) await savePromise
  if (dirty.value && !conflict.value) await saveNow()
  if (!dirty.value) return true
  await persistDraft()
  error.value ||= '当前修改尚未保存。请重试保存，或下载备份后重新读取云端。'
  return false
}

async function newFile() {
  if (
    creating.value ||
    transitioning.value ||
    deletingId.value ||
    restoringVersion.value !== null ||
    reloadingCloud.value
  )
    return
  creating.value = true
  error.value = ''
  try {
    if (!(await settleEdits())) return
    const created = await createDrawing()
    if (!sameOwner()) return
    updateSummary(created)
    await openFile(created.id)
    sidebarOpen.value = window.innerWidth > 760
  } catch (cause) {
    error.value = message(cause, '新建草图失败')
  } finally {
    creating.value = false
  }
}

async function renameFile(file: DrawingFileSummary) {
  await openFile(file.id)
  if (current.value?.id === file.id) await startTitleEdit()
}

async function startTitleEdit() {
  if (
    !current.value ||
    renaming.value ||
    transitioning.value ||
    deletingId.value ||
    reloadingCloud.value
  )
    return
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
    if (!sameOwner()) return
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
  if (
    !current.value ||
    transitioning.value ||
    deletingId.value ||
    version.revision === revision.value ||
    restoringVersion.value !== null
  )
    return
  if (dirty.value) {
    error.value = '请先保存当前修改，再恢复历史版本'
    return
  }
  if (!window.confirm(`恢复版本 ${version.revision} 吗？当前内容会作为历史版本保留。`)) return
  restoringVersion.value = version.revision
  error.value = ''
  try {
    const id = current.value.id
    const restored = await restoreDrawingVersion(id, version.revision, revision.value)
    if (!sameOwner() || activeId.value !== id) return
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
  if (transitioning.value || deletingId.value || restoringVersion.value !== null) return
  if (!window.confirm(`删除「${file.title}」吗？该操作无法撤销。`)) return
  deletingId.value = file.id
  try {
    if (activeId.value === file.id) {
      if (savePromise) await savePromise
      clearTimeout(saveTimer)
      clearTimeout(draftTimer)
    }
    await deleteDrawing(file.id)
    if (!sameOwner()) return
    await clearDraft(file.id)
    files.value = files.value.filter((item) => item.id !== file.id)
    if (activeId.value === file.id) {
      activeId.value = ''
      current.value = null
      sceneJson.value = ''
      syncState.value = 'saved'
      dirty.value = false
      conflict.value = false
      error.value = ''
      draftError.value = ''
    }
  } catch (cause) {
    error.value = message(cause, '草图删除失败')
  } finally {
    deletingId.value = ''
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
  if (!current.value || transitioning.value || deletingId.value || restoringVersion.value !== null)
    return
  if (!window.confirm('确定放弃当前本机草稿，重新读取云端版本吗？建议先下载草稿备份。')) return
  transitioning.value = true
  reloadingCloud.value = true
  try {
    const id = current.value.id
    if (savePromise) await savePromise
    clearTimeout(saveTimer)
    // Fetch first: a failed reload must never erase the only remaining draft.
    let remote: DrawingFile
    try {
      remote = await fetchDrawing(id)
    } catch (cause) {
      error.value = message(cause, '云端读取失败，当前草稿已保留')
      return
    }
    if (!sameOwner() || current.value?.id !== id) return
    await clearDraft(id)
    dirty.value = false
    conflict.value = false
    current.value = remote
    sceneJson.value = remote.sceneJson
    revision.value = remote.revision
    draftScene.value = ''
    draftError.value = ''
    error.value = ''
    syncState.value = 'saved'
    canvasKey.value = `${id}:${++canvasGeneration}`
    updateSummary(remote)
    void loadVersions(id)
  } finally {
    transitioning.value = false
    reloadingCloud.value = false
  }
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
    void persistDraft()
  }
  disposed = true
  ++loadToken
  window.removeEventListener('beforeunload', beforeUnload)
  document.removeEventListener('fullscreenchange', fullscreenChanged)
})
watch(
  () => session.userId,
  () => {
    clearTimeout(saveTimer)
    clearTimeout(draftTimer)
    if (dirty.value) void persistDraft()
    ++loadToken
    current.value = null
    files.value = []
    dirty.value = false
  },
)
useSaveBeforeRefresh(async () => {
  if (!(await settleEdits())) throw new Error('草图尚未保存，请先处理保存失败或版本冲突')
})
if (inject(routerKey, null)) onBeforeRouteLeave(settleEdits)
</script>

<template>
  <section
    ref="page"
    class="drawings-page"
    :class="{ 'wao-page-frame': !embedded, 'sidebar-open': sidebarOpen, embedded }"
  >
    <button
      v-if="sidebarOpen && !embedded"
      class="sidebar-backdrop"
      aria-label="关闭文件列表"
      @click="sidebarOpen = false"
    ></button>
    <aside
      v-if="!embedded && sidebarOpen"
      class="file-sidebar"
      aria-label="草图文件列表"
      @keydown.esc="sidebarOpen = false"
    >
      <div class="sidebar-head">
        <div>
          <strong>我的草图</strong>
          <span>{{
            loadingList
              ? '正在读取…'
              : listError
                ? '暂时无法读取'
                : `${files.length} 个文件 · 最近编辑优先`
          }}</span>
        </div>
        <button title="收起文件列表" aria-label="收起文件列表" @click="sidebarOpen = false">
          <i class="ri-side-bar-line"></i>
        </button>
      </div>

      <WaoButton
        class="new-file"
        variant="primary"
        :loading="creating"
        :disabled="transitioning || !!deletingId"
        @click="newFile"
      >
        <i v-if="!creating" class="ri-add-line"></i>
        {{ creating ? '正在新建' : '新建草图' }}
      </WaoButton>

      <label class="file-search"
        ><i class="ri-search-line" aria-hidden="true"></i
        ><input v-model="search" type="search" aria-label="搜索草图" placeholder="搜索草图"
      /></label>

      <div v-if="loadingList" class="list-state">
        <i class="ri-loader-4-line spin"></i>正在读取文件
      </div>
      <div v-else-if="listError" class="list-state empty-list" role="alert">
        <span>{{ listError }}</span
        ><WaoButton @click="loadList">重试读取</WaoButton>
      </div>
      <div v-else-if="!files.length" class="list-state empty-list">
        <i class="ri-draft-line"></i>
        <span>还没有草图</span>
        <small>新建后会以当前账号保存</small>
      </div>
      <div v-else-if="!visibleFiles.length" class="list-state empty-list">
        <span>没有匹配的草图</span
        ><WaoButton variant="ghost" @click="search = ''">清除搜索</WaoButton>
      </div>
      <div v-else class="file-list">
        <div
          v-for="file in visibleFiles"
          :key="file.id"
          class="file-row"
          :class="{ active: activeId === file.id }"
        >
          <button
            class="file-open"
            :title="file.title"
            :aria-current="activeId === file.id ? 'true' : undefined"
            :disabled="transitioning || !!deletingId || restoringVersion !== null"
            @click="openFile(file.id)"
          >
            <i class="ri-draft-line file-icon"></i>
            <span class="file-main">
              <strong>{{ file.title }}</strong>
              <small
                >编辑于
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
          </button>
          <span class="file-actions">
            <a
              v-if="file.docId && file.kbId"
              :href="`/kb/${file.kbId}?doc=${file.docId}`"
              title="在知识库管理"
              >知识库</a
            >
            <button
              v-if="!file.docId"
              type="button"
              title="重命名"
              :aria-label="`重命名 ${file.title}`"
              :disabled="transitioning || !!deletingId"
              @click="renameFile(file)"
            >
              <i class="ri-pencil-line"></i>
            </button>
            <button
              v-if="!file.docId"
              type="button"
              title="删除"
              :aria-label="`删除 ${file.title}`"
              :disabled="transitioning || !!deletingId"
              @click="removeFile(file)"
            >
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
          aria-label="展开文件列表"
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
            <p v-if="versionsError" role="alert">
              {{ versionsError }} <button @click="loadVersions()">重试</button>
            </p>
            <p v-else-if="loadingVersions && !versions.length">正在读取版本…</p>
            <p v-else-if="!versions.length">暂无历史版本</p>
            <button
              v-for="version in versions"
              :key="version.revision"
              type="button"
              :disabled="
                dirty ||
                transitioning ||
                !!deletingId ||
                version.revision === revision ||
                restoringVersion !== null
              "
              @click="restoreVersion(version)"
            >
              <span>版本 {{ version.revision }}</span>
              <small>{{ new Date(version.createdAt).toLocaleString('zh-CN') }}</small>
              <i v-if="restoringVersion === version.revision" class="ri-loader-4-line spin"></i>
              <em v-else>{{ version.revision === revision ? '当前' : '恢复' }}</em>
            </button>
          </div>
        </details>
        <div
          v-if="current"
          class="sync-pill"
          :class="syncState"
          role="status"
          aria-live="polite"
          :title="syncMeta.text"
        >
          <i
            :class="[syncMeta.icon, { spin: syncState === 'loading' || syncState === 'saving' }]"
          ></i>
          <span>{{ syncMeta.text }}</span>
        </div>
        <button
          v-if="dirty && syncState !== 'conflict'"
          class="save-button"
          aria-label="立即保存"
          :disabled="syncState === 'saving'"
          @click="saveNow"
        >
          <i class="ri-save-3-line"></i>立即保存
        </button>
        <button
          v-if="current"
          class="icon-button"
          title="下载草图备份"
          aria-label="下载草图备份"
          @click="downloadDraft"
        >
          <i class="ri-download-2-line"></i>
        </button>
        <button
          v-if="current"
          type="button"
          class="icon-button fullscreen-button"
          :title="isFullscreen ? '退出全屏' : '全屏演示'"
          :aria-label="isFullscreen ? '退出全屏' : '全屏演示'"
          @click="toggleFullscreen"
        >
          <i :class="isFullscreen ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'"></i>
        </button>
      </header>

      <div v-if="draftError && dirty" class="error-banner" role="alert">
        <span>{{ draftError }}</span
        ><button @click="downloadDraft">下载备份</button>
      </div>
      <div
        v-if="error"
        class="error-banner"
        :class="{ conflict: syncState === 'conflict' }"
        role="alert"
      >
        <i class="ri-error-warning-line"></i>
        <span>{{ error }}</span>
        <template v-if="syncState === 'conflict'">
          <button @click="downloadDraft">下载本机草稿</button>
          <button @click="useCloudVersion">重新读取云端</button>
        </template>
        <template v-else-if="current && dirty"
          ><button @click="saveNow">重试保存</button
          ><button @click="useCloudVersion">重新读取云端</button></template
        >
        <button v-else-if="!current" @click="activeId ? openFile(activeId) : loadList()">
          重试读取
        </button>
        <button v-else @click="error = ''">关闭提示</button>
      </div>

      <div class="canvas-shell">
        <div v-if="loadingFile" class="canvas-loading">
          <i class="ri-loader-4-line spin"></i>
          <span>正在打开草图</span>
        </div>
        <DrawingCanvas
          v-else-if="current"
          :key="canvasKey"
          :scene-json="sceneJson"
          :scene-key="canvasKey"
          :name="current.title"
          @change="canvasChanged"
          @ready="canvasReady"
          @save="saveNow"
        />
        <div v-else-if="!embedded && !loadingList && !error" class="canvas-empty">
          <span><i class="ri-draft-line"></i></span>
          <h1>{{ files.length ? '继续你的想法' : '把想法画出来' }}</h1>
          <p>用自由画布整理思路、绘制流程或记录灵感。修改自动保存到账号。</p>
          <div v-if="recentFiles.length" class="recent-files">
            <span>最近编辑</span
            ><button
              v-for="file in recentFiles"
              :key="file.id"
              class="recent-file"
              :disabled="transitioning"
              @click="openFile(file.id)"
            >
              <i class="ri-draft-line"></i><span>{{ file.title }}</span
              ><i class="ri-arrow-right-line"></i>
            </button>
          </div>
          <WaoButton
            :variant="files.length ? 'secondary' : 'primary'"
            :loading="creating"
            @click="newFile"
            ><i class="ri-add-line"></i>新建草图</WaoButton
          >
        </div>
        <div
          v-if="
            restoringVersion !== null || (deletingId && deletingId === activeId) || reloadingCloud
          "
          class="canvas-loading"
          role="status"
        >
          {{
            reloadingCloud
              ? '正在读取云端…'
              : restoringVersion !== null
                ? '正在恢复版本…'
                : '正在删除…'
          }}
        </div>
      </div>
    </main>
  </section>
</template>

<style scoped>
.drawings-page {
  position: relative;
  height: 100%;
  min-height: 0;
  display: flex;
  overflow: hidden;
  background: var(--wao-bg-page);
}
.drawings-page.embedded {
  flex: 1;
  height: 100%;
  min-height: 0;
  margin: 0;
  padding: 0;
  width: 100%;
}
.file-sidebar {
  box-sizing: border-box;
  min-height: 0;
  overflow: hidden;
  width: 280px;
  flex: 0 0 280px;
  display: flex;
  flex-direction: column;
  gap: var(--wao-space-2);
  padding: var(--wao-space-2);
  border-right: 1px solid var(--wao-line);
  background: var(--wao-bg-card);
}
.sidebar-head,
.drawing-toolbar {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
}
.sidebar-head > div {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 4px;
}
.sidebar-head strong {
  font-size: var(--wao-font-card-title);
}
.sidebar-head span,
.title-block > span {
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.sidebar-head button,
.icon-button {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.sidebar-head button:hover,
.icon-button:hover {
  background: var(--wao-bg-hover);
}
.new-file {
  width: 100%;
  flex-shrink: 0;
}
.file-search {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  min-height: 38px;
  padding: 0 var(--wao-space-1);
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  color: var(--wao-text-3);
}
.file-search:focus-within {
  border-color: var(--wao-primary);
}
.file-search input {
  width: 100%;
  min-width: 0;
  border: 0;
  background: transparent;
  color: var(--wao-text);
  font: inherit;
  font-size: var(--wao-font-body);
  outline: none;
}
.file-list {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  display: grid;
  align-content: start;
  gap: var(--wao-space-1);
}
.file-row {
  box-sizing: border-box;
  width: 100%;
  display: flex;
  align-items: center;
  border: 1px solid transparent;
  border-radius: var(--wao-radius-control);
  color: var(--wao-text);
}
.file-row:hover,
.file-row:focus-within {
  background: var(--wao-bg-hover);
}
.file-row.active {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-soft);
}
.file-open {
  min-width: 0;
  min-height: 68px;
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  padding: var(--wao-space-1);
  border: 0;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.file-icon {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  display: grid;
  place-items: center;
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-sub);
  color: var(--wao-primary-text);
  font-size: 18px;
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
  font-size: var(--wao-font-body);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-main small {
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.file-actions {
  display: flex;
  flex-direction: column;
  padding-right: 4px;
  opacity: 0;
}
.file-row:hover .file-actions,
.file-row.active .file-actions,
.file-row:focus-within .file-actions {
  opacity: 1;
}
.file-actions button {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.file-actions a {
  font-size: var(--wao-font-caption);
  color: var(--wao-primary-text);
}
.file-actions button:hover {
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.file-actions button:last-child:hover {
  color: var(--wao-danger-strong);
}
.list-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--wao-space-1);
  padding: var(--wao-space-3) 0;
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
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
  max-width: 210px;
  font-size: var(--wao-font-caption);
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
  min-height: 64px;
  flex-shrink: 0;
  flex-wrap: wrap;
  padding: var(--wao-space-1) var(--wao-space-2);
  border-bottom: 1px solid var(--wao-line);
  background: var(--wao-bg-card);
}
.title-block {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 4px;
}
.title-block > strong,
.title-trigger strong {
  overflow: hidden;
  font-size: var(--wao-font-card-title);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.title-trigger {
  min-width: 0;
  width: fit-content;
  max-width: 100%;
  display: inline-flex;
  align-items: center;
  gap: var(--wao-space-1);
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--wao-text);
  cursor: text;
}
.title-trigger i {
  flex: 0 0 auto;
  color: var(--wao-text-3);
  font-size: 14px;
}
.title-block input {
  box-sizing: border-box;
  width: min(360px, 100%);
  min-height: 36px;
  padding: 0 var(--wao-space-1);
  border: 1px solid var(--wao-primary);
  border-radius: var(--wao-radius-control);
  outline: 2px solid var(--wao-primary-soft);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  font-size: var(--wao-font-body);
  font-weight: 600;
}
.version-menu {
  position: relative;
}
.version-menu summary {
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 var(--wao-space-1);
  border-radius: var(--wao-radius-control);
  color: var(--wao-text-2);
  font-size: var(--wao-font-help);
  cursor: pointer;
  list-style: none;
}
.version-menu summary::-webkit-details-marker {
  display: none;
}
.version-menu summary:hover,
.version-menu[open] summary {
  background: var(--wao-bg-hover);
}
.version-popover {
  position: absolute;
  z-index: 30;
  top: calc(100% + 8px);
  right: 0;
  width: min(270px, calc(100vw - 48px));
  display: grid;
  gap: var(--wao-space-1);
  padding: var(--wao-space-1);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-pop);
}
.version-popover > strong,
.version-popover p {
  margin: 0;
  padding: var(--wao-space-1);
  font-size: var(--wao-font-help);
}
.version-popover button {
  min-width: 0;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px 8px;
  padding: var(--wao-space-1);
  border: 0;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: var(--wao-text);
  text-align: left;
  cursor: pointer;
}
.version-popover button:hover:not(:disabled) {
  background: var(--wao-bg-hover);
}
.version-popover button span {
  font-size: var(--wao-font-help);
  font-weight: 600;
}
.version-popover button small {
  grid-column: 1;
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.version-popover button i,
.version-popover button em {
  grid-column: 2;
  grid-row: 1 / span 2;
  align-self: center;
  color: var(--wao-primary-text);
  font-size: var(--wao-font-caption);
  font-style: normal;
}
.sync-pill {
  min-width: 0;
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 var(--wao-space-1);
  border-radius: var(--wao-radius-control);
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
  font-size: var(--wao-font-caption);
}
.sync-pill.dirty,
.sync-pill.loading,
.sync-pill.saving {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.sync-pill.error,
.sync-pill.conflict {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.save-button,
.error-banner button {
  min-height: 36px;
  padding: 0 var(--wao-space-1);
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font-size: var(--wao-font-help);
  cursor: pointer;
}
.save-button i {
  margin-right: 5px;
}
.error-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--wao-space-1);
  padding: var(--wao-space-1) var(--wao-space-2);
  border-bottom: 1px solid var(--wao-danger-line);
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  font-size: var(--wao-font-help);
}
.error-banner span {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
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
.drawings-page:fullscreen .file-sidebar,
.drawings-page:fullscreen .sidebar-backdrop {
  display: none;
}
.drawings-page:fullscreen .drawing-toolbar {
  position: absolute;
  z-index: 25;
  top: 10px;
  right: 10px;
  width: auto;
  min-height: 40px;
  padding: 0 4px;
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-control);
  box-shadow: var(--wao-shadow-pop);
}
.drawings-page:fullscreen .drawing-toolbar > :not(.fullscreen-button) {
  display: none;
}
.canvas-loading,
.canvas-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--wao-space-2);
  padding: var(--wao-space-3);
  overflow: auto;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
  z-index: 2;
}
.canvas-empty > span {
  width: 58px;
  height: 58px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  border-radius: var(--wao-radius-modal);
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 27px;
}
.canvas-empty h1 {
  margin: 0;
  color: var(--wao-text);
  font-size: var(--wao-font-title);
  font-weight: var(--wao-weight-heading);
  line-height: var(--wao-line-heading);
}
.canvas-empty p {
  max-width: 430px;
  margin: 0;
  line-height: 1.7;
  text-align: center;
}
.recent-files {
  width: min(360px, 100%);
  display: grid;
  gap: var(--wao-space-1);
  margin: var(--wao-space-1) 0;
}
.recent-files > span {
  font-size: var(--wao-font-caption);
}
.recent-file {
  width: 100%;
  min-width: 0;
  min-height: 48px;
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  padding: var(--wao-space-1) var(--wao-space-2);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  text-align: left;
  cursor: pointer;
}
.recent-file:hover {
  background: var(--wao-primary-soft);
  border-color: var(--wao-primary-line);
}
.recent-file span {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--wao-font-body);
}
.recent-file i {
  color: var(--wao-primary-text);
}
.sidebar-backdrop {
  display: none;
}
button:disabled {
  opacity: 0.6;
  cursor: default;
}
button:focus-visible,
summary:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: -2px;
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
  .file-sidebar {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 20;
    width: min(90%, 310px);
    box-shadow: var(--wao-shadow-pop);
  }
  .sidebar-backdrop {
    display: block;
    position: absolute;
    inset: 0;
    z-index: 19;
    border: 0;
    background: var(--wao-mask-bg);
  }
  .drawing-toolbar {
    padding: var(--wao-space-1);
    gap: 4px;
  }
  .title-block {
    flex-basis: calc(100% - 56px);
  }
  .sync-pill {
    order: 1;
    flex-basis: 100%;
    min-height: 28px;
  }
  .version-menu summary,
  .icon-button,
  .sidebar-head button {
    min-width: 44px;
    min-height: 44px;
  }
  .version-popover {
    left: 0;
    right: auto;
  }
  .save-button {
    margin-left: auto;
  }
  .error-banner span {
    flex-basis: calc(100% - 28px);
  }
  .file-actions {
    opacity: 1;
  }
  .file-actions button {
    width: 44px;
    height: 44px;
  }
  .file-search input,
  .title-block input {
    font-size: 16px;
  }
  .canvas-empty {
    padding: var(--wao-space-2);
  }
}
@media (pointer: coarse) {
  .file-actions {
    opacity: 1;
  }
  .file-actions button {
    width: 44px;
    height: 44px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .spin {
    animation: none;
  }
}
</style>
