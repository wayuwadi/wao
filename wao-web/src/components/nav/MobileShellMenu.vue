<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLElement>()
const closeButton = ref<HTMLButtonElement>()
useOverlayDismiss(() => props.open, () => emit('close'))
watch(() => props.open, async (open) => {
  if (open) { await nextTick(); closeButton.value?.focus() }
})
function trapTab(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const nodes = [...(panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), [tabindex="0"]') ?? [])]
    .filter(node => node.getClientRects().length && !node.closest('[inert]'))
  const first = nodes[0], last = nodes[nodes.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
</script>

<template>
  <div v-show="open" class="mobile-menu-backdrop" @click.self="emit('close')">
    <section ref="panel" class="mobile-menu-panel" role="dialog" aria-modal="true" aria-labelledby="mobile-menu-title" @keydown="trapTab">
      <div class="mobile-menu-heading">
        <strong id="mobile-menu-title">菜单与工具</strong>
        <button ref="closeButton" aria-label="关闭完整菜单" @click="emit('close')"><i class="ri-close-line" aria-hidden="true"></i></button>
      </div>
      <div id="mobile-tools-slot"></div>
      <div id="mobile-navigation-slot"></div>
    </section>
  </div>
</template>

<style scoped>
.mobile-menu-backdrop { position: fixed; inset: 0; z-index: 90; background: rgb(16 18 28 / 24%); display: flex; align-items: flex-end; }
.mobile-menu-panel { width: 100%; max-height: calc(100dvh - 20px - env(safe-area-inset-top)); height: 720px; display: flex; flex-direction: column; overflow: hidden; background: var(--wao-bg-card); border-radius: 14px 14px 0 0; padding-bottom: env(safe-area-inset-bottom); box-sizing: border-box; }
.mobile-menu-heading { flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; padding: 8px 12px; border-bottom: 1px solid var(--wao-line); }
.mobile-menu-heading strong { font-size: 15px; }
.mobile-menu-heading button { width: 40px; height: 40px; border: 0; border-radius: 8px; background: transparent; color: var(--wao-text-3); font-size: 20px; cursor: pointer; }
.mobile-menu-heading button:focus-visible { outline: 2px solid var(--wao-primary); }
#mobile-tools-slot { flex-shrink: 0; }
#mobile-navigation-slot { flex: 1; min-height: 0; }
</style>
