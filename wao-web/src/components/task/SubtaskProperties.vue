<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
const props = defineProps<{ taskId: string; subId: string }>()
const data = useDataStore(), session = useSessionStore(), ui = useUiStore()
const sub = computed(() => data.subById(props.taskId, props.subId))
const destination = ref(props.taskId)
const parent = ref('')
const error = ref('')
watch(() => [props.taskId, props.subId], () => { destination.value = props.taskId; parent.value = sub.value?.parentId || ''; error.value = '' }, { immediate: true })
watch(destination, () => parent.value = '')
const candidates = computed(() => data.taskById(destination.value)?.subs.filter(item => item.id && item.id !== props.subId) || [])
async function move() {
  error.value = ''
  try { await data.moveSubtask(props.taskId, props.subId, destination.value, parent.value); ui.openTask(destination.value, props.subId) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '移动失败，请重试' }
}
</script>
<template>
  <section v-if="sub" class="subtask-properties" :inert="data.movingSubtasks">
    <label>负责人<select :value="sub.owner || ''" @change="data.updateSub(taskId, subId, { owner: ($event.target as HTMLSelectElement).value || null })"><option value="">未分配</option><option v-for="member in session.members" :key="member.id" :value="member.id">{{ member.name }}</option></select></label>
    <label>截止日期<input type="date" :value="sub.due || ''" @change="data.updateSub(taskId, subId, { due: ($event.target as HTMLInputElement).value || null })" /></label>
    <details><summary>调整所属任务与层级</summary>
      <label>主任务<select v-model="destination"><option v-for="task in data.tasks" :key="task.id" :value="task.id">{{ task.title }}</option></select></label>
      <label>父子任务<select v-model="parent"><option value="">直接属于主任务</option><option v-for="item in candidates" :key="item.id" :value="item.id">{{ item.text }}</option></select></label>
      <button :disabled="data.movingSubtasks" @click="move">{{ data.movingSubtasks ? '移动中…' : '移动整棵子树' }}</button>
    </details>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>
<style scoped>
/* 与任务属性栏的 prop-row 对齐：左侧 60px 灰色键名，右侧紧凑输入 */
.subtask-properties { display: grid; gap: 0; margin: 0; }
label { display: grid; grid-template-columns: 60px minmax(0, 1fr); gap: 8px; align-items: center; padding: 5px 0; font-size: 12px; color: var(--wao-text-weak); }
input, select { min-width: 0; height: 28px; padding: 0 6px; font: inherit; font-size: 12.5px; color: var(--wao-text-2); background: transparent; border: 1px solid transparent; border-radius: 6px; cursor: pointer; }
input:hover, select:hover, input:focus, select:focus { border-color: var(--wao-line-control); background: var(--wao-bg-card); outline: none; }
details { margin-top: 6px; }
summary { padding: 8px 0; font-size: 12px; color: var(--wao-text-weak); cursor: pointer; }
details button { margin-top: 6px; height: 28px; padding: 0 12px; border: 1px solid var(--wao-line-control); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-text-2); font-size: 12px; cursor: pointer; }
p { color: var(--wao-danger-strong); font-size: 12px; }
</style>
