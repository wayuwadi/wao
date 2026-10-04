<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLElement>()
const closeButton = ref<HTMLButtonElement>()
const standalone = window.matchMedia?.('(display-mode: standalone)').matches
  || (navigator as Navigator & { standalone?: boolean }).standalone === true
useOverlayDismiss(() => props.open, () => emit('close'))
watch(() => props.open, async (open) => {
  if (open) { await nextTick(); closeButton.value?.focus() }
})
function trapTab(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const nodes = [...(panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), summary, [tabindex="0"]') ?? [])]
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
      <details v-if="!standalone" class="home-screen-guide">
        <summary>把 WAO 放到手机主屏幕</summary>
        <p>在 Safari 打开小记页，点分享 → 添加到主屏幕；若有“作为 Web App 打开”，保持开启。以后点图标就能进入小记。</p>
        <p>若仍显示桌面布局，在 Safari 的页面菜单中选择“请求移动网站”。升级后重新打开即可，正在写的内容请先保存。</p>
      </details>
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
.home-screen-guide { flex-shrink: 0; padding: 0 12px; border-bottom: 1px solid var(--wao-line); font-size: 13px; }
.home-screen-guide summary { min-height: 44px; display: flex; align-items: center; cursor: pointer; color: var(--wao-primary-text); }
.home-screen-guide summary::after { content: '⌄'; margin-left: auto; }
.home-screen-guide summary:focus-visible { outline: 2px solid var(--wao-primary); }
.home-screen-guide p { margin: 0 0 10px; line-height: 1.6; color: var(--wao-text-3); }
</style>
