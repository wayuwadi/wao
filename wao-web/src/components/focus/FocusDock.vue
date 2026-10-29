<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useFocusStore } from '@/stores/focus'
const focus = useFocusStore(), route = useRoute()
let timer: ReturnType<typeof setInterval>, sync: ReturnType<typeof setInterval>
function refresh() { focus.tick(); if (document.visibilityState === 'visible') void focus.load() }
onMounted(() => { timer = setInterval(focus.tick, 1000); sync = setInterval(() => void focus.load(), 30_000); window.addEventListener('focus', refresh); document.addEventListener('visibilitychange', refresh) })
onBeforeUnmount(() => { clearInterval(timer); clearInterval(sync); window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh) })
</script>
<template><RouterLink v-if="focus.active && route.path !== '/focus'" class="focus-dock" to="/focus" aria-label="返回番茄专注"><i class="ri-timer-line" aria-hidden="true"></i><span>{{ focus.active.mode === 'break' ? '休息' : '专注' }} · {{ focus.display }}</span><small>{{ focus.error ? '待重试' : focus.active.status === 'paused' ? '已暂停' : '进行中' }}</small></RouterLink></template>
<style scoped>
.focus-dock { position: fixed; right: var(--wao-space-2); bottom: calc(72px + env(safe-area-inset-bottom)); z-index: 80; display: flex; align-items: center; gap: var(--wao-space-1); padding: var(--wao-space-1) var(--wao-space-2); min-height: 44px; border: 1px solid var(--wao-line); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-primary); box-shadow: var(--wao-shadow-pop); text-decoration: none; font-size: var(--wao-font-body); font-variant-numeric: tabular-nums; }
small { font-size: var(--wao-font-caption); color: var(--wao-text-3); }
</style>
