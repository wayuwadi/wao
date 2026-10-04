<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchTianrenImage, fetchTianrenVisual, generateTianrenImage, type TianrenVisual } from '@/api/tianren'

const props = defineProps<{ readingId: string; today: boolean; visible: boolean; refreshKey: number; interpreting: boolean }>()
const visual = ref<TianrenVisual | null>(null), loading = ref(false), submitting = ref(false)
const error = ref(''), imageError = ref(''), imageUrl = ref(''), zoomed = ref(false)
const imageLoading = ref(false), pageVisible = ref(document.visibilityState !== 'hidden')
const zoomDialog = ref<HTMLElement | null>(null)
let generation = 0, imageGeneration = 0, disposed = false
let timer: ReturnType<typeof setTimeout> | undefined
let loadedAttempt: string | null = null, pendingRequest: { runId: string; requestId: string } | null = null
let zoomTrigger: HTMLElement | null = null
const active = computed(() => ['QUEUED', 'RUNNING'].includes(visual.value?.state ?? ''))
const canGenerate = computed(() => props.today && visual.value?.retryable && visual.value.interpretationRunId
  && !active.value && !submitting.value && !loading.value && !props.interpreting)
const stateMessage = computed(() => {
  if (props.interpreting) return '正在解读，保存后继续生成本次配图'
  if (loading.value && !visual.value) return '正在读取当日配图'
  if (!props.today && visual.value?.state === 'NOT_GENERATED') return '当日没有保存配图'
  return visual.value?.message || '解读保存后，在这里查看当日配图'
})

function stopPolling() { if (timer) clearTimeout(timer); timer = undefined }
function releaseImage() {
  imageGeneration++
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''; loadedAttempt = null; imageLoading.value = false; zoomed.value = false
}
async function loadImage(attempt: string) {
  if (attempt === loadedAttempt && imageUrl.value) return
  const ticket = ++imageGeneration, readingId = props.readingId
  imageLoading.value = true; imageError.value = ''
  try {
    const blob = await fetchTianrenImage(readingId, attempt)
    if (disposed || ticket !== imageGeneration || props.readingId !== readingId) return
    const url = URL.createObjectURL(blob), previous = imageUrl.value
    imageUrl.value = url; loadedAttempt = attempt
    if (previous) URL.revokeObjectURL(previous)
  } catch (cause) {
    if (ticket === imageGeneration && !disposed) imageError.value = cause instanceof Error ? cause.message : '图片读取失败，请重试'
  } finally { if (ticket === imageGeneration) imageLoading.value = false }
}
function applyVisual(result: TianrenVisual) {
  if (visual.value?.interpretationRunId !== result.interpretationRunId) { releaseImage(); pendingRequest = null }
  visual.value = result
  if (result.imageAttemptId) void loadImage(result.imageAttemptId)
  else releaseImage()
}
function schedule() {
  stopPolling()
  if (!disposed && props.visible && pageVisible.value && active.value && !props.interpreting) timer = setTimeout(() => void load(), 5000)
}
async function load() {
  stopPolling()
  const ticket = ++generation, readingId = props.readingId
  loading.value = true; error.value = ''
  try {
    const result = await fetchTianrenVisual(readingId)
    if (disposed || ticket !== generation) return
    applyVisual(result)
  } catch (cause) {
    if (ticket === generation && !disposed) error.value = cause instanceof Error ? cause.message : '配图状态读取失败，请重试'
  } finally {
    if (ticket === generation && !disposed) { loading.value = false; schedule() }
  }
}
async function generate() {
  if (!canGenerate.value || !visual.value?.interpretationRunId) return
  const runId = visual.value.interpretationRunId, ticket = ++generation, readingId = props.readingId
  stopPolling(); submitting.value = true; error.value = ''
  if (pendingRequest?.runId !== runId) pendingRequest = { runId, requestId: crypto.randomUUID() }
  try {
    const result = await generateTianrenImage(readingId, runId, pendingRequest.requestId)
    if (disposed || ticket !== generation) return
    applyVisual(result); pendingRequest = null
  } catch (cause) {
    if (ticket !== generation || disposed) return
    error.value = cause instanceof Error ? cause.message : '配图请求未确认，请重试'
    const status = (cause as { status?: number; response?: { status?: number } })?.status ?? (cause as { response?: { status?: number } })?.response?.status
    if (status === 409) { pendingRequest = null; void load() }
  } finally { if (!disposed && props.readingId === readingId) { submitting.value = false; schedule() } }
}
function onVisibility() { pageVisible.value = document.visibilityState !== 'hidden'; if (pageVisible.value && props.visible) void load(); else stopPolling() }
function openZoom(event: MouseEvent) { zoomTrigger = event.currentTarget as HTMLElement; zoomed.value = true }
function closeZoom() { zoomed.value = false; zoomTrigger?.focus() }
function imageFailed() { loadedAttempt = null; imageError.value = '图片暂时无法显示，请重新读取图片' }
function trapZoom(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); closeZoom() }
  if (event.key === 'Tab') { event.preventDefault(); zoomDialog.value?.querySelector<HTMLButtonElement>('button')?.focus() }
}
watch(zoomed, value => { if (value) void Promise.resolve().then(() => zoomDialog.value?.querySelector<HTMLButtonElement>('button')?.focus()) })
watch(() => props.readingId, () => { generation++; stopPolling(); releaseImage(); visual.value = null; pendingRequest = null; error.value = ''; imageError.value = ''; submitting.value = false; void load() }, { immediate: true })
watch(() => props.refreshKey, () => { releaseImage(); visual.value = null; pendingRequest = null; void load() })
watch(() => [props.visible, props.interpreting], () => { if (props.visible && !props.interpreting && pageVisible.value) void load(); else stopPolling() })
onMounted(() => document.addEventListener('visibilitychange', onVisibility))
onBeforeUnmount(() => { disposed = true; generation++; stopPolling(); releaseImage(); document.removeEventListener('visibilitychange', onVisibility) })
</script>

<template>
  <section class="visual-card" aria-label="当日卦意配图" :aria-busy="active || submitting">
    <header><span><i class="ri-image-2-line" aria-hidden="true"></i> 卦意配图</span><small>变与不变，一眼照见</small></header>
    <button v-if="imageUrl && !interpreting" class="image-button" type="button" aria-label="放大查看卦意配图" @click="openZoom">
      <img :src="imageUrl" :alt="visual?.title || '当日卦意配图'" @error="imageFailed" />
      <span class="zoom-hint"><i class="ri-zoom-in-line"></i> 放大</span>
    </button>
    <div v-else class="image-placeholder" role="status"><i :class="active || interpreting || loading ? 'ri-loader-4-line spin' : 'ri-landscape-line'"></i><span>{{ imageError || error || stateMessage }}</span><small v-if="active">关闭页面后仍会继续，重新打开可查看结果</small></div>
    <div v-if="visual?.title && !interpreting" class="visual-title">{{ visual.title }}</div>
    <div v-if="visual?.change && visual?.keep && !interpreting" class="meaning">
      <div><strong>变 · 调整行动</strong><p>{{ visual.change }}</p></div><div class="keep"><strong>不变 · 保持原则</strong><p>{{ visual.keep }}</p></div>
    </div>
    <footer><span role="status">{{ submitting ? '正在提交配图请求' : imageLoading ? '正在读取图片' : stateMessage }}</span><button v-if="canGenerate" type="button" @click="generate">{{ visual?.state === 'FAILED' ? '仅重试配图' : visual?.imageAttemptId ? '重新配图' : '生成配图' }}</button></footer>
    <div v-if="error || imageError" class="visual-error" role="alert"><span>{{ error || imageError }}</span><button v-if="imageError && visual?.imageAttemptId" type="button" :disabled="imageLoading" @click="loadImage(visual.imageAttemptId)">重新读取图片</button><button v-else type="button" :disabled="loading" @click="load">重新读取状态</button></div>
    <Teleport to="body"><div v-if="zoomed && imageUrl" class="zoom-overlay" @click.self="closeZoom"><section ref="zoomDialog" class="zoom-dialog" role="dialog" aria-modal="true" aria-label="卦意配图大图" @keydown="trapZoom"><header><strong>{{ visual?.title || '卦意配图' }}</strong><button type="button" aria-label="关闭大图" @click="closeZoom"><i class="ri-close-line"></i></button></header><img :src="imageUrl" :alt="visual?.title || '当日卦意配图'" /><p>配图用于理解本次解读中的变与不变。</p></section></div></Teleport>
  </section>
</template>

<style scoped>
.visual-card { min-width: 0; overflow: hidden; border: 1px solid var(--wao-line); background: var(--wao-bg-sub); border-radius: 13px; }
header, footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 9px 12px; font-size: 12px; } header small, footer { color: var(--wao-text-3); font-size: 11px; } footer { border-top: 1px solid var(--wao-line); flex-wrap: wrap; }
button { cursor: pointer; color: var(--wao-text); background: var(--wao-bg-card); border: 1px solid var(--wao-line); border-radius: 7px; padding: 4px 7px; font: inherit; font-size: 11px; } button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.image-button { display: block; position: relative; border: 0; border-radius: 0; padding: 0; width: 100%; } .image-button img { display: block; width: 100%; aspect-ratio: 2 / 1; object-fit: contain; } .zoom-hint { position: absolute; bottom: 8px; right: 8px; padding: 3px 7px; background: var(--wao-bg-card); border-radius: 6px; }
.image-placeholder { aspect-ratio: 2 / 1; box-sizing: border-box; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 16px; color: var(--wao-text-3); font-size: 12px; text-align: center; } .image-placeholder i { font-size: 25px; color: var(--wao-primary); } .image-placeholder small { font-size: 11px; }
.visual-title { font-weight: 600; font-size: 15px; padding: 10px 12px 0; overflow-wrap: anywhere; } .meaning { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; padding: 12px; font-size: 12px; line-height: 1.7; } .meaning strong { color: var(--wao-warning-strong); } .meaning .keep strong { color: var(--wao-success-strong, var(--wao-primary-text)); } .meaning p { margin: 4px 0 0; overflow-wrap: anywhere; } .visual-error { padding: 10px 12px; color: var(--wao-danger-strong); font-size: 12px; display: flex; gap: 8px; flex-wrap: wrap; }
.zoom-overlay { position: fixed; inset: 0; z-index: 1200; display: flex; align-items: center; justify-content: center; padding: 16px; background: #12223c70; } .zoom-dialog { width: min(100%, 1000px); max-height: calc(100dvh - 32px); overflow: auto; background: var(--wao-bg-card); color: var(--wao-text); border-radius: 14px; padding: 12px; box-sizing: border-box; } .zoom-dialog img { width: 100%; display: block; border-radius: 8px; } .zoom-dialog p { font-size: 12px; color: var(--wao-text-3); }
.spin { animation: spin .9s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } } @media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
