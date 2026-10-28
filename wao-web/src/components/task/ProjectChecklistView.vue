<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useMemberPreference } from '@/composables/memberPreference'
import { useSessionStore } from '@/stores/session'
import CellMenu from '@/components/task/CellMenu.vue'
import { PROJECT_STATUS_COLORS, PROJECT_PRIORITY_COLORS, PROJECT_PRIORITY_LABELS, projectStatusLabel } from '@/utils/projectStatusPresentation'
import { selectableTaskStatuses } from '@/config/features'
import type { Task, TaskStatus, Priority } from '@/types'
import { useDataStore } from '@/stores/data'
import { useProjectItemActions } from '@/composables/projectItemActions'
import { toast } from '@/composables/toast'
import { taskHierarchyDrop } from '@/utils/taskDrop'
import { taskTreeRows } from '@/utils/taskHierarchy'
import { withProjectAncestors, type ProjectItemRefs } from '@/utils/projectWorkItems'
import { searchProjectTasks } from '@/utils/projectTaskSearch'
import WaoCard from '@/components/ui/WaoCard.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import SaveHint from '@/components/ui/SaveHint.vue'
import TaskDocuments from '@/components/task/TaskDocuments.vue'
import TaskDueDate from '@/components/task/TaskDueDate.vue'
import { deadlineInfo } from '@/utils/taskDeadline'
import { todayIso } from '@/utils/time'
import { useTaskDeadlineDefaults } from '@/composables/taskDeadlineDefaults'

const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const props = defineProps<{ items: Task[]; contextItems: Task[]; itemRefs: ProjectItemRefs; showDone: boolean; collapsed: string[]; query: string; busy?: boolean; sections?: { id: string; name: string; header: boolean; collapsed: boolean; itemIds: string[] }[] }>()
const emit = defineEmits<{ toggle: [id: string]; toggleGroup: [id: string]; add: [groupId: string]; reveal: [id: string]; move: [source: string, target: string | null, side: 'before' | 'inside' | 'after'] }>()
const data = useDataStore()
const deadlineDefaults = useTaskDeadlineDefaults()
const today = ref(todayIso())
const refreshToday = () => { today.value = todayIso() }
const dayTimer = setInterval(refreshToday, 60000)
onMounted(() => window.addEventListener('focus', refreshToday))
onBeforeUnmount(() => { clearInterval(dayTimer); window.removeEventListener('focus', refreshToday) })
const overdue = (task:Task) => deadlineInfo(task.due, today.value, task.status === '已完成').overdue
const actions = useProjectItemActions(() => props.itemRefs)
// Keep completion reversible locally before saving, so even a slow request cannot block undo.
const pendingCompletion = ref(new Set<string>())
const completionTimers = new Map<string, ReturnType<typeof setTimeout>>()
const committingCompletion = new Set<string>()
let completionQueue = Promise.resolve()
function cancelCompletion(id: string) {
  clearTimeout(completionTimers.get(id)); completionTimers.delete(id)
  pendingCompletion.value = new Set([...pendingCompletion.value].filter(key => key !== id))
}
function commitCompletion(id: string) {
  if (!pendingCompletion.value.has(id) || committingCompletion.has(id)) return
  committingCompletion.add(id)
  const member = useSessionStore().userId
  completionQueue = completionQueue.then(async () => {
    try {
    if (member !== useSessionStore().userId || !pendingCompletion.value.has(id)) return
    await data.flushTask(actions.sourceId(id))
    if (member !== useSessionStore().userId || !pendingCompletion.value.has(id)) return
    cancelCompletion(id)
    await actions.status(id, '已完成')
    if (selectedId.value === id && data.taskSaveState(actions.sourceId(id)) === 'saved') detailOnlySelection.value = false
    cancelCompletion(id)
    } catch {
      cancelCompletion(id)
      toast('完成操作未保存，请重试', 'ri-error-warning-line')
    } finally { committingCompletion.delete(id) }
  })
  return completionQueue
}
function toggleCompletion(id: string) {
  if (pendingCompletion.value.has(id)) { cancelCompletion(id); return }
  const task = props.contextItems.find(task => task.id === id)
  if (!task || actions.busy(id)) return
  if (task.status === '已完成') { void actions.toggle(id); return }
  pendingCompletion.value = new Set([...pendingCompletion.value, id])
  completionTimers.set(id, setTimeout(() => { void commitCompletion(id) }, 3000))
}
const isComplete = (task: Task) => task.status === '已完成' || pendingCompletion.value.has(task.id)
watch(() => useSessionStore().userId, () => {
  for (const id of [...pendingCompletion.value]) cancelCompletion(id)
}, { flush: 'sync' })
onBeforeUnmount(() => {
  for (const id of [...pendingCompletion.value]) {
    clearTimeout(completionTimers.get(id))
    void commitCompletion(id)
  }
})
const layout = ref<HTMLElement | null>(null)
const layoutWidth = ref(0)
const split = useMemberPreference('project-checklist-split-v1', raw => {
  const value = raw == null ? NaN : Number(raw)
  return Number.isFinite(value) ? Math.min(.75, Math.max(.25, value)) : .395
}, String)
const previewSplit = ref<number | null>(null)
const splitPointer = ref<number | null>(null)
const splitTarget = ref<HTMLElement | null>(null)
const compact = computed(() => layoutWidth.value > 0 && layoutWidth.value < 600)
function boundSplit(value: number) {
  const width = Math.max(1, (layoutWidth.value || 1000) - 9)
  const min = Math.min(.45, Math.max(.25, 220 / width))
  const max = Math.max(min, Math.min(.75, 1 - 280 / width))
  return Math.min(max, Math.max(min, value))
}
const splitRatio = computed(() => boundSplit(previewSplit.value ?? split.value.value))
const splitStyle = computed(() => compact.value ? {} : { gridTemplateColumns: `minmax(0, ${splitRatio.value}fr) 9px minmax(0, ${1 - splitRatio.value}fr)` })
function startSplit(event: PointerEvent) {
  if (event.button !== 0 || compact.value) return
  event.preventDefault()
  splitPointer.value = event.pointerId
  previewSplit.value = splitRatio.value
  splitTarget.value = event.currentTarget as HTMLElement
  splitTarget.value.setPointerCapture(event.pointerId)
}
function moveSplit(event: PointerEvent) {
  if (splitPointer.value !== event.pointerId || !layout.value) return
  const rect = layout.value.getBoundingClientRect()
  previewSplit.value = boundSplit((event.clientX - rect.left - 4.5) / Math.max(1, rect.width - 9))
}
function endSplit(save = true) {
  const pointer = splitPointer.value, target = splitTarget.value, ratio = previewSplit.value
  splitPointer.value = null; splitTarget.value = null
  if (pointer != null && target?.hasPointerCapture(pointer)) target.releasePointerCapture(pointer)
  if (save && ratio != null) void split.set(ratio)
  previewSplit.value = null
}
function keySplit(event: KeyboardEvent) {
  if (event.key === 'Escape') { endSplit(false); return }
  const delta = event.shiftKey ? .05 : .02
  const next = event.key === 'ArrowLeft' ? splitRatio.value - delta : event.key === 'ArrowRight' ? splitRatio.value + delta : event.key === 'Home' ? .25 : event.key === 'End' ? .75 : null
  if (next == null) return
  event.preventDefault(); void split.set(boundSplit(next))
}
let layoutObserver: ResizeObserver | undefined
onMounted(() => {
  if (!layout.value) return
  layoutWidth.value = layout.value.getBoundingClientRect().width
  if (typeof ResizeObserver !== 'undefined') {
    layoutObserver = new ResizeObserver(entries => { layoutWidth.value = entries[0]?.contentRect.width ?? 0 })
    layoutObserver.observe(layout.value)
  }
})
watch(() => useSessionStore().userId, () => endSplit(false))
onBeforeUnmount(() => { endSplit(); layoutObserver?.disconnect() })
const selectedId = ref('')
const detailOnlySelection = ref(false)
const matches = computed(() => {
  const items = props.items.filter(t => props.showDone || t.status !== '已完成' || pendingCompletion.value.has(t.id) || data.taskSaveState(actions.sourceId(t.id)) === 'error')
  const found = props.query ? searchProjectTasks(items, props.query).map(hit => hit.task) : items
  const located = detailOnlySelection.value ? props.contextItems.find(t => t.id === selectedId.value) : null
  return located && !found.some(t => t.id === located.id) ? [...found, located] : found
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
  if (detailOnlySelection.value && props.contextItems.some(t => t.id === selectedId.value)) return
  if (!value.some(row => row.task.id === selectedId.value)) selectedId.value = value[0]?.task.id ?? ''
}, { immediate: true })
const selected = computed(() => props.contextItems.find(t => t.id === selectedId.value))
const source = computed(() => actions.source(selectedId.value))
const propertyMenu = ref<'group' | 'status' | 'priority' | null>(null)
const groupAnchor = ref<HTMLElement | null>(null)
const priorityAnchor = ref<HTMLElement | null>(null)
const priorities: Priority[] = ['P0', 'P1', 'P2', 'P3']
const priorityColor = (task: Task) => props.itemRefs[task.id]?.priorityUnset ? PROJECT_PRIORITY_COLORS.P3 : PROJECT_PRIORITY_COLORS[task.pr]
const directChildren = computed(() => props.contextItems.filter(task => task.parentTaskId === selectedId.value))
const parentTask = computed(() => props.contextItems.find(task => task.id === selected.value?.parentTaskId))
async function selectChild(id: string) {
  detailOnlySelection.value = true; selectedId.value = id
  emit('reveal', id)
  let parentId = props.contextItems.find(task => task.id === id)?.parentTaskId
  const seen = new Set<string>()
  while (parentId && !seen.has(parentId)) {
    seen.add(parentId)
    if (props.collapsed.includes(parentId)) emit('toggle', parentId)
    parentId = props.contextItems.find(task => task.id === parentId)?.parentTaskId
  }
  await nextTick()
  for (const section of props.sections ?? []) if (section.collapsed && section.itemIds.includes(id)) emit('toggleGroup', section.id)
  await nextTick()
  const row = Array.from(layout.value?.querySelectorAll<HTMLElement>('.checklist-row') ?? []).find(row => row.dataset.taskId === id)
  row?.scrollIntoView?.({ block:'nearest', behavior:'smooth' })
}
async function changePriority(priority: Priority) {
  propertyMenu.value = null
  if (selected.value) await actions.priority(selected.value.id, priority)
}
watch(() => props.query, () => { detailOnlySelection.value = false })
const statusAnchor = ref<HTMLElement | null>(null)
watch(selectedId, () => { propertyMenu.value = null })
async function changeStatus(status: TaskStatus) {
  propertyMenu.value = null
  if (selected.value) await actions.status(selected.value.id, status)
}
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
const statusOptions = computed(() => (source.value.subId ? ['待办', '进行中', '待验证'] : selectableTaskStatuses(selected.value?.status ?? '待办').filter(status => status !== '已完成')) as TaskStatus[])
async function changeGroup(groupId: string) {
  if (!selected.value || inheritedGroup.value) return
  propertyMenu.value = null
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
async function changeDue(due:string) {
  if (!selected.value) return
  const { taskId, subId } = source.value
  await actions.save(selected.value.id, () => subId ? data.updateSub(taskId, subId, { due }) : data.patchTask(taskId, { due }))
}
const taskTitle = computed(() => source.value.subId
  ? data.subById(source.value.taskId, source.value.subId)?.text ?? ''
  : data.taskById(source.value.taskId)?.title ?? '')
const titleDraft = ref('')
watch([selectedId, taskTitle], () => { titleDraft.value = taskTitle.value }, { immediate:true })
function editTitle(event: Event) {
  const title = (event.target as HTMLInputElement).value
  titleDraft.value = title
  if (!title.trim() || !selected.value) return
  detailOnlySelection.value = true
  const { taskId, subId } = source.value
  if (subId) data.updateSub(taskId, subId, { text:title })
  else data.patchTask(taskId, { title })
}
function finishTitle() {
  if (!titleDraft.value.trim()) titleDraft.value = taskTitle.value
  void data.flushTask(source.value.taskId)
}
const saveState = computed(() => data.taskSaveState(source.value.taskId))
const hasChildren = (id: string) => props.contextItems.some(t => t.parentTaskId === id)
async function uploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) { done(null, file.name); return }
  const image = await data.uploadImage(file, `任务 · ${selected.value?.title ?? ''}`)
  done(image?.url ?? null, image?.name ?? file.name)
}
</script>

<template>
  <div v-if="split.error.value" class="split-sync" role="status">{{ split.error.value }}<button @click="split.retry()">重试布局同步</button></div>
  <div ref="layout" class="checklist-layout" :class="{ 'is-resizing': splitPointer !== null, 'is-compact': compact }" :style="splitStyle">
    <WaoCard class="checklist-tasks">

      <p v-if="!rows.length" class="empty">{{ query ? '没有匹配的任务，试试其他关键词' : '当前没有待办任务' }}</p>
      <div v-if="dragging" class="root-drop" @dragover.prevent @drop.prevent="drop(null, $event)">拖到这里移为顶层任务</div>
      <section v-for="section in groupedRows" :key="section.id">
      <div v-if="section.header" class="group-header">
        <button class="group-heading" :aria-expanded="!section.collapsed" @click="emit('toggleGroup', section.id)"><i :class="section.collapsed ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>{{ section.name }} <small>{{ section.rows.length }}</small></button>
        <button class="group-add-button" :aria-label="`在 ${section.name} 新建任务`" title="快速新建任务" @click="emit('add', section.id)"><i class="ri-add-line" aria-hidden="true"></i></button>
      </div>
      <div v-show="!section.collapsed">
      <slot name="group-add" :section="section" />
      <div v-for="{ task, depth } in section.rows" :key="task.id" class="checklist-row" :data-task-id="task.id" :draggable="!busy" @dragstart="startDrag(task.id, $event)" @dragend="dragging = null; over = null" @dragover.prevent="over = { id: task.id, side: taskHierarchyDrop($event) }" @drop.prevent.stop="drop(task.id, $event)" :class="{ selected: selectedId === task.id, 'drop-inside': dragging && over?.id === task.id && over.side === 'inside', 'drop-before': dragging && over?.id === task.id && over.side === 'before', 'drop-after': dragging && over?.id === task.id && over.side === 'after' }" :style="{ paddingInlineStart: `${Math.min(depth, 6) * 16 + 8}px` }">
        <button v-if="hasChildren(task.id)" class="tree-toggle" :aria-label="`${collapsed.includes(task.id) ? '展开' : '收起'} ${task.title}`" :aria-expanded="!collapsed.includes(task.id)" @click="$emit('toggle', task.id)"><i :class="collapsed.includes(task.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
        <span v-else class="tree-spacer"></span>
        <input type="checkbox" :style="{ '--task-priority-color': priorityColor(task) }" :checked="isComplete(task)" :disabled="!pendingCompletion.has(task.id) && actions.busy(task.id)" :aria-label="`完成 ${task.title}`" @change="toggleCompletion(task.id)" />
        <button class="task-select" :aria-pressed="selectedId === task.id" @click="detailOnlySelection = false; selectedId = task.id">
          <span class="task-title" :title="task.title" :class="{ finished: isComplete(task) }">{{ task.title || '未命名任务' }}</span>
          <span v-if="task.due" class="task-meta" :class="{ overdue:overdue(task) > 0 }" :title="overdue(task) ? `已延期 ${overdue(task)} 天` : '截止日期'">{{ task.due }}</span>
        </button>
      </div>
      </div></section>
    </WaoCard>
    <div class="checklist-divider" role="separator" tabindex="0" aria-label="调整任务清单和详情宽度" aria-orientation="vertical" :aria-valuenow="Math.round(splitRatio * 100)" :aria-valuemin="Math.round(boundSplit(.25) * 100)" :aria-valuemax="Math.round(boundSplit(.75) * 100)" title="拖动调整宽度；方向键微调，双击恢复默认" @pointerdown="startSplit" @pointermove="moveSplit" @pointerup="endSplit()" @pointercancel="endSplit(false)" @lostpointercapture="splitPointer !== null && endSplit(false)" @keydown="keySplit" @dblclick="split.set(.395)"></div>
    <WaoCard class="checklist-detail" padding="roomy" aria-label="任务详情卡片">
      <template v-if="selected">
        <div class="detail-caption"><label class="detail-completion"><input type="checkbox" :style="{ '--task-priority-color': priorityColor(selected) }" :checked="isComplete(selected)" :disabled="!pendingCompletion.has(selected.id) && actions.busy(selected.id)" @change="toggleCompletion(selected.id)" /><span>{{ pendingCompletion.has(selected.id) ? '3 秒内可取消' : selected.status === '已完成' ? '已完成' : '未完成' }}</span></label><span v-if="taskGroups.length" ref="groupAnchor" class="property-anchor">
            <button class="group-chip" aria-label="任务分组" aria-haspopup="menu" :aria-expanded="propertyMenu === 'group'" :title="inheritedGroup ? '子任务跟随父任务分组，请在父任务上调整' : '切换分组'" :disabled="inheritedGroup || actions.busy(selected.id)" @click="propertyMenu = propertyMenu === 'group' ? null : 'group'"><i class="ri-folder-2-line"></i>{{ taskGroups.find(group => group.id === selectedGroup)?.name }}<i v-if="!inheritedGroup" class="ri-arrow-down-s-line"></i></button>
            <CellMenu v-if="propertyMenu === 'group'" :anchor="groupAnchor" @close="propertyMenu = null"><button v-for="group in taskGroups" :key="group.id" role="menuitemradio" :aria-checked="group.id === selectedGroup" @click="changeGroup(group.id)">{{ group.name }}<i v-if="group.id === selectedGroup" class="ri-check-line"></i></button></CellMenu>
          </span>
          <span v-if="selected.status !== '已完成'" ref="statusAnchor" class="property-anchor">
            <button class="status-pill" aria-label="任务状态" aria-haspopup="menu" :aria-expanded="propertyMenu === 'status'" :style="{ color: PROJECT_STATUS_COLORS[selected.status], borderColor: PROJECT_STATUS_COLORS[selected.status] }" :disabled="actions.busy(selected.id)" @click="propertyMenu = propertyMenu === 'status' ? null : 'status'">{{ projectStatusLabel(selected.status) }}</button>
            <CellMenu v-if="propertyMenu === 'status'" :anchor="statusAnchor" @close="propertyMenu = null"><button v-for="status in statusOptions" :key="status" role="menuitemradio" :aria-checked="status === selected.status" @click="changeStatus(status)"><span class="status-pill" :style="{ color: PROJECT_STATUS_COLORS[status], borderColor: PROJECT_STATUS_COLORS[status] }">{{ projectStatusLabel(status) }}</span><i v-if="status === selected.status" class="ri-check-line"></i></button></CellMenu>
          </span>
          <TaskDueDate :key="selected.id" :due="selected.due" :today="today" :completed="selected.status === '已完成'" :disabled="actions.busy(selected.id)" :missing-count="deadlineDefaults.missingCount.value" :bulk-busy="deadlineDefaults.busy.value" @fill-missing="deadlineDefaults.fill" @change="changeDue" /><span ref="priorityAnchor" class="priority-anchor">
            <button class="priority-flag" aria-label="任务优先级" :title="props.itemRefs[selected.id]?.priorityUnset ? '设置优先级' : selected.pr + ' · ' + PROJECT_PRIORITY_LABELS[selected.pr]" aria-haspopup="menu" :aria-expanded="propertyMenu === 'priority'" :style="{ color: priorityColor(selected) }" :disabled="actions.busy(selected.id)" @click="propertyMenu = propertyMenu === 'priority' ? null : 'priority'"><i :class="props.itemRefs[selected.id]?.priorityUnset ? 'ri-flag-line' : 'ri-flag-fill'"></i></button>
            <CellMenu v-if="propertyMenu === 'priority'" :anchor="priorityAnchor" @close="propertyMenu = null"><button v-for="priority in priorities" :key="priority" class="priority-option" role="menuitemradio" :aria-checked="!props.itemRefs[selected.id]?.priorityUnset && selected.pr === priority" @click="changePriority(priority)"><i class="ri-flag-fill" :style="{ color: PROJECT_PRIORITY_COLORS[priority] }"></i><span class="priority-code">{{ priority }}</span><span class="priority-label">{{ PROJECT_PRIORITY_LABELS[priority] }}</span></button></CellMenu>
          </span><WaoButton class="more-actions" variant="ghost" aria-label="更多操作" title="更多操作" @click="actions.open(selected.id)"><i class="ri-more-line"></i></WaoButton></div>
        <h2><input class="detail-title-input" aria-label="任务标题" :value="titleDraft" placeholder="任务标题" @input="editTitle" @blur="finishTitle" @keydown.enter="($event.target as HTMLInputElement).blur()" /></h2>
        <MarkdownEditor :key="selected.id" v-model="description" bare :polishable="false" :min-height="directChildren.length ? 180 : 360" placeholder="记录任务详情…" @upload-image="uploadImage" />
        <section v-if="directChildren.length" class="detail-subtasks" aria-label="直属子任务">
          <div v-for="child in directChildren" :key="child.id" class="detail-subtask">
            <input type="checkbox" :style="{ '--task-priority-color': priorityColor(child) }" :checked="isComplete(child)" :disabled="!pendingCompletion.has(child.id) && actions.busy(child.id)" :aria-label="`完成子任务 ${child.title}`" @change="toggleCompletion(child.id)" />
            <button class="child-title" :class="{ finished: isComplete(child) }" @click="selectChild(child.id)">{{ child.title || '未命名任务' }}</button>
            <span v-if="child.due" class="task-meta" :class="{ overdue:overdue(child) > 0 }">{{ child.due }}</span>
          </div>
        </section>
        <WaoButton v-if="parentTask" variant="ghost" class="parent-task-link" @click="selectChild(parentTask.id)"><i class="ri-arrow-go-back-line" aria-hidden="true"></i><span>父任务：{{ parentTask.title }}</span></WaoButton>
        <TaskDocuments :key="`${useSessionStore().userId}:${selected.id}`" :task-id="source.taskId" :sub-id="source.subId" :title="taskTitle" :description="description" />
        <div class="save-status" aria-live="polite">
          <span v-if="deadlineDefaults.result.value" role="status">{{ deadlineDefaults.result.value }}</span>
          <WaoButton v-for="failure in deadlineDefaults.failures.value" :key="failure.id" variant="ghost" @click="actions.open(failure.id)">{{ failure.title }}：{{ failure.error }} · 查看保存问题</WaoButton>
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
.split-sync { display:flex; gap:var(--wao-space-1); color:var(--wao-text-weak); font-size:var(--wao-font-caption); }
.split-sync button { border:0; background:transparent; color:var(--wao-primary-text); cursor:pointer; }
.checklist-divider { position:relative; align-self:stretch; min-height:440px; cursor:col-resize; touch-action:none; user-select:none; }
.checklist-divider::before { content:''; position:absolute; top:0; bottom:0; left:4px; width:1px; background:var(--wao-line); }
.checklist-divider:hover::before,.checklist-divider:focus-visible::before,.is-resizing .checklist-divider::before { background:var(--wao-primary); width:3px; left:3px; }
.checklist-divider:focus-visible { outline:2px solid var(--wao-primary); outline-offset:0; }
.is-resizing { user-select:none; cursor:col-resize; }
.checklist-layout.is-compact { grid-template-columns:minmax(0,1fr); }
.is-compact .checklist-divider { display:none; }
.is-compact .checklist-tasks { max-height:260px; padding:0; }
.is-compact .checklist-detail { position:static; max-height:none; min-height:300px; border-top:1px solid var(--wao-line); padding:var(--wao-space-2) 0 0; }
.checklist-layout { display:grid; grid-template-columns:minmax(240px, 0.85fr) minmax(0, 1.3fr); align-items:start; }
.checklist-tasks,.checklist-detail { min-width:0; max-height:calc(100dvh - 220px); overflow:auto; border:0; border-radius:0; box-shadow:none; }
.checklist-tasks { position:relative; padding:0 var(--wao-space-2) 0 0; }
.checklist-detail { position:sticky; top:var(--wao-space-1); min-height:calc(100dvh - 240px); padding:0 0 0 var(--wao-space-2); }
.detail-caption { display:flex; flex-wrap:wrap; align-items:center; gap:var(--wao-space-2); color:var(--wao-text-weak); font-size:var(--wao-font-caption); border-bottom:1px solid var(--wao-line); min-height:36px; }
.property-anchor { display:inline-flex; align-items:center; }
.group-chip { display:inline-flex; align-items:center; gap:var(--wao-space-1); max-width:220px; border:0; border-radius:var(--wao-radius-control); background:var(--wao-primary-soft); color:var(--wao-primary-text); padding:4px var(--wao-space-1); font:inherit; cursor:pointer; }
.group-chip:disabled { cursor:default; }
.status-pill { display:inline-flex; align-items:center; height:20px; padding:0 var(--wao-space-1); border:1px solid; border-radius:var(--wao-radius-pill); background:transparent; font:inherit; font-size:var(--wao-font-caption); line-height:1; white-space:nowrap; cursor:pointer; }
.detail-due { display:inline-flex; align-items:center; gap:var(--wao-space-1); white-space:nowrap; }
.detail-completion { display:flex; align-items:center; gap:var(--wao-space-1); cursor:pointer; }
.priority-anchor { margin-left:auto; display:inline-flex; }
.priority-flag { display:inline-flex; align-items:center; justify-content:center; width:32px; height:32px; border:0; background:transparent; border-radius:var(--wao-radius-control); font-size:18px; cursor:pointer; }
.priority-flag:hover { background:var(--wao-bg-sub); }
.detail-subtasks { margin-top:var(--wao-space-3); border-top:1px solid var(--wao-line); }
.detail-subtask { display:flex; align-items:center; gap:var(--wao-space-1); min-height:40px; border-bottom:1px solid var(--wao-line); }
.child-title { flex:1; min-width:0; padding:var(--wao-space-1) 0; border:0; background:transparent; font:inherit; font-weight:400; color:var(--wao-text-2); text-align:left; overflow-wrap:anywhere; cursor:pointer; }
.more-actions { padding:0 var(--wao-space-1); min-height:32px; }
.checklist-row { display:flex; align-items:center; gap:var(--wao-space-1); border-radius:var(--wao-radius-control); border-bottom:1px solid var(--wao-line); min-height:38px; }
.checklist-row:hover,.checklist-row.selected { background:var(--wao-bg-sub); }
input[type=checkbox] { appearance:none; box-sizing:border-box; border:1px solid var(--task-priority-color, var(--wao-text-weak)); border-radius:var(--wao-radius-checkbox); background:var(--wao-bg-card); display:grid; place-content:center; flex-shrink:0; accent-color:var(--wao-primary); width:14px; height:14px; margin:0; }
input[type=checkbox]:checked { background:var(--task-priority-color, var(--wao-primary)); border-color:var(--task-priority-color, var(--wao-primary)); }
input[type=checkbox]:checked::after { content:'✓'; color:var(--wao-on-primary); font-size:var(--wao-font-caption); line-height:1; }
.group-header { display:flex; align-items:center; background:var(--wao-bg-sub); }
.group-heading { flex:1; min-width:0; border:0; background:transparent; color:var(--wao-text); text-align:left; padding:var(--wao-space-1); font:inherit; cursor:pointer; }
.group-add-button { display:inline-flex; align-items:center; justify-content:center; flex-shrink:0; width:28px; height:28px; padding:0; border:0; background:transparent; color:var(--wao-text-3); border-radius:var(--wao-radius-control); cursor:pointer; opacity:0; }
.group-add-button i { font-size:16px; }
.group-header:hover .group-add-button, .group-header:focus-within .group-add-button { opacity:1; }
.group-add-button:hover { color:var(--wao-on-primary); background:var(--wao-primary); }
@media (hover:none) { .group-add-button { opacity:1; } }
.group-heading small { color:var(--wao-text-weak); }
.cell-menu button.priority-option { display:grid; grid-template-columns:16px 24px 1fr; justify-content:start; gap:var(--wao-space-2); }
.priority-option > i { text-align:center; }
.priority-code { font-variant-numeric:tabular-nums; }
.cell-menu button.priority-option[aria-checked=true] { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.root-drop { position:absolute; bottom:0; left:0; right:0; z-index:1; background:var(--wao-bg-card); padding:var(--wao-space-2); border:1px dashed var(--wao-primary); color:var(--wao-primary-text); }
.drop-inside { outline:1px solid var(--wao-primary); background:var(--wao-primary-soft); }
.drop-before { border-top:2px solid var(--wao-primary); }
.drop-after { border-bottom:2px solid var(--wao-primary); }
.tree-toggle,.tree-spacer { width:16px; flex-shrink:0; }
.tree-toggle { height:32px; padding:0; color:var(--wao-text-weak); }
.task-select { display:flex; align-items:center; gap:var(--wao-space-1); flex:1; min-width:0; min-height:38px; padding:0 var(--wao-space-1) 0 0; text-align:left; font-size:var(--wao-font-body); }
.task-title { font-family:var(--wao-font); font-weight:400; color:var(--wao-text-2); letter-spacing:0.01em; -webkit-font-smoothing:antialiased; flex:1; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; line-height:1.5; }
.finished { text-decoration:line-through; color:var(--wao-text-weak); }
.task-meta.overdue { color:var(--wao-danger-strong); }
.task-meta { flex-shrink:0; color:var(--wao-text-weak); font-size:var(--wao-font-caption); }
h2 { font-size:calc(var(--wao-font-body) + 2px); line-height:1.5; overflow-wrap:anywhere; margin:var(--wao-space-2) 0 var(--wao-space-1); }
.detail-title-input { width:100%; min-width:0; box-sizing:border-box; border:0; border-radius:var(--wao-radius-control); background:transparent; color:var(--wao-text); font:inherit; padding:var(--wao-space-1) 0; }
.detail-title-input:hover,.detail-title-input:focus { background:var(--wao-bg-sub); }
.save-status { margin-top:var(--wao-space-1); display:flex; gap:var(--wao-space-2); flex-wrap:wrap; }
.parent-task-link { justify-content:flex-start; max-width:100%; margin-top:var(--wao-space-3); padding:var(--wao-space-1) var(--wao-space-2); font-family:var(--wao-font); text-align:left; }
.parent-task-link span { min-width:0; overflow-wrap:anywhere; }
.parent-task-link i { flex-shrink:0; }
.empty { padding:var(--wao-space-2); color:var(--wao-text-weak); }
button:focus-visible,input:focus-visible,select:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(max-width:760px) {
  .group-add-button { width:44px; height:44px; }
  .checklist-layout { grid-template-columns:minmax(0,1fr) !important; }
  .checklist-divider { display:none; }
  .checklist-tasks { max-height:260px; padding:0; }
  .checklist-detail { position:static; max-height:none; min-height:300px; border-left:0; border-top:1px solid var(--wao-line); padding:var(--wao-space-2) 0 0; }
  .task-select { min-height:44px; }
}
</style>
