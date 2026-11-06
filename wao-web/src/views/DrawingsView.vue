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
import { onBeforeRouteLeave, routerKey, routeLocationKey } from 'vue-router'
import { usePageVisibility } from '@/composables/pageVisibility'
import PaneResizeHandle from '@/components/ui/PaneResizeHandle.vue'
import { useSessionStore } from '@/stores/session'
import WaoButton from '@/components/ui/WaoButton.vue'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import {
  createDrawing,
  createDrawingGroup, copyDrawing, fetchDrawingGroups, moveDrawing, renameDrawingGroup, saveDrawingOrder,
  type DrawingGroup,
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
const lastDrawingKey = `wao:last-drawing:${owner}`
function readLastDrawing() { try { return localStorage.getItem(lastDrawingKey) || '' } catch { return '' } }
function rememberDrawing(id: string) { try { if (id) localStorage.setItem(lastDrawingKey, id); else localStorage.removeItem(lastDrawingKey) } catch { /* 本机偏好不可用时仍可使用草图。 */ } }
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
const recentFiles = computed(() => files.value.slice().sort((a, b) => byOrder('f', a, b)).slice(0, 3))
const groups = ref<DrawingGroup[]>([])
const assignments = ref<Record<string, string>>({})
const groupError = ref(''), groupLoading = ref(false), actionBusy = ref(false), backgroundNotice = ref('')
const groupCollapsed = ref<Record<string, boolean>>({})
const sidebarWidth = ref(280)
const order = ref<string[]>([]), orderRevision = ref(0)
const dragging = ref<{ kind: 'file' | 'group'; id: string } | null>(null)
const dropKey = ref('')
const pageVisible = usePageVisibility()
const localRoute = inject(routeLocationKey, null)
let handledRouteDrawing = ''
function byOrder(prefix: string, a: { id: string }, b: { id: string }) {
  const ai = order.value.indexOf(`${prefix}:${a.id}`), bi = order.value.indexOf(`${prefix}:${b.id}`)
  if (ai < 0 && bi < 0) return 0
  if (ai < 0) return 1
  if (bi < 0) return -1
  return ai - bi
}
function allOrderKeys() {
  return [...groups.value.slice().sort((a, b) => byOrder('g', a, b)).map(group => `g:${group.id}`),
    ...files.value.slice().sort((a, b) => byOrder('f', a, b)).map(file => `f:${file.id}`)]
}
async function reorder(key: string, target: string, after = false) {
  if (actionBusy.value || groupLoading.value || groupError.value || search.value.trim() || key === target) return
  const keys = allOrderKeys().filter(item => item !== key)
  const index = keys.indexOf(target)
  if (index < 0) return
  keys.splice(index + (after ? 1 : 0), 0, key)
  actionBusy.value = true
  try {
    const next = await saveDrawingOrder(keys, orderRevision.value)
    if (!sameOwner()) return
    order.value = keys; orderRevision.value = next
  } catch (cause) { groupError.value = message(cause, '排序保存失败，请重新读取后重试') }
  finally { actionBusy.value = false }
}
function shiftItem(kind: 'file' | 'group', id: string, offset: number) {
  const items = kind === 'group' ? groups.value.slice().sort((a,b) => byOrder('g', a, b)) : (fileGroups.value.find(group => group.files.some(file => file.id === id))?.files ?? [])
  const index = items.findIndex(item => item.id === id), target = items[index + offset]
  if (target) void reorder(`${kind === 'group' ? 'g' : 'f'}:${id}`, `${kind === 'group' ? 'g' : 'f'}:${target.id}`, offset > 0)
}
function startDrag(event: DragEvent, kind: 'file' | 'group', id: string) {
  if (actionBusy.value || groupLoading.value || groupError.value || search.value.trim()) { event.preventDefault(); return }
  dragging.value = { kind, id }
  if (event.dataTransfer) { event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', `${kind}:${id}`) }
}
function endDrag() { dragging.value = null; dropKey.value = '' }
function dragOver(event: DragEvent, key: string) { if (!dragging.value || (dragging.value.kind === 'group' && (key.startsWith('f:') || key === 'g:'))) return; event.preventDefault(); if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'; dropKey.value = key }
async function dropOnGroup(event: DragEvent, groupId: string) {
  event.preventDefault(); event.stopPropagation()
  const bounds = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const after = event.clientY > bounds.top + bounds.height / 2
  const source = dragging.value; endDrag()
  if (!source) return
  if (source.kind === 'group') { if (groupId) await reorder(`g:${source.id}`, `g:${groupId}`, after); return }
  const file = files.value.find(item => item.id === source.id)
  if (file && (assignments.value[file.id] || '') !== groupId) await changeGroup(file, groupId || null)
}
async function dropOnFile(event: DragEvent, target: DrawingFileSummary, groupId: string) {
  event.preventDefault(); event.stopPropagation()
  const bounds = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const after = event.clientY > bounds.top + bounds.height / 2
  const source = dragging.value; endDrag()
  if (!source || source.kind !== 'file' || source.id === target.id) return
  const file = files.value.find(item => item.id === source.id)
  if (!file) return
  const moved = (assignments.value[file.id] || '') !== groupId
  if (moved && !(await changeGroup(file, groupId || null))) return
  await reorder(`f:${source.id}`, `f:${target.id}`, after)
  if (moved && groupError.value) groupError.value = `草图已移入分组，但${groupError.value}`
}

const contextMenu = ref<InstanceType<typeof ContextActionMenu>>()
const dialogMode = ref<'new-group' | 'rename-group' | 'rename-file' | null>(null)
const dialogName = ref(''), dialogError = ref(''), dialogTarget = ref('')
const fileGroups = computed(() => [
  ...groups.value.slice().sort((a,b) => byOrder('g', a, b)).map(group => ({ ...group, files: visibleFiles.value.filter(file => assignments.value[file.id] === group.id).sort((a,b) => byOrder('f', a, b)) })),
  { id: '', name: '未分组', files: visibleFiles.value.filter(file => !groups.value.some(group => group.id === assignments.value[file.id])).sort((a,b) => byOrder('f', a, b)) },
].filter(group => !search.value.trim() || group.files.length))
async function loadGroups() {
  if (props.embedded) return
  groupLoading.value = true; groupError.value = ''
  try {
    const result = await fetchDrawingGroups()
    if (!sameOwner()) return
    groups.value = result.groups
    assignments.value = Object.fromEntries(result.assignments.map(item => [item.fileId, item.groupId]))
    order.value = result.order ?? []; orderRevision.value = result.revision ?? 0
  } catch { groupError.value = '分组读取失败，请重试' }
  finally { groupLoading.value = false }
}
function showDialog(mode: 'new-group' | 'rename-group' | 'rename-file', id = '', name = '') {
  dialogMode.value = mode; dialogTarget.value = id; dialogName.value = name; dialogError.value = ''
}
async function saveDialog() {
  const name = dialogName.value.trim()
  if (!name || actionBusy.value || !dialogMode.value) return
  actionBusy.value = true; dialogError.value = ''
  try {
    if (dialogMode.value === 'new-group') {
      const created = await createDrawingGroup(name)
      if (!sameOwner()) return
      groups.value.push(created)
    }
    else if (dialogMode.value === 'rename-group') {
      await renameDrawingGroup(dialogTarget.value, name)
      if (!sameOwner()) return
      const group = groups.value.find(item => item.id === dialogTarget.value)
      if (group) group.name = name
    } else {
      if (dialogTarget.value === activeId.value && !(await settleEdits())) throw new Error(error.value || '请先保存当前修改')
      const renamed = await renameDrawing(dialogTarget.value, name)
      if (!sameOwner()) return
      updateSummary(renamed)
      if (current.value?.id === renamed.id) { current.value = { ...current.value, title: renamed.title, updatedAt: renamed.updatedAt }; titleDraft.value = renamed.title }
    }
    if (sameOwner()) dialogMode.value = null
  } catch (cause) { dialogError.value = message(cause, '操作结果未确认，请刷新列表确认后重试，输入已保留') }
  finally { actionBusy.value = false }
}
async function changeGroup(file: DrawingFileSummary, groupId: string | null) {
  if (actionBusy.value || groupLoading.value || groupError.value) return false
  actionBusy.value = true
  try { await moveDrawing(file.id, groupId); if (!sameOwner()) return false; if (groupId) { assignments.value[file.id] = groupId; groupCollapsed.value[groupId] = false } else delete assignments.value[file.id]; return true }
  catch (cause) { groupError.value = message(cause, '移动结果未确认，请重新读取后重试'); return false }
  finally { actionBusy.value = false }
}
async function duplicateFile(file: DrawingFileSummary) {
  if (actionBusy.value) return
  actionBusy.value = true; error.value = ''
  try {
    if (!(await settleEdits())) return
    transitioning.value = true
    const copy = await copyDrawing(file.id)
    if (!sameOwner()) return
    updateSummary(copy)
    if (assignments.value[file.id]) assignments.value[copy.id] = assignments.value[file.id]!
    transitioning.value = false
    await openFile(copy.id)
    void loadGroups()
  } catch (cause) { error.value = message(cause, '复制结果未确认，请先刷新列表检查是否已创建副本') }
  finally { transitioning.value = false; actionBusy.value = false }
}
async function exportFile(file: DrawingFileSummary) {
  if (actionBusy.value) return
  actionBusy.value = true; error.value = ''
  try {
    const selected = current.value?.id === file.id
    const scene = selected ? sceneJson.value : (await fetchDrawing(file.id)).sceneJson
    if (!sameOwner()) return
    downloadScene(file.title, scene)
  } catch (cause) { error.value = message(cause, '导出失败，请重试') }
  finally { actionBusy.value = false }
}
function openFileMenu(event: MouseEvent | KeyboardEvent, file: DrawingFileSummary, anchor?: HTMLElement) {
  const disabled = actionBusy.value || transitioning.value || !!deletingId.value || restoringVersion.value !== null
  void contextMenu.value?.openAt(event, file.title, [
    { id: 'new-group', label: '新建分组', icon: 'ri-folder-add-line', disabled: groupLoading.value || !!groupError.value, run: () => showDialog('new-group') },
    { id: 'rename', label: '重命名', icon: 'ri-edit-line', disabled, run: () => showDialog('rename-file', file.id, file.title) },
    { id: 'export', label: '导出源文件', icon: 'ri-download-line', disabled, run: () => exportFile(file) },
    { id: 'copy', label: '复制当前文件', icon: 'ri-file-copy-line', disabled, run: () => duplicateFile(file) },
    { id: 'up', label: '上移', icon: 'ri-arrow-up-line', disabled: disabled || !!search.value.trim(), run: () => shiftItem('file', file.id, -1) },
    { id: 'down', label: '下移', icon: 'ri-arrow-down-line', disabled: disabled || !!search.value.trim(), run: () => shiftItem('file', file.id, 1) },
    ...(!groupLoading.value && !groupError.value ? [
      { id: 'ungroup', label: '移至未分组', icon: 'ri-folder-transfer-line', disabled: !assignments.value[file.id], separatorBefore: true, run: () => { void changeGroup(file, null) } },
      ...groups.value.map(group => ({ id: `move:${group.id}`, label: `移至 ${group.name}`, icon: 'ri-folder-transfer-line', disabled: assignments.value[file.id] === group.id, run: () => { void changeGroup(file, group.id) } })),
    ] : []),
  ], anchor)
}
function openGroupMenu(event: MouseEvent | KeyboardEvent, group: DrawingGroup, anchor?: HTMLElement) {
  void contextMenu.value?.openAt(event, group.name, [
    { id: 'new', label: '新建分组', icon: 'ri-folder-add-line', disabled: actionBusy.value, run: () => showDialog('new-group') },
    { id: 'rename', label: '重命名分组', icon: 'ri-edit-line', disabled: actionBusy.value, run: () => showDialog('rename-group', group.id, group.name) },
    { id: 'new-file', label: '在此分组新建草图', icon: 'ri-file-add-line', disabled: actionBusy.value, run: () => newFile(group.id) },
    { id: 'up', label: '上移分组', icon: 'ri-arrow-up-line', disabled: actionBusy.value || !!search.value.trim(), run: () => shiftItem('group', group.id, -1) },
    { id: 'down', label: '下移分组', icon: 'ri-arrow-down-line', disabled: actionBusy.value || !!search.value.trim(), run: () => shiftItem('group', group.id, 1) },
  ], anchor)
}
function menuFromButton(event: MouseEvent, file: DrawingFileSummary) { openFileMenu(event, file, event.currentTarget as HTMLElement) }
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
  files.value.sort((left, right) => left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id))
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
    files.value = loaded.sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id))
    const queryDrawing = localRoute?.query.drawing
    const requested = typeof queryDrawing === 'string' ? queryDrawing : new URLSearchParams(window.location.search).get('drawing') ?? ''
    const remembered = readLastDrawing()
    if (requested) await openFile(requested)
    else if (remembered && files.value.some(file => file.id === remembered)) await openFile(remembered)
    else { if (remembered) rememberDrawing(''); syncState.value = 'saved' }
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
    if (!props.embedded) rememberDrawing(id)
    titleDraft.value = remote.title
    sceneJson.value = draft?.sceneJson ?? remote.sceneJson
    dirty.value = !!draft && draft.sceneJson !== remote.sceneJson
    revision.value = dirty.value ? draft!.revision : remote.revision
    draftScene.value = draft?.sceneJson ?? ''
    if (draft && !dirty.value) await clearDraft(id)
    if (token !== loadToken || !sameOwner()) return
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
      const capturedTitle = current.value.title
      syncState.value = 'saving'
      error.value = ''
      try {
        const saved = await saveDrawing(id, capturedScene, capturedRevision)
        if (!sameOwner() || activeId.value !== id || !current.value) return
        // A rename may finish while the scene response is in flight.
        if (current.value.title !== capturedTitle) saved.title = current.value.title
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

async function newFile(groupId?: string) {
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
    const created = groupId ? await createDrawing(undefined, groupId) : await createDrawing()
    if (!sameOwner()) return
    updateSummary(created)
    if (groupId) { assignments.value[created.id] = groupId; groupCollapsed.value[groupId] = false }
    await openFile(created.id)
    sidebarOpen.value = window.innerWidth > 760
  } catch (cause) {
    error.value = message(cause, '新建结果未确认，请先刷新列表检查是否已创建草图')
  } finally {
    creating.value = false
  }
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

let renamePromise: Promise<void> | null = null
async function saveTitle() {
  if (renamePromise) return renamePromise
  renamePromise = performSaveTitle()
  try { await renamePromise } finally { renamePromise = null }
}
async function performSaveTitle() {
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
    if (!props.embedded && readLastDrawing() === file.id) rememberDrawing('')
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
  downloadScene(current.value.title, sceneJson.value)
}

function downloadScene(title: string, scene: string) {
  const blob = new Blob([scene], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${title || '未命名草图'}.excalidraw`
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
  if (!dirty.value && (!editingTitle.value || titleDraft.value === current.value?.title)) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  document.addEventListener('fullscreenchange', fullscreenChanged)
  void loadList()
  void loadGroups()
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
    groups.value = []
    assignments.value = {}
    dialogMode.value = null
    contextMenu.value?.close()
    dirty.value = false
  },
)
// 隐藏时保留画布实例；返回后只在云端版本变化时更新，不能覆盖新输入。
let backgroundRequest = 0
async function refreshOnReturn() {
  if (props.embedded || !current.value || loadingFile.value || transitioning.value || !sameOwner()) return
  const id = current.value.id, seenRevision = revision.value, request = ++backgroundRequest
  try {
    const remote = await fetchDrawing(id)
    if (!sameOwner() || request !== backgroundRequest || !pageVisible.value || current.value?.id !== id || revision.value !== seenRevision || transitioning.value || renaming.value || editingTitle.value || savePromise) return
    if (remote.revision !== seenRevision) {
      if (dirty.value) {
        conflict.value = true; syncState.value = 'conflict'
        error.value = '云端草图已更新，本机修改已保留，请处理版本冲突。'
        await persistDraft()
        return
      }
      current.value = remote; revision.value = remote.revision; sceneJson.value = remote.sceneJson
      titleDraft.value = remote.title; updateSummary(remote)
      canvasKey.value = `${id}:${++canvasGeneration}`
      void loadVersions(id)
    } else if (remote.title !== current.value.title) {
      current.value = { ...current.value, title: remote.title, updatedAt: remote.updatedAt }
      titleDraft.value = remote.title; updateSummary(remote)
    }
  } catch (cause) {
    if (sameOwner() && request === backgroundRequest && pageVisible.value && current.value?.id === id) {
      const status = (cause as { status?: number }).status
      if ((status === 403 || status === 404) && !dirty.value && !savePromise) {
        current.value = null; sceneJson.value = ''; syncState.value = 'error'
        error.value = '草图不存在或当前账号无权访问，请重新读取。'
      } else error.value = message(cause, '后台刷新失败，当前画布已保留')
    }
  }
}
async function refreshDirectoryOnReturn() {
  if (props.embedded || !sameOwner() || !pageVisible.value || actionBusy.value || groupLoading.value || groupError.value) return
  const token = backgroundRequest
  const fileSnapshot = JSON.stringify(files.value.map(file => [file.id, file.title, file.revision, file.updatedAt]))
  const groupSnapshot = JSON.stringify([groups.value, assignments.value, order.value, orderRevision.value])
  try {
    const [freshFiles, freshGroups] = await Promise.all([fetchDrawings(), fetchDrawingGroups()])
    if (!sameOwner() || token !== backgroundRequest || !pageVisible.value || actionBusy.value || dragging.value || groupLoading.value || groupError.value
      || fileSnapshot !== JSON.stringify(files.value.map(file => [file.id, file.title, file.revision, file.updatedAt]))
      || groupSnapshot !== JSON.stringify([groups.value, assignments.value, order.value, orderRevision.value])) return
    files.value = freshFiles.sort((a,b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id))
    groups.value = freshGroups.groups
    assignments.value = Object.fromEntries(freshGroups.assignments.map(item => [item.fileId, item.groupId]))
    order.value = freshGroups.order ?? []; orderRevision.value = freshGroups.revision ?? 0
    backgroundNotice.value = ''
  } catch {
    if (sameOwner() && token === backgroundRequest && pageVisible.value) backgroundNotice.value = '目录刷新失败，正在显示上次结果'
  }
}
watch(pageVisible, (visible, previous) => {
  if (!visible) { ++backgroundRequest; return }
  if (!previous) { void refreshOnReturn(); void refreshDirectoryOnReturn() }
})
watch(() => [localRoute?.query.drawing, loadingList.value, transitioning.value, pageVisible.value] as const, ([requested, loading, moving, visible]) => {
  if (props.embedded || typeof requested !== 'string' || !requested || !visible || loading || moving) return
  if (activeId.value === requested) { handledRouteDrawing = requested; return }
  if (handledRouteDrawing === requested) return
  handledRouteDrawing = requested
  void openFile(requested)
})
watch(() => localRoute?.query.drawing, requested => { if (!requested) handledRouteDrawing = '' })
useSaveBeforeRefresh(async () => {
  if (!(await settleEdits())) throw new Error('草图尚未保存，请先处理保存失败或版本冲突')
})
if (inject(routerKey, null)) onBeforeRouteLeave(settleEdits)
async function flushEmbedded() {
  if (!(await settleEdits())) throw new Error(error.value || '画板尚未保存，请重试')
  await persistDraft()
  await saveNow()
  if (dirty.value || conflict.value) throw new Error(error.value || '画板尚未保存，请重试')
}
defineExpose({ flush: flushEmbedded })
</script>

<template>
  <section
    ref="page"
    class="drawings-page"
    :class="{ 'wao-page-frame': !embedded, 'sidebar-open': sidebarOpen, embedded }"
  >
    <ContextActionMenu ref="contextMenu" />
    <SettingsDialogShell v-if="dialogMode" :title="dialogMode === 'new-group' ? '新建分组' : dialogMode === 'rename-group' ? '重命名分组' : '重命名草图'" width="480px" height="auto" :close-disabled="actionBusy" @close="dialogMode = null">
      <form class="name-dialog" @submit.prevent="saveDialog"><label>名称<input v-model="dialogName" maxlength="128" autofocus :disabled="actionBusy" /></label><p v-if="dialogError" role="alert">{{ dialogError }}</p><WaoButton type="submit" :loading="actionBusy" :disabled="!dialogName.trim()">保存</WaoButton></form>
    </SettingsDialogShell>
    <button
      v-if="sidebarOpen && !embedded"
      class="sidebar-backdrop"
      aria-label="关闭文件列表"
      @click="sidebarOpen = false"
    ></button>
    <aside
      v-if="!embedded && sidebarOpen"
      class="file-sidebar"
      :style="{ width: `${sidebarWidth}px`, flexBasis: `${sidebarWidth}px` }"
      aria-label="草图文件列表"
      @keydown.esc="sidebarOpen = false"
    >
      <PaneResizeHandle :storage-key="`wao:drawing-sidebar-width:${owner}`" label="草图目录宽度" :default-width="280" :min="220" :max="560" @resize="sidebarWidth = $event" />
      <div class="sidebar-head">
        <div>
          <strong>我的草图</strong>
          <span>{{
            loadingList
              ? '正在读取…'
              : listError
                ? '暂时无法读取'
                : `${files.length} 个文件 · 拖动排序`
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
        @click="newFile()"
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
      <div v-if="backgroundNotice" class="group-error" role="status">{{ backgroundNotice }} <button type="button" @click="refreshDirectoryOnReturn">重试</button></div>
      <div v-if="groupError" class="group-error" role="alert">{{ groupError }} <button type="button" @click="loadGroups">重试</button></div>
      <div v-else-if="groupLoading" class="group-error" role="status">正在读取分组…</div>
      <button type="button" class="group-create" :disabled="groupLoading || !!groupError || actionBusy" @click="showDialog('new-group')"><i class="ri-folder-add-line"></i>新建分组</button>
      <div v-if="!loadingList && !listError" class="file-list" @contextmenu.self.prevent="showDialog('new-group')">
       <section v-for="group in fileGroups" :key="group.id || 'ungrouped'" class="drawing-group">
        <div class="group-header" :class="{ 'drop-target': dropKey === `g:${group.id}` }" :draggable="!!group.id && !search.trim()" @dragstart.stop="group.id && startDrag($event, 'group', group.id)" @dragend.stop="endDrag" @dragover.stop="dragOver($event, `g:${group.id}`)" @drop.stop="dropOnGroup($event, group.id)" @contextmenu.prevent.stop="group.id && openGroupMenu($event, group)">
          <button type="button" class="group-toggle" :aria-expanded="groupCollapsed[group.id] !== true || !!search.trim()" @click="groupCollapsed[group.id] = !groupCollapsed[group.id]"><i :class="groupCollapsed[group.id] && !search.trim() ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i><i class="ri-folder-line"></i>{{ group.name }} <span>{{ group.files.length }}</span></button>
          <button v-if="group.id" type="button" class="group-menu" :aria-label="`在${group.name}新建草图`" title="在此分组新建草图" :disabled="creating || actionBusy || transitioning" @click="newFile(group.id)"><i class="ri-add-line"></i></button>
          <button v-if="group.id" type="button" class="group-menu" :aria-label="`${group.name}的操作`" @click="openGroupMenu($event, group, $event.currentTarget as HTMLElement)"><i class="ri-more-line"></i></button>
        </div>
        <template v-if="!groupCollapsed[group.id] || !!search.trim()">
        <div
          v-for="file in group.files"
          :key="file.id"
          class="file-row"
          :class="{ active: activeId === file.id, 'drop-target': dropKey === `f:${file.id}` }"
          :draggable="!search.trim()" @dragstart.stop="startDrag($event, 'file', file.id)" @dragend.stop="endDrag" @dragover.stop="dragOver($event, `f:${file.id}`)" @drop.stop="dropOnFile($event, file, group.id)"
          @contextmenu.prevent.stop="openFileMenu($event, file)"
          @keydown.shift.f10.prevent="openFileMenu($event, file, $event.currentTarget as HTMLElement)"
        >
          <button
            class="file-open"
            :title="file.title"
            :aria-current="activeId === file.id ? 'true' : undefined"
            :disabled="transitioning || !!deletingId || restoringVersion !== null"
            @click="openFile(file.id)"
          >
            <span class="file-main"><strong>{{ file.title }}</strong></span>
          </button>
          <button type="button" class="file-menu" :aria-label="`${file.title}的操作`" @click="menuFromButton($event, file)"><i class="ri-more-line"></i></button>
        </div>
        </template>
       </section>
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
        <div class="title-block">
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
            v-else-if="current"
            type="button"
            class="title-trigger"
            title="点击修改草图名称"
            @click="startTitleEdit"
          >
            <strong>{{ current.title }}</strong>
          </button>
          <strong v-else>个人草图</strong>
          <span v-if="current">{{ renaming ? '正在保存名称' : `版本 ${revision}` }}</span>
        </div>
        <div v-if="current" class="drawing-meta">
          <span class="edited-at">编辑于 {{ new Date(current.updatedAt).toLocaleString('zh-CN', { month:'numeric', day:'numeric', hour:'2-digit', minute:'2-digit' }) }}</span>
          <button class="icon-button" :aria-label="`重命名 ${current.title}`" title="重命名" :disabled="renaming || transitioning || !!deletingId" @click="startTitleEdit"><i class="ri-pencil-line" aria-hidden="true" /></button>
          <button v-if="!current.docId && !embedded" class="icon-button" :aria-label="`删除 ${current.title}`" title="删除" :disabled="transitioning || !!deletingId" @click="removeFile(current)"><i class="ri-delete-bin-line" aria-hidden="true" /></button>
          <a v-if="current.docId && current.kbId && !embedded" :href="`/kb/${current.kbId}?doc=${current.docId}`" title="在知识库查看" aria-label="在知识库查看"><i class="ri-book-open-line" aria-hidden="true" /></a>
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
            <span>草图文件</span
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
            @click="newFile()"
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
.drawing-meta { display:flex; align-items:center; gap:var(--wao-space-1); color:var(--wao-text-3); font-size:var(--wao-font-caption); }
.drawing-meta a { color:var(--wao-primary-text); }
.edited-at { white-space:nowrap; }

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
  max-width: min(560px, 55vw);
  position: relative;
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
.name-dialog { display:grid; gap:var(--wao-space-2); padding:var(--wao-space-3); }
.name-dialog label { display:grid; gap:var(--wao-space-1); }
.name-dialog input { width:100%; min-height:40px; border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); padding:var(--wao-space-1); background:var(--wao-bg-card); color:var(--wao-text); }
.name-dialog [role=alert], .group-error { color:var(--wao-danger-strong); }
.group-create,.group-toggle,.group-menu,.file-menu { border:0; background:transparent; color:var(--wao-text-3); cursor:pointer; font:inherit; min-height:36px; }
.group-create { text-align:left; padding:var(--wao-space-1); }
.group-header { display:flex; align-items:center; }
.group-toggle { flex:1; min-width:0; display:flex; align-items:center; gap:4px; text-align:left; }
.group-toggle span { margin-left:auto; }
.group-menu,.file-menu { min-width:36px; }
.group-menu:hover,.file-menu:hover,.group-create:hover { color:var(--wao-primary-text); background:var(--wao-bg-hover); }
.group-error { font-size:var(--wao-font-help); }
.file-list {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  display: grid;
  align-content: start;
  gap: 2px;
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
.drop-target { outline: 2px solid var(--wao-primary); outline-offset: -2px; }
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
  min-height: 34px;
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  padding: 4px var(--wao-space-1);
  border: 0;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
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
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
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
    width: min(90%, 310px) !important;
    max-width: none;
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
  .file-search input,
  .title-block input {
    font-size: var(--wao-font-card-title);
  }
  .canvas-empty {
    padding: var(--wao-space-2);
  }
}
@media (pointer: coarse) {
  .file-open { min-height:44px; }
}
@media (prefers-reduced-motion: reduce) {
  .spin {
    animation: none;
  }
}
</style>
