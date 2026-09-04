<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import DOMPurify from 'dompurify'

const props = defineProps<NodeViewProps>()
const svg = ref('')
const error = ref('')
const editing = ref(false)
const draft = ref(String(props.node.attrs.source ?? ''))

async function render() {
  const source = String(props.node.attrs.source ?? '')
  draft.value = source
  if (!source.trim() || source.length > 20_000) {
    error.value = source.length > 20_000 ? '图形源码超过 20000 字符' : '请输入图形源码'
    svg.value = ''
    return
  }
  try {
    const mermaid = (await import('mermaid')).default
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', htmlLabels: false, maxTextSize: 20_000, maxEdges: 500 })
    const rendered = await mermaid.render(`wao-mermaid-${Math.random().toString(36).slice(2)}`, source)
    svg.value = DOMPurify.sanitize(rendered.svg, { USE_PROFILES: { svg: true, svgFilters: true } })
    error.value = ''
  } catch (cause) {
    svg.value = ''
    error.value = cause instanceof Error ? cause.message : '图形语法无法解析'
  }
}
function save() {
  props.updateAttributes({ source: draft.value })
  editing.value = false
}
watch(() => props.node.attrs.source, render)
onMounted(render)
</script>

<template>
  <NodeViewWrapper class="mermaid-node" contenteditable="false">
    <div class="mermaid-head"><b>流程图</b><button v-if="props.editor.isEditable" type="button" @click="editing = !editing">{{ editing ? '取消' : '编辑源码' }}</button></div>
    <form v-if="editing" @submit.prevent="save"><textarea v-model="draft" rows="9" spellcheck="false"></textarea><button>应用并预览</button></form>
    <div v-else-if="svg" class="mermaid-svg" v-html="svg"></div>
    <pre v-else><code>{{ props.node.attrs.source }}</code></pre>
    <p v-if="error" role="alert">{{ error }}</p>
  </NodeViewWrapper>
</template>

<style scoped>
.mermaid-node{margin:14px 0;border:1px solid var(--wao-line-control);border-radius:12px;background:var(--wao-bg-soft);padding:10px}.mermaid-node.ProseMirror-selectednode{outline:2px solid var(--wao-primary)}.mermaid-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}.mermaid-head button,form button{border:0;border-radius:7px;padding:5px 9px;background:var(--wao-primary-soft);color:var(--wao-primary);cursor:pointer}textarea{width:100%;box-sizing:border-box;border:1px solid var(--wao-line-control);border-radius:8px;padding:9px;font:12px/1.55 ui-monospace,monospace;background:var(--wao-bg-card);color:var(--wao-text)}form button{margin-top:8px}.mermaid-svg{overflow:auto;text-align:center}.mermaid-svg :deep(svg){max-width:100%;height:auto}pre{overflow:auto;margin:0}p{color:var(--wao-danger-strong);font-size:12px;word-break:break-word}
</style>
