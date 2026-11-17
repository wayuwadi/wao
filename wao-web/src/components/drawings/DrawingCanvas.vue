<script setup lang="ts">
import { CaptureUpdateAction, Excalidraw, serializeAsJSON } from '@excalidraw/excalidraw'
import '@excalidraw/excalidraw/index.css'
import type {
  AppState,
  BinaryFiles,
  ExcalidrawImperativeAPI,
  ExcalidrawInitialDataState,
} from '@excalidraw/excalidraw/types'
import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTheme } from '@/composables/theme'
import { usePageVisibility } from '@/composables/pageVisibility'
import type { DrawingSnapMode } from '@/composables/drawingSnapMode'

const props = defineProps<{
  sceneJson: string
  sceneKey: string
  name: string
  snapMode: DrawingSnapMode
}>()

const emit = defineEmits<{
  change: [sceneJson: string]
  ready: []
  save: []
  'update:snapMode': [mode: DrawingSnapMode]
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
    emit('ready')
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
    additiveSelectionPointer = null
    mountedKey = props.sceneKey
    ready = false
    lastScene = props.sceneJson
  }
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
    }),
  )
}

watch(() => [props.sceneKey, props.name, effective.value], render)
watch(() => props.snapMode, applySnapMode)
function preventBrowserSave(event: KeyboardEvent) {
  if (!pageVisible.value) return
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
