<script setup lang="ts">
import { Excalidraw, serializeAsJSON } from '@excalidraw/excalidraw'
import '@excalidraw/excalidraw/index.css'
import type {
  AppState,
  BinaryFiles,
  ExcalidrawInitialDataState,
} from '@excalidraw/excalidraw/types'
import type { OrderedExcalidrawElement } from '@excalidraw/excalidraw/element/types'
import { createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTheme } from '@/composables/theme'

const props = defineProps<{
  sceneJson: string
  sceneKey: string
  name: string
}>()

const emit = defineEmits<{
  change: [sceneJson: string]
  ready: []
}>()

const host = ref<HTMLElement | null>(null)
const { effective } = useTheme()
let root: Root | null = null
let ready = false
let mountedKey = ''
let lastScene = props.sceneJson

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
  try {
    return JSON.parse(props.sceneJson) as ExcalidrawInitialDataState
  } catch {
    return { elements: [], appState: {}, files: {} }
  }
}

function changed(
  elements: readonly OrderedExcalidrawElement[],
  appState: AppState,
  files: BinaryFiles,
) {
  const sceneJson = serializeAsJSON(elements, appState, files, 'local')
  if (!ready) {
    lastScene = sceneJson
    return
  }
  if (persistedContent(sceneJson) === persistedContent(lastScene)) {
    lastScene = sceneJson
    return
  }
  lastScene = sceneJson
  emit('change', sceneJson)
}

function render() {
  if (!host.value) return
  if (!root) root = createRoot(host.value)
  if (mountedKey !== props.sceneKey) {
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
      onChange: changed,
      excalidrawAPI: () => {
        window.requestAnimationFrame(() => {
          ready = true
          emit('ready')
        })
      },
    }),
  )
}

watch(() => [props.sceneKey, props.name, effective.value], render)
function preventBrowserSave(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 's') {
    event.preventDefault()
    event.stopImmediatePropagation()
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
  <div ref="host" class="drawing-canvas"></div>
</template>

<style scoped>
.drawing-canvas {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}
</style>
