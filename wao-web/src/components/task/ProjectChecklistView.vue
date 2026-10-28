<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { TASK_STATUSES } from '@/utils/domain'
import type { Task, TaskStatus } from '@/types'
import { useDataStore } from '@/stores/data'
import { useProjectItemActions } from '@/composables/projectItemActions'
import { taskHierarchyDrop } from '@/utils/taskDrop'
import { taskTreeRows } from '@/utils/taskHierarchy'
import { withProjectAncestors, type ProjectItemRefs } from '@/utils/projectWorkItems'
import { searchProjectTasks } from '@/utils/projectTaskSearch'
import WaoCard from '@/components/ui/WaoCard.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import SaveHint from '@/components/ui/SaveHint.vue'

const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const props = defineProps<{ items: Task[]; contextItems: Task[]; itemRefs: ProjectItemRefs; showDone: boolean; collapsed: string[]; query: string; busy?: boolean; sections?: { id: string; name: string; header: boolean; collapsed: boolean; itemIds: string[] }[] }>()
const emit = defineEmits<{ toggle: [id: string]; toggleGroup: [id: string]; move: [source: string, target: string | null, side: 'before' | 'inside' | 'after'] }>()
const data = useDataStore()
const actions = useProjectItemActions(() => props.itemRefs)
const selectedId = ref('')
const matches = computed(() => {
  const items = props.items.filter(t => props.showDone || t.status !== '已完成' || data.taskSaveState(actions.sourceId(t.id)) === 'error')
  return props.query ? searchProjectTasks(items, props.query).map(hit => hit.task) : items
})
const rows = computed(() => taskTreeRows(withProjectAncestors(matches.value, props.contextItems), props.query ? [] : props.collapsed))
const groupedRows = computed(() => (props.sections ?? [{ id: '__all', name: '', header: false, collapsed: false, itemIds: props.contextItems.map(t => t.id) }]).map(section => ({ ...section, rows: rows.value.filter(row => section.itemIds.includes(row.task.id)) })))
const dragging = ref<string | null>(null)
const over = ref<{ id: string; side: 'before' | 'inside' | 'after' } | null>(null)
function startDrag(id: string, event: DragEvent) {
  if (props.busy) { event.preventDefault(); return }
  dragging.value = id
  event.dataTransfer?.setData('text/plain', id)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function drop(target: string | null, event: DragEvent) {
  if (dragging.value && !props.busy) emit('move', dragging.value, target, target ? taskHierarchyDrop(event) : 'inside')
  dragging.value = null; over.value = null
}
watch(rows, value => {
  if (!value.some(row => row.task.id === selectedId.value)) selectedId.value = value[0]?.task.id ?? ''
}, { immediate: true })
const selected = computed(() => props.contextItems.find(t => t.id === selectedId.value))
const source = computed(() => actions.source(selectedId.value))
const taskGroups = computed(() => selected.value?.project ? data.taskGroupsOf(selected.value.project) : [])
const groupRoot = computed(() => {
  let task = data.taskById(source.value.taskId)
  const seen = new Set<string>()
  while (task?.parentTaskId && !seen.has(task.id)) {
    seen.add(task.id)
    const parent = data.taskById(task.parentTaskId)
    if (!parent) break
    task = parent
  }
  return task
})
const inheritedGroup = computed(() => !!source.value.subId || groupRoot.value?.id !== source.value.taskId)
const selectedGroup = computed(() => taskGroups.value.some(group => group.id === groupRoot.value?.groupId) ? groupRoot.value!.groupId : taskGroups.value[0]?.id)
const statusOptions = computed(() => (source.value.subId ? ['待办', '进行中', '待验证'] : TASK_STATUSES.filter(status => status !== '已完成')) as TaskStatus[])
async function changeGroup(event: Event) {
  if (!selected.value || inheritedGroup.value) return
  const groupId = (event.target as HTMLSelectElement).value
  if (!taskGroups.value.some(group => group.id === groupId)) return
  await actions.save(selected.value.id, () => data.moveTaskToGroup(source.value.taskId, groupId))
}
const description = computed({
  get: () => source.value.subId ? data.subById(source.value.taskId, source.value.subId)?.desc ?? '' : data.taskById(source.value.taskId)?.desc ?? '',
  set: desc => {
    if (!selected.value) return
    const { taskId, subId } = source.value
    if (subId) data.updateSub(taskId, subId, { desc })
    else data.patchTask(taskId, { desc })
  },
})
const saveState = computed(() => data.taskSaveState(source.value.taskId))
const hasChildren = (id: string) => props.contextItems.some(t => t.parentTaskId === id)
async function uploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) { done(null, file.name); return }
  const image = await data.uploadImage(file, `任务 · ${selected.value?.title ?? ''}`)
  done(image?.url ?? null, image?.name ?? file.name)
}
</script>

<template>
  <div class="checklist-layout">
    <WaoCard class="checklist-tasks">

      <p v-if="!rows.length" class="empty">{{ query ? '没有匹配的任务，试试其他关键词' : '当前没有待办任务' }}</p>
      <div v-if="dragging" class="root-drop" @dragover.prevent @drop.prevent="drop(null, $event)">拖到这里移为顶层任务</div>
      <section v-for="section in groupedRows" :key="section.id">
      <button v-if="section.header" class="group-heading" :aria-expanded="!section.collapsed" @click="emit('toggleGroup', section.id)"><i :class="section.collapsed ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>{{ section.name }} <small>{{ section.rows.length }}</small></button>
      <div v-show="!section.collapsed">
      <div v-for="{ task, depth } in section.rows" :key="task.id" class="checklist-row" :draggable="!busy" @dragstart="startDrag(task.id, $event)" @dragend="dragging = null; over = null" @dragover.prevent="over = { id: task.id, side: taskHierarchyDrop($event) }" @drop.prevent.stop="drop(task.id, $event)" :class="{ selected: selectedId === task.id, 'drop-inside': dragging && over?.id === task.id && over.side === 'inside', 'drop-before': dragging && over?.id === task.id && over.side === 'before', 'drop-after': dragging && over?.id === task.id && over.side === 'after' }" :style="{ paddingInlineStart: `${Math.min(depth, 6) * 16 + 8}px` }">
        <button v-if="hasChildren(task.id)" class="tree-toggle" :aria-label="`${collapsed.includes(task.id) ? '展开' : '收起'} ${task.title}`" :aria-expanded="!collapsed.includes(task.id)" @click="$emit('toggle', task.id)"><i :class="collapsed.includes(task.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
        <span v-else class="tree-spacer"></span>
        <input type="checkbox" :checked="task.status === '已完成'" :disabled="actions.busy(task.id)" :aria-label="`完成 ${task.title}`" @change="actions.toggle(task.id)" />
        <button class="task-select" :aria-pressed="selectedId === task.id" @click="selectedId = task.id">
          <span class="task-title" :title="task.title" :class="{ finished: task.status === '已完成' }">{{ task.title || '未命名任务' }}</span>
          <span v-if="task.due" class="task-meta">{{ task.due }}</span>
        </button>
      </div>
      </div></section>
    </WaoCard>
    <WaoCard class="checklist-detail" padding="roomy" aria-label="任务详情卡片">
      <template v-if="selected">
        <div class="detail-caption"><label class="detail-completion"><input type="checkbox" :checked="selected.status === '已完成'" :disabled="actions.busy(selected.id)" @change="actions.toggle(selected.id)" /><span>{{ selected.status === '已完成' ? '已完成' : '未完成' }}</span></label><select v-if="taskGroups.length" class="detail-property" aria-label="任务分组" :title="inheritedGroup ? '子任务跟随父任务分组，请在父任务上调整' : '切换分组'" :value="selectedGroup" :disabled="inheritedGroup || actions.busy(selected.id)" @change="changeGroup"><option v-for="group in taskGroups" :key="group.id" :value="group.id">{{ group.name }}</option></select>
          <select v-if="selected.status !== '已完成'" class="detail-property" aria-label="任务状态" :value="selected.status" :disabled="actions.busy(selected.id)" @change="actions.status(selected.id, ($event.target as HTMLSelectElement).value as TaskStatus)"><option v-for="status in statusOptions" :key="status" :value="status">{{ status === '待办' ? '未开始' : status }}</option></select>
          <span v-if="selected.due" class="detail-due" title="预计结束日期"><i class="ri-calendar-line" aria-hidden="true"></i>{{ selected.due }}</span><WaoButton class="more-actions" variant="ghost" aria-label="更多操作" title="更多操作" @click="actions.open(selected.id)"><i class="ri-more-line"></i></WaoButton></div>
        <h2>{{ selected.title }}</h2>
        <MarkdownEditor :key="selected.id" v-model="description" bare :polishable="false" :min-height="360" placeholder="记录任务详情…" @upload-image="uploadImage" />
        <div class="save-status" aria-live="polite">
          <SaveHint :saving="saveState === 'saving'" :failed="saveState === 'error'" :saved-at="data.taskSavedAt(source.taskId)" :error-message="data.taskSaveError(source.taskId)" />
          <WaoButton v-if="saveState === 'error'" variant="ghost" @click="actions.open(selected.id)">查看保存问题</WaoButton>
        </div>
      </template>
      <p v-else class="empty">选择左侧任务，在这里查看详情</p>
    </WaoCard>
  </div>
</template>

<style scoped>
.tree-toggle,.task-select { border:0; background:transparent; font:inherit; color:inherit; cursor:pointer; }
.checklist-layout { display:grid; grid-template-columns:minmax(240px, 0.85fr) minmax(0, 1.3fr); align-items:start; }
.checklist-tasks,.checklist-detail { min-width:0; max-height:calc(100dvh - 220px); overflow:auto; border:0; border-radius:0; box-shadow:none; }
.checklist-tasks { position:relative; padding:0 var(--wao-space-2) 0 0; }
.checklist-detail { position:sticky; top:var(--wao-space-1); min-height:calc(100dvh - 240px); border-left:1px solid var(--wao-line); padding:0 0 0 var(--wao-space-3); }
.detail-caption { display:flex; flex-wrap:wrap; align-items:center; gap:var(--wao-space-2); color:var(--wao-text-weak); font-size:var(--wao-font-caption); border-bottom:1px solid var(--wao-line); min-height:36px; }
.detail-property { min-width:0; max-width:180px; padding:var(--wao-space-1); border:0; border-radius:var(--wao-radius-control); background:transparent; color:var(--wao-text-2); font:inherit; cursor:pointer; }
.detail-property:hover { background:var(--wao-bg-sub); }
.detail-property:disabled { cursor:default; color:var(--wao-text-weak); }
.detail-property option { background:var(--wao-bg-card); color:var(--wao-text); }
.detail-due { display:inline-flex; align-items:center; gap:var(--wao-space-1); white-space:nowrap; }
.detail-completion { display:flex; align-items:center; gap:var(--wao-space-1); cursor:pointer; }
.more-actions { margin-left:auto; padding:0 var(--wao-space-1); min-height:32px; }
.checklist-row { display:flex; align-items:center; gap:var(--wao-space-1); border-radius:var(--wao-radius-control); border-bottom:1px solid var(--wao-line); min-height:38px; }
.checklist-row:hover,.checklist-row.selected { background:var(--wao-bg-sub); }
input[type=checkbox] { appearance:none; box-sizing:border-box; border:1px solid var(--wao-text-weak); border-radius:0; background:var(--wao-bg-card); display:grid; place-content:center; flex-shrink:0; accent-color:var(--wao-primary); width:14px; height:14px; margin:0; }
input[type=checkbox]:checked { background:var(--wao-primary); border-color:var(--wao-primary); }
input[type=checkbox]:checked::after { content:'✓'; color:var(--wao-on-primary); font-size:var(--wao-font-caption); line-height:1; }
.group-heading { width:100%; border:0; background:var(--wao-bg-sub); color:var(--wao-text); text-align:left; padding:var(--wao-space-1); font:inherit; cursor:pointer; }
.group-heading small { color:var(--wao-text-weak); }
.root-drop { position:absolute; bottom:0; left:0; right:0; z-index:1; background:var(--wao-bg-card); padding:var(--wao-space-2); border:1px dashed var(--wao-primary); color:var(--wao-primary-text); }
.drop-inside { outline:1px solid var(--wao-primary); background:var(--wao-primary-soft); }
.drop-before { border-top:2px solid var(--wao-primary); }
.drop-after { border-bottom:2px solid var(--wao-primary); }
.tree-toggle,.tree-spacer { width:16px; flex-shrink:0; }
.tree-toggle { height:32px; padding:0; color:var(--wao-text-weak); }
.task-select { display:flex; align-items:center; gap:var(--wao-space-1); flex:1; min-width:0; min-height:38px; padding:0 var(--wao-space-1) 0 0; text-align:left; font-size:var(--wao-font-body); }
.task-title { flex:1; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; line-height:1.5; }
.finished { text-decoration:line-through; color:var(--wao-text-weak); }
.task-meta { flex-shrink:0; color:var(--wao-text-weak); font-size:var(--wao-font-caption); }
h2 { font-size:calc(var(--wao-font-body) + 2px); line-height:1.5; overflow-wrap:anywhere; margin:var(--wao-space-2) 0 var(--wao-space-1); }
.save-status { margin-top:var(--wao-space-1); display:flex; gap:var(--wao-space-2); flex-wrap:wrap; }
.empty { padding:var(--wao-space-2); color:var(--wao-text-weak); }
button:focus-visible,input:focus-visible,select:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(max-width:760px) {
  .checklist-layout { grid-template-columns:minmax(0,1fr); }
  .checklist-tasks { max-height:260px; padding:0; }
  .checklist-detail { position:static; max-height:none; min-height:300px; border-left:0; border-top:1px solid var(--wao-line); padding:var(--wao-space-2) 0 0; }
  .task-select { min-height:44px; }
}
</style>
