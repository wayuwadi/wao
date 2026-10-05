<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { NotePreviewImage } from '@/utils/notePreviewImages'
const props = defineProps<{ images: NotePreviewImage[]; index: number }>()
const emit = defineEmits<{ change: [index: number] }>()
const root = ref<HTMLElement>()
function move(delta: number) {
  if (props.images.length > 1) emit('change', (props.index + delta + props.images.length) % props.images.length)
}
function keys(event: KeyboardEvent) {
  if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
  const dialog = root.value?.closest('[role="dialog"]')
  if (!dialog?.contains(document.activeElement)) return
  if (!['ArrowLeft', 'ArrowUp', 'ArrowRight', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
  event.preventDefault(); event.stopPropagation()
  if (event.key === 'Home') emit('change', 0)
  else if (event.key === 'End') emit('change', props.images.length - 1)
  else move(event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 1)
}
onMounted(() => window.addEventListener('keydown', keys, true))
onBeforeUnmount(() => window.removeEventListener('keydown', keys, true))
</script>
<template>
  <nav ref="root" class="image-navigation" aria-label="图片切换">
    <button type="button" aria-label="上一张图片（← / ↑）" :disabled="images.length < 2" @click="move(-1)"><i class="ri-arrow-left-line" aria-hidden="true"></i></button>
    <span role="status" aria-live="polite">{{ index + 1 }} / {{ images.length }}<small v-if="images.length > 1">方向键切换</small></span>
    <button type="button" aria-label="下一张图片（→ / ↓）" :disabled="images.length < 2" @click="move(1)"><i class="ri-arrow-right-line" aria-hidden="true"></i></button>
  </nav>
</template>
<style scoped>
.image-navigation{position:absolute;bottom:max(20px,env(safe-area-inset-bottom));left:50%;transform:translateX(-50%);display:flex;align-items:center;gap:20px;padding:6px 10px;border-radius:14px;background:rgb(20 24 42 / .8);color:#fff;font-size:14px;z-index:2}
.image-navigation button{position:static!important;inset:auto!important;width:44px;height:44px;padding:0;border:0;border-radius:8px;background:transparent;color:#fff;cursor:pointer;display:grid;place-items:center;font-size:20px}
button:disabled{opacity:.3;cursor:default}button:focus-visible{outline:2px solid #c7cbff;outline-offset:2px}span{min-width:68px;text-align:center;white-space:nowrap}small{display:block;font-size:10px;opacity:.7;margin-top:3px}
</style>
