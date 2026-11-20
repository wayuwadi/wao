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
import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTheme } from '@/composables/theme'
import { usePageVisibility } from '@/composables/pageVisibility'
import type { DrawingSnapMode } from '@/composables/drawingSnapMode'
import { calculateDrawingTidy, type DrawingTidyCanvas } from '@/utils/drawingTidyClient'
import type { DrawingTidyRequest } from '@/utils/drawingTidy'
import { sameLibraryItem } from '@/stores/drawingLibrary'

const props = defineProps<{
  sceneJson: string
  sceneKey: string
  name: string
  snapMode: DrawingSnapMode
  libraryItems?: LibraryItems
}>()

const emit = defineEmits<{
  change: [sceneJson: string]
  ready: [tidyCanvas?: DrawingTidyCanvas]
  save: []
  'update:snapMode': [mode: DrawingSnapMode]
  'library-change': [previous: LibraryItems, next: LibraryItems]
}>()

const host = ref<HTMLElement | null>(null)
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
  const sceneJson = serializeAsJSON(elements, { ...appState, gridModeEnabled: sceneGridModeEnabled }, files, 'local')
  if (!ready) {
    // Excalidraw calls onChange after loading and normalizing initialData.
    // Its API callback runs earlier, before that normalization has completed.
    lastScene = sceneJson
    ready = true
    lastSnapMode = mode
    applySnapMode(props.snapMode)
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

async function prepareTidy(request: DrawingTidyRequest, signal: AbortSignal) {
  const api = editableCanvas()
  if (!api) throw new Error('请先结束当前编辑，再整理草图。')
  const elements = api.getSceneElementsIncludingDeleted()
  // Keep immutable versions separately because the editor can mutate a scene element in place.
  const fingerprint = (items: readonly OrderedExcalidrawElement[]) =>
    JSON.stringify(items.map(({ id, version, versionNonce }) => [id, version, versionNonce]))
  const version = fingerprint(elements)
  const result = await calculateDrawingTidy({ elements, selectedIds: selectedIds(api), request }, signal)
  const ensureCurrent = () => {
    signal.throwIfAborted()
    if (editableCanvas() !== api || fingerprint(api.getSceneElementsIncludingDeleted()) !== version) {
      throw new Error('草图已发生变化，请重新整理。')
    }
  }
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
    laneCount: lanes.length, mainCount: result.mainCount, sections: result.sections,
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
    ref="host"
    class="drawing-canvas"
    @pointerdown.capture="startAdditiveSelection"
    @pointerup.capture="finishAdditiveSelection"
    @pointercancel.capture="finishAdditiveSelection"
  ></div>
</template>

<style scoped>
.drawing-canvas {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}
</style>
