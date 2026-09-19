<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
defineProps<{ label: string; icon?: string; items: { id: string; label: string; icon?: string; danger?: boolean }[] }>()
const emit = defineEmits<{ select: [id: string] }>()
const open = ref(false)
const trigger = ref<HTMLButtonElement | null>(null)
const panel = ref<HTMLElement | null>(null)
const position = ref({ left: '0px', top: '0px' })
function close() { open.value = false }
async function toggle() {
  if (open.value) { close(); return }
  const rect = trigger.value!.getBoundingClientRect()
  position.value = { left: `${Math.max(8, Math.min(rect.left, window.innerWidth - 228))}px`, top: `${Math.max(8, Math.min(rect.bottom + 5, window.innerHeight - 310))}px` }
  open.value = true
  await nextTick()
  panel.value?.querySelector<HTMLButtonElement>('button')?.focus()
}
function choose(id: string) { close(); emit('select', id); trigger.value?.focus() }
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { close(); trigger.value?.focus(); return }
  if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return
  event.preventDefault()
  const buttons = Array.from(panel.value?.querySelectorAll<HTMLButtonElement>('button') ?? [])
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
  buttons[(index + (event.key === 'ArrowDown' ? 1 : buttons.length - 1)) % buttons.length]?.focus()
}
window.addEventListener('resize', close)
onBeforeUnmount(() => window.removeEventListener('resize', close))
</script>
<template>
  <button ref="trigger" class="resource-trigger" :title="label" :aria-label="label" aria-haspopup="menu" :aria-expanded="open" @click.stop="toggle"><i :class="icon || 'ri-more-fill'"></i></button>
  <Teleport to="body">
    <template v-if="open">
      <div class="resource-mask" @click="close"></div>
      <div ref="panel" class="resource-menu" :style="position" role="menu" :aria-label="label" @keydown="keydown">
        <button v-for="item in items" :key="item.id" role="menuitem" :class="{ danger: item.danger }" @click="choose(item.id)"><i :class="item.icon"></i>{{ item.label }}</button>
      </div>
    </template>
  </Teleport>
</template>
<style scoped>
.resource-trigger { flex: 0 0 28px; width: 28px; height: 28px; border: 0; border-radius: 6px; color: var(--wao-text-2); background: transparent; cursor: pointer; }
.resource-trigger:hover { background: var(--wao-bg-hover); }
.resource-mask { position: fixed; inset: 0; z-index: 1000; }
.resource-menu { position: fixed; z-index: 1001; width: 220px; max-height: calc(100dvh - 24px); overflow: auto; padding: 6px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); box-shadow: 0 8px 30px #0002; }
.resource-menu button { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 38px; border: 0; border-radius: 6px; padding: 8px 12px; background: transparent; color: var(--wao-text); cursor: pointer; text-align: left; }
.resource-menu button:hover,.resource-menu button:focus-visible { background: var(--wao-bg-hover); }
.resource-menu .danger { color: var(--wao-danger-strong); }
</style>
