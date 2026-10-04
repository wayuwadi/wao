<script setup lang="ts">
import type { NoteImageMode } from '@/composables/noteImageMode'
defineProps<{ modelValue: NoteImageMode }>()
const emit = defineEmits<{ 'update:modelValue': [value: NoteImageMode] }>()
</script>

<template>
  <div class="note-image-mode-switch" role="group" aria-label="编辑图片显示模式">
    <button type="button" :aria-pressed="modelValue === 'references'" title="图片在右侧，正文保留图号引用" @mousedown.prevent @click="emit('update:modelValue', 'references')">预览引入</button>
    <button type="button" :aria-pressed="modelValue === 'embedded'" title="图片直接显示在正文原位置" @mousedown.prevent @click="emit('update:modelValue', 'embedded')">图片嵌入</button>
  </div>
</template>

<style scoped>
.note-image-mode-switch { display: inline-flex; flex-shrink: 0; gap: 2px; padding: 2px; margin-left: auto; border-radius: 8px; background: var(--wao-primary-soft); }
button { border: 0; padding: 3px 8px; border-radius: 6px; background: transparent; font: inherit; font-size: 12px; line-height: 1.5; color: var(--wao-text-3); white-space: nowrap; cursor: pointer; }
button[aria-pressed='true'] { background: var(--wao-bg-card); color: var(--wao-primary-text); box-shadow: 0 1px 4px color-mix(in srgb, var(--wao-text-1) 8%, transparent); }
button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
@media (pointer: coarse) { button { min-height: 44px; } }
</style>
