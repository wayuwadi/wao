<script setup lang="ts">
import TaskTimeSettings from './TaskTimeSettings.vue'
import { deadlineFeedback } from '@/composables/taskChangeFeedback'
import { useTaskPinsStore } from '@/stores/taskPins'
import { DOCUMENT_EDITOR_FEATURES } from '@/config/documentEditor'
import { useTaskEditorUpload } from '@/composables/taskEditorUpload'
const uploadEditorFile = useTaskEditorUpload()
import { useTaskViewMemory, useTaskScrollMemory } from '@/composables/taskViewMemory'
import { computed, defineAsyncComponent, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useMemberPreference } from '@/composables/memberPreference'
import { useSessionStore } from '@/stores/session'
import { isTaskFinished } from '@/utils/projectCompletion'
import TaskLabelEditor from '@/components/task/TaskLabelEditor.vue'
import CellMenu from '@/components/task/CellMenu.vue'
import { PROJECT_STATUS_COLORS, PROJECT_PRIORITY_COLORS, PROJECT_PRIORITY_LABELS, projectStatusLabel } from '@/utils/projectStatusPresentation'
import { selectableTaskStatuses } from '@/config/features'
import type { Task, TaskStatus, Priority } from '@/types'
import { useDataStore } from '@/stores/data'
import { useProjectItemActions } from '@/composables/projectItemActions'
import { toast } from '@/composables/toast'
import { taskHierarchyDrop } from '@/utils/taskDrop'
import { taskTreeRows } from '@/utils/taskHierarchy'
import { subItemKey, withProjectAncestors, type ProjectItemRefs } from '@/utils/projectWorkItems'
import { searchProjectTasks } from '@/utils/projectTaskSearch'
import WaoCard from '@/components/ui/WaoCard.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import TaskContextMenu from './TaskContextMenu.vue'
import SaveHint from '@/components/ui/SaveHint.vue'
import TaskDocuments from '@/components/task/TaskDocuments.vue'
import TaskComments from '@/components/task/TaskComments.vue'
import TaskDueDate from '@/components/task/TaskDueDate.vue'
import { deadlineInfo, shiftDay } from '@/utils/taskDeadline'
import { todayIso } from '@/utils/time'
import { useTaskDeadlineDefaults } from '@/composables/taskDeadlineDefaults'
import { taskExecutionLabel } from '@/utils/taskSchedule'
import { sortTodoTasks, todoDateLabel, todoTaskDate, todoTaskGroups, todoTimelinePlacement, TODO_TIMELINE_DAYS, type TodoViewSettings, type TodoDisplaySettings } from '@/utils/todoViewSettings'

const descriptionHost = ref<HTMLElement | null>(null)
const outline = ref<{ text: string; level: number }[]>([])
const outlineOpen = ref(false)
let outlineObserver: MutationObserver | null = null
function refreshOutline() {
  outline.value = Array.from(descriptionHost.value?.querySelectorAll('.tiptap h1, .tiptap h2, .tiptap h3, .tiptap h4') ?? []).map(node => ({ text: node.textContent?.trim() || '未命名标题', level: Number(node.tagName.slice(1)) }))
}
watch(descriptionHost, host => {
  outlineObserver?.disconnect()
  outlineOpen.value = false
  refreshOutline()
  if (!host) return
  outlineObserver = new MutationObserver(refreshOutline)
  outlineObserver.observe(host, { childList: true, subtree: true, characterData: true })
}, { flush: 'post' })
onBeforeUnmount(() => outlineObserver?.disconnect())
function jumpToHeading(index: number) {
  const heading = descriptionHost.value?.querySelectorAll<HTMLElement>('.tiptap h1, .tiptap h2, .tiptap h3, .tiptap h4')[index]
  const panel = descriptionHost.value?.closest<HTMLElement>('.checklist-detail')
  if (heading && panel) {
    if (panel.scrollHeight > panel.clientHeight) panel.scrollTop += heading.getBoundingClientRect().top - panel.getBoundingClientRect().top - 48
    else heading.scrollIntoView({ block: 'start' })
  }
  outlineOpen.value = false
}

const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const props = defineProps<{ showAbandoned?: boolean; groupOverdue?: boolean; hideGroups?: boolean; hideListName?: boolean; keepScopeOnChildAdd?: boolean; stateKey?: string; selectedIds?: string[]; selectionMode?: boolean; items: Task[]; contextItems: Task[]; itemRefs: ProjectItemRefs; showDone: boolean; collapsed: string[]; query: string; busy?: boolean; disableHierarchyDrag?: boolean; todoView?: TodoViewSettings; todoDisplay?: TodoDisplaySettings; todoTags?: (id:string)=>readonly string[]; sections?: { id: string; name: string; header: boolean; collapsed: boolean; itemIds: string[] }[] }>()
const emit = defineEmits<{ select: [id: string]; toggle: [id: string]; toggleGroup: [id: string]; add: [groupId: string]; reveal: [id: string]; move: [source: string, target: string | null, side: 'before' | 'inside' | 'after'] }>()
const data = useDataStore()
const pins=useTaskPinsStore()
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
// Only the row being completed may stay visible while its shared source saves.
const retainedCompletion = ref(new Set<string>())
watch(() => [...retainedCompletion.value].map(id => [id, data.taskSaveState(actions.sourceId(id))] as const), states => {
  for (const [id, state] of states) if (state === 'saved') retainedCompletion.value.delete(id)
})
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
    retainedCompletion.value.add(id)
    cancelCompletion(id)
    await actions.status(id, '已完成')
    if (data.taskSaveState(actions.sourceId(id)) !== 'error') retainedCompletion.value.delete(id)
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
const rowStatusMenu = ref<{ id: string; anchor: HTMLElement } | null>(null)
const rowStatusTask = computed(() => props.contextItems.find(task => task.id === rowStatusMenu.value?.id))
function rowStatuses(task?: Task): TaskStatus[] {
  return task && actions.isSub(task.id) ? ['待办', '进行中', '待验证', '已完成'] : selectableTaskStatuses(task?.status)
}
const rowStatusOptions = computed(() => rowStatuses(rowStatusTask.value))
function openRowStatus(event: MouseEvent, id: string) {
  propertyMenu.value = null
  rowStatusMenu.value = rowStatusMenu.value?.id === id ? null : { id, anchor: event.currentTarget as HTMLElement }
}
async function setTaskStatus(id: string | undefined, status: TaskStatus) {
  if (!id || actions.busy(id) || props.contextItems.find(task => task.id === id)?.status === status) return
  cancelCompletion(id)
  if (status === '已完成') { toggleCompletion(id); return }
  await actions.status(id, status)
}
function changeRowStatus(status: TaskStatus) {
  const id = rowStatusMenu.value?.id
  rowStatusMenu.value = null
  void setTaskStatus(id, status)
}
watch(() => useSessionStore().userId, () => { rowStatusMenu.value = null })
watch(rowStatusTask, task => { if (!task) rowStatusMenu.value = null })

watch(() => useSessionStore().userId, () => {
  retainedCompletion.value.clear()
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
  return Number.isFinite(value) ? Math.min(.75, Math.max(.25, value)) : .55
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
const popupDetail = computed(() => props.todoDisplay?.detail === 'popup')
const splitStyle = computed(() => popupDetail.value ? { gridTemplateColumns: 'minmax(0, 1fr)' } : compact.value ? {} : { gridTemplateColumns: `minmax(0, ${splitRatio.value}fr) 9px minmax(0, ${1 - splitRatio.value}fr)` })
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
const viewMemory = useTaskViewMemory(() => props.stateKey ?? 'checklist', () => ({ selectedId: '' }))
const selectedId = computed({ get: () => viewMemory.value.selectedId, set: value => { viewMemory.value.selectedId = value } })
const detailOnlySelection = ref(false)
// A date edit can remove its own row from the active filter. Keep its save/retry UI
// until the server acknowledges the write, including a later retry after failure.
const pendingDateChanges = ref(new Set<string>())
watch(() => [...pendingDateChanges.value].map(id => [id, data.taskSaveState(actions.sourceId(id))] as const), states => {
  for (const [id, state] of states) if (state === 'saved') pendingDateChanges.value.delete(id)
})
watch(() => useSessionStore().userId, () => pendingDateChanges.value.clear(), { flush: 'sync' })
const matches = computed(() => {
  const itemIds=new Set(props.items.map(t=>t.id))
  const candidates = [...props.items, ...props.contextItems.filter(t => pendingDateChanges.value.has(t.id) && !itemIds.has(t.id))]
  const items = candidates.filter(t => (props.showAbandoned || t.status!=='已放弃') && ( props.query.trim() || props.showDone || !isTaskFinished(t) || pendingCompletion.value.has(t.id) || (retainedCompletion.value.has(t.id) && ['saving','error'].includes(data.taskSaveState(actions.sourceId(t.id))))))
  const found = ordered(props.query ? searchProjectTasks(items, props.query).map(hit => hit.task) : items)
  const located = detailOnlySelection.value ? props.contextItems.find(t => t.id === selectedId.value) : null
  return located && !found.some(t => t.id === located.id) ? [...found, located] : found
})
const listName = (task:Task) => task.project ? data.projectById(task.project)?.name || '未知清单' : '收集箱'
const todoMeta = computed(() => ({
  tags: props.todoTags ?? (() => []), list: listName, today: today.value,
  priorityUnset: (id: string) => !!props.itemRefs[id]?.priorityUnset,
}))
function ordered(tasks: Task[]) {
  const view = props.todoView
  return pins.sort(view ? sortTodoTasks(tasks, view.sort, view.direction, todoMeta.value) : tasks)
}
const board = computed(() => props.todoView?.layout === 'board')
const timeline = computed(() => props.todoView?.layout === 'timeline')
const grouping = computed(() => {
  const group = props.todoView?.group ?? 'auto'
  if (group !== 'auto') return group
  return board.value ? 'status' : props.groupOverdue ? 'time' : 'none'
})
const hierarchyDrag = computed(() => !props.disableHierarchyDrag && (!props.todoView ||
  (!board.value && !timeline.value && props.todoView.sort === 'manual' && (grouping.value === 'none' || (grouping.value === 'time' && props.groupOverdue)))))
const boardStatusDrag = computed(() => board.value && grouping.value === 'status' && !props.showAbandoned)
const timelineStart = ref('')
const timelineFrom = computed(() => timelineStart.value || shiftDay(today.value, -1))
const timelineDays = computed(() => timeline.value ? Array.from({ length:TODO_TIMELINE_DAYS }, (_, i) => shiftDay(timelineFrom.value,i)) : [])
const timelineTo = computed(() => shiftDay(timelineFrom.value, TODO_TIMELINE_DAYS - 1))
const placements = computed(() => new Map(timeline.value ? matches.value.map(task => [task.id, todoTimelinePlacement(task, timelineFrom.value, timelineTo.value)]) : []))
const timelineRows = computed(() => matches.value.filter(task => {
  const placement = placements.value.get(task.id)
  return placement && (placement.execution || placement.deadline || placement.undated)
}))
const timelineHidden = computed(() => matches.value.length - timelineRows.value.length)
function stepTimeline(offset: number) { timelineStart.value = shiftDay(timelineFrom.value, offset * TODO_TIMELINE_DAYS) }
const shortDay = (date: string) => date.slice(5).replace('-', '/')
const expandableIds = computed(() => {
  const result = new Set<string>(), byId = new Map(props.contextItems.map(task => [task.id, task]))
  for (const task of props.contextItems) {
    if (isTaskFinished(task)) continue
    let parent = task.parentTaskId
    const seen = new Set<string>([task.id])
    while (parent && !seen.has(parent)) { seen.add(parent); result.add(parent); parent = byId.get(parent)?.parentTaskId }
  }
  return result
})
const rows = computed(() => {
  if (timeline.value) return timelineRows.value.map(task => ({ task, depth: 0 }))
  if (props.todoView && (grouping.value !== 'none' || board.value)) return matches.value.map(task => ({ task, depth: 0 }))
  return taskTreeRows(ordered(withProjectAncestors(matches.value, props.contextItems)), props.query ? [] : props.collapsed.filter(id => expandableIds.value.has(id)))
})
const dateSectionsCollapsed = ref(new Set<string>())
function treeSection(items: Task[]) {
  const ids = new Set(items.map(task => task.id))
  const tree = ordered(withProjectAncestors(items, props.contextItems))
  // An ancestor supplied as context cannot hide a match in another date/tag group.
  const expandable = new Set(tree.flatMap(task => task.parentTaskId && ids.has(task.parentTaskId) ? [task.parentTaskId] : []))
  return { count:items.length, expandable, rows:taskTreeRows(tree, props.query ? [] : props.collapsed.filter(id=>expandable.has(id))) }
}
const groupedRows = computed(() => {
  if (props.todoView) {
    const group = grouping.value
    if (group === 'none' && !board.value) return [{ id:'all', name:'任务', header:false, collapsed:false, rows:rows.value, count:matches.value.length, expandable:timeline.value ? new Set<string>() : expandableIds.value }]
    const groups = todoTaskGroups(rows.value.map(row => row.task), group, todoMeta.value, props.groupOverdue)
    if (boardStatusDrag.value) {
      for (const status of ['待办', '进行中', '待验证', ...(props.showDone ? ['已完成'] : [])] as TaskStatus[]) {
        if (!groups.some(g => g.id === status)) groups.push({ id:status, name:projectStatusLabel(status), tasks:[] })
      }
      const statuses: TaskStatus[] = ['待办', '收集中', '进行中', 'AI 进行中', '待验证', '已完成', '已放弃']
      groups.sort((a,b) => statuses.indexOf(a.id as TaskStatus) - statuses.indexOf(b.id as TaskStatus))
    }
    return groups.map(g => ({
      id:g.id, name:g.name, header:true, collapsed:!board.value && dateSectionsCollapsed.value.has(g.id),
      ...(!board.value && !timeline.value ? treeSection(g.tasks) : { count:g.tasks.length, expandable:new Set<string>(), rows:g.tasks.map(task=>({task,depth:0})) }),
    }))
  }
  if (props.groupOverdue) {
    return [
      { id:'overdue', name:'已过期', items:matches.value.filter(task=>overdue(task)>0) },
      { id:'today', name:'今天', items:matches.value.filter(task=>overdue(task)===0) },
    ].filter(section=>section.items.length).map(({ items, ...section }) => {
      return { ...section, header:true, collapsed:dateSectionsCollapsed.value.has(section.id), ...treeSection(items) }
    })
  }
  return (props.sections ?? [{ id:'__all', name:'', header:false, collapsed:false, itemIds:props.contextItems.map(t=>t.id) }]).map(section=>{
    const sectionRows = rows.value.filter(row=>section.itemIds.includes(row.task.id))
    return {...section, rows:sectionRows, count:sectionRows.length, expandable:expandableIds.value}
  })
})
const displayedRows = computed(() => groupedRows.value.flatMap(section => section.rows))
function toggleSection(id:string) {
  if(!props.todoView && !props.groupOverdue){emit('toggleGroup',id);return}
  if(dateSectionsCollapsed.value.has(id))dateSectionsCollapsed.value.delete(id);else dateSectionsCollapsed.value.add(id)
}
const displayDate = (task:Task) => props.todoView ? todoTaskDate(task) : task.due
const relativeDate = (task:Task) => todoDateLabel(displayDate(task), today.value, props.todoDisplay?.date ?? 'time')
const canDragStatus = (task: Task) => boardStatusDrag.value && task.workItemType !== 'note' && !actions.busy(task.id) && !pendingCompletion.value.has(task.id)

const dragging = ref<string | null>(null)
const over = ref<{ id: string; side: 'before' | 'inside' | 'after' } | null>(null)
function startDrag(id: string, event: DragEvent) {
  if (props.busy || !hierarchyDrag.value && !boardStatusDrag.value) { event.preventDefault(); return }
  dragging.value = id
  event.dataTransfer?.setData('text/plain', id)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function drop(target: string | null, event: DragEvent) {
  if (dragging.value && !props.busy && hierarchyDrag.value) emit('move', dragging.value, target, target ? taskHierarchyDrop(event) : 'inside')
  dragging.value = null; over.value = null
}
const boardDrop = ref<string>()
function boardDropAllowed(section: string) {
  const item = props.items.find(task => task.id === dragging.value)
  return !!item && canDragStatus(item) && rowStatuses(item).includes(section as TaskStatus) && item.status !== section
    && ['待办','进行中','待验证','已完成'].includes(section)
}
function overBoard(event: DragEvent, section: string) {
  if (!boardDropAllowed(section)) return
  event.preventDefault(); boardDrop.value = section
}
function dropBoard(event: DragEvent, section: string) {
  if (!boardDropAllowed(section)) return
  event.preventDefault()
  const id = dragging.value
  dragging.value = null; boardDrop.value = undefined
  void setTaskStatus(id ?? undefined, section as TaskStatus)
}
function openRow(id: string) {
  detailOnlySelection.value = false; selectedId.value = id
  if (popupDetail.value) actions.open(id)
}
watch(displayedRows, (value, previous) => {
  if (!value.length && data.taskLoadState !== 'loaded') return
  if (detailOnlySelection.value && props.contextItems.some(t => t.id === selectedId.value)) return
  const visibleMatches = value.filter(row => matches.value.some(t => t.id === row.task.id))
  const selectableRows = props.todoView || props.groupOverdue ? value : visibleMatches
  if (!selectableRows.some(row => row.task.id === selectedId.value)) {
    const at=Math.max(0,previous?.findIndex(row=>row.task.id===selectedId.value)??0)
    const hadFocus=document.activeElement instanceof HTMLElement && !!document.activeElement.closest('.checklist-row')
    const candidates=visibleMatches.length?visibleMatches:value
    selectedId.value=candidates[Math.min(at,candidates.length-1)]?.task.id??''
    if(hadFocus)void nextTick(()=>focusRow(selectedId.value))
  }
}, { immediate: true })
const rememberScroll = useTaskScrollMemory(layout, () => `${props.stateKey ?? 'checklist'}:scroll`, () => `${displayedRows.value.length}:${selectedId.value}`)
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
async function changePriority(priority: Priority | undefined) {
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
  // Keep the selected detail visible even if moving it changes the current filter.
  detailOnlySelection.value = true
  try { await actions.save(selected.value.id, () => data.moveTaskToGroup(source.value.taskId, groupId)) }
  catch { toast('分组未保存，请重试', 'ri-error-warning-line') }
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
  if (!selected.value || actions.busy(selected.value.id)) return
  const id = selected.value.id
  const { taskId, subId } = source.value
  const before=subId?data.subById(taskId,subId)?.due:data.taskById(taskId)?.due
  pendingDateChanges.value.add(id)
  try {
    await actions.save(id, () => subId ? data.updateSub(taskId, subId, { due }) : data.patchTask(taskId, { due }))
    if (data.taskSaveState(taskId) === 'saved') { pendingDateChanges.value.delete(id); deadlineFeedback([{taskId,subId,before,after:due}]) }
  } catch {
    toast('日期未保存，请重试', 'ri-error-warning-line')
  }
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
const contextMenu = ref<InstanceType<typeof TaskContextMenu> | null>(null)
const deleteTarget = ref<{ id: string; taskId: string; subId?: string; title: string } | null>(null)
const deleting = ref(false)
const deleteError = ref('')
const deleteBusy = computed(() => deleting.value || props.busy || data.movingTask || data.movingSubtasks || !!deleteTarget.value && data.taskSaveState(deleteTarget.value.taskId) === 'saving')
watch(() => useSessionStore().userId, () => { deleteTarget.value = null; deleteError.value = '' }, { flush: 'sync' })
function requestDelete(id: string, title: string) {
  if (deleting.value || props.busy || actions.busy(id)) return
  deleteTarget.value = { id, ...actions.source(id), title }
  deleteError.value = ''
}
async function confirmDelete() {
  const target = deleteTarget.value
  if (!target || deleteBusy.value) return
  const member = useSessionStore().userId
  deleting.value = true
  deleteError.value = ''
  // Completion is delayed locally. Do not let its timer write the item being deleted.
  for (const id of [...pendingCompletion.value]) {
    if (id === target.id || !target.subId && actions.sourceId(id) === target.taskId) cancelCompletion(id)
  }
  try {
    if (target.subId) {
      if (!data.taskById(target.taskId)) throw new Error('任务已不存在，请刷新列表。')
      if (data.subById(target.taskId, target.subId)) data.removeSub(target.taskId, target.subId)
      await data.flushTask(target.taskId)
      if (data.taskSaveState(target.taskId) === 'error' || data.apiLive && data.taskSaveState(target.taskId) !== 'saved') {
        throw new Error('子任务删除尚未保存，本机修改已保留，请重试或查看保存问题。')
      }
    } else if (!(await data.deleteTask(target.taskId))) {
      throw new Error('删除失败，任务已保留，请重试。')
    }
    if (member !== useSessionStore().userId || deleteTarget.value !== target) return
    retainedCompletion.value.delete(target.id)
    deleteTarget.value = null
    toast(`已删除${target.subId ? '子任务' : '任务'}「${target.title}」`, 'ri-delete-bin-line')
  } catch (error) {
    if (member === useSessionStore().userId && deleteTarget.value === target) deleteError.value = error instanceof Error ? error.message : '删除失败，请重试。'
  } finally { deleting.value = false }
}
const addingChild = ref(false)
const childDraft = ref('')
const childDraftError = ref('')
const childInput = ref<HTMLInputElement | null>(null)
const childDraftKey = computed(() => `wao-inline-sub:${useSessionStore().userId}:${source.value.taskId}${source.value.subId ? `:${source.value.subId}` : ''}`)
const canAddChild = computed(() => !!selected.value && selected.value.workItemType !== 'note' && selected.value.status !== '已放弃')
const childBlocked = computed(() => !!props.busy || data.movingTask || data.movingSubtasks)
watch([selectedId, () => useSessionStore().userId, canAddChild], () => {
  addingChild.value = canAddChild.value
  childDraft.value = ''
  childDraftError.value = ''
  contextMenu.value?.close()
  if (addingChild.value) {
    try { childDraft.value = localStorage.getItem(childDraftKey.value) || '' }
    catch { childDraftError.value = '本机草稿无法读取，请保持页面打开' }
  }
}, { flush: 'sync', immediate: true })
watch(saveState, state => {
  if (state === 'saved' && childDraftError.value.startsWith('子任务已保留')) childDraftError.value = ''
})
async function startChild(id: string) {
  if (childBlocked.value || !props.contextItems.some(t => t.id === id)) return
  const member = useSessionStore().userId
  if (selectedId.value !== id) await selectChild(id)
  if (member !== useSessionStore().userId || selectedId.value !== id) return
  addingChild.value = true
  childDraftError.value = ''
  try { childDraft.value = localStorage.getItem(childDraftKey.value) || '' }
  catch { childDraftError.value = '本机草稿无法读取，请保持页面打开' }
  await nextTick()
  childInput.value?.focus()
  childInput.value?.scrollIntoView?.({ block: 'nearest' })
}
function storeChildDraft(event: Event) {
  childDraft.value = (event.target as HTMLInputElement).value
  try {
    if (childDraft.value) localStorage.setItem(childDraftKey.value, childDraft.value)
    else localStorage.removeItem(childDraftKey.value)
    childDraftError.value = ''
  } catch { childDraftError.value = '草稿无法写入本机，请保持页面打开' }
}
async function addChild() {
  const parentId = selectedId.value
  const member = useSessionStore().userId
  if (!addingChild.value || !canAddChild.value || !childDraft.value.trim() || childBlocked.value) return
  const { taskId, subId } = source.value
  const id = data.addSub(taskId, childDraft.value, subId)
  if (!id) { childDraftError.value = '未能添加子任务，请稍后重试'; return }
  childDraft.value = ''
  try { localStorage.removeItem(childDraftKey.value) }
  catch { childDraftError.value = '子任务已添加，本机输入草稿未能清除' }
  if (props.collapsed.includes(parentId)) emit('toggle', parentId)
  if (!props.keepScopeOnChildAdd) emit('reveal', subItemKey(taskId, id))
  try {
    await data.flushTask(taskId)
    if (member === useSessionStore().userId && selectedId.value === parentId && data.taskSaveState(taskId) === 'error') {
      childDraftError.value = '子任务已保留在任务草稿中，保存失败，请重试保存。'
    }
  }
  catch { toast('子任务尚未保存，已保留草稿，请查看保存问题', 'ri-error-warning-line') }
  await nextTick()
  if (member === useSessionStore().userId && selectedId.value === parentId && addingChild.value) childInput.value?.focus()
}
function childKey(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    event.preventDefault()
    if (!event.isComposing) void addChild()
  }
}
function editRow(task:Task,mode:'date'|'priority'|'tags'|'move'){contextMenu.value?.edit(task,mode)}
function rowKey(event: KeyboardEvent, id:string) {
  const target=event.target as HTMLElement
  if(event.isComposing || target.closest('input,textarea,select,[contenteditable="true"]'))return
  if(event.key==='ArrowDown'||event.key==='ArrowUp'||event.key==='Home'||event.key==='End'){
    if(event.altKey||event.ctrlKey||event.metaKey)return
    const list=[...new Set(groupedRows.value.filter(s=>!s.collapsed).flatMap(s=>s.rows.map(r=>r.task.id)))]
    const at=list.indexOf(id), next=event.key==='Home'?0:event.key==='End'?list.length-1:Math.min(list.length-1,Math.max(0,at+(event.key==='ArrowDown'?1:-1)))
    if(list[next]) {event.preventDefault();event.stopPropagation();selectedId.value=list[next]!;void nextTick(()=>focusRow(list[next]!))}
    return
  }
  if(event.key==='Enter'&&!event.ctrlKey&&!event.metaKey&&target.classList.contains('checklist-row')){event.preventDefault();openRow(id);void nextTick(()=>layout.value?.querySelector<HTMLInputElement>('.detail-title-input')?.focus());return}
  if(event.key===' '&&target.classList.contains('checklist-row')){event.preventDefault();if(props.selectionMode)emit('select',id);else toggleCompletion(id);return}
  if((event.ctrlKey||event.metaKey)&&event.key==='Enter'){event.preventDefault();event.stopPropagation();toggleCompletion(id);return}
  openChecklistMenu(event,id)
}
function focusRow(id:string){ const row=Array.from(layout.value?.querySelectorAll<HTMLElement>('.checklist-row')??[]).find(el=>el.dataset.taskId===id);row?.focus();row?.scrollIntoView?.({block:'nearest'}) }
function returnToRow(event:KeyboardEvent){if(event.key!=='Escape'||propertyMenu.value)return;event.preventDefault();event.stopPropagation();focusRow(selectedId.value)}
function openChecklistMenu(event: MouseEvent | KeyboardEvent, id: string) {
  if (event instanceof KeyboardEvent) {
    if (!(event.shiftKey && event.key === 'F10') && event.key !== 'ContextMenu') return
    event.preventDefault()
  } else if (event.target instanceof Element && event.target.closest('input:not([type=checkbox]), textarea, [contenteditable="true"]')) return
  const task = props.contextItems.find(t => t.id === id) ?? props.items.find(t => t.id === id)
  if (!task) return
  event.preventDefault()
  event.stopPropagation()
  detailOnlySelection.value = !displayedRows.value.some(row => row.task.id === id)
  selectedId.value = id
  void contextMenu.value?.openAt(event, task, [
    ...(!popupDetail.value ? [{ id:'add-child', label:'添加子任务', icon:'ri-node-tree', disabled:childBlocked.value, run:()=>startChild(id) }] : []),
    { id:'delete', label:actions.isSub(id)?'删除子任务':'删除任务', icon:'ri-delete-bin-line', danger:true, separatorBefore:true,
      disabled:deleting.value || props.busy || actions.busy(id), run:()=>requestDelete(id,task.title||'未命名任务') },
  ])
}
async function uploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) { done(null, file.name); return }
  const image = await data.uploadImage(file, `任务 · ${selected.value?.title ?? ''}`)
  done(image?.url ?? null, image?.name ?? file.name)
}
</script>

<template>
  <div v-if="split.error.value && !popupDetail" class="split-sync" role="status">{{ split.error.value }}<button @click="split.retry()">重试布局同步</button></div>
  <div ref="layout" @scroll.capture="rememberScroll" class="checklist-layout" :class="{ 'is-resizing': splitPointer !== null, 'is-compact': compact && !popupDetail, 'todo-checklist': hideGroups, 'popup-detail':popupDetail }" :style="splitStyle">
    <div class="checklist-list-pane"><slot name="list-header" />
    <div v-if="timeline" class="timeline-controls" aria-label="时间线日期范围">
      <button type="button" title="上一段" aria-label="上一段时间线" @click="stepTimeline(-1)"><i class="ri-arrow-left-s-line" aria-hidden="true" /></button>
      <button type="button" title="下一段" aria-label="下一段时间线" @click="stepTimeline(1)"><i class="ri-arrow-right-s-line" aria-hidden="true" /></button>
      <button type="button" @click="timelineStart=''">回到今天</button>
      <input type="date" aria-label="时间线起始日期" :value="timelineFrom" @change="timelineStart=($event.target as HTMLInputElement).value" />
      <span v-if="timelineHidden" role="status">{{ timelineHidden }} 项在当前日期范围外</span>
    </div>
    <WaoCard class="checklist-tasks" :class="{ 'timeline-tasks':timeline }" :data-task-scroll="todoView?.layout ?? 'list'">
      <div v-if="timeline" class="timeline-line timeline-header">
        <span class="timeline-task-cell">任务</span><div class="timeline-track">
          <time v-for="day in timelineDays" :key="day" :datetime="day" :class="{ 'timeline-today':day===today }">{{ shortDay(day) }}</time>
        </div>
      </div>
      <p v-if="!displayedRows.length && (!board || !groupedRows.length)" class="empty">{{ query ? '没有匹配的任务，试试其他关键词' : timelineHidden ? '当前日期范围内没有任务' : '当前没有待办任务' }}</p>
      <div v-if="dragging && hierarchyDrag" class="root-drop" @dragover.prevent @drop.prevent="drop(null, $event)">拖到这里移为顶层任务</div>
      <div :class="{ 'board-columns':board }">
      <section v-for="section in groupedRows" :key="section.id" :class="{ 'board-column':board, 'board-drop':boardDrop===section.id }" @dragover="overBoard($event,section.id)" @dragleave="!($event.currentTarget as HTMLElement).contains($event.relatedTarget as Node | null) && (boardDrop=undefined)" @drop="dropBoard($event,section.id)">
      <div v-if="section.header" class="group-header">
        <h2 v-if="board" class="board-heading">{{ section.name }} <small>{{ section.rows.length }}</small></h2>
        <button v-else class="group-heading" :aria-expanded="!section.collapsed" @click="toggleSection(section.id)"><i :class="section.collapsed ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>{{ section.name }} <small>{{ section.count }}</small></button>
        <slot v-if="groupOverdue || todoView" name="section-action" :section="section" /><button v-else class="group-add-button" :aria-label="`在 ${section.name} 新建任务`" title="快速新建任务" @click="emit('add', section.id)"><i class="ri-add-line" aria-hidden="true"></i></button>
      </div>
      <div v-show="!section.collapsed">
      <slot name="group-add" :section="section" />
      <div v-for="{ task, depth } in section.rows" :key="task.id" :class="{ 'timeline-line':timeline }">
      <div class="checklist-row" :tabindex="selectedId===task.id?0:-1" :aria-label="task.title" :data-task-id="task.id" @contextmenu.capture="openChecklistMenu($event, task.id)" @keydown="rowKey($event, task.id)" :draggable="!busy && (hierarchyDrag || canDragStatus(task))" @dragstart="startDrag(task.id, $event)" @dragend="dragging = null; over = null; boardDrop = undefined" @dragover="hierarchyDrag && dragging && ($event.preventDefault(), over = { id: task.id, side: taskHierarchyDrop($event) })" @drop="hierarchyDrag && ($event.preventDefault(), $event.stopPropagation(), drop(task.id, $event))" :class="{ selected: selectedId === task.id && !popupDetail, 'timeline-task-cell':timeline, 'row-detailed':todoView?.showDetails || board, 'drop-inside': dragging && over?.id === task.id && over.side === 'inside', 'drop-before': dragging && over?.id === task.id && over.side === 'before', 'drop-after': dragging && over?.id === task.id && over.side === 'after' }" :style="{ paddingInlineStart: `${Math.min(depth, 6) * 16 + 8}px` }">
        <button v-if="section.expandable.has(task.id)" class="tree-toggle" :aria-label="`${collapsed.includes(task.id) ? '展开' : '收起'} ${task.title}`" :aria-expanded="!collapsed.includes(task.id)" @click="$emit('toggle', task.id)"><i :class="collapsed.includes(task.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
        <span v-else-if="!board && !timeline" class="tree-spacer"></span>
        <input v-if="selectionMode" type="checkbox" :checked="selectedIds?.includes(task.id)" :disabled="!matches.some(item=>item.id===task.id)" :aria-label="`选择任务 ${task.title}`" @change="emit('select', task.id)" />
        <i v-else-if="task.workItemType==='note'" class="ri-sticky-note-line" aria-label="笔记" /><i v-else-if="task.status==='已放弃'" class="ri-close-circle-line" aria-label="已放弃" />
        <input v-else type="checkbox" :style="{ '--task-priority-color': priorityColor(task) }" :checked="isComplete(task)" :disabled="!pendingCompletion.has(task.id) && actions.busy(task.id)" :aria-label="`完成 ${task.title}`" @change="toggleCompletion(task.id)" />
        <button class="task-select" :aria-pressed="popupDetail ? undefined : selectedId === task.id" :aria-haspopup="popupDetail ? 'dialog' : undefined" @click="openRow(task.id)">
          <i v-if="pins.has(task.id)" class="ri-pushpin-line" aria-label="已置顶" /><span class="task-title" :title="task.title" :class="{ finished: isComplete(task) }">{{ task.title || '未命名任务' }}</span>
          <span v-if="hideGroups && !hideListName" class="task-meta task-list-name" :title="listName(task)">{{ listName(task) }}</span><i v-if="task.desc && !todoView?.showDetails" class="ri-file-text-line task-meta" aria-label="含描述" /><span v-if="displayDate(task)" class="task-meta" :class="{ overdue:overdue(task) > 0 }" :title="overdue(task) ? `已延期 ${overdue(task)} 天` : displayDate(task)">{{ relativeDate(task) }}</span>
          <span v-if="todoView?.showDetails && (task.start || task.startAt)" class="row-execution task-meta">{{ taskExecutionLabel(task,today) }}<template v-if="task.startAt && task.endAt"> → {{ task.endAt.replace('T',' ').slice(0,16) }}</template></span>
          <span v-if="todoView?.showDetails && todoTags?.(task.id).length" class="row-preview">{{ todoTags(task.id).map(tag=>'#'+tag).join(' · ') }}</span>
          <span v-if="todoView?.showDetails && task.desc" class="row-preview">{{ task.desc.trim().slice(0, 200) }}</span>
        </button>
        <div class="row-quick-fields" @pointerdown.stop @dragstart.prevent.stop>
          <button v-if="task.workItemType!=='note'" :aria-label="'修改 '+task.title+' 的截止日期'" title="截止日期" :disabled="actions.busy(task.id)" @click.stop="editRow(task,'date')"><i class="ri-calendar-line" /></button>
          <button :aria-label="'修改 '+task.title+' 的优先级'" title="优先级" :style="{color:priorityColor(task)}" :disabled="actions.busy(task.id)" @click.stop="editRow(task,'priority')"><i class="ri-flag-line" /></button>
          <button :aria-label="'修改 '+task.title+' 的标签'" title="标签" @click.stop="editRow(task,'tags')"><i class="ri-price-tag-3-line" /></button>
          <button v-if="!actions.isSub(task.id) && !task.parentTaskId" :aria-label="'移动 '+task.title+' 到清单'" title="移动清单" :disabled="actions.busy(task.id)" @click.stop="editRow(task,'move')"><i class="ri-folder-transfer-line" /></button>
        </div>
        <button v-if="task.workItemType!=='note' && task.status!=='已放弃'" class="row-status" :data-status="task.status" :aria-label="`修改 ${task.title} 的状态：${projectStatusLabel(task.status)}`" aria-haspopup="menu" :aria-expanded="rowStatusMenu?.id === task.id" :disabled="actions.busy(task.id) || pendingCompletion.has(task.id)" @click.stop="openRowStatus($event, task.id)" @pointerdown.stop @dragstart.prevent.stop>
          <span class="row-status-dot" aria-hidden="true"></span>{{ projectStatusLabel(task.status) }}<i class="ri-arrow-down-s-line" aria-hidden="true"></i>
        </button>
        <button v-if="data.taskSaveState(actions.sourceId(task.id)) === 'error'" class="row-status-retry" :title="data.taskSaveError(actions.sourceId(task.id))" :aria-label="`重试保存 ${task.title}`" @click.stop="data.flushTask(actions.sourceId(task.id))">未保存 · 重试</button>
        <button class="row-more" :aria-label="`任务操作 ${task.title}`" @click="openChecklistMenu($event,task.id)"><i class="ri-more-line" /></button>
      </div>
      <div v-if="timeline" class="timeline-track timeline-bars" :style="{ '--timeline-today-column': timelineDays.indexOf(today)+1 }">
        <button v-if="placements.get(task.id)?.execution" type="button" class="timeline-execution" :style="{gridColumn:`${placements.get(task.id)?.execution?.from} / ${placements.get(task.id)?.execution?.to}`, '--bar-color':priorityColor(task)}" :title="taskExecutionLabel(task,today)" :aria-label="`执行安排：${task.title}`" @click="openRow(task.id)"></button>
        <button v-if="placements.get(task.id)?.deadline" type="button" class="timeline-deadline" :style="{gridColumn:placements.get(task.id)?.deadline || undefined}" :title="`截止 ${task.due}`" :aria-label="`${task.title} 截止 ${task.due}`" @click="openRow(task.id)"><i class="ri-flag-fill" aria-hidden="true" /></button>
        <span v-if="placements.get(task.id)?.undated" class="timeline-undated">无日期</span>
      </div>
      </div>
      <p v-if="board && !section.rows.length" class="board-empty">暂无任务</p>
      </div></section></div>
    </WaoCard></div>
    <div v-if="!popupDetail" class="checklist-divider" role="separator" tabindex="0" aria-label="调整任务清单和详情宽度" aria-orientation="vertical" :aria-valuenow="Math.round(splitRatio * 100)" :aria-valuemin="Math.round(boundSplit(.25) * 100)" :aria-valuemax="Math.round(boundSplit(.75) * 100)" title="拖动调整宽度；方向键微调，双击恢复默认" @pointerdown="startSplit" @pointermove="moveSplit" @pointerup="endSplit()" @pointercancel="endSplit(false)" @lostpointercapture="splitPointer !== null && endSplit(false)" @keydown="keySplit" @dblclick="split.set(.55)"></div>
    <WaoCard v-if="!popupDetail" :data-task-scroll="`detail:${selectedId}`" class="checklist-detail" @keydown="returnToRow" padding="roomy" aria-label="任务详情卡片">
      <template v-if="selected">
        <div class="detail-caption"><span v-if="selected.workItemType==='note'"><i class="ri-sticky-note-line" /> 笔记</span><span v-else-if="selected.status==='已放弃'">已放弃</span><label v-else class="detail-completion"><input type="checkbox" :aria-label="`完成 ${selected.title}`" :style="{ '--task-priority-color': priorityColor(selected) }" :checked="isComplete(selected)" :disabled="!pendingCompletion.has(selected.id) && actions.busy(selected.id)" @change="toggleCompletion(selected.id)" /><span v-if="!hideGroups || pendingCompletion.has(selected.id)">{{ pendingCompletion.has(selected.id) ? '3 秒内可取消' : selected.status === '已完成' ? '已完成' : '未完成' }}</span></label><span v-if="taskGroups.length" ref="groupAnchor" class="property-anchor">
            <button class="group-chip" aria-label="任务分组" aria-haspopup="menu" :aria-expanded="propertyMenu === 'group'" :title="inheritedGroup ? '子任务跟随父任务分组，请在父任务上调整' : '切换分组'" :disabled="inheritedGroup || actions.busy(selected.id)" @click="propertyMenu = propertyMenu === 'group' ? null : 'group'"><i class="ri-folder-2-line"></i>{{ taskGroups.find(group => group.id === selectedGroup)?.name }}<i v-if="!inheritedGroup" class="ri-arrow-down-s-line"></i></button>
            <CellMenu v-if="propertyMenu === 'group'" :anchor="groupAnchor" @close="propertyMenu = null"><button v-for="group in taskGroups" :key="group.id" role="menuitemradio" :aria-checked="group.id === selectedGroup" @click="changeGroup(group.id)">{{ group.name }}<i v-if="group.id === selectedGroup" class="ri-check-line"></i></button></CellMenu>
          </span>
          <span v-if="selected.status !== '已完成' && selected.status!=='已放弃' && selected.workItemType!=='note'" ref="statusAnchor" class="property-anchor">
            <button class="status-pill" aria-label="任务状态" aria-haspopup="menu" :aria-expanded="propertyMenu === 'status'" :style="{ color: PROJECT_STATUS_COLORS[selected.status], borderColor: PROJECT_STATUS_COLORS[selected.status] }" :disabled="actions.busy(selected.id)" @click="propertyMenu = propertyMenu === 'status' ? null : 'status'">{{ projectStatusLabel(selected.status) }}</button>
            <CellMenu v-if="propertyMenu === 'status'" :anchor="statusAnchor" @close="propertyMenu = null"><button v-for="status in statusOptions" :key="status" role="menuitemradio" :aria-checked="status === selected.status" @click="changeStatus(status)"><span class="status-pill" :style="{ color: PROJECT_STATUS_COLORS[status], borderColor: PROJECT_STATUS_COLORS[status] }">{{ projectStatusLabel(status) }}</span><i v-if="status === selected.status" class="ri-check-line"></i></button></CellMenu>
          </span>
          <TaskDueDate v-if="selected.workItemType!=='note'" :key="selected.id" :due="selected.due" :today="today" :completed="selected.status === '已完成'" :disabled="actions.busy(selected.id)" :missing-count="deadlineDefaults.missingCount.value" :bulk-busy="deadlineDefaults.busy.value" @fill-missing="deadlineDefaults.fill" @change="changeDue" /><span v-if="selected.workItemType!=='note'" ref="priorityAnchor" class="priority-anchor">
            <button class="priority-flag" aria-label="任务优先级" :title="props.itemRefs[selected.id]?.priorityUnset ? '设置优先级' : selected.pr + ' · ' + PROJECT_PRIORITY_LABELS[selected.pr]" aria-haspopup="menu" :aria-expanded="propertyMenu === 'priority'" :style="{ color: priorityColor(selected) }" :disabled="actions.busy(selected.id)" @click="propertyMenu = propertyMenu === 'priority' ? null : 'priority'"><i :class="props.itemRefs[selected.id]?.priorityUnset ? 'ri-flag-line' : 'ri-flag-fill'"></i></button>
            <CellMenu v-if="propertyMenu === 'priority'" :anchor="priorityAnchor" @close="propertyMenu = null"><button v-if="props.itemRefs[selected.id]?.subId" role="menuitemradio" :aria-checked="!!props.itemRefs[selected.id]?.priorityUnset" @click="changePriority(undefined)">跟随父任务</button><button v-for="priority in priorities" :key="priority" class="priority-option" role="menuitemradio" :aria-checked="!props.itemRefs[selected.id]?.priorityUnset && selected.pr === priority" @click="changePriority(priority)"><i class="ri-flag-fill" :style="{ color: PROJECT_PRIORITY_COLORS[priority] }"></i><span class="priority-code">{{ priority }}</span><span class="priority-label">{{ PROJECT_PRIORITY_LABELS[priority] }}</span></button></CellMenu>
          </span><WaoButton class="more-actions" variant="ghost" aria-label="更多操作" title="更多操作" @click="openChecklistMenu($event,selected.id)"><i class="ri-more-line"></i></WaoButton></div>
        <TaskTimeSettings v-if="!source.subId && selected.workItemType!=='note'" :key="source.taskId" :task-id="source.taskId" />
        <h2><input class="detail-title-input" aria-label="任务标题" :value="titleDraft" placeholder="任务标题" @input="editTitle" @blur="finishTitle" @keydown.enter="($event.target as HTMLInputElement).blur()" /></h2>
        <div ref="descriptionHost" :key="selected.id" class="checklist-description">
          <MarkdownEditor markdown-layout v-bind="DOCUMENT_EDITOR_FEATURES" hide-toolbar v-model="description" bare allow-h1 slash-commands :polishable="false" :min-height="hideGroups ? 120 : directChildren.length ? 180 : 360" placeholder="记录任务详情，输入 / 插入标题、列表等…" @upload-image="uploadImage" @upload-file="uploadEditorFile" />
        </div>
        <section v-if="directChildren.length" class="detail-subtasks" aria-label="直属子任务">
          <div v-for="child in directChildren" :key="child.id" class="detail-subtask" @contextmenu.capture="openChecklistMenu($event, child.id)" @keydown="openChecklistMenu($event, child.id)">
            <i v-if="child.workItemType==='note' || child.status==='已放弃'" :class="child.workItemType==='note'?'ri-sticky-note-line':'ri-close-circle-line'" :aria-label="child.workItemType==='note'?'笔记':'已放弃'"></i>
            <input v-else type="checkbox" :style="{ '--task-priority-color': priorityColor(child) }" :checked="isComplete(child)" :disabled="!pendingCompletion.has(child.id) && actions.busy(child.id)" :aria-label="`完成子任务 ${child.title}`" @change="toggleCompletion(child.id)" />
            <button class="child-title" :class="{ finished: isComplete(child) }" @click="selectChild(child.id)">{{ child.title || '未命名任务' }}</button>
            <span v-if="child.due" class="task-meta" :class="{ overdue:overdue(child) > 0 }">{{ child.due }}</span>
          </div>
        </section>
        <form v-if="addingChild && canAddChild" class="child-composer" @submit.prevent="addChild" @keydown.esc.stop.prevent="addingChild = false">
          <input ref="childInput" :value="childDraft" aria-label="子任务名称" placeholder="添加子任务，回车创建" :disabled="childBlocked" @input="storeChildDraft" @keydown="childKey" />
          <WaoButton type="submit" variant="primary" :disabled="!childDraft.trim() || childBlocked">添加</WaoButton>
          <WaoButton type="button" variant="ghost" @click="addingChild = false">收起</WaoButton>
          <p v-if="childDraftError" class="child-draft-error" role="alert">{{ childDraftError }}</p>
          <WaoButton v-if="saveState === 'error'" type="button" variant="ghost" @click="data.flushTask(source.taskId)">重试保存任务</WaoButton>
        </form>
        <WaoButton v-else-if="canAddChild" class="add-child-button" variant="ghost" :disabled="childBlocked" @click="startChild(selected.id)"><i class="ri-add-line" aria-hidden="true"></i>添加子任务</WaoButton>
        <WaoButton v-if="parentTask" variant="ghost" class="parent-task-link" @click="selectChild(parentTask.id)"><i class="ri-arrow-go-back-line" aria-hidden="true"></i><span>父任务：{{ parentTask.title }}</span></WaoButton>
        <div class="detail-secondary-actions">
        <div v-if="outline.length" class="description-outline-anchor" @mouseleave="outlineOpen = false" @keydown.esc.stop.prevent="outlineOpen = false">
          <button v-if="outline.length" class="outline-trigger" aria-label="任务描述目录" :aria-expanded="outlineOpen" @mouseenter="outlineOpen = true" @click="outlineOpen = !outlineOpen"><i class="ri-list-unordered" aria-hidden="true"></i><span>大纲</span></button>
          <nav v-if="outlineOpen && outline.length" class="description-outline" aria-label="任务描述目录">
            <button v-for="(heading, index) in outline" :key="index" :style="{ paddingInlineStart: `calc(var(--wao-space-1) * ${heading.level})` }" @click="jumpToHeading(index)">{{ heading.text }}</button>
          </nav>
        </div>

        <RouterLink v-if="selected.workItemType!=='note' && selected.status!=='已放弃'" :to="{ path: '/focus', query: { focusTask: props.itemRefs[selected.id]?.taskId ?? selected.id, focusSub: props.itemRefs[selected.id]?.subId } }"><i class="ri-timer-line"></i> 开始专注</RouterLink>
        <TaskLabelEditor :key="selected.id" :task-id="props.itemRefs[selected.id]?.taskId ?? selected.id" :sub-id="props.itemRefs[selected.id]?.subId" />
        </div>
        <TaskDocuments :key="`${useSessionStore().userId}:${selected.id}`" :task-id="source.taskId" :sub-id="source.subId" :title="taskTitle" :description="description" />
        <TaskComments :key="`${useSessionStore().account?.accountId ?? ''}:${useSessionStore().userId}:${source.taskId}:${source.subId ?? ''}`" :task-id="source.taskId" :sub-id="source.subId" />
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
  <TaskContextMenu ref="contextMenu" :inline-selection="!popupDetail" @changed="detailOnlySelection=false" @created="selectedId=$event;detailOnlySelection=!popupDetail" :item-refs="itemRefs" @date-changing="pendingDateChanges.add($event)" />
  <CellMenu v-if="rowStatusMenu && rowStatusTask" :anchor="rowStatusMenu.anchor" :min-width="140" @close="rowStatusMenu = null">
    <button v-for="status in rowStatusOptions" :key="status" role="menuitemradio" :aria-checked="status === rowStatusTask.status" @click="changeRowStatus(status)">{{ projectStatusLabel(status) }}<i v-if="status === rowStatusTask.status" class="ri-check-line" aria-hidden="true"></i></button>
  </CellMenu>
  <SettingsDialogShell v-if="deleteTarget" :title="deleteTarget.subId ? '删除子任务' : '删除任务'" icon="ri-delete-bin-line" width="460px" height="auto" :close-disabled="deleting" @close="deleteTarget = null">
    <div class="delete-content">
      <p class="delete-description">确定删除「{{ deleteTarget.title }}」？</p>
      <p class="delete-consequence">{{ deleteTarget.subId ? '下级子任务会保留并上移一级。' : '内嵌子事项会一并删除，独立子任务会保留并移回顶层。' }}</p>
      <p v-if="deleteError" class="child-draft-error" role="alert">{{ deleteError }}</p>
      <WaoButton v-if="deleteError && deleteTarget.subId" variant="ghost" @click="actions.open(deleteTarget.taskId)">查看保存问题</WaoButton>
    </div>
    <template #footer>
      <div class="delete-actions">
        <WaoButton variant="secondary" :disabled="deleting" @click="deleteTarget = null">取消</WaoButton>
        <WaoButton variant="danger" :loading="deleting" :disabled="deleteBusy" @click="confirmDelete">{{ deleteError ? '重试删除' : '确认删除' }}</WaoButton>
      </div>
    </template>
  </SettingsDialogShell>
</template>

<style scoped>
.checklist-list-pane {container-type:inline-size;}
@container(max-width:520px) { .row-quick-fields {display:none !important;} }
.row-quick-fields { display:flex; align-items:center; flex-shrink:0; opacity:0; }
.checklist-row:hover .row-quick-fields,.checklist-row:focus-within .row-quick-fields { opacity:1; }
.row-quick-fields button { display:inline-flex; align-items:center; justify-content:center; width:28px; height:28px; padding:0; color:var(--wao-text-3); border:0; border-radius:var(--wao-radius-control); background:transparent; font:inherit; cursor:pointer; }
.row-quick-fields button:hover { background:var(--wao-bg-hover); }
.checklist-row:focus-visible,.row-quick-fields button:focus-visible { outline:2px solid var(--wao-primary); outline-offset:-2px; }
@media(pointer:coarse),(max-width:760px) { .row-quick-fields {opacity:1;} .row-quick-fields button {min-width:36px;min-height:44px;} .row-quick-fields {flex-wrap:wrap;} }
@media(max-width:600px) { .row-quick-fields {display:none;} }

.row-status { display:inline-flex; align-items:center; gap:4px; flex-shrink:0; min-height:28px; padding:0 6px; border:0; border-radius:var(--wao-radius-control); background:transparent; color:var(--wao-text-3); font:inherit; font-size:var(--wao-font-caption); white-space:nowrap; cursor:pointer; }
.row-status:hover, .row-status[aria-expanded="true"] { background:var(--wao-bg-hover); color:var(--wao-text); }
.row-status:disabled { cursor:wait; opacity:.65; }
.row-status-dot { width:6px; height:6px; border-radius:var(--wao-radius-pill); background:var(--wao-text-3); }
.row-status[data-status="进行中"] .row-status-dot { background:var(--wao-primary); }
.row-status[data-status="待验证"] .row-status-dot, .row-status[data-status="收集中"] .row-status-dot { background:var(--wao-warning-strong); }
.row-status[data-status="已完成"] .row-status-dot { background:var(--wao-success-strong); }
.row-status-retry { flex-shrink:0; border:0; background:transparent; color:var(--wao-danger-strong); font:inherit; font-size:var(--wao-font-caption); cursor:pointer; }
@media (pointer:coarse) { .row-status { min-height:44px; } }

.delete-content { display: flex; flex-direction: column; gap: var(--wao-space-1); padding: var(--wao-space-3); overflow-wrap: anywhere; }
.delete-description { margin: 0; color: var(--wao-text); font-size: var(--wao-font-body); }
.delete-consequence { margin: 0; color: var(--wao-text-3); font-size: var(--wao-font-help); }
.delete-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: var(--wao-space-1); }
@media (max-width: 650px) { .delete-content { padding: var(--wao-space-2); } }
.detail-secondary-actions { margin-top:var(--wao-space-2); display: flex; align-items: center; gap: var(--wao-space-2); min-width: 0; }
.detail-secondary-actions > a { flex-shrink: 0; white-space: nowrap; }
.description-outline-anchor { position: relative; order: 1; margin-left: auto; z-index: 3; }
.outline-trigger { display: inline-flex; align-items: center; gap: var(--wao-space-1); min-height: 32px; padding: 0 var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: transparent; color: var(--wao-text-3); font: inherit; cursor: pointer; }
.outline-trigger:hover, .outline-trigger[aria-expanded="true"] { background: var(--wao-bg-hover); color: var(--wao-primary-text); }
.description-outline { position: absolute; right: 0; top: 100%; width: min(280px, 65vw); max-height: 50vh; overflow: auto; padding: var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-card); background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop); }
.description-outline button { display: block; width: 100%; min-height: 32px; padding-block: var(--wao-space-1); font-size: var(--wao-font-help); border: 0; background: transparent; color: var(--wao-text); text-align: left; overflow-wrap: anywhere; cursor: pointer; }
.description-outline button:hover { background: var(--wao-primary-soft); }
.checklist-description { padding-right: var(--wao-space-3); }
.checklist-description :deep(.tiptap h1) { font-size: calc(var(--wao-font-body) * 1.85); }
.checklist-description :deep(.tiptap h2) { font-size: calc(var(--wao-font-body) * 1.55); }
.checklist-description :deep(.tiptap h3) { font-size: calc(var(--wao-font-body) * 1.3); }
.checklist-description :deep(.tiptap h4) { font-size: calc(var(--wao-font-body) * 1.1); }

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
.checklist-list-pane { display:flex; flex-direction:column; min-width:0; min-height:0; overflow:hidden; padding-right:var(--wao-space-2); container-type:inline-size; }
.checklist-list-pane .checklist-tasks { flex:1; min-height:0; }
.row-more { border:0; background:transparent; color:var(--wao-text-3); cursor:pointer; width:28px; min-height:38px; opacity:0; flex-shrink:0; }
.checklist-row:hover .row-more,.row-more:focus-visible { opacity:1; }
.task-list-name { max-width:100px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
@container (max-width:420px) { .task-list-name { display:none; } }
@media(hover:none) { .row-more {opacity:1;min-height:44px;} }
.checklist-layout { display:grid; grid-template-columns:minmax(240px, 0.85fr) minmax(0, 1.3fr); align-items:start; font-family:var(--wao-font); font-size:var(--wao-font-body); line-height:1.5; }
.checklist-tasks,.checklist-detail { min-width:0; max-height:calc(100dvh - 220px); overflow:auto; border:0; border-radius:0; box-shadow:none; }
.checklist-tasks { position:relative; padding:0 var(--wao-space-2) 0 0; }
.checklist-detail { position:sticky; top:var(--wao-space-1); min-height:calc(100dvh - 240px); padding:0 0 0 var(--wao-space-2); }
.todo-checklist .detail-caption {font-size:var(--wao-font-body);min-height:44px;}
.todo-checklist h2 {font-size:var(--wao-font-title);font-weight:var(--wao-weight-heading);}
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
.child-composer { display:flex; flex-wrap:wrap; align-items:center; gap:var(--wao-space-1); margin-top:var(--wao-space-2); }
.child-composer input { flex:1; min-width:120px; min-height:var(--wao-control-height); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); padding:var(--wao-space-1); background:var(--wao-bg-card); color:var(--wao-text); font:inherit; }
.child-draft-error { width:100%; margin:0; color:var(--wao-danger-strong); font-size:var(--wao-font-caption); }
.add-child-button { margin-top:var(--wao-space-1); color:var(--wao-primary-text); }
.more-actions { padding:0 var(--wao-space-1); min-height:32px; }
.checklist-row { position:relative; display:flex; align-items:center; gap:var(--wao-space-1); border-radius:var(--wao-radius-control); min-height:44px; }
.checklist-row:hover,.checklist-row.selected { background:var(--wao-bg-sub); }
input[type=checkbox] { appearance:none; box-sizing:border-box; border:1px solid var(--task-priority-color, var(--wao-text-weak)); border-radius:var(--wao-radius-checkbox); background:var(--wao-bg-card); display:grid; place-content:center; flex-shrink:0; accent-color:var(--wao-primary); width:14px; height:14px; margin:0; }
input[type=checkbox]:checked { background:var(--task-priority-color, var(--wao-primary)); border-color:var(--task-priority-color, var(--wao-primary)); }
input[type=checkbox]:checked::after { content:'✓'; color:var(--wao-on-primary); font-size:var(--wao-font-caption); line-height:1; }
.group-header { display:flex; align-items:center; min-height:40px; margin-top:var(--wao-space-1); }
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
.checklist-tasks { padding-bottom: calc(var(--wao-control-height) + var(--wao-space-3)); }
.root-drop { position:absolute; bottom:0; left:0; right:0; z-index:1; background:var(--wao-bg-card); padding:var(--wao-space-2); border:1px dashed var(--wao-primary); color:var(--wao-primary-text); }
.drop-inside { outline:1px solid var(--wao-primary); background:var(--wao-primary-soft); }
.drop-before::before,.drop-after::after { content:""; position:absolute; left:0; right:0; height:2px; background:var(--wao-primary); pointer-events:none; }
.drop-before::before { top:0; }
.drop-after::after { bottom:0; }
.tree-toggle,.tree-spacer { width:16px; flex-shrink:0; }
.tree-toggle { height:32px; padding:0; color:var(--wao-text-weak); }
.task-select { display:flex; align-items:center; gap:var(--wao-space-1); flex:1; min-width:0; overflow:hidden; min-height:44px; padding:0 var(--wao-space-1) 0 0; text-align:left; font-size:var(--wao-font-body); }
.task-title { font-family:var(--wao-font); font-weight:400; color:var(--wao-text-2); flex:1; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; line-height:1.5; }
.finished { text-decoration:line-through; color:var(--wao-text-weak); }
.task-meta.overdue { color:var(--wao-danger-strong); }
.task-meta { flex-shrink:0; color:var(--wao-text-weak); font-size:var(--wao-font-caption); }
.row-detailed .task-select { flex-wrap:wrap; gap:2px var(--wao-space-1); padding-block:var(--wao-space-1); }
.row-detailed .task-title { flex:1 0 100%; white-space:normal; overflow-wrap:anywhere; }
.row-preview,.row-execution { flex:1 0 100%; min-width:0; overflow-wrap:anywhere; color:var(--wao-text-3); font-size:var(--wao-font-help); }
.row-preview { display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; overflow:hidden; }
.board-columns { display:flex; align-items:stretch; gap:var(--wao-space-2); min-height:100%; width:max-content; max-width:none; padding:var(--wao-space-1) var(--wao-space-1) var(--wao-space-2) 0; }
.board-column { flex:0 0 clamp(232px, 40cqw, 296px); width:clamp(232px, 40cqw, 296px); min-height:180px; padding:0 var(--wao-space-1) var(--wao-space-1); border:1px solid var(--wao-line); border-radius:var(--wao-radius-card); background:var(--wao-bg-sub); }
.board-column.board-drop { border-color:var(--wao-primary); background:var(--wao-primary-soft); }
.board-column .group-header { margin:0; }
.board-column .board-heading { margin:0; padding:var(--wao-space-1); font-size:var(--wao-font-card-title); }
.board-heading small,.board-empty { color:var(--wao-text-3); font-size:var(--wao-font-help); font-weight:400; }
.board-empty { margin:var(--wao-space-1); }
.board-column .checklist-row { margin-bottom:var(--wao-space-1); padding:0 4px; border:1px solid var(--wao-line); background:var(--wao-bg-card); }
.board-column .checklist-row:hover,.board-column .checklist-row.selected { border-color:var(--wao-primary); }
.board-column .row-status { max-width:70px; overflow:hidden; }
.board-column .row-more { width:23px; }
.timeline-controls { display:flex; align-items:center; flex-wrap:wrap; gap:4px; margin-bottom:var(--wao-space-1); color:var(--wao-text-3); font-size:var(--wao-font-help); }
.timeline-controls button,.timeline-controls input { min-height:36px; padding:4px 6px; border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); background:var(--wao-bg-card); color:var(--wao-text); font:inherit; }
.timeline-controls button { cursor:pointer; }
.timeline-controls button:hover { background:var(--wao-bg-hover); }
.timeline-controls span { padding-inline:4px; }
.timeline-tasks { --timeline-day-width:44px; --timeline-task-width:clamp(138px,42cqw,244px); }
.timeline-line { display:grid; grid-template-columns:var(--timeline-task-width) minmax(0,1fr); width:calc(var(--timeline-task-width) + 14 * var(--timeline-day-width)); min-height:44px; border-bottom:1px solid var(--wao-line); }
.timeline-header { position:sticky; top:0; z-index:4; align-items:center; background:var(--wao-bg-card); color:var(--wao-text-3); font-size:var(--wao-font-caption); }
.timeline-task-cell { position:sticky; left:0; z-index:2; border-right:1px solid var(--wao-line); border-radius:0; background:var(--wao-bg-card); }
.timeline-header .timeline-task-cell { padding:var(--wao-space-1); }
.timeline-task-cell .task-list-name,.timeline-task-cell .row-status,.timeline-task-cell .task-meta:not(.row-execution) { display:none; }
.timeline-track { display:grid; grid-template-columns:repeat(14,var(--timeline-day-width)); align-items:center; }
.timeline-header time { text-align:center; }
.timeline-today { color:var(--wao-primary-text); font-weight:var(--wao-weight-heading); }
.timeline-bars { position:relative; background:repeating-linear-gradient(to right,transparent 0 calc(var(--timeline-day-width) - 1px),var(--wao-line) calc(var(--timeline-day-width) - 1px) var(--timeline-day-width)); }
.timeline-bars > * { grid-row:1; }
.timeline-execution { height:20px; margin:0 2px; border:1px solid var(--bar-color); border-radius:var(--wao-radius-control); background:color-mix(in srgb,var(--bar-color) 22%,var(--wao-bg-card)); cursor:pointer; }
.timeline-deadline { justify-self:center; z-index:1; width:32px; height:36px; padding:0; border:0; border-radius:var(--wao-radius-control); background:transparent; color:var(--wao-primary-text); cursor:pointer; }
.timeline-deadline:hover { background:var(--wao-primary-soft); }
.timeline-undated { grid-column:1 / -1; padding-left:var(--wao-space-1); color:var(--wao-text-3); font-size:var(--wao-font-caption); }
@media (pointer:coarse) { .timeline-controls button,.timeline-controls input,.timeline-deadline { min-height:44px; } }
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
  .child-composer input { font-size:var(--wao-font-card-title); }
  .group-add-button { width:44px; height:44px; }
  .checklist-layout { grid-template-columns:minmax(0,1fr) !important; }
  .checklist-divider { display:none; }
  .checklist-tasks { max-height:260px; padding:0; }
  .checklist-detail { position:static; max-height:none; min-height:300px; border-left:0; border-top:1px solid var(--wao-line); padding:var(--wao-space-2) 0 0; }
  .task-select { min-height:44px; }
}
@container (max-width:420px) {
  .task-select { flex-wrap:wrap; column-gap:4px; row-gap:0; padding-block:4px; }
  .task-title { flex:1 0 100%; }
  .task-select .task-meta { font-size:var(--wao-font-caption); }
}
</style>
