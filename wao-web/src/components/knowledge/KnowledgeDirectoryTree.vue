<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import type { DirectoryNode } from '@/utils/knowledgeDirectory'

const props = withDefaults(defineProps<{
  nodes: DirectoryNode[]
  expanded: Record<string, boolean>
  label: string
  searching?: boolean
  iconOf?: (id: string) => string
  density?: 'compact' | 'comfortable'
}>(), { searching: false, density: 'compact' })
const emit = defineEmits<{
  open: [id: string]
  toggle: [key: string, expanded: boolean]
  preload: [id: string]
}>()
const tree = ref<HTMLElement | null>(null)
const focused = ref('')
const renderLimit = ref(500)
const visibleNodes = computed(() => props.nodes.slice(0, renderLimit.value))
const siblingPositions = computed(() => {
  const totals = new Map<string, number>(), positions = new Map<string, number>()
  for (const node of props.nodes) totals.set(node.parent, (totals.get(node.parent) ?? 0) + 1)
  return new Map(props.nodes.map(node => {
    const position = (positions.get(node.parent) ?? 0) + 1
    positions.set(node.parent, position)
    return [node.key, { position, size: totals.get(node.parent)! }]
  }))
})
let typeAhead = '', lastTyped = 0
const isExpanded = (node: DirectoryNode) => props.searching || props.expanded[node.key] !== false

watch([() => props.label, () => props.searching], () => { renderLimit.value = 500 })
watch(visibleNodes, nodes => {
  if (!nodes.some(node => node.key === focused.value)) {
    const restoreFocus = !!tree.value?.contains(document.activeElement)
    focused.value = nodes[0]?.key ?? ''
    if (restoreFocus && focused.value) void focusNode(focused.value)
  }
}, { immediate: true })

function showMore() {
  const next = props.nodes[renderLimit.value]
  renderLimit.value += 500
  if (next) void focusNode(next.key)
}

async function focusNode(key: string) {
  focused.value = key
  await nextTick()
  const element = Array.from(tree.value?.querySelectorAll<HTMLElement>('[data-tree-key]') ?? [])
    .find(item => item.dataset.treeKey === key)
  element?.focus({ preventScroll: true })
  element?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
}
function toggle(node: DirectoryNode) {
  if (node.hasChildren && !props.searching) emit('toggle', node.key, !isExpanded(node))
}
function activate(node: DirectoryNode) {
  if (node.kind === 'folder') toggle(node)
  else emit('open', node.id)
}
function preload(node: DirectoryNode) { if (node.kind === 'doc') emit('preload', node.id) }
function onFocus(node: DirectoryNode) { focused.value = node.key; preload(node) }
function onKey(event: KeyboardEvent, node: DirectoryNode) {
  if (event.isComposing || event.altKey || event.ctrlKey || event.metaKey) return
  const index = visibleNodes.value.findIndex(item => item.key === node.key)
  let target: DirectoryNode | undefined
  switch (event.key) {
    case 'ArrowDown': target = visibleNodes.value[Math.min(index + 1, visibleNodes.value.length - 1)]; break
    case 'ArrowUp': target = visibleNodes.value[Math.max(index - 1, 0)]; break
    case 'Home': target = visibleNodes.value[0]; break
    case 'End': target = visibleNodes.value[visibleNodes.value.length - 1]; break
    case 'ArrowRight':
      if (node.hasChildren && !isExpanded(node)) emit('toggle', node.key, true)
      else if (props.nodes[index + 1]?.parent === node.key) {
        if (index + 1 >= visibleNodes.value.length) renderLimit.value += 500
        target = props.nodes[index + 1]
      }
      break
    case 'ArrowLeft':
      if (node.hasChildren && isExpanded(node) && !props.searching) emit('toggle', node.key, false)
      else target = visibleNodes.value.find(item => item.key === node.parent)
      break
    default:
      if (event.key.length !== 1 || event.key === ' ') return
      typeAhead = Date.now() - lastTyped < 600 ? typeAhead + event.key : event.key
      lastTyped = Date.now()
      target = [...visibleNodes.value.slice(index + 1), ...visibleNodes.value.slice(0, index + 1)]
        .find(item => item.title.toLocaleLowerCase().startsWith(typeAhead.toLocaleLowerCase()))
  }
  event.preventDefault()
  if (target) void focusNode(target.key)
}
</script>

<template>
  <div class="knowledge-directory">
  <div ref="tree" class="knowledge-tree" role="tree" :aria-label="label">
    <button v-for="node in visibleNodes" :key="node.key" type="button" class="tree-node" :class="{ 'tree-node--comfortable': density === 'comfortable' }"
      role="treeitem" :aria-level="node.depth + 1" :aria-expanded="node.hasChildren ? isExpanded(node) : undefined"
      :aria-posinset="siblingPositions.get(node.key)?.position" :aria-setsize="siblingPositions.get(node.key)?.size"
      :tabindex="focused === node.key ? 0 : -1" :data-tree-key="node.key" :title="node.title"
      :style="{ '--tree-depth': Math.min(node.depth, 8) }"
      @focus="onFocus(node)" @pointerenter="preload(node)" @click="activate(node)" @keydown="onKey($event, node)">
      <span class="tree-caret" aria-hidden="true" @click.stop="toggle(node)">
        <i v-if="node.hasChildren" :class="isExpanded(node) ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'" />
      </span>
      <i class="node-icon" :class="node.kind === 'folder' ? (isExpanded(node) ? 'ri-folder-open-line' : 'ri-folder-line') : (iconOf?.(node.id) || 'ri-file-text-line')" aria-hidden="true" />
      <span class="node-title"><slot name="title" :node="node">{{ node.title }}</slot></span>
      <slot name="suffix" :node="node" />
    </button>
  </div>
  <WaoButton v-if="nodes.length > renderLimit" variant="ghost" class="directory-more" @click="showMore">显示更多目录（剩余 {{ nodes.length - renderLimit }} 项）</WaoButton>
  </div>
</template>

<style scoped>
.knowledge-tree { display: grid; gap: 2px; min-width: 0; }
.knowledge-directory { min-width: 0; }
.directory-more { width: 100%; margin-top: var(--wao-space-1); padding-inline: var(--wao-space-1); font-size: var(--wao-font-help); }
.tree-node { display: flex; align-items: center; width: 100%; min-width: 0; min-height: 34px; gap: 6px; padding: 4px 6px 4px calc(4px + var(--tree-depth) * 16px); border: 0; border-radius: var(--wao-radius-control); background: transparent; color: var(--wao-text-2); font: inherit; font-size: var(--wao-font-body); text-align: left; cursor: pointer; }
.tree-node--comfortable { min-height: 40px; gap: var(--wao-space-1); }
.tree-node:hover { background: var(--wao-bg-hover); color: var(--wao-text); }
.tree-node:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: -2px; background: var(--wao-primary-soft); }
.tree-caret { display: inline-flex; align-items: center; justify-content: center; width: 16px; min-height: 24px; flex: 0 0 16px; }
.node-icon { flex: 0 0 auto; color: var(--wao-text-3); }
.node-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (pointer: coarse) { .tree-node { min-height: 44px; } }
</style>
