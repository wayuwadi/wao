<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
const props = defineProps<{ taskId: string }>()
const data = useDataStore()
const ui = useUiStore()
const session = useSessionStore()
const task = computed(() => data.taskById(props.taskId))
const draft = ref('')
const localError = ref('')
const key = computed(() => `wao-inline-sub:${session.userId}:${props.taskId}`)
watch(key, value => {
  try { draft.value = localStorage.getItem(value) || '' }
  catch { localError.value = '本机草稿无法读取，请保持页面打开' }
}, { immediate: true })
watch(draft, value => {
  try { if (value) localStorage.setItem(key.value, value); else localStorage.removeItem(key.value) }
  catch { localError.value = '草稿无法写入本机，请保持页面打开' }
})
function add() {
  if (data.addSub(props.taskId, draft.value)) {
    draft.value = ''
    void data.flushTask(props.taskId)
  }
}
</script>
<template>
  <section v-if="task" class="inline-subs" aria-label="任务子任务" @click.stop @dragstart.stop.prevent>
    <div v-for="(sub, index) in task.subs" :key="data.subKey(taskId, sub, index)" class="inline-sub">
      <input type="checkbox" :checked="sub.done" :aria-label="`完成子任务 ${sub.text}`" @change="data.updateSub(taskId, data.subKey(taskId, sub, index), { done: !sub.done })">
      <button :class="{ done: sub.done }" @click="ui.openTask(taskId, data.subKey(taskId, sub, index))"><span class="number">{{ index + 1 }}.</span>{{ sub.text }}</button>
    </div>
    <form @submit.prevent="add"><input v-model="draft" :aria-label="`为 ${task.title} 添加子任务`" placeholder="＋ 添加子任务，回车保存" @keydown.enter="$event.isComposing && $event.preventDefault()"><button :disabled="!draft.trim()">添加</button></form>
    <small v-if="data.taskSaveState(taskId) === 'saving'" role="status">保存中…</small>
    <small v-else-if="data.taskSaveState(taskId) === 'saved'" role="status">已保存</small>
    <small v-else-if="data.taskSaveState(taskId) === 'error'" class="error" role="alert">保存失败，草稿已保留<button @click="data.flushTask(taskId)">重试保存</button></small>
    <small v-if="localError" class="error" role="alert">{{ localError }}</small>
  </section>
</template>
<style scoped>
.inline-subs { margin: 0 12px 12px 55px; padding-left: 12px; border-left: 2px solid var(--wao-line); min-width: 0; }
.inline-sub, form { display: flex; align-items: center; gap: 8px; padding: 5px 0; }
button { border: 0; background: transparent; color: var(--wao-text-2); text-align: left; cursor: pointer; overflow-wrap: anywhere; font: inherit; font-size: 12px; }
.inline-sub button { flex: 1; }
.number { color: var(--wao-text-faint); margin-right: 6px; font-variant-numeric: tabular-nums; }
.done { text-decoration: line-through; color: var(--wao-text-faint); }
form input { flex: 1; min-width: 0; padding: 6px; border: 1px solid var(--wao-line-control); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-text); }
form button { color: var(--wao-primary); } button:disabled { opacity: .5; cursor: default; }
small { font-size: 11px; color: var(--wao-text-faint); }.error { color: var(--wao-danger-strong); }
</style>
