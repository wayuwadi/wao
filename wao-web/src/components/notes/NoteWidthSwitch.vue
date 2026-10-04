<script setup lang="ts">
import type { NoteWritingWidth } from '@/composables/noteWritingWidth'
withDefaults(defineProps<{ modelValue: NoteWritingWidth; label?: string }>(), { label: '书写宽度' })
const emit = defineEmits<{ 'update:modelValue': [value: NoteWritingWidth] }>()
</script>

<template>
  <div class="note-width-switch" role="group" :aria-label="label">
    <button type="button" :aria-pressed="modelValue === 'wide'" @mousedown.prevent @click="emit('update:modelValue', 'wide')">宽屏</button>
    <button type="button" :aria-pressed="modelValue === 'narrow'" @mousedown.prevent @click="emit('update:modelValue', 'narrow')">窄屏</button>
  </div>
</template>

<style scoped>
.note-width-switch { display: inline-flex; gap: 2px; padding: 2px; flex-shrink: 0; border-radius: 8px; background: var(--wao-primary-soft); }
button { border: 0; background: transparent; padding: 3px 8px; font-family: inherit; font-size: 12px; line-height: 1.5; color: var(--wao-text-3); border-radius: 6px; cursor: pointer; }
button[aria-pressed="true"] { background: var(--wao-bg-card); color: var(--wao-primary-text); box-shadow: 0 1px 4px color-mix(in srgb, var(--wao-text-1) 8%, transparent); }
button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
@media (pointer: coarse) { button { min-height: 44px; min-width: 44px; } }
</style>
