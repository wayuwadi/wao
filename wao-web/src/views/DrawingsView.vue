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
  shallowRef,
  watch,
} from 'vue'
import { onBeforeRouteLeave, routerKey, routeLocationKey } from 'vue-router'
import { usePageVisibility } from '@/composables/pageVisibility'
import { useDrawingSnapMode } from '@/composables/drawingSnapMode'
import { toast } from '@/composables/toast'
import type { DrawingTidyCanvas } from '@/utils/drawingTidyClient'
import PaneResizeHandle from '@/components/ui/PaneResizeHandle.vue'
import { useSessionStore } from '@/stores/session'
import { useDrawingLibraryStore } from '@/stores/drawingLibrary'
import { useConfirmAction } from '@/composables/confirmAction'
import { loadOfficialDrawingLibrary, officialDrawingLibraryUrl } from '@/utils/drawingLibraryCatalog'
import ConfirmActionDialog from '@/components/ui/ConfirmActionDialog.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import DrawingShareDialog from '@/components/drawings/DrawingShareDialog.vue'
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
let canvasModule: Promise<typeof import('@/components/drawings/DrawingCanvas.vue').default> | undefined
function loadCanvas() {
  return canvasModule ??= import('@/components/drawings/DrawingCanvas.vue')
    .then((module) => module.default)
    .catch((cause: unknown) => { canvasModule = undefined; throw cause })
}
const DrawingCanvas = defineAsyncComponent({
  loader: loadCanvas,
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
const DrawingTidyDialog = defineAsyncComponent(() => import('@/components/drawings/DrawingTidyDialog.vue'))
const DrawingPhotoDialog = defineAsyncComponent(() => import('@/components/drawings/DrawingPhotoDialog.vue'))
import { readDrawingDraft, removeDrawingDraft, writeDrawingDraft, type DrawingDraft } from '@/utils/drawingDrafts'
import { sameDrawingContent } from '@/utils/drawingScene'
import type { DrawingImageFormat } from '@/utils/drawingImageExport'

type SyncState = 'loading' | 'dirty' | 'saving' | 'saved' | 'error' | 'conflict'

const props = defineProps<{ drawingId?: string; embedded?: boolean }>()
const session = useSessionStore()
const library = useDrawingLibraryStore()
const { confirmation: libraryConfirmation, confirmAction: confirmLibrary, dismissConfirmation: dismissLibraryConfirmation } = useConfirmAction()
const libraryRequests = new AbortController()
const owner = session.userId
const snapMode = useDrawingSnapMode(owner)
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
let pendingSave: DrawingDraft['pendingSave']
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
const sharing = ref<DrawingFileSummary>()
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
type DrawingExportFormat = DrawingImageFormat | 'excalidraw'
const exporting = ref(false)
const exportError = ref('')
const exportFormats = [
  { format: 'png', label: '导出 PNG', icon: 'ri-image-line' },
  { format: 'jpg', label: '导出 JPG', icon: 'ri-image-line' },
  { format: 'svg', label: '导出 SVG', icon: 'ri-image-line' },
  { format: 'excalidraw', label: '导出源文件', icon: 'ri-file-download-line' },
] as const

function exportActions(file: DrawingFileSummary, disabled = false) {
  return exportFormats.map(({ format, label, icon }) => ({
    id: `export:${format}`, label, icon, disabled: disabled || exporting.value,
    run: () => exportFile(file, format),
  }))
}

function openExportMenu(event: MouseEvent) {
  if (!current.value || !(event.currentTarget instanceof HTMLElement)) return
  void contextMenu.value?.openAt(event, '导出草图', exportActions(current.value), event.currentTarget)
}

async function exportFile(file: DrawingFileSummary, format: DrawingExportFormat) {
  if (exporting.value || !sameOwner()) return
  exporting.value = true
  exportError.value = ''
  try {
    const selected = current.value?.id === file.id ? current.value : null
    const scene = selected ? { ...selected, sceneJson: sceneJson.value } : await fetchDrawing(file.id)
    if (!sameOwner()) return
    if (format === 'excalidraw') {
      downloadScene(scene.title, scene.sceneJson)
    } else {
      const { renderDrawingImage } = await import('@/utils/drawingImageExport')
      if (!sameOwner()) return
      const blob = await renderDrawingImage(scene.sceneJson, format)
      if (sameOwner()) downloadBlob(scene.title, format, blob)
    }
  } catch (cause) {
    if (sameOwner()) exportError.value = message(cause, '导出失败，请重试')
  } finally {
    exporting.value = false
  }
}
function openFileMenu(event: MouseEvent | KeyboardEvent, file: DrawingFileSummary, anchor?: HTMLElement) {
  const disabled = actionBusy.value || transitioning.value || !!deletingId.value || restoringVersion.value !== null
  void contextMenu.value?.openAt(event, file.title, [
    { id: 'new-group', label: '新建分组', icon: 'ri-folder-add-line', disabled: groupLoading.value || !!groupError.value, run: () => showDialog('new-group') },
    { id: 'rename', label: '重命名', icon: 'ri-edit-line', disabled, run: () => showDialog('rename-file', file.id, file.title) },
    ...(!file.docId ? [
      { id: 'share', label: '分享草图', icon: 'ri-share-forward-line', disabled, run: () => { sharing.value = file } },
    ] : []),
    ...exportActions(file, disabled),
    { id: 'copy', label: '复制当前文件', icon: 'ri-file-copy-line', disabled, run: () => duplicateFile(file) },
    { id: 'up', label: '上移', icon: 'ri-arrow-up-line', disabled: disabled || !!search.value.trim(), run: () => shiftItem('file', file.id, -1) },
    { id: 'down', label: '下移', icon: 'ri-arrow-down-line', disabled: disabled || !!search.value.trim(), run: () => shiftItem('file', file.id, 1) },
    ...(!groupLoading.value && !groupError.value ? [
      { id: 'ungroup', label: '移至未分组', icon: 'ri-folder-transfer-line', disabled: !assignments.value[file.id], separatorBefore: true, run: () => { void changeGroup(file, null) } },
      ...groups.value.map(group => ({ id: `move:${group.id}`, label: `移至 ${group.name}`, icon: 'ri-folder-transfer-line', disabled: assignments.value[file.id] === group.id, run: () => { void changeGroup(file, group.id) } })),
    ] : []),
    ...(!file.docId && !props.embedded ? [
      { id: 'delete', label: '删除', icon: 'ri-delete-bin-line', danger: true, separatorBefore: true, disabled, run: () => removeFile(file) },
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
const photoOpen = ref(false)
const photoFitId = ref<string>()
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
const versionMenu = ref<HTMLDetailsElement | null>(null)
let versionsRequest = 0
const restoringVersion = ref<number | null>(null)
let loadToken = 0
let openingRequest: AbortController | undefined
const openFailure = ref<{ id: string; message: string }>()
let canvasGeneration = 0
let readyCanvasKey = ''
const activeCanvas = computed(() => current.value
  ? { key: canvasKey.value, sceneJson: sceneJson.value, name: current.value.title }
  : undefined)
const retainedCanvas = shallowRef<NonNullable<typeof activeCanvas.value>>()
const canvasLayers = computed(() =>
  [retainedCanvas.value, activeCanvas.value].filter((layer) => layer !== undefined),
)
const tidyCanvas = shallowRef<DrawingTidyCanvas>()
const tidyDialog = ref<'route' | 'layout' | 'ai'>()
const tidyBusy = ref(false)
let tidyRequest: AbortController | undefined

function cancelTidy() {
  tidyRequest?.abort()
  tidyRequest = undefined
  tidyBusy.value = false
  tidyDialog.value = undefined
}

watch(canvasKey, () => {
  cancelTidy()
  tidyCanvas.value = undefined
})
watch(current, (file) => { if (!file) cancelTidy() })
onBeforeUnmount(cancelTidy)

async function quickTidy() {
  const canvas = tidyCanvas.value
  if (!canvas || tidyBusy.value || transitioning.value || restoringVersion.value !== null || deletingId.value) return
  if (!canvas.hasSelection()) {
    toast('先框选或多选要整理的内容。')
    return
  }
  const request = new AbortController()
  tidyRequest = request
  tidyBusy.value = true
  try {
    const result = await canvas.prepare({ mode: 'light', scope: 'selection' }, request.signal)
    if (request.signal.aborted) return
    result.apply()
    toast(result.changed
      ? `整理完成。移动 ${result.moved} 个图形，重新布线 ${result.routed} 条。`
      : '当前选区没有可安全改善的布局。')
  } catch (cause) {
    if (!request.signal.aborted) toast(message(cause, '整理失败，请重试。'), 'ri-error-warning-line')
  } finally {
    if (tidyRequest === request) {
      tidyRequest = undefined
      tidyBusy.value = false
    }
  }
}

function openTidyMenu(event: MouseEvent) {
  if (!(event.currentTarget instanceof HTMLElement)) return
  void contextMenu.value?.openAt(event, '智能排版', [
    { id: 'tidy:ai', label: 'AI 整理', icon: 'ri-sparkling-2-line', run: () => { tidyDialog.value = 'ai' } },
    { id: 'tidy:light', label: '轻量整理', icon: 'ri-align-top', run: quickTidy },
    { id: 'tidy:route', label: '只整理连线', icon: 'ri-route-line', run: () => { tidyDialog.value = 'route' } },
  ], event.currentTarget)
}
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

async function loadDirectory() {
  loadingList.value = true
  listError.value = ''
  try {
    const loaded = await fetchDrawings()
    if (!sameOwner()) return null
    files.value = loaded.sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id))
    if (current.value) {
      const summary = loaded.find((file) => file.id === current.value?.id)
      if (!summary || summary.revision < current.value.revision ||
          (summary.revision === current.value.revision && summary.updatedAt < current.value.updatedAt)) {
        updateSummary(current.value)
      }
    }
    return loaded
  } catch (cause) {
    if (sameOwner()) listError.value = message(cause, '草图文件列表读取失败')
    return null
  } finally {
    loadingList.value = false
  }
}

async function loadList() {
  if (props.embedded && props.drawingId) {
    try { await openFile(props.drawingId) }
    finally { loadingList.value = false }
    return
  }
  const directory = loadDirectory()
  if (current.value) {
    await directory
    return
  }
  const queryDrawing = localRoute?.query.drawing
  const requested = typeof queryDrawing === 'string' ? queryDrawing : new URLSearchParams(window.location.search).get('drawing') ?? ''
  const remembered = readLastDrawing()
  if (requested) {
    handledRouteDrawing = requested
    await Promise.all([directory, openFile(requested)])
  } else if (remembered) {
    await openFile(remembered, directory.then((loaded) => {
      if (!loaded || !sameOwner()) return false
      if (loaded.some((file) => file.id === remembered)) return true
      if (readLastDrawing() === remembered) rememberDrawing('')
      return false
    }))
  } else await directory
}

async function openFile(id: string, eligible: boolean | Promise<boolean> = true) {
  if (
    !sameOwner() ||
    transitioning.value ||
    deletingId.value ||
    restoringVersion.value !== null ||
    renaming.value
  )
    return
  if (activeId.value === id && current.value && !loadingFile.value) {
    sidebarOpen.value = (page.value?.clientWidth || window.innerWidth) > 760
    return
  }
  const token = ++loadToken
  const request = new AbortController()
  openingRequest = request
  transitioning.value = true
  loadingFile.value = true
  openFailure.value = undefined
  clearTimeout(saveTimer)
  void loadCanvas().catch(() => undefined)

  const settled = settleEdits()
  const remoteRequest = fetchDrawing(id, request.signal)
  const mayMigrate = Promise.all([remoteRequest, settled, eligible]).then(
    ([remote, saved, allowed]) => saved && allowed && !remote.docId && sameOwner() && !request.signal.aborted,
    () => false,
  )
  const draftRequest = readDrawingDraft(owner, id, mayMigrate).catch(() => null)
  const target = Promise.all([remoteRequest, draftRequest]).then(
    ([remote, draft]) => ({ remote, draft }),
    (cause: unknown) => ({ cause }),
  )
  try {
    if (!(await settled) || !(await eligible)) return
    const loaded = await target
    if ('cause' in loaded) throw loaded.cause
    if (token !== loadToken || !sameOwner()) return
    const { remote, draft } = loaded
    const targetScene = draft?.sceneJson ?? remote.sceneJson
    const targetDirty = !!draft && !sameDrawingContent(targetScene, remote.sceneJson)
    const targetPendingSave = targetDirty ? draft?.pendingSave : undefined
    const pendingConfirmed = !!targetPendingSave && sameDrawingContent(targetPendingSave.sceneJson, remote.sceneJson)

    if (draft && !targetDirty) await clearDraft(id)
    if ((dirty.value || editingTitle.value) && !(await settleEdits())) return
    if (token !== loadToken || !sameOwner()) return
    if (activeCanvas.value?.key === readyCanvasKey) retainedCanvas.value = activeCanvas.value
    activeId.value = id
    current.value = remote
    if (!props.embedded) rememberDrawing(id)
    titleDraft.value = remote.title
    sceneJson.value = targetScene
    dirty.value = targetDirty
    pendingSave = pendingConfirmed ? undefined : targetPendingSave
    revision.value = draft && targetDirty && !pendingConfirmed ? draft.revision : remote.revision
    draftScene.value = draft?.sceneJson ?? ''
    draftError.value = ''
    error.value = ''
    ++versionsRequest
    versions.value = []
    versionsError.value = ''
    loadingVersions.value = false
    canvasKey.value = `${id}:${++canvasGeneration}`
    updateSummary(remote)

    conflict.value = dirty.value && revision.value !== remote.revision
    if (conflict.value) {
      syncState.value = 'conflict'
      error.value = '本机草稿基于旧版本，云端已有更新。当前先展示本机草稿，不会覆盖云端。'
    } else {
      syncState.value = dirty.value ? 'dirty' : 'saved'
    }
    if (pendingConfirmed) await persistDraft()
    sidebarOpen.value = (page.value?.clientWidth || window.innerWidth) > 760 && sidebarOpen.value
  } catch (cause) {
    if (token !== loadToken || !sameOwner()) return
    openFailure.value = { id, message: message(cause, '草图文件读取失败') }
  } finally {
    request.abort()
    if (openingRequest === request) openingRequest = undefined
    if (token === loadToken) {
      loadingFile.value = false
      transitioning.value = false
    }
  }
}

async function loadVersions(id = activeId.value) {
  if (!id || !versionMenu.value?.open) return
  const request = ++versionsRequest
  const isCurrent = () => request === versionsRequest && activeId.value === id && sameOwner()
  loadingVersions.value = true
  versionsError.value = ''
  try {
    const loaded = await fetchDrawingVersions(id)
    if (isCurrent()) versions.value = loaded
  } catch (cause) {
    if (isCurrent())
      versionsError.value = message(cause, '草图版本读取失败')
  } finally {
    if (isCurrent()) loadingVersions.value = false
  }
}

function persistDraft() {
  clearTimeout(draftTimer)
  if (!current.value || !dirty.value) return Promise.resolve(true)
  const id = current.value.id
  const capturedScene = sceneJson.value
  const capturedRevision = revision.value
  const capturedPending = pendingSave
  const operation = draftQueue
    .then(() =>
      writeDrawingDraft({
        id,
        memberId: owner,
        sceneJson: capturedScene,
        revision: capturedRevision,
        ...(capturedPending ? { pendingSave: capturedPending } : {}),
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

function canvasReady(key: string, canvas?: DrawingTidyCanvas) {
  if (key !== canvasKey.value) return
  tidyCanvas.value = canvas
  readyCanvasKey = key
  if (current.value?.id === photoFitId.value) photoFitId.value = undefined
  retainedCanvas.value = undefined
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
      // Retry the exact unconfirmed snapshot first. Sending newer edits with its
      // old revision would conflict with our own successful but timed-out save.
      const capturedScene = pendingSave?.sceneJson ?? sceneJson.value
      const capturedRevision = pendingSave?.revision ?? revision.value
      pendingSave = { sceneJson: capturedScene, revision: capturedRevision }
      const capturedTitle = current.value.title
      syncState.value = 'saving'
      error.value = ''
      try {
        const saved = await saveDrawing(id, capturedScene, capturedRevision)
        if (!sameOwner() || activeId.value !== id || !current.value) return
        pendingSave = undefined
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
        const code = status?.response?.status ?? status?.status
        if (code && code < 500) pendingSave = undefined
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
    sidebarOpen.value = (page.value?.clientWidth || window.innerWidth) > 760
  } catch (cause) {
    error.value = message(cause, '新建结果未确认，请先刷新列表检查是否已创建草图')
  } finally {
    creating.value = false
  }
}

async function savePhotoDrawing(title: string, sceneJson: string, requestId: string) {
  if (!sameOwner() || creating.value || transitioning.value || actionBusy.value || deletingId.value ||
      restoringVersion.value !== null || reloadingCloud.value) {
    throw new Error('草图正在切换，请稍后再保存。')
  }
  creating.value = true
  try {
    if (!(await settleEdits()) || !sameOwner()) {
      throw new Error('当前草图有未保存的修改，请先处理后重试。')
    }
    const created = await createDrawing(title, undefined, { sceneJson, requestId })
    if (!sameOwner()) throw new Error('账号已切换，请重新进入草图。')
    photoFitId.value = created.id
    updateSummary(created)
    await openFile(created.id)
  } catch (cause) {
    throw new Error(message(cause, '保存结果未确认。可重试，同一次识别只会保存一张草图。'))
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
      pendingSave = undefined
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
  downloadBlob(title, 'excalidraw', new Blob([scene], { type: 'application/json' }))
}

function downloadBlob(title: string, format: DrawingExportFormat, blob: Blob) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${title || '未命名草图'}.${format}`
  try {
    anchor.click()
  } finally {
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
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
    pendingSave = undefined
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
  if (!library.pending && !dirty.value && (!editingTitle.value || titleDraft.value === current.value?.title)) return
  event.preventDefault()
  event.returnValue = ''
}

function refreshLibrary() {
  if (sameOwner() && pageVisible.value && !document.hidden) void library.sync()
}

function checkLibraryInstallLink() {
  if (props.embedded || !sameOwner() || !owner || !pageVisible.value || location.pathname !== '/drawings') return
  const params = new URLSearchParams(location.hash.slice(1))
  const source = params.get('addLibrary')
  if (source === null) return
  params.delete('addLibrary')
  params.delete('token')
  history.replaceState(history.state, '', location.pathname + location.search + (params.size ? '#' + params.toString() : ''))
  const url = officialDrawingLibraryUrl(source)
  if (!url) {
    toast('此素材链接不在 Excalidraw 官方素材库中。', 'ri-error-warning-line')
    return
  }
  confirmLibrary({
    title: '添加在线素材',
    description: '将这套 Excalidraw 官方素材添加到当前账号，在其他设备上也能使用。',
    confirmLabel: '添加到素材库',
    danger: false,
    async run() {
      const signal = AbortSignal.any([libraryRequests.signal, AbortSignal.timeout(30_000)])
      const [incoming, { mergeLibraryItems }] = await Promise.all([
        loadOfficialDrawingLibrary(url, signal),
        import('@excalidraw/excalidraw'),
        library.sync(),
      ])
      if (!sameOwner() || signal.aborted) return
      if (!incoming.length) throw new Error('这套素材没有可添加的图形。')
      const before = library.items
      const next = mergeLibraryItems(before, incoming)
      library.change(before, next)
      await library.sync()
      if (!sameOwner()) return
      if (library.error) throw new Error(library.error)
      if (next.length === before.length) toast('这套素材已在账号素材库中。')
    },
  })
}

watch(() => library.pending, (pending, previous) => {
  if (previous && !pending && sameOwner() && pageVisible.value) toast('素材库已同步到账号。', 'ri-cloud-line')
})
watch(pageVisible, (visible) => {
  if (!visible) return
  refreshLibrary()
  checkLibraryInstallLink()
})
watch(() => localRoute?.hash, checkLibraryInstallLink)

onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  window.addEventListener('hashchange', checkLibraryInstallLink)
  window.addEventListener('focus', refreshLibrary)
  window.addEventListener('online', refreshLibrary)
  document.addEventListener('visibilitychange', refreshLibrary)
  document.addEventListener('fullscreenchange', fullscreenChanged)
  refreshLibrary()
  checkLibraryInstallLink()
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
  libraryRequests.abort()
  ++loadToken
  openingRequest?.abort()
  window.removeEventListener('beforeunload', beforeUnload)
  window.removeEventListener('hashchange', checkLibraryInstallLink)
  window.removeEventListener('focus', refreshLibrary)
  window.removeEventListener('online', refreshLibrary)
  document.removeEventListener('visibilitychange', refreshLibrary)
  document.removeEventListener('fullscreenchange', fullscreenChanged)
})
watch(
  () => session.userId,
  () => {
    clearTimeout(saveTimer)
    clearTimeout(draftTimer)
    if (dirty.value) void persistDraft()
    ++loadToken
    openingRequest?.abort()
    loadingFile.value = false
    transitioning.value = false
    openFailure.value = undefined
    retainedCanvas.value = undefined
    current.value = null
    pendingSave = undefined
    files.value = []
    groups.value = []
    assignments.value = {}
    dialogMode.value = null
    sharing.value = undefined
    contextMenu.value?.close()
    exportError.value = ''
    dirty.value = false
  },
)
// 隐藏时保留画布实例；返回后只在云端版本变化时更新，不能覆盖新输入。
let backgroundRequest = 0
async function refreshOnReturn() {
  if (props.embedded || !current.value || loadingFile.value || transitioning.value || !sameOwner()) return
  if (pendingSave && dirty.value && !conflict.value) {
    await saveNow()
    return
  }
  const id = current.value.id, seenRevision = revision.value, request = ++backgroundRequest
  try {
    const remote = await fetchDrawing(id)
    if (!sameOwner() || request !== backgroundRequest || !pageVisible.value || current.value?.id !== id || revision.value !== seenRevision || transitioning.value || renaming.value || editingTitle.value || savePromise) return
    if (remote.revision !== seenRevision) {
      if (dirty.value && !sameDrawingContent(sceneJson.value, remote.sceneJson)) {
        conflict.value = true; syncState.value = 'conflict'
        error.value = '云端草图已更新，本机修改已保留，请处理版本冲突。'
        await persistDraft()
        return
      }
      if (dirty.value) {
        current.value = remote; revision.value = remote.revision; updateSummary(remote)
        conflict.value = false
        dirty.value = false; syncState.value = 'saved'; error.value = ''
        await clearDraft(id)
        if (!sameOwner() || current.value?.id !== id) return
        if (dirty.value) await persistDraft()
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
  if (library.pending) {
    await library.sync()
    if (library.pending) throw new Error('素材未同步到账号，请先重试同步。')
  }
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
    <DrawingTidyDialog
      v-if="tidyDialog && tidyCanvas"
      :mode="tidyDialog"
      :canvas="tidyCanvas"
      :disabled="transitioning || !!deletingId || restoringVersion !== null"
      @close="tidyDialog = undefined"
    />
    <DrawingShareDialog
      v-if="sharing"
      :key="sharing.id"
      :drawing-id="sharing.id"
      :title="sharing.title"
      @close="sharing = undefined"
    />
    <DrawingPhotoDialog
      v-if="photoOpen && owner && sameOwner()"
      :owner="owner"
      :save-drawing="savePhotoDrawing"
      :download-drawing="downloadScene"
      @close="photoOpen = false"
    />
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

      <WaoButton
        v-if="!embedded && owner"
        class="new-file"
        variant="secondary"
        :disabled="creating || transitioning"
        @click="photoOpen = true"
      ><i class="ri-camera-line" aria-hidden="true"></i>拍照转草图</WaoButton>

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
            <i class="ri-artboard-line file-icon" aria-hidden="true"></i>
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
        <label v-if="current" class="drawing-snap-control">
          <i class="ri-focus-3-line" aria-hidden="true"></i>
          <select v-model="snapMode" aria-label="草图吸附模式">
            <option value="objects">对象吸附</option>
            <option value="grid">网格吸附</option>
            <option value="off">关闭吸附</option>
          </select>
        </label>
        <div v-if="current" class="drawing-tidy-control" role="group" aria-label="智能排版">
          <WaoButton
            variant="ghost"
            :loading="tidyBusy"
            :disabled="!tidyCanvas || transitioning || !!deletingId || restoringVersion !== null"
            aria-haspopup="dialog"
            @click="tidyDialog = 'layout'"
          >智能排版</WaoButton>
          <button
            type="button"
            class="icon-button"
            aria-label="整理方式"
            title="整理方式"
            aria-haspopup="menu"
            :disabled="!tidyCanvas || tidyBusy || transitioning || !!deletingId || restoringVersion !== null"
            @click="openTidyMenu"
          ><i class="ri-arrow-down-s-line" aria-hidden="true"></i></button>
        </div>
        <div v-if="current" class="drawing-meta">
          <span class="edited-at">编辑于 {{ new Date(current.updatedAt).toLocaleString('zh-CN', { month:'numeric', day:'numeric', hour:'2-digit', minute:'2-digit' }) }}</span>
          <button class="icon-button" :aria-label="`重命名 ${current.title}`" title="重命名" :disabled="renaming || transitioning || !!deletingId" @click="startTitleEdit"><i class="ri-pencil-line" aria-hidden="true" /></button>
          <button v-if="!current.docId && !embedded" class="icon-button" :aria-label="`删除 ${current.title}`" title="删除" :disabled="transitioning || !!deletingId" @click="removeFile(current)"><i class="ri-delete-bin-line" aria-hidden="true" /></button>
          <a v-if="current.docId && current.kbId && !embedded" :href="`/kb/${current.kbId}?doc=${current.docId}`" title="在知识库查看" aria-label="在知识库查看"><i class="ri-book-open-line" aria-hidden="true" /></a>
        </div>
        <details v-if="current" :key="current.id" ref="versionMenu" class="version-menu" @toggle="loadVersions()">
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
          v-if="current && !current.docId && !embedded"
          type="button"
          class="icon-button"
          title="分享草图"
          aria-label="分享草图"
          aria-haspopup="dialog"
          :disabled="transitioning || !!deletingId || restoringVersion !== null"
          @click="sharing = current"
        >
          <i class="ri-share-forward-line" aria-hidden="true"></i>
        </button>
        <button
          v-if="current"
          type="button"
          class="icon-button"
          :title="exporting ? '正在导出草图…' : '导出草图'"
          :aria-label="exporting ? '正在导出草图…' : '导出草图'"
          aria-haspopup="menu"
          :disabled="exporting || transitioning || !!deletingId || restoringVersion !== null"
          @click="openExportMenu"
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

      <div v-if="exportError" class="error-banner" role="alert">
        <span>{{ exportError }}</span>
        <button type="button" @click="exportError = ''">关闭</button>
      </div>
      <ConfirmActionDialog v-if="libraryConfirmation" :action="libraryConfirmation" @close="dismissLibraryConfirmation" />
      <div v-if="library.error" class="error-banner" role="alert">
        <span>{{ library.error }}</span>
        <button type="button" @click="library.sync()">重试同步素材</button>
      </div>
      <div v-if="draftError && dirty" class="error-banner" role="alert">
        <span>{{ draftError }}</span
        ><button @click="downloadDraft">下载备份</button>
      </div>
      <div v-if="openFailure" class="error-banner" role="alert">
        <i class="ri-error-warning-line" aria-hidden="true"></i>
        <span>{{ openFailure.message }}</span>
        <button type="button" @click="openFile(openFailure.id)">重试读取</button>
        <button v-if="current" type="button" @click="openFailure = undefined">关闭提示</button>
      </div>
      <div v-else-if="listError && !current && !loadingFile" class="error-banner" role="alert">
        <span>{{ listError }}</span>
        <button type="button" @click="loadList">重试读取</button>
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

      <div class="canvas-shell" :aria-busy="loadingFile || !!retainedCanvas">
        <template v-if="current">
          <div
            v-for="layer in canvasLayers"
            :key="layer.key"
            class="canvas-layer"
            :class="{ 'retained-canvas': layer.key !== canvasKey }"
            :inert.attr="loadingFile || layer.key !== canvasKey ? true : undefined"
          >
            <DrawingCanvas
              v-model:snap-mode="snapMode"
              :scene-json="layer.sceneJson"
              :scene-key="layer.key"
              :name="layer.name"
              :library-items="library.items"
              :fit-on-open="layer.key === canvasKey && !!photoFitId && current.id === photoFitId"
              @library-change="(previous, next) => sameOwner() && library.change(previous, next)"
              @change="layer.key === canvasKey && canvasChanged($event)"
              @ready="canvasReady(layer.key, $event)"
              @save="layer.key === canvasKey && saveNow()"
            />
          </div>
          <div v-if="loadingFile || retainedCanvas" class="canvas-switch-status" role="status">
            <i class="ri-loader-4-line spin" aria-hidden="true"></i>
            <span>正在打开草图</span>
          </div>
        </template>
        <div v-else-if="loadingFile" class="canvas-loading" role="status">
          <i class="ri-loader-4-line spin"></i>
          <span>正在打开草图</span>
        </div>
        <div v-else-if="!embedded && !loadingList && !error && !openFailure && !listError" class="canvas-empty">
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
          <WaoButton
            v-if="!sidebarOpen && !embedded && owner"
            variant="secondary"
            :disabled="creating || transitioning"
            @click="photoOpen = true"
          ><i class="ri-camera-line" aria-hidden="true"></i>拍照转草图</WaoButton>
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
  overflow: auto;
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
  width: calc(100% - 18px);
  margin-left: 18px;
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
.file-icon { flex: none; color: var(--wao-text-3); }
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
.drawing-snap-control {
  display: inline-flex;
  align-items: center;
  gap: var(--wao-space-1);
  padding-inline-start: var(--wao-space-1);
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  color: var(--wao-text-3);
}
.drawing-tidy-control {
  display: inline-flex;
  align-items: center;
}
.drawing-tidy-control :deep(.wao-button) {
  padding-inline: var(--wao-space-1);
  white-space: nowrap;
}
.drawing-tidy-control .icon-button { width: 30px; }
.drawing-tidy-control button:disabled { opacity: 0.5; cursor: not-allowed; }
.drawing-snap-control select {
  min-height: 34px;
  padding-inline-end: var(--wao-space-1);
  border: 0;
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  font-size: var(--wao-font-body);
  cursor: pointer;
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
.drawings-page:fullscreen .drawing-toolbar > :not(.fullscreen-button, .drawing-snap-control) {
  display: none;
}
.canvas-layer {
  position: absolute;
  inset: 0;
}
.retained-canvas {
  z-index: 1;
  background: var(--wao-bg-card);
}
.canvas-switch-status {
  position: absolute;
  z-index: 2;
  inset-block-start: var(--wao-space-2);
  inset-inline-end: var(--wao-space-2);
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  padding: var(--wao-space-1);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
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
@container app-main (max-width: 760px) {
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
  .drawing-snap-control select { min-height:44px; }
  .drawing-tidy-control .icon-button { min-width: 44px; min-height: 44px; }
}
@media (prefers-reduced-motion: reduce) {
  .spin {
    animation: none;
  }
}
</style>
