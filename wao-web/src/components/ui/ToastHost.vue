<script setup lang="ts">
import { useToastHost, dismissToast, pauseToast, runToastAction } from '@/composables/toast'
const host = useToastHost()
</script>

<template>
  <div class="toast-host" aria-label="操作提示">
    <div v-for="t in host.items" :key="t.id" class="toast" :class="{ error: t.tone === 'error' }"
      :role="t.tone === 'error' ? 'alert' : 'status'" aria-atomic="true"
      @mouseenter="pauseToast(t.id, 'pointer', true)" @mouseleave="pauseToast(t.id, 'pointer', false)"
      @focusin="pauseToast(t.id, 'focus', true)" @focusout="pauseToast(t.id, 'focus', false)">
      <i :class="t.icon" aria-hidden="true"></i>
      <span><b v-if="t.tone === 'error'">需要处理：</b>{{ t.text }}</span>
      <button v-if="t.action" class="toast-action" :disabled="t.busy" @click="runToastAction(t.id)">{{ t.busy ? '处理中…' : t.action.label }}</button>
      <button type="button" aria-label="关闭这条操作提示" @click="dismissToast(t.id)"><span aria-hidden="true">×</span></button>
    </div>
  </div>
</template>

<style scoped>
.toast-host { position: fixed; top: 18px; left: 50%; transform: translateX(-50%); z-index: 200; display: flex; flex-direction: column; align-items: center; gap: 8px; width: min(560px, calc(100vw - 24px)); max-height: 45dvh; overflow-y: auto; pointer-events: none; }
.toast { display: flex; align-items: center; gap: 8px; box-sizing: border-box; max-width: 100%; flex-shrink: 0; background: #23262f; color: #fff; border-radius: 14px; padding: 9px 12px; font-size: 13px; line-height: 1.6; box-shadow: 0 8px 24px rgba(16, 18, 28, .28); animation: waoToast .2s ease; pointer-events: auto; }
.toast span { min-width: 0; overflow-wrap: anywhere; }
.toast > i { font-size: 15px; flex: none; }
.toast b { font-weight: 600; }
.toast button { flex: none; display: grid; place-items: center; border: 0; border-radius: 6px; width: 28px; height: 28px; font-size: 20px; background: transparent; color: inherit; cursor: pointer; }
.toast button:hover { background: #ffffff24; }
.toast button:focus-visible { outline: 2px solid currentColor; outline-offset: 2px; }
.toast .toast-action { width:auto; min-height:32px; height:auto; padding-inline:var(--wao-space-1); font:inherit; text-decoration:underline; }
@media (prefers-reduced-motion: reduce) { .toast { animation: none; } }
</style>
