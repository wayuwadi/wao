<script setup lang="ts">
import { CaptureUpdateAction, Excalidraw, convertToExcalidrawElements, newElementWith, serializeAsJSON } from '@excalidraw/excalidraw'
import '@excalidraw/excalidraw/index.css'
import type {
  AppState,
  BinaryFiles,
  ExcalidrawImperativeAPI,
  ExcalidrawInitialDataState,
  LibraryItems,
} from '@excalidraw/excalidraw/types'
import type { ExcalidrawTextElement, OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { createElement } from 'react'
import type { UIAppState } from '@excalidraw/excalidraw/types'
import { createRoot, type Root } from 'react-dom/client'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTheme } from '@/composables/theme'
import { toast } from '@/composables/toast'
import { usePageVisibility } from '@/composables/pageVisibility'
import type { DrawingSnapMode } from '@/composables/drawingSnapMode'
import { calculateDrawingTidy, type DrawingTidyCanvas } from '@/utils/drawingTidyClient'
import type { DrawingTidyRequest } from '@/utils/drawingTidy'
import { sameLibraryItem } from '@/stores/drawingLibrary'
import { readTextStyle, textSource, textStyleKey, type DrawingTextStyle } from '@/utils/drawingTextStyle.mjs'
import DrawingTextStyleDialog from './DrawingTextStyleDialog.vue'

const props = defineProps<{
  sceneJson: string
  sceneKey: string
  name: string
  snapMode: DrawingSnapMode
  libraryItems?: LibraryItems
  fitOnOpen?: boolean
}>()

const emit = defineEmits<{
  change: [sceneJson: string]
  ready: [tidyCanvas?: DrawingTidyCanvas]
  save: []
  'update:snapMode': [mode: DrawingSnapMode]
  'library-change': [previous: LibraryItems, next: LibraryItems]
}>()

const host = ref<HTMLElement | null>(null)
const textStyleEditor = ref<{ element: ExcalidrawTextElement; value: DrawingTextStyle; version: number; nonce: number }>()
const { effective } = useTheme()
const pageVisible = usePageVisibility()
let root: Root | null = null
let canvasApi: ExcalidrawImperativeAPI | null = null
let additiveSelectionPointer: number | null = null
let ready = false
let mountedKey = ''
let lastScene = props.sceneJson
let sceneGridModeEnabled = false
let lastSnapMode: DrawingSnapMode | null = null
let canvasLibrary: LibraryItems = []

function persistedContent(sceneJson: string) {
  try {
    const scene = JSON.parse(sceneJson) as {
      elements?: unknown[]
      appState?: { viewBackgroundColor?: string }
      files?: Record<string, unknown>
    }
    return JSON.stringify({
      elements: scene.elements ?? [],
      viewBackgroundColor: scene.appState?.viewBackgroundColor ?? '#ffffff',
      files: scene.files ?? {},
    })
  } catch {
    return sceneJson
  }
}

function initialData(): ExcalidrawInitialDataState {
  let scene: ExcalidrawInitialDataState | null = null
  try {
    scene = JSON.parse(props.sceneJson) as ExcalidrawInitialDataState | null
  } catch {
    // An unreadable scene still opens a blank editor with the member's preference.
  }
  sceneGridModeEnabled = scene?.appState?.gridModeEnabled === true
  return {
    ...scene,
    libraryItems: props.libraryItems === undefined ? scene?.libraryItems : undefined,
    scrollToContent: true,
    appState: { ...scene?.appState, ...snapState(props.snapMode) },
  }
}

function snapState(mode: DrawingSnapMode) {
  return { objectsSnapModeEnabled: mode === 'objects', gridModeEnabled: mode === 'grid' }
}

function applySnapMode(mode: DrawingSnapMode) {
  if (!ready || mode === lastSnapMode) return
  canvasApi?.updateScene({ appState: snapState(mode), captureUpdate: CaptureUpdateAction.NEVER })
}

function changed(
  elements: readonly OrderedExcalidrawElement[],
  appState: AppState,
  files: BinaryFiles,
) {
  const mode = appState.gridModeEnabled ? 'grid' : appState.objectsSnapModeEnabled ? 'objects' : 'off'
  // Excalidraw exports its grid flag. Retain the file's original value so preferences stay local.
  const snapshot = elements.map(element => {
    if (element.type !== 'text' || !element.customData?.[textStyleKey]) return element
    return { ...element, customData: { ...element.customData, [textStyleKey]: readTextStyle(element) } }
  })
  const sceneJson = serializeAsJSON(snapshot, { ...appState, gridModeEnabled: sceneGridModeEnabled }, files, 'local')
  if (!ready) {
    // Excalidraw calls onChange after loading and normalizing initialData.
    // Its API callback runs earlier, before that normalization has completed.
    lastScene = sceneJson
    ready = true
    lastSnapMode = mode
    applySnapMode(props.snapMode)
    if (props.fitOnOpen && canvasApi && elements.length) {
      const api = canvasApi
      requestAnimationFrame(() => {
        if (canvasApi === api) api.scrollToContent(elements, { fitToViewport: true, viewportZoomFactor: 0.82 })
      })
    }
    emit('ready', tidyCanvas)
    return
  }
  if (mode !== lastSnapMode) {
    lastSnapMode = mode
    if (mode !== props.snapMode) emit('update:snapMode', mode)
  }
  if (persistedContent(sceneJson) === persistedContent(lastScene)) {
    lastScene = sceneJson
    return
  }
  lastScene = sceneJson
  emit('change', sceneJson)
}

function setCanvasApi(api: ExcalidrawImperativeAPI) {
  canvasApi = api
  syncCanvasLibrary(true)
}

function libraryChanged(next: LibraryItems) {
  const previous = canvasLibrary
  canvasLibrary = next
  if (props.libraryItems !== undefined) emit('library-change', previous, next)
}

function syncCanvasLibrary(force = false) {
  const api = canvasApi
  const desired = props.libraryItems
  if (!api || desired === undefined || !pageVisible.value) return
  if (!force && canvasLibrary.length === desired.length &&
      canvasLibrary.every((item, index) => sameLibraryItem(item, desired[index]!))) return
  void api.updateLibrary({
    merge: false,
    libraryItems: (current) => {
      if (canvasApi !== api) return current
      // Excalidraw batches library notifications. Record edits queued ahead of this load.
      libraryChanged(current)
      canvasLibrary = props.libraryItems ?? desired
      return canvasLibrary
    },
  }).catch((cause: unknown) => console.warn('Unable to load account drawing library', cause))
}

function editableCanvas() {
  if (!canvasApi || !ready || !pageVisible.value || !host.value || host.value.closest('[inert]')) return null
  const state = canvasApi.getAppState()
  if (state.viewModeEnabled || state.isLoading || state.editingTextElement ||
      state.editingLinearElement || state.croppingElementId || state.newElement ||
      state.selectedElementsAreBeingDragged || state.isResizing || state.isRotating) return null
  return canvasApi
}

function selectedIds(api: ExcalidrawImperativeAPI | null) {
  return Object.entries(api?.getAppState().selectedElementIds ?? {}).filter(([, selected]) => selected).map(([id]) => id)
}

function selectedElements(ids: AppState['selectedElementIds']) {
  return canvasApi?.getSceneElementsIncludingDeleted().filter(element => ids[element.id] && !element.isDeleted) ?? []
}

function selectedText(ids: AppState['selectedElementIds']) {
  const selection = selectedElements(ids)
  const selected = selection[0]
  if (!canvasApi || selection.length !== 1 || !selected || selected.locked) return
  const elements = canvasApi.getSceneElementsIncludingDeleted()
  const text = selected.type === 'text' ? selected : elements.find(element => element.type === 'text' && element.containerId === selected.id && !element.isDeleted && !element.locked)
  if (text?.type === 'text' && textSource(text).trim()) return text
}

type CenterAction = 'alignHorizontallyCentered' | 'alignVerticallyCentered'

function canAlign(state: Pick<AppState, 'selectedElementIds'>) {
  const elements = selectedElements(state.selectedElementIds)
  if (elements.length < 2 || elements.some(element => element.locked || element.type === 'frame' || element.type === 'magicframe')) return false
  const groups = new Set(elements.map(({ id, groupIds }) => groupIds[groupIds.length - 1] ?? id))
  return groups.size > 1
}

function renderSelectionTools(_mobile: boolean, state: UIAppState, execute?: (name: CenterAction) => void) {
  if (state.viewModeEnabled || state.editingTextElement) return null
  const button = (label: string, icon: string, title: string, onClick: () => void) =>
    createElement('button', { type: 'button', className: 'wao-drawing-text-format', title, onClick },
      createElement('i', { className: icon, 'aria-hidden': 'true' }), label)
  if (selectedText(state.selectedElementIds)) return button('文字高亮', 'ri-mark-pen-line', '文字高亮', openTextStyle)
  if (!execute || !canAlign(state)) return null
  const align = (action: CenterAction) => {
    const api = editableCanvas()
    if (api && canAlign(api.getAppState())) execute(action)
  }
  return createElement('div', { role: 'group', 'aria-label': '选区对齐', className: 'wao-drawing-selection-tools' },
    button('列居中', 'ri-align-center', '列居中（对齐到同一竖线）', () => align('alignHorizontallyCentered')),
    button('行居中', 'ri-align-vertically', '行居中（对齐到同一横线）', () => align('alignVerticallyCentered')))
}

function openTextStyle() {
  const api = editableCanvas()
  const element = api && selectedText(api.getAppState().selectedElementIds)
  if (element) textStyleEditor.value = { element, value: readTextStyle(element), version: element.version, nonce: element.versionNonce }
}

function applyTextStyle(style: DrawingTextStyle) {
  const pending = textStyleEditor.value
  const api = editableCanvas()
  const elements = api?.getSceneElementsIncludingDeleted()
  const current = elements?.find(element => element.id === pending?.element.id)
  if (!api || !elements || !pending || current?.type !== 'text' || current.isDeleted || current.locked ||
      current.version !== pending.version || current.versionNonce !== pending.nonce) {
    textStyleEditor.value = undefined
    toast('文字已发生变化，请重新选中后设置高亮。', 'ri-information-line')
    return
  }
  const customData = { ...current.customData }
  if (style.background || style.runs.length) customData[textStyleKey] = style
  else delete customData[textStyleKey]
  api.updateScene({
    elements: elements.map(element => element === current ? newElementWith(current, { customData }) : element),
    captureUpdate: CaptureUpdateAction.IMMEDIATELY,
  })
  textStyleEditor.value = undefined
}

async function prepareTidy(request: DrawingTidyRequest, signal: AbortSignal) {
  const api = editableCanvas()
  if (!api) throw new Error('请先结束当前编辑，再整理草图。')
  const elements = api.getSceneElementsIncludingDeleted()
  // Keep immutable versions separately because the editor can mutate a scene element in place.
  const fingerprint = (items: readonly OrderedExcalidrawElement[]) =>
    JSON.stringify(items.map(({ id, version, versionNonce }) => [id, version, versionNonce]))
  const version = fingerprint(elements)
  const input = { elements, selectedIds: selectedIds(api), request }
  const ensureCurrent = () => {
    signal.throwIfAborted()
    if (editableCanvas() !== api || fingerprint(api.getSceneElementsIncludingDeleted()) !== version) {
      throw new Error('草图已发生变化，请重新整理。')
    }
  }
  let aiSummary: string | undefined
  if (request.mode === 'ai') {
    const [{ describeDrawingForAi }, { requestDrawingAiGuidance }] = await Promise.all([
      import('@/utils/drawingTidy'), import('@/utils/drawingAiLayoutModel'),
    ])
    ensureCurrent()
    const guidance = await requestDrawingAiGuidance(describeDrawingForAi(input), signal)
    ensureCurrent()
    aiSummary = guidance.summary
    input.request = { ...request, mode: 'layout', guidance }
  }
  const result = await calculateDrawingTidy(input, signal)
  ensureCurrent()
  const changes = new Map(result.changed.map((element) => [element.id, element]))
  const lanes = result.lanes ?? []
  const frames = lanes.length ? convertToExcalidrawElements(lanes.map(({ name, bounds }) =>
    ({ type: 'frame', name, children: [], ...bounds }))) : []
  const currentElements = new Map(elements.map((element) => [element.id, element]))
  lanes.forEach(({ units }, index) => {
    const frameId = frames[index]!.id
    for (const id of units) {
      const element = changes.get(id) ?? currentElements.get(id)
      if (element && !element.isDeleted && !element.locked) changes.set(id, { ...element, frameId })
    }
  })
  const preview = [...elements.map((element) => changes.get(element.id) ?? element), ...frames]
  const includePreview = request.mode !== 'light' && changes.size > 0
  const before = includePreview ? serializeAsJSON(elements, api.getAppState(), api.getFiles(), 'local') : ''
  const after = includePreview ? serializeAsJSON(preview, api.getAppState(), api.getFiles(), 'local') : ''
  return {
    moved: result.moved, routed: result.routed, skipped: result.skipped, changed: changes.size,
    laneCount: lanes.length, mainCount: result.mainCount, sections: result.sections, images: result.images, captions: result.captions,
    aiSummary,
    crossings: result.crossings, canExpandFrames: result.canExpandFrames, limited: result.limited, before, after,
    apply() {
      ensureCurrent()
      if (!changes.size) return
      api.updateScene({
        elements: [...api.getSceneElementsIncludingDeleted().map((element) => {
          const candidate = changes.get(element.id)
          return candidate ? newElementWith(element, candidate) : element
        }), ...frames],
        captureUpdate: CaptureUpdateAction.IMMEDIATELY,
      })
      requestAnimationFrame(() => {
        if (canvasApi === api) host.value?.querySelector<HTMLElement>('.excalidraw')?.focus({ preventScroll: true })
      })
    },
  }
}

const tidyCanvas: DrawingTidyCanvas = {
  hasSelection: () => selectedIds(editableCanvas()).length > 0,
  prepare: prepareTidy,
}

function useAdditiveSelection(event: PointerEvent) {
  // Excalidraw uses Shift for additive selection and Cmd/Ctrl for entering groups.
  Object.defineProperties(event, {
    shiftKey: { value: true, configurable: true },
    metaKey: { value: false, configurable: true },
    ctrlKey: { value: false, configurable: true },
  })
}

function startAdditiveSelection(event: PointerEvent) {
  additiveSelectionPointer = null
  if (event.pointerType !== 'mouse' || event.button !== 0 || event.altKey) return
  if (!(event.metaKey || event.ctrlKey)) return
  if (!(event.target instanceof HTMLCanvasElement) ||
      !event.target.matches('.excalidraw__canvas.interactive')) return

  const state = canvasApi?.getAppState()
  if (state?.activeTool.type !== 'selection' || state.viewModeEnabled) return
  if (state.editingTextElement || state.editingLinearElement || state.croppingElementId) return

  additiveSelectionPointer = event.pointerId
  useAdditiveSelection(event)
}

function finishAdditiveSelection(event: PointerEvent) {
  if (event.pointerId !== additiveSelectionPointer) return
  additiveSelectionPointer = null
  if (event.type === 'pointerup' && event.button === 0) useAdditiveSelection(event)
}

function render() {
  if (!host.value) return
  if (!root) root = createRoot(host.value)
  if (mountedKey !== props.sceneKey) {
    canvasApi = null
    canvasLibrary = []
    additiveSelectionPointer = null
    mountedKey = props.sceneKey
    ready = false
    lastScene = props.sceneJson
    textStyleEditor.value = undefined
  }
  const key = mountedKey
  root.render(
    createElement(Excalidraw, {
      key: props.sceneKey,
      initialData: initialData(),
      langCode: 'zh-CN',
      name: props.name,
      theme: effective.value,
      autoFocus: true,
      excalidrawAPI: setCanvasApi,
      onChange: changed,
      renderTopRightUI: renderSelectionTools,
      onLibraryChange: (items: LibraryItems) => {
        if (canvasApi && mountedKey === key) libraryChanged(items)
      },
      libraryReturnUrl: new URL('/drawings', location.origin).href,
    }),
  )
}

watch(() => [props.sceneKey, props.name, effective.value], render)
watch(() => props.snapMode, applySnapMode)
watch(() => props.libraryItems, () => syncCanvasLibrary())
watch(pageVisible, (visible) => { if (visible) syncCanvasLibrary(true) })
watch(pageVisible, (visible) => { if (!visible) textStyleEditor.value = undefined })
function preventBrowserSave(event: KeyboardEvent) {
  if (!pageVisible.value || host.value?.closest('[inert]')) return
  if (event.target instanceof Element && host.value && !host.value.closest('.drawings-page')?.contains(event.target)) return
  if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 's') {
    event.preventDefault()
    event.stopImmediatePropagation()
    emit('save')
  }
}
onMounted(() => {
  window.addEventListener('keydown', preventBrowserSave, true)
  render()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', preventBrowserSave, true)
  root?.unmount()
  root = null
  canvasApi = null
})
</script>

<template>
  <div
    class="drawing-canvas"
    @pointerdown.capture="startAdditiveSelection"
    @pointerup.capture="finishAdditiveSelection"
    @pointercancel.capture="finishAdditiveSelection"
  >
    <div ref="host" class="drawing-host" />
    <DrawingTextStyleDialog v-if="textStyleEditor" :value="textStyleEditor.value" :stroke-color="textStyleEditor.element.strokeColor"
      @close="textStyleEditor = undefined" @apply="applyTextStyle" />
  </div>
</template>

<style scoped>
.drawing-canvas, .drawing-host {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}
.drawing-host :deep(.wao-drawing-text-format) {
  display: inline-flex; align-items: center; gap: var(--wao-space-1); min-height: 44px;
  padding: 0 var(--wao-space-2); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card); color: var(--wao-primary-text); font: inherit; white-space: nowrap; cursor: pointer;
}
.drawing-host :deep(.wao-drawing-text-format:hover) { background: var(--wao-primary-soft); }
.drawing-host :deep(.wao-drawing-text-format:focus-visible) { outline: 2px solid var(--wao-primary); }
.drawing-host :deep(.wao-drawing-selection-tools) { display: flex; align-items: center; gap: var(--wao-space-1); }
</style>
