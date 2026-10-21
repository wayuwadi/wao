<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NodeViewContent, NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import { codeLanguages } from './codeHighlight'

const props = defineProps<NodeViewProps>()
const wrap = ref(false)
const copyState = ref('复制')
const language = computed(() => String(props.node.attrs.language || ''))
watch(() => props.node.textContent, () => { copyState.value = '复制' })
const unknownLanguage = computed(() => language.value && !codeLanguages.includes(language.value))
function changeLanguage(event: Event) {
  props.updateAttributes({ language: (event.target as HTMLSelectElement).value || null })
}
async function copy() {
  try {
    await navigator.clipboard.writeText(props.node.textContent)
    copyState.value = '已复制'
  } catch {
    copyState.value = '复制失败，请重试'
  }
}
</script>

<template>
  <NodeViewWrapper class="wao-code-block" :class="{ 'code-wrap': wrap }">
    <div class="code-block-head" contenteditable="false">
      <select v-if="editor.isEditable" aria-label="代码语言" :value="language" @change="changeLanguage">
        <option value="">自动识别</option>
        <option v-if="unknownLanguage" :value="language">{{ language }}</option>
        <option v-for="item in codeLanguages" :key="item" :value="item">{{ item === 'plaintext' ? '纯文本' : item === 'sql' ? 'SQL' : item }}</option>
      </select>
      <span v-else>{{ language || '自动识别' }}</span>
      <div class="code-block-actions">
        <button type="button" :aria-pressed="wrap" @click="wrap = !wrap">自动换行</button>
        <button type="button" aria-label="复制代码" aria-live="polite" @click="copy">{{ copyState }}</button>
      </div>
    </div>
    <pre class="code-block-pre" spellcheck="false"><NodeViewContent as="code" :style="{ whiteSpace: wrap ? 'pre-wrap' : 'pre' }" /></pre>
  </NodeViewWrapper>
</template>

<style scoped>
.wao-code-block { margin: var(--wao-space-2) 0; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-sub); color: var(--wao-text); overflow: hidden; }
.code-block-head { display: flex; align-items: center; justify-content: space-between; gap: var(--wao-space-1); padding: var(--wao-space-1); border-bottom: 1px solid var(--wao-line); font: 12px/1.5 var(--wao-font); }
.code-block-head select, .code-block-head button { font: inherit; color: var(--wao-text-2); background: transparent; border: 1px solid transparent; border-radius: var(--wao-radius-control); min-height: 32px; padding: 4px 8px; cursor: pointer; }
.code-block-head select { min-width: 0; max-width: 45%; background: var(--wao-bg-sub); }
.code-block-actions { display: flex; flex-shrink: 0; gap: 4px; }
.code-block-head button:hover, .code-block-head button[aria-pressed='true'] { color: var(--wao-primary-text); background: var(--wao-primary-soft); }
.code-block-head :focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 1px; }
.wao-code-block pre { margin: 0; padding: var(--wao-space-2); border: 0; border-radius: 0; overflow-x: auto; background: transparent; }
.wao-code-block pre code { display: block; font: 14px/1.7 var(--wao-font-mono); white-space: pre; color: var(--wao-text); background: transparent; padding: 0; tab-size: 2; }
.wao-code-block.code-wrap pre code { white-space: pre-wrap; overflow-wrap: anywhere; }
@media (pointer: coarse) { .code-block-head select, .code-block-head button { min-height: 44px; } }
</style>
