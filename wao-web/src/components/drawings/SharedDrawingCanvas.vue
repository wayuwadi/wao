<script setup lang="ts">
import { CaptureUpdateAction, Excalidraw, restore } from '@excalidraw/excalidraw'
import '@excalidraw/excalidraw/index.css'
import type { ExcalidrawImperativeAPI, ExcalidrawInitialDataState } from '@excalidraw/excalidraw/types'
import { createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTheme } from '@/composables/theme'

const props = defineProps<{ scene: ExcalidrawInitialDataState }>()
const host = ref<HTMLElement>()
const { effective } = useTheme()
let root: Root | undefined
let api: ExcalidrawImperativeAPI | undefined
let ready = false
let appliedScene: ExcalidrawInitialDataState | undefined

function getInitialData() {
  appliedScene = props.scene
  return { ...appliedScene, scrollToContent: true }
}

function updateCanvas() {
  if (!api || !ready || appliedScene === props.scene) return
  appliedScene = props.scene
  const { elements, files, appState } = restore(appliedScene, null, null, { repairBindings: true })
  api.addFiles(Object.values(files))
  // Keep the viewer's zoom and scroll when applying a saved version.
  api.updateScene({
    elements,
    appState: { viewBackgroundColor: appState.viewBackgroundColor },
    captureUpdate: CaptureUpdateAction.NEVER,
  })
}

function render() {
  root?.render(createElement(Excalidraw, {
    initialData: getInitialData,
    excalidrawAPI: (value) => { api = value },
    onChange: () => {
      if (ready) return
      ready = true
      updateCanvas()
    },
    viewModeEnabled: true,
    validateEmbeddable: false,
    langCode: 'zh-CN',
    theme: effective.value,
    UIOptions: {
      canvasActions: {
        export: false,
        saveAsImage: false,
        saveToActiveFile: false,
        loadScene: false,
        toggleTheme: false,
      },
    },
  }))
}

onMounted(() => {
  if (host.value) root = createRoot(host.value)
  render()
})
watch(() => props.scene, updateCanvas)
watch(effective, render)
onBeforeUnmount(() => {
  root?.unmount()
  api = undefined
})
</script>

<template><div ref="host" class="shared-drawing-canvas" aria-label="只读画布"></div></template>

<style scoped>
.shared-drawing-canvas { width: 100%; height: 100%; min-width: 0; min-height: 0; }
</style>
