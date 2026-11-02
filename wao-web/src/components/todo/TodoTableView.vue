<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useProjectItemActions } from '@/composables/projectItemActions'
import { isTaskFinished } from '@/utils/projectCompletion'
import { taskTreeRows } from '@/utils/taskHierarchy'
import { withProjectAncestors, type ProjectItemRefs } from '@/utils/projectWorkItems'
import WorkItemIcon from '@/components/task/WorkItemIcon.vue'
import CellMenu from '@/components/task/CellMenu.vue'
import { PROJECT_STATUS_COLORS, PROJECT_PRIORITY_COLORS, projectStatusLabel } from '@/utils/projectStatusPresentation'
import { selectableTaskStatuses } from '@/config/features'
import TaskLabelEditor from '@/components/task/TaskLabelEditor.vue'
import SaveHint from '@/components/ui/SaveHint.vue'
import type { Task, TaskStatus, Priority } from '@/types'
const props = defineProps<{ showDone: boolean; items: Task[]; contextItems: Task[]; itemRefs: ProjectItemRefs; collapsed: string[]; query: string; selectedIds: string[]; selectionMode: boolean }>()
const emit = defineEmits<{ toggle: [id: string]; select: [id: string] }>()
const data = useDataStore(), session = useSessionStore()
const actions = useProjectItemActions(() => props.itemRefs)
const menu = ref<{id:string; field:'status'|'priority'|'owner'; anchor:HTMLElement}>()
function openMenu(id:string,field:'status'|'priority'|'owner',event:MouseEvent) { if (!actions.busy(id)) menu.value={id,field,anchor:event.currentTarget as HTMLElement} }
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
    <table class="project-task-table" aria-label="任务表格"><colgroup><col v-if="selectionMode" style="width:44px" /><col style="width:360px" /><col style="width:160px" /><col style="width:100px" /><col style="width:76px" /><col style="width:140px" /><col style="width:140px" /><col style="width:130px" /><col style="width:160px" /></colgroup>
      <thead><tr><th v-if="selectionMode">选择</th><th><button @click="order('title')">任务名称</button></th><th>清单</th><th>状态</th><th><button @click="order('pr')">优先级</button></th><th>负责人</th><th><button @click="order('due')">截止日期</button></th><th>标签</th><th>保存状态</th></tr></thead>
      <tbody><tr v-for="row in rows" :key="row.task.id" :data-task-id="row.task.id">
        <td v-if="selectionMode"><input type="checkbox" :aria-label="`选择 ${row.task.title}`" :checked="selectedIds.includes(row.task.id)" @change="emit('select', row.task.id)" /></td>
        <td><div class="table-title" :style="{paddingLeft: `calc(${row.depth} * var(--wao-space-2))`}">
          <button v-if="contextItems.some(item => item.parentTaskId === row.task.id)" :aria-label="`展开或收起 ${row.task.title}`" @click="emit('toggle', row.task.id)"><i :class="collapsed.includes(row.task.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'" /></button>
          <WorkItemIcon :kind="actions.isSub(row.task.id) ? 'subtask' : row.task.workItemType || 'task'" />
          <input :value="row.task.title" :aria-label="`标题 ${row.task.title}`" :disabled="actions.busy(row.task.id)" @change="titleChange(row.task,$event)" />
          <button :aria-label="`打开详情 ${row.task.title}`" @click="actions.open(row.task.id)"><i class="ri-expand-diagonal-line" /></button>
        </div></td>
        <td>{{ data.projectById(row.task.project)?.name || '收集箱' }}</td>
        <td><button class="status-pill" :aria-label="`状态 ${row.task.title}`" :style="{color:PROJECT_STATUS_COLORS[row.task.status],borderColor:PROJECT_STATUS_COLORS[row.task.status]}" :disabled="actions.busy(row.task.id)" @click="openMenu(row.task.id,'status',$event)">{{projectStatusLabel(row.task.status)}}</button></td>
        <td><button class="priority" :aria-label="`优先级 ${row.task.title}`" :style="{background:PROJECT_PRIORITY_COLORS[row.task.pr]}" :disabled="actions.busy(row.task.id)" @click="openMenu(row.task.id,'priority',$event)">{{row.task.pr}}</button></td>
        <td><button class="table-member" :aria-label="`负责人 ${row.task.title}`" :disabled="actions.busy(row.task.id)" @click="openMenu(row.task.id,'owner',$event)"><span v-if="row.task.owner" class="member-av" :style="{background:session.memberById(row.task.owner).color}">{{session.memberById(row.task.owner).initial}}</span><span>{{row.task.owner ? session.memberById(row.task.owner).name : '未指派'}}</span></button></td>
        <td><input type="date" :aria-label="`截止日期 ${row.task.title}`" :value="row.task.due" :disabled="actions.busy(row.task.id)" @change="edit(row.task,'due',value($event))" /></td>
        <td><TaskLabelEditor :task-id="actions.sourceId(row.task.id)" :sub-id="actions.source(row.task.id).subId" /></td>
        <td><SaveHint :saving="data.taskSaveState(actions.sourceId(row.task.id)) === 'saving'" :failed="data.taskSaveState(actions.sourceId(row.task.id)) === 'error'" :error-message="data.taskSaveError(actions.sourceId(row.task.id))" :saved-at="data.taskSavedAt(actions.sourceId(row.task.id))" /><button v-if="data.taskSaveState(actions.sourceId(row.task.id)) === 'error'" @click="data.flushTask(actions.sourceId(row.task.id))">重试</button></td>
      </tr></tbody>
    </table>
    <CellMenu v-if="menu" :anchor="menu.anchor" @close="menu=undefined">
      <template v-if="menu.field==='status'"><button v-for="option in (actions.isSub(menu.id) ? ['待办','进行中','待验证','已完成'] : selectableTaskStatuses(contextItems.find(t=>t.id===menu!.id)?.status ?? '待办'))" :key="option" role="menuitem" @click="status(menu.id,option as TaskStatus);menu=undefined">{{projectStatusLabel(option as TaskStatus)}}</button></template>
      <template v-else-if="menu.field==='priority'"><button v-for="pr in ['P0','P1','P2','P3']" :key="pr" role="menuitem" @click="actions.priority(menu.id,pr as Priority);menu=undefined">{{pr}}</button></template>
      <template v-else><button role="menuitem" @click="edit(contextItems.find(t=>t.id===menu!.id)!,'owner','');menu=undefined">未指派</button><button v-for="member in session.members" :key="member.id" role="menuitem" @click="edit(contextItems.find(t=>t.id===menu!.id)!,'owner',member.id);menu=undefined">{{member.name}}</button></template>
    </CellMenu>
    <p v-if="!rows.length">当前没有匹配任务</p>
  </div>
</template>
<style scoped>
.todo-table-scroll {overflow:auto;max-width:100%;max-height:calc(100dvh - 190px);border:1px solid var(--wao-line);border-radius:var(--wao-radius-control);}
.project-task-table {width:100%;min-width:1200px;table-layout:fixed;border-collapse:separate;border-spacing:0;font-size:var(--wao-font-body);}
th,td {padding:0 var(--wao-space-1);border-right:1px solid var(--wao-line);border-bottom:1px solid var(--wao-line);text-align:left;height:36px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--wao-text-2);}
th {position:sticky;top:0;z-index:2;height:34px;font-weight:var(--wao-weight-heading);color:var(--wao-text-3);background:var(--wao-bg-sub);}
th:last-child,td:last-child {border-right:0;}
tr:hover td {background:var(--wao-bg-hover);}
.table-title {display:flex;align-items:center;gap:var(--wao-space-1);}
.table-title input {color:var(--wao-primary-text);flex:1;min-width:0;width:100%;}
button,input {font:inherit;color:inherit;background:transparent;border:1px solid transparent;border-radius:var(--wao-radius-control);padding:0;}
button {cursor:pointer;}
input {height:26px;width:100%;}
input:hover,input:focus {border-color:var(--wao-line-control);}
.status-pill {border:1px solid; border-radius:var(--wao-radius-pill);font-size:var(--wao-font-caption);padding:0 var(--wao-space-1);line-height:20px;}
.priority {color:var(--wao-on-primary);min-width:26px;font-size:var(--wao-font-caption);line-height:20px;}
.table-member {display:flex;align-items:center;gap:var(--wao-space-1);max-width:100%;}
.table-member>span:last-child {overflow:hidden;text-overflow:ellipsis;}
.member-av {display:inline-flex;align-items:center;justify-content:center;width:22px;height:22px;flex-shrink:0;border-radius:var(--wao-radius-pill);color:var(--wao-on-primary);font-size:var(--wao-font-caption);}
button:focus-visible,input:focus-visible {outline:2px solid var(--wao-primary);}
</style>
