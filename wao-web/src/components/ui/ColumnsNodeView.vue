<script setup lang="ts">
import { NodeViewWrapper, NodeViewContent, type NodeViewProps } from '@tiptap/vue-3'
const props = defineProps<NodeViewProps>()
function addColumn() {
  const position = props.getPos()
  if (!props.editor.isEditable || position == null || props.node.childCount >= 3) return
  const column = props.editor.schema.nodes.column!.createAndFill()
  if (!column) return
  props.editor.view.dispatch(props.editor.state.tr.insert(position + props.node.nodeSize - 1, column))
  props.editor.commands.focus()
}
</script>
<template>
  <NodeViewWrapper class="editor-columns" :data-columns="node.childCount">
    <NodeViewContent class="editor-columns-content" :style="{ '--column-count': node.childCount }" />
    <button v-if="editor.isEditable && node.childCount === 2" class="column-add" type="button" contenteditable="false" aria-label="添加第三栏" title="添加第三栏" @mousedown.prevent @click="addColumn"><i class="ri-add-line" /></button>
  </NodeViewWrapper>
</template>
<style scoped>
.editor-columns { position:relative; margin:var(--wao-space-2) 0; padding-right:24px; }
.editor-columns-content { display:grid; grid-template-columns:repeat(var(--column-count),minmax(0,1fr)); gap:var(--wao-space-2); }
.editor-columns-content :deep(section[data-column]) { min-width:0; min-height:80px; padding:var(--wao-space-1); border:1px dashed var(--wao-line-control); border-radius:var(--wao-radius-control); }
.column-add { position:absolute; right:0; top:0; width:24px; min-height:44px; border:0; background:var(--wao-bg-sub); color:var(--wao-text-3); border-radius:var(--wao-radius-control); cursor:pointer; }
.column-add:hover,.column-add:focus-visible { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
@media(max-width:600px) { .editor-columns-content { grid-template-columns:1fr; } }
</style>
