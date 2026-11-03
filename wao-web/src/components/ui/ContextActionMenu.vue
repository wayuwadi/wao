<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import type { ContextAction } from '@/types/contextAction'

const menu = ref<HTMLElement | null>(null)
const open = ref(false)
const ready = ref(false)
const label = ref('')
const actions = ref<ContextAction[]>([])
const sections = computed(() => {
  const result: { key:string; title?:string; items:ContextAction[] }[] = []
  for (const item of actions.value) {
    const previous = result[result.length - 1]
    if (item.quickGroup && previous?.title === item.quickGroup) previous.items.push(item)
    else result.push({ key:item.id, title:item.quickGroup, items:[item] })
  }
  return result
})
const position = ref({ left: 0, top: 0 })
let trigger: HTMLElement | null = null
let anchored = false

function close(restoreFocus = false) {
  if (!open.value) return
  open.value = false
  ready.value = false
  window.removeEventListener('pointerdown', outside, true)
  window.removeEventListener('scroll', onScroll, true)
  window.removeEventListener('resize', onResize)
  if (restoreFocus && trigger?.isConnected) trigger.focus({ preventScroll: true })
  trigger = null
}

function outside(event: PointerEvent) {
  if (event.target instanceof Node && menu.value?.contains(event.target)) return
  close()
}
function onScroll(event: Event) {
  if (event.target instanceof Node && menu.value?.contains(event.target)) return
  if (anchored && trigger && menu.value) {
    const rect = trigger.getBoundingClientRect()
    const bounds = menu.value.getBoundingClientRect()
    position.value = {
      left: Math.max(8, Math.min(rect.left, window.innerWidth - bounds.width - 8)),
      top: Math.max(8, Math.min(rect.bottom, window.innerHeight - bounds.height - 8)),
    }
    return
  }
  close()
}
function onResize() { close() }

async function openAt(event: MouseEvent | KeyboardEvent, targetLabel: string, items: ContextAction[], anchor?: HTMLElement) {
  if (!items.length) return
  if (event instanceof MouseEvent) event.preventDefault()
  close()
  anchored = !!anchor
  trigger = anchor ?? (event.currentTarget instanceof HTMLElement ? event.currentTarget : null)
  const rect = trigger?.getBoundingClientRect()
  const keyboard = !!anchor || event instanceof KeyboardEvent
  position.value = {
    left: keyboard ? (rect?.left ?? 8) : event.clientX,
    top: keyboard ? (rect?.bottom ?? 8) : event.clientY,
  }
  label.value = targetLabel
  actions.value = items
  ready.value = false
  open.value = true
  window.addEventListener('pointerdown', outside, true)
  window.addEventListener('scroll', onScroll, true)
  window.addEventListener('resize', onResize)
  await nextTick()
  if (!open.value || !menu.value) return
  const bounds = menu.value.getBoundingClientRect()
  position.value = {
    left: Math.max(8, Math.min(position.value.left, window.innerWidth - bounds.width - 8)),
    top: Math.max(8, Math.min(position.value.top, window.innerHeight - bounds.height - 8)),
  }
  ready.value = true
  await nextTick()
  menu.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus({ preventScroll: true })
}

function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape' || event.key === 'Tab') {
    event.preventDefault()
    event.stopPropagation()
    close(true)
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const buttons = [...(menu.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  if (!buttons.length) return
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
    : (index + (['ArrowDown','ArrowRight'].includes(event.key) ? 1 : -1) + buttons.length) % buttons.length
  buttons[next]?.focus()
}

function choose(item: ContextAction) {
  if (item.disabled) return
  close(true)
  void item.run()
}

onBeforeUnmount(() => close())
defineExpose({ openAt, close })
</script>

<template>
  <Teleport to="body">
    <div v-if="open" ref="menu" class="wao-context-menu" role="menu" :aria-label="`${label}的操作`"
      :style="{ left: `${position.left}px`, top: `${position.top}px`, visibility: ready ? 'visible' : 'hidden' }"
      @keydown="keydown" @contextmenu.prevent>
      <div class="wao-context-label" aria-hidden="true">{{ label }}</div>
      <div v-for="section in sections" :key="section.key" :class="{'quick-section':section.title}">
       <div v-if="section.title" class="wao-context-label">{{section.title}}</div>
       <div :class="{'quick-actions':section.title}">
       <template v-for="item in section.items" :key="item.id">
        <div v-if="item.separatorBefore" class="wao-context-separator" role="separator"></div>
        <button type="button" :role="item.checked === undefined ? 'menuitem' : 'menuitemradio'" :aria-checked="item.checked" :aria-label="item.label" :title="item.label" :disabled="item.disabled" :class="{ danger: item.danger, checked:item.checked }" @click="choose(item)"><i v-if="item.icon" :class="item.icon" :style="{color:item.color}" aria-hidden="true" /><span v-if="!section.title">{{ item.label }}</span></button>
       </template>
       </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/* Teleport 到 body 后不再继承任务区域字号，浮层须明确使用同一正文基线。 */
.wao-context-menu { position: fixed; z-index: 3000; min-width: 190px; max-width: min(280px, calc(100vw - 16px)); max-height: calc(100dvh - 16px); overflow-y: auto; padding: 5px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop); font-family:var(--wao-font); font-size:var(--wao-font-body); font-weight:400; line-height:1.5; }
.wao-context-label { padding: 6px var(--wao-space-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--wao-text-3); font-size:var(--wao-font-caption); }
.wao-context-menu button { display: block; width: 100%; min-height: 34px; border: 0; border-radius:var(--wao-radius-control); padding: 6px var(--wao-space-1); background: transparent; color: var(--wao-text); font: inherit; text-align: left; cursor: pointer; }
.wao-context-menu button:hover, .wao-context-menu button:focus-visible { outline: none; background: var(--wao-bg-hover); }
.wao-context-menu button:disabled { opacity: .5; cursor: not-allowed; }
.wao-context-menu button.danger { color: var(--wao-danger-strong); }
.wao-context-separator { height: 1px; margin: 4px 5px; background: var(--wao-divider); }
.quick-actions { display:flex; justify-content:space-between; gap:4px; padding:0 4px var(--wao-space-1); }
.quick-actions button { display:inline-flex; align-items:center; justify-content:center; min-width:36px; width:auto; min-height:36px; padding:var(--wao-space-1); }
.wao-context-menu button.checked { background:var(--wao-primary-soft); }
.wao-context-menu button i { margin-right:var(--wao-space-1); }.quick-actions button i { margin:0; }
.quick-section + div:not(.quick-section) { border-top:1px solid var(--wao-line); padding-top:4px; }
@media(pointer:coarse) { .wao-context-menu button { min-height:44px; }.quick-actions button { min-width:40px; } }
</style>
