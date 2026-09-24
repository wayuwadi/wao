<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import type { ContextAction } from '@/types/contextAction'

const menu = ref<HTMLElement | null>(null)
const open = ref(false)
const ready = ref(false)
const label = ref('')
const actions = ref<ContextAction[]>([])
const position = ref({ left: 0, top: 0 })
let trigger: HTMLElement | null = null

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
  close()
}
function onResize() { close() }

async function openAt(event: MouseEvent | KeyboardEvent, targetLabel: string, items: ContextAction[]) {
  if (!items.length) return
  if (event instanceof MouseEvent) event.preventDefault()
  close()
  trigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  const rect = trigger?.getBoundingClientRect()
  const keyboard = event instanceof KeyboardEvent
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
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const buttons = [...(menu.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  if (!buttons.length) return
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
    : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
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
      <template v-for="item in actions" :key="item.id">
        <div v-if="item.separatorBefore" class="wao-context-separator" role="separator"></div>
        <button type="button" role="menuitem" :disabled="item.disabled" :class="{ danger: item.danger }" @click="choose(item)">{{ item.label }}</button>
      </template>
    </div>
  </Teleport>
</template>

<style scoped>
.wao-context-menu { position: fixed; z-index: 3000; min-width: 190px; max-width: min(280px, calc(100vw - 16px)); max-height: calc(100dvh - 16px); overflow-y: auto; padding: 5px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop); }
.wao-context-label { padding: 6px 9px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--wao-text-3); font-size: 12px; }
.wao-context-menu button { display: block; width: 100%; min-height: 34px; border: 0; border-radius: 6px; padding: 6px 9px; background: transparent; color: var(--wao-text); font: inherit; text-align: left; cursor: pointer; }
.wao-context-menu button:hover, .wao-context-menu button:focus-visible { outline: none; background: var(--wao-bg-hover); }
.wao-context-menu button:disabled { opacity: .5; cursor: not-allowed; }
.wao-context-menu button.danger { color: var(--wao-danger-strong); }
.wao-context-separator { height: 1px; margin: 4px 5px; background: var(--wao-divider); }
</style>
