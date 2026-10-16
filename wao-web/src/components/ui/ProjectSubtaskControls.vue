<script setup lang="ts">
defineProps<{ show: boolean; showDone: boolean; tree?: boolean; saving?: boolean; error?: string }>()
const emit = defineEmits<{ 'update:show': [value: boolean]; 'update:showDone': [value: boolean]; expand: []; collapse: []; retry: [] }>()
</script>
<template>
  <div class="subtask-controls" role="group" aria-label="子任务显示设置">
    <button type="button" role="switch" :aria-checked="show" @click="emit('update:show', !show)"><span class="track"><span /></span>显示子任务</button>
    <button type="button" role="switch" :aria-checked="showDone" @click="emit('update:showDone', !showDone)"><span class="track"><span /></span>显示已完成</button>
    <template v-if="show && tree"><button type="button" @click="emit('expand')">展开全部</button><button type="button" @click="emit('collapse')">收起全部</button></template>
    <span v-if="saving" role="status">同步中…</span>
    <span v-if="error" role="alert">{{ error }}<button type="button" @click="emit('retry')">重试</button></span>
  </div>
</template>
<style scoped>
.subtask-controls { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
button { display: inline-flex; align-items: center; gap: 6px; padding: 5px 9px; min-height: 32px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text-2); font: inherit; font-size: 12px; cursor: pointer; }
button:hover { background: var(--wao-bg-hover); }
.track { display: inline-flex; align-items: center; width: 28px; height: 16px; padding: 2px; border-radius: var(--wao-radius-pill); background: var(--wao-text-3); }
.track span { width: 12px; height: 12px; border-radius: var(--wao-radius-pill); background: var(--wao-on-primary); }
[aria-checked=true] .track { background: var(--wao-primary); justify-content: flex-end; }
[role=status], [role=alert] { font-size: 12px; color: var(--wao-text-3); }
[role=alert] { color: var(--wao-danger-strong); }
@media (max-width: 760px) { button { min-height: 44px; } }
</style>
