<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
const props = defineProps<{ label: string; storageKey: string; defaultWidth: number; min?: number; max?: number }>()
const emit = defineEmits<{ resize: [width: number] }>()
const minimum = computed(() => props.min ?? 200)
const maximum = computed(() => props.max ?? 480)
const width = ref(props.defaultWidth)
const dragging = ref(false)
let startX = 0
let startWidth = 0
let active: HTMLElement | null = null
let pointer = -1
function setWidth(value: number, persist = true) {
  width.value = Math.round(Math.max(minimum.value, Math.min(maximum.value, value)))
  emit('resize', width.value)
  if (persist) { try { localStorage.setItem(props.storageKey, String(width.value)) } catch { /* Session-only sizing remains available. */ } }
}
watch(() => props.storageKey, () => {
  stop()
  let stored = NaN
  try { const raw = localStorage.getItem(props.storageKey); if (raw) stored = Number(raw) } catch { /* Use default. */ }
  setWidth(Number.isFinite(stored) ? stored : props.defaultWidth, false)
}, { immediate: true })
function start(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  active = event.currentTarget as HTMLElement
  pointer = event.pointerId
  startX = event.clientX
  startWidth = active.parentElement?.getBoundingClientRect().width || width.value
  active.setPointerCapture?.(pointer)
  dragging.value = true
}
function move(event: PointerEvent) {
  if (dragging.value && event.pointerId === pointer) setWidth(startWidth + event.clientX - startX)
}
function stop() {
  if (active?.hasPointerCapture?.(pointer)) active.releasePointerCapture(pointer)
  active = null
  pointer = -1
  dragging.value = false
}
function keydown(event: KeyboardEvent) {
  const value = event.key === 'ArrowRight' ? width.value + 16 : event.key === 'ArrowLeft' ? width.value - 16 : event.key === 'Home' ? props.defaultWidth : null
  if (value == null) return
  event.preventDefault()
  setWidth(value)
}
onBeforeUnmount(stop)
</script>

<template>
  <div class="pane-resize-handle" :class="{ dragging }" role="separator" tabindex="0"
    aria-orientation="vertical" :aria-label="label" :aria-valuemin="minimum" :aria-valuemax="maximum" :aria-valuenow="width"
    title="拖动调整宽度，双击恢复；方向键调整，Home 恢复"
    @pointerdown="start" @pointermove="move" @pointerup="stop" @pointercancel="stop" @lostpointercapture="stop"
    @keydown="keydown" @dblclick="setWidth(defaultWidth)" />
</template>

<style scoped>
.pane-resize-handle { position: absolute; inset: 0 0 0 auto; width: 8px; z-index: 10; cursor: col-resize; touch-action: none; user-select: none; }
.pane-resize-handle::after { content: ''; position: absolute; inset: 0 0 0 auto; width: 2px; background: transparent; }
.pane-resize-handle:hover::after, .pane-resize-handle:focus-visible::after, .pane-resize-handle.dragging::after { background: var(--wao-primary); }
.pane-resize-handle:focus-visible { outline: 1px solid var(--wao-primary); outline-offset: -1px; }
@media (max-width: 760px) { .pane-resize-handle { display: none; } }
</style>
