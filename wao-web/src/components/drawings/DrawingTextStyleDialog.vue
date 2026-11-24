<script setup lang="ts">
import { computed, ref } from 'vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { contrastingText, setTextRange, textSegments, type DrawingTextStyle } from '@/utils/drawingTextStyle.mjs'

const props = defineProps<{ value: DrawingTextStyle; strokeColor: string }>()
const emit = defineEmits<{ close: []; apply: [value: DrawingTextStyle] }>()
const original = props.value
const background = ref(original.background)
const runs = ref(original.runs)
const textarea = ref<HTMLTextAreaElement>()
const start = ref(0), end = ref(0)
const error = ref('')
const selection = computed(() => end.value > start.value)
const segments = computed(() => textSegments(original.source, runs.value))
const modified = computed(() => background.value !== original.background || JSON.stringify(runs.value) !== JSON.stringify(original.runs))
const backgrounds = [
  { name: '浅黄', hex: '#fff0a8' }, { name: '浅绿', hex: '#bee9cc' },
  { name: '浅蓝', hex: '#cce4ff' }, { name: '靛蓝', hex: '#243a70' }, { name: '深灰', hex: '#2b2e38' },
]
const colors = [
  { name: '红色', hex: '#c92a2a' }, { name: '橙色', hex: '#a95403' },
  { name: '绿色', hex: '#237a48' }, { name: '蓝色', hex: '#245eb5' },
  { name: '紫色', hex: '#7343b0' }, { name: '白色', hex: '#ffffff' }, { name: '黑色', hex: '#000000' },
]
function readSelection() {
  if (!textarea.value) return
  start.value = textarea.value.selectionStart
  end.value = textarea.value.selectionEnd
}
function changeRange(color?: string) {
  const next = setTextRange(runs.value, start.value, end.value, color)
  if (next.length > 256) {
    error.value = '此文本的颜色分段已达上限。请清除部分颜色后继续。'
    return
  }
  error.value = ''
  runs.value = next
}
function reset() {
  error.value = ''
  background.value = undefined
  runs.value = []
}
</script>

<template>
  <SettingsDialogShell title="文字高亮" icon="ri-mark-pen-line" width="540px" height="auto" @close="emit('close')">
    <div class="format-body">
      <div class="format-preview" :style="{ background: background || 'var(--wao-bg-sub)', color: background ? contrastingText(background) : strokeColor }" aria-label="文字样式预览">
        <span v-for="part in segments" :key="part.start" :style="{ color: part.color }">{{ part.text }}</span>
      </div>
      <fieldset>
        <legend>整块背景</legend>
        <div class="palette">
          <button v-for="item in backgrounds" :key="item.hex" type="button" class="swatch"
            :style="{ background: item.hex, color: contrastingText(item.hex) }"
            :aria-label="`背景${item.name}`" :title="item.name" :aria-pressed="background === item.hex" @click="background = item.hex">
            <i v-if="background === item.hex" class="ri-check-line" aria-hidden="true" />
          </button>
          <WaoButton variant="ghost" :disabled="!background" @click="background = undefined">清除背景</WaoButton>
        </div>
      </fieldset>
      <label class="selection-label">
        <span>选中要改色的文字</span>
        <textarea ref="textarea" :value="original.source" readonly rows="3" @select="readSelection" @pointerup="readSelection" @keyup="readSelection" />
      </label>
      <fieldset :disabled="!selection">
        <legend>选中文字颜色</legend>
        <div class="palette">
          <button v-for="item in colors" :key="item.hex" type="button" class="swatch"
            :style="{ background: item.hex }" :aria-label="`选中文字${item.name}`" :title="item.name"
            @mousedown.prevent @click="changeRange(item.hex)" />
          <WaoButton variant="ghost" @mousedown.prevent @click="changeRange()">清除选区颜色</WaoButton>
        </div>
      </fieldset>
      <p v-if="error" class="format-error" role="alert">{{ error }}</p>
    </div>
    <template #footer>
      <div class="format-footer">
        <WaoButton variant="ghost" :disabled="!background && !runs.length" @click="reset">清除高亮</WaoButton>
        <WaoButton variant="ghost" @click="emit('close')">取消</WaoButton>
        <WaoButton :disabled="!modified" @click="emit('apply', { source: original.source, background, runs })">应用</WaoButton>
      </div>
    </template>
  </SettingsDialogShell>
</template>

<style scoped>
.format-body { display: grid; gap: var(--wao-space-3); padding: var(--wao-space-3); min-width: 0; }
.format-preview { border-radius: var(--wao-radius-control); padding: var(--wao-space-2); white-space: pre-wrap; overflow-wrap: anywhere; max-height: 8em; overflow: auto; }
fieldset { min-width: 0; margin: 0; border: 0; padding: 0; }
legend, .selection-label { color: var(--wao-text-2); font-size: var(--wao-font-help); }
.palette, .format-footer { display: flex; flex-wrap: wrap; align-items: center; gap: var(--wao-space-1); }
.palette { margin-top: var(--wao-space-1); }
.selection-label { display: grid; gap: var(--wao-space-1); }
textarea { box-sizing: border-box; width: 100%; resize: vertical; padding: var(--wao-space-2); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text); font: inherit; }
.swatch { width: 44px; height: 44px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); cursor: pointer; }
.swatch[aria-pressed=true], .swatch:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 1px; }
fieldset:disabled .swatch { opacity: .45; cursor: default; }
.format-footer { justify-content: flex-end; }
.format-error { margin: 0; color: var(--wao-danger-strong); }
</style>
