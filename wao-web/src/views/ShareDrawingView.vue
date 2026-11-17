<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { readSharedDrawing, type SharedDrawing } from '@/api/drawingShare'
import WaoButton from '@/components/ui/WaoButton.vue'

const SharedDrawingCanvas = defineAsyncComponent(() =>
  import('@/components/drawings/SharedDrawingCanvas.vue').then((module) => module.default),
)
const route = useRoute()
const token = computed(() => route.hash.slice(1))
const metadata = shallowRef<SharedDrawing>()
const scene = shallowRef<NonNullable<SharedDrawing['scene']>>()
const unavailable = ref(false)
const failed = ref(false)
const page = ref<HTMLElement>()
const fullscreen = ref(false)
const canFullscreen = computed(() => !!page.value && document.fullscreenEnabled)
let request: AbortController | undefined
let pollTimer: ReturnType<typeof setTimeout> | undefined
let expiryTimer: ReturnType<typeof setTimeout> | undefined

function stop() {
  clearTimeout(pollTimer)
  clearTimeout(expiryTimer)
  request?.abort()
  request = undefined
}

function invalidate() {
  stop()
  metadata.value = undefined
  scene.value = undefined
  failed.value = false
  unavailable.value = true
}

function checkExpiry(expiresAt: string | null) {
  clearTimeout(expiryTimer)
  if (!expiresAt) return
  const remaining = Date.parse(expiresAt) - Date.now()
  if (remaining <= 0) invalidate()
  else if (remaining <= 2_147_000_000) expiryTimer = setTimeout(invalidate, remaining)
}

async function refresh() {
  if (request || unavailable.value || document.hidden) return
  clearTimeout(pollTimer)
  const active = new AbortController()
  request = active
  try {
    const latest = await readSharedDrawing(token.value, metadata.value?.revision ?? 0, active.signal)
    if (active.signal.aborted) return
    failed.value = false
    if (latest.scene) scene.value = latest.scene
    metadata.value = latest
    checkExpiry(latest.expiresAt)
  } catch (cause) {
    if (active.signal.aborted) return
    const error = cause as { status?: number; response?: { status?: number } }
    const status = error?.status ?? error?.response?.status
    if (status === 404 || status === 410 || status === 403) invalidate()
    else failed.value = true
  } finally {
    if (request === active) {
      request = undefined
      if (!document.hidden && !unavailable.value) pollTimer = setTimeout(refresh, 5000)
    }
  }
}

function openLink() {
  stop()
  scene.value = undefined
  metadata.value = undefined
  failed.value = false
  unavailable.value = !/^ds_[A-Za-z0-9_-]{43}$/.test(token.value)
  void refresh()
}

function visibilityChanged() {
  if (document.hidden) {
    clearTimeout(pollTimer)
    return
  }
  if (metadata.value) checkExpiry(metadata.value.expiresAt)
  void refresh()
}

async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await page.value?.requestFullscreen()
  } catch {
    fullscreenChanged()
  }
}

function fullscreenChanged() {
  fullscreen.value = document.fullscreenElement === page.value
}

onMounted(() => {
  openLink()
  document.addEventListener('visibilitychange', visibilityChanged)
  document.addEventListener('fullscreenchange', fullscreenChanged)
  window.addEventListener('online', refresh)
})
watch(token, openLink)
onBeforeUnmount(() => {
  stop()
  document.removeEventListener('visibilitychange', visibilityChanged)
  document.removeEventListener('fullscreenchange', fullscreenChanged)
  window.removeEventListener('online', refresh)
})
</script>

<template>
  <main ref="page" class="share-drawing-page">
    <header v-if="metadata" class="share-drawing-header">
      <div class="share-drawing-title">
        <h1>{{ metadata.title }}</h1>
        <p>只读 · 更新于 {{ new Date(metadata.updatedAt).toLocaleString() }}</p>
      </div>
      <WaoButton v-if="scene && canFullscreen" variant="ghost" @click="toggleFullscreen">
        {{ fullscreen ? '退出全屏' : '全屏' }}
      </WaoButton>
    </header>
    <div v-if="failed" class="share-drawing-notice" role="status">
      {{ scene ? '暂时无法同步，正在显示上次加载的版本。' : '暂时无法加载草图。' }}
      <WaoButton variant="ghost" @click="refresh">重试</WaoButton>
    </div>
    <div v-if="scene" class="share-drawing-body">
      <Suspense>
        <SharedDrawingCanvas :scene="scene" />
        <template #fallback><p class="share-drawing-state" role="status">正在加载画布…</p></template>
      </Suspense>
    </div>
    <div v-else-if="unavailable" class="share-drawing-state" role="status">
      <h1>草图链接已失效</h1>
      <p>请联系分享者获取新的链接。</p>
    </div>
    <p v-else-if="!failed" class="share-drawing-state" role="status">正在读取草图…</p>
  </main>
</template>

<style scoped>
.share-drawing-page {
  display: flex;
  flex-direction: column;
  height: 100dvh;
  background: var(--wao-bg-page);
  color: var(--wao-text);
}
.share-drawing-header, .share-drawing-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wao-space-1);
  padding: var(--wao-space-1) var(--wao-space-2);
  border-bottom: 1px solid var(--wao-line);
}
.share-drawing-title { min-width: 0; }
h1 { margin: 0; font-size: var(--wao-font-title); line-height: 1.4; overflow-wrap: anywhere; }
p { margin: 0; color: var(--wao-text-3); font-size: var(--wao-font-help); }
.share-drawing-body { flex: 1; min-height: 0; min-width: 0; }
.share-drawing-state { margin: auto; padding: var(--wao-space-2); text-align: center; }
@media (pointer: coarse) { .share-drawing-page button { min-height: 44px; } }
</style>
