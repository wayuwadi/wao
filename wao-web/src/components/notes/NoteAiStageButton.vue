<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
const props = defineProps<{ holdEnabled?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ activate: []; four: []; charging: [value: boolean] }>()
const progress = ref(0)
const holding = ref(false)
let started = 0
let suppressClick = false
let timer: ReturnType<typeof setTimeout> | undefined
let ticker: ReturnType<typeof setInterval> | undefined
function clearTimers() { clearTimeout(timer); clearInterval(ticker); timer = undefined; ticker = undefined }
function start(event?: PointerEvent) {
  if (!props.holdEnabled || props.disabled || holding.value || event && event.button !== 0) return
  started = Date.now(); suppressClick = false; progress.value = 0; holding.value = true
  emit('charging', true)
  ticker = setInterval(() => progress.value = Math.min(1, (Date.now() - started) / 3000), 30)
  timer = setTimeout(() => {
    clearTimers(); progress.value = 1; suppressClick = true
    emit('four'); emit('charging', false)
  }, 3000)
}
function release(cancelled = false) {
  if (!holding.value) return
  suppressClick ||= cancelled || Date.now() - started >= 250
  clearTimers(); holding.value = false; progress.value = 0; emit('charging', false)
}
function click() { if (!suppressClick && !props.disabled) emit('activate') }
function spaceRelease() { release(); click() }
function enter() { release(true); suppressClick = false; if (!props.disabled) emit('activate') }
function cancel() { release(true) }
onMounted(() => { window.addEventListener('blur', cancel); window.addEventListener('pointerup', cancel) })
onBeforeUnmount(() => { clearTimers(); window.removeEventListener('blur', cancel); window.removeEventListener('pointerup', cancel) })
</script>

<template>
  <button type="button" class="stage-icon" :class="{ charging: holding }" :disabled="disabled" :style="holding ? { '--charge': `${progress * 360}deg` } : undefined"
    @pointerdown="start" @pointerup="release()" @pointercancel="cancel" @pointerleave="cancel" @blur="cancel" @click.stop="click" @keydown.enter.stop.prevent="enter" @keydown.space.stop.prevent="start()" @keyup.space.stop.prevent="spaceRelease" @keydown.esc.stop.prevent="cancel">
    <slot />
  </button>
</template>

<style scoped>
.charging { position:relative; transform:scale(1.15); }
.charging::after { content:''; position:absolute; inset:-4px; border-radius:50%; background:conic-gradient(var(--wao-primary) var(--charge), transparent 0); mask:radial-gradient(transparent 55%,black 57%); pointer-events:none; }
@media (prefers-reduced-motion: reduce) { .charging { transform:none; } }
</style>
