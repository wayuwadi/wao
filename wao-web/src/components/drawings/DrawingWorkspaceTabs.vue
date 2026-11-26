<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps<{
  tabs: { id: string; title: string }[]
  activeId: string
  panelId: string
  busy: boolean
}>()
const emit = defineEmits<{ select: [id: string]; close: [id: string] }>()
const strip = ref<HTMLElement>()
const focusedId = ref('')
const tabStop = computed(() => props.tabs.some(tab => tab.id === focusedId.value)
  ? focusedId.value : props.activeId || props.tabs[0]?.id)
let closingId = ''

function button(id: string) {
  return Array.from(strip.value?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? [])
    .find(tab => tab.dataset.drawingTab === id)
}

function reveal(id: string) {
  button(id)?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
}

function close(id: string) {
  if (props.busy) return
  closingId = strip.value?.contains(document.activeElement) ? id : ''
  emit('close', id)
}

function onKeydown(event: KeyboardEvent, index: number, id: string) {
  if (event.altKey || event.ctrlKey || event.metaKey || event.isComposing) return
  const count = props.tabs.length
  switch (event.key) {
    case 'ArrowLeft': index = (index + count - 1) % count; break
    case 'ArrowRight': index = (index + 1) % count; break
    case 'Home': index = 0; break
    case 'End': index = count - 1; break
    case 'Delete': event.preventDefault(); close(id); return
    default: return
  }
  event.preventDefault()
  button(props.tabs[index]!.id)?.focus()
}

watch(() => props.activeId, id => { void nextTick(() => reveal(id)) })
watch(() => props.tabs, async (tabs, previous) => {
  if (!closingId || tabs.some(tab => tab.id === closingId)) return
  const closedIndex = previous.findIndex(tab => tab.id === closingId)
  closingId = ''
  await nextTick()
  if (document.activeElement !== document.body) return
  const next = tabs[Math.min(closedIndex, tabs.length - 1)]
  if (next) button(next.id)?.focus()
})
</script>

<template>
  <div ref="strip" class="drawing-tabs" role="tablist" aria-label="草图页签"
    @focusout="!strip?.contains($event.relatedTarget as Node | null) && (focusedId = '')">
    <div v-for="(tab, index) in tabs" :key="tab.id" class="drawing-tab" role="presentation"
      :class="{ selected: activeId === tab.id }">
      <button :id="`${panelId}-${tab.id}`" type="button" role="tab" :data-drawing-tab="tab.id"
        :title="tab.title" :aria-selected="activeId === tab.id" :aria-controls="panelId" :aria-disabled="busy || undefined"
        :tabindex="tabStop === tab.id ? 0 : -1" aria-keyshortcuts="Delete"
        @focus="focusedId = tab.id" @keydown="onKeydown($event, index, tab.id)" @click="!busy && emit('select', tab.id)">
        <i class="ri-artboard-line" aria-hidden="true"></i><span>{{ tab.title }}</span>
      </button>
      <button type="button" class="tab-close" tabindex="-1" :aria-disabled="busy || undefined"
        :aria-label="`关闭 ${tab.title}`" :title="`关闭 ${tab.title}`" @click="close(tab.id)">
        <i class="ri-close-line" aria-hidden="true"></i>
      </button>
    </div>
  </div>
</template>

<style scoped>
.drawing-tabs {
  display: flex;
  flex-shrink: 0;
  gap: 4px;
  padding: 4px var(--wao-space-1);
  border-bottom: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
  overflow-x: auto;
  scrollbar-width: thin;
}
.drawing-tab {
  display: flex;
  flex: 0 0 auto;
  max-inline-size: min(15rem, 76vw);
  border-radius: var(--wao-radius-control);
}
.drawing-tab.selected {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.drawing-tab button {
  display: flex;
  align-items: center;
  gap: 4px;
  min-inline-size: 0;
  min-block-size: 32px;
  padding: 0 var(--wao-space-1);
  border: 0;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: var(--wao-font-body);
  cursor: pointer;
}
.drawing-tab button:hover { background: var(--wao-bg-hover); }
.drawing-tab button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: -2px; }
.drawing-tab button[aria-disabled='true'] { cursor: wait; }
.drawing-tab span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.drawing-tab .tab-close { flex-shrink: 0; justify-content: center; inline-size: 32px; }
@media (pointer: coarse) {
  .drawing-tab button { min-block-size: 44px; }
  .drawing-tab .tab-close { inline-size: 44px; }
}
</style>
