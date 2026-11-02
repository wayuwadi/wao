<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useProjectItemActions } from '@/composables/projectItemActions'
import { isTaskFinished } from '@/utils/projectCompletion'
import { taskTreeRows } from '@/utils/taskHierarchy'
import { withProjectAncestors, type ProjectItemRefs } from '@/utils/projectWorkItems'
import { projectStatusLabel } from '@/utils/projectStatusPresentation'
import { selectableTaskStatuses } from '@/config/features'
import TaskLabelEditor from '@/components/task/TaskLabelEditor.vue'
import SaveHint from '@/components/ui/SaveHint.vue'
import type { Task, TaskStatus, Priority } from '@/types'
const props = defineProps<{ showDone: boolean; items: Task[]; contextItems: Task[]; itemRefs: ProjectItemRefs; collapsed: string[]; query: string; selectedIds: string[]; selectionMode: boolean }>()
const emit = defineEmits<{ toggle: [id: string]; select: [id: string] }>()
const data = useDataStore(), session = useSessionStore()
const actions = useProjectItemActions(() => props.itemRefs)
const completing = ref(new Set<string>())
watch(() => [...completing.value].map(id => [id, data.taskSaveState(actions.sourceId(id))] as const), states => { for (const [id,state] of states) if (state === 'saved') completing.value.delete(id) })
watch(() => session.userId, () => completing.value.clear())
async function status(id: string, next: TaskStatus) {
  completing.value.add(id)
  await actions.status(id, next)
  if (data.taskSaveState(actions.sourceId(id)) !== 'error') completing.value.delete(id)
}
const sort = ref<'title' | 'due' | 'pr' | ''>(''), descending = ref(false)
const rows = computed(() => {
  const items = withProjectAncestors(props.items.filter(task => props.showDone || !isTaskFinished(task) || completing.value.has(task.id)), props.contextItems)
  const field = sort.value
  if (field) items.sort((a,b) => String(a[field] ?? '').localeCompare(String(b[field] ?? ''), 'zh-CN') * (descending.value ? -1 : 1))
  return taskTreeRows(items, props.query.trim() ? [] : props.collapsed)
})
function order(field: 'title' | 'due' | 'pr') { descending.value = sort.value === field ? !descending.value : false; sort.value = field }
function value(event: Event) { return (event.target as HTMLInputElement).value }
function titleChange(task: Task, event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.value.trim()) { input.value = task.title; return }
  void edit(task, 'title', input.value.trim())
}
async function edit(task: Task, field: 'title' | 'owner' | 'due', next: string) {
  if (field === 'title' && !next.trim()) return
  const source = actions.source(task.id)
  await actions.save(task.id, () => {
    if (source.subId) data.updateSub(source.taskId, source.subId, { [field === 'title' ? 'text' : field]: next })
    else data.patchTask(source.taskId, { [field]: next })
  })
}
</script>
<template>
  <div class="todo-table-scroll">
    <table aria-label="任务表格">
      <thead><tr><th v-if="selectionMode">选择</th><th><button @click="order('title')">任务名称</button></th><th>清单</th><th>状态</th><th><button @click="order('pr')">优先级</button></th><th>负责人</th><th><button @click="order('due')">截止日期</button></th><th>标签</th><th>保存状态</th></tr></thead>
      <tbody><tr v-for="row in rows" :key="row.task.id" :data-task-id="row.task.id">
        <td v-if="selectionMode"><input type="checkbox" :aria-label="`选择 ${row.task.title}`" :checked="selectedIds.includes(row.task.id)" @change="emit('select', row.task.id)" /></td>
        <td><div class="table-title" :style="{paddingLeft: `calc(${row.depth} * var(--wao-space-2))`}">
          <button v-if="contextItems.some(item => item.parentTaskId === row.task.id)" :aria-label="`展开或收起 ${row.task.title}`" @click="emit('toggle', row.task.id)"><i :class="collapsed.includes(row.task.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'" /></button>
          <input :value="row.task.title" :aria-label="`标题 ${row.task.title}`" :disabled="actions.busy(row.task.id)" @change="titleChange(row.task,$event)" />
          <button :aria-label="`打开详情 ${row.task.title}`" @click="actions.open(row.task.id)"><i class="ri-expand-diagonal-line" /></button>
        </div></td>
        <td>{{ data.projectById(row.task.project)?.name || '收集箱' }}</td>
        <td><select :aria-label="`状态 ${row.task.title}`" :value="row.task.status" :disabled="actions.busy(row.task.id)" @change="status(row.task.id,value($event) as TaskStatus)"><option v-for="status in (actions.isSub(row.task.id) ? ['待办','进行中','待验证','已完成'] : selectableTaskStatuses(row.task.status))" :key="status" :value="status">{{ projectStatusLabel(status as TaskStatus) }}</option></select></td>
        <td><select :aria-label="`优先级 ${row.task.title}`" :value="row.task.pr" :disabled="actions.busy(row.task.id)" @change="actions.priority(row.task.id,value($event) as Priority)"><option v-for="pr in ['P0','P1','P2','P3']" :key="pr">{{ pr }}</option></select></td>
        <td><select :aria-label="`负责人 ${row.task.title}`" :value="row.task.owner" :disabled="actions.busy(row.task.id)" @change="edit(row.task,'owner',value($event))"><option value="">未指派</option><option v-for="member in session.members" :key="member.id" :value="member.id">{{ member.name }}</option></select></td>
        <td><input type="date" :aria-label="`截止日期 ${row.task.title}`" :value="row.task.due" :disabled="actions.busy(row.task.id)" @change="edit(row.task,'due',value($event))" /></td>
        <td><TaskLabelEditor :task-id="actions.sourceId(row.task.id)" :sub-id="actions.source(row.task.id).subId" /></td>
        <td><SaveHint :saving="data.taskSaveState(actions.sourceId(row.task.id)) === 'saving'" :failed="data.taskSaveState(actions.sourceId(row.task.id)) === 'error'" :error-message="data.taskSaveError(actions.sourceId(row.task.id))" :saved-at="data.taskSavedAt(actions.sourceId(row.task.id))" /><button v-if="data.taskSaveState(actions.sourceId(row.task.id)) === 'error'" @click="data.flushTask(actions.sourceId(row.task.id))">重试</button></td>
      </tr></tbody>
    </table>
    <p v-if="!rows.length">当前没有匹配任务</p>
  </div>
</template>
<style scoped>
.todo-table-scroll { overflow:auto; max-width:100%; }
table { width:100%; border-collapse:collapse; font-size:var(--wao-font-body); }
th,td { padding:var(--wao-space-1); border-bottom:1px solid var(--wao-line); text-align:left; white-space:nowrap; }
th { color:var(--wao-text-3); background:var(--wao-bg-sub); }
.table-title { display:flex; align-items:center; min-width:260px; gap:var(--wao-space-1); }
.table-title input { flex:1; min-width:160px; }
button,input,select { font:inherit; color:var(--wao-text); background:transparent; border:1px solid transparent; border-radius:var(--wao-radius-control); padding:var(--wao-space-1); }
button { cursor:pointer; }
input:hover,select:hover { border-color:var(--wao-line-control); }
button:focus-visible,input:focus-visible,select:focus-visible { outline:2px solid var(--wao-primary); }
</style>
