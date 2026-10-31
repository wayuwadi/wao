<script setup lang="ts">
import { useTaskViewMemory, useTaskScrollMemory } from '@/composables/taskViewMemory'
import TaskQuickActionDialog from './TaskQuickActionDialog.vue'
import { computed, defineAsyncComponent, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useMemberPreference } from '@/composables/memberPreference'
import { useSessionStore } from '@/stores/session'
import { isTaskFinished } from '@/utils/projectCompletion'
import TaskLabelEditor from '@/components/task/TaskLabelEditor.vue'
import { useTaskLabelsStore } from '@/stores/taskLabels'
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
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import SaveHint from '@/components/ui/SaveHint.vue'
import TaskDocuments from '@/components/task/TaskDocuments.vue'
import TaskDueDate from '@/components/task/TaskDueDate.vue'
import { deadlineInfo } from '@/utils/taskDeadline'
import { todayIso } from '@/utils/time'
import { useTaskDeadlineDefaults } from '@/composables/taskDeadlineDefaults'

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
const props = defineProps<{ stateKey?: string; selectedIds?: string[]; selectionMode?: boolean; items: Task[]; contextItems: Task[]; itemRefs: ProjectItemRefs; showDone: boolean; collapsed: string[]; query: string; busy?: boolean; disableHierarchyDrag?: boolean; sections?: { id: string; name: string; header: boolean; collapsed: boolean; itemIds: string[] }[] }>()
const emit = defineEmits<{ select: [id: string]; toggle: [id: string]; toggleGroup: [id: string]; add: [groupId: string]; reveal: [id: string]; move: [source: string, target: string | null, side: 'before' | 'inside' | 'after'] }>()
const data = useDataStore()
const labels = useTaskLabelsStore()
const tagsFor = (id: string) => { const ref = props.itemRefs[id]; return labels.get(ref?.taskId ?? id, ref?.subId).tags }
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
const viewMemory = useTaskViewMemory(() => props.stateKey ?? 'checklist', () => ({ selectedId: '' }))
const selectedId = computed({ get: () => viewMemory.value.selectedId, set: value => { viewMemory.value.selectedId = value } })
const detailOnlySelection = ref(false)
const matches = computed(() => {
  const items = props.items.filter(t => props.showDone || !isTaskFinished(t) || pendingCompletion.value.has(t.id) || (retainedCompletion.value.has(t.id) && ['saving','error'].includes(data.taskSaveState(actions.sourceId(t.id)))))
  const found = props.query ? searchProjectTasks(items, props.query).map(hit => hit.task) : items
  const located = detailOnlySelection.value ? props.contextItems.find(t => t.id === selectedId.value) : null
  return located && !found.some(t => t.id === located.id) ? [...found, located] : found
})
const rows = computed(() => taskTreeRows(withProjectAncestors(matches.value, props.contextItems), props.query ? [] : props.collapsed))
const groupedRows = computed(() => (props.sections ?? [{ id: '__all', name: '', header: false, collapsed: false, itemIds: props.contextItems.map(t => t.id) }]).map(section => ({ ...section, rows: rows.value.filter(row => section.itemIds.includes(row.task.id)) })))
const dragging = ref<string | null>(null)
const over = ref<{ id: string; side: 'before' | 'inside' | 'after' } | null>(null)
function startDrag(id: string, event: DragEvent) {
  if (props.busy || props.disableHierarchyDrag) { event.preventDefault(); return }
  dragging.value = id
  event.dataTransfer?.setData('text/plain', id)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function drop(target: string | null, event: DragEvent) {
  if (dragging.value && !props.busy) emit('move', dragging.value, target, target ? taskHierarchyDrop(event) : 'inside')
  dragging.value = null; over.value = null
}
watch(rows, value => {
  if (!value.length && data.taskLoadState !== 'loaded') return
  if (detailOnlySelection.value && props.contextItems.some(t => t.id === selectedId.value)) return
  const visibleMatches = value.filter(row => matches.value.some(t => t.id === row.task.id))
  if (!visibleMatches.some(row => row.task.id === selectedId.value)) selectedId.value = visibleMatches[0]?.task.id ?? value[0]?.task.id ?? ''
}, { immediate: true })
const rememberScroll = useTaskScrollMemory(layout, () => `${props.stateKey ?? 'checklist'}:scroll`, () => `${rows.value.length}:${selectedId.value}`)
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
const quickAction = ref<{taskId:string;subId?:string;mode:'tags'|'child'|'priority'}|null>(null)
const contextMenu = ref<InstanceType<typeof ContextActionMenu> | null>(null)
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
watch([selectedId, () => useSessionStore().userId], () => {
  addingChild.value = false
  childDraft.value = ''
  childDraftError.value = ''
  contextMenu.value?.close()
}, { flush: 'sync' })
async function startChild(id: string) {
  if (props.busy || actions.busy(id) || !props.contextItems.some(t => t.id === id)) return
  const member = useSessionStore().userId
  await selectChild(id)
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
  if (!addingChild.value || !childDraft.value.trim() || props.busy || actions.busy(parentId)) return
  const { taskId, subId } = source.value
  const id = data.addSub(taskId, childDraft.value, subId)
  if (!id) { childDraftError.value = '未能添加子任务，请稍后重试'; return }
  childDraft.value = ''
  try { localStorage.removeItem(childDraftKey.value) }
  catch { childDraftError.value = '子任务已添加，本机输入草稿未能清除' }
  if (props.collapsed.includes(parentId)) emit('toggle', parentId)
  emit('reveal', subItemKey(taskId, id))
  try { await data.flushTask(taskId) }
  catch { toast('子任务尚未保存，已保留草稿，请查看保存问题', 'ri-error-warning-line') }
  await nextTick()
  if (member === useSessionStore().userId && selectedId.value === parentId && addingChild.value) childInput.value?.focus()
}
function openChecklistMenu(event: MouseEvent | KeyboardEvent, id: string) {
  if (event instanceof KeyboardEvent) {
    if (!(event.shiftKey && event.key === 'F10') && event.key !== 'ContextMenu') return
    event.preventDefault()
  } else if (event.target instanceof Element && event.target.closest('input:not([type=checkbox]), textarea, [contenteditable="true"]')) return
  const task = props.contextItems.find(t => t.id === id) ?? props.items.find(t => t.id === id)
  if (!task) return
  event.preventDefault()
  event.stopPropagation()
  void contextMenu.value?.openAt(event, task.title || '未命名任务', [
    { id: 'add-child', label: '添加子任务', disabled: props.busy || actions.busy(id), run: () => startChild(id) },
    { id: 'tags', label: '设置标签', run: () => { quickAction.value={...actions.source(id),mode:'tags'} } },
    { id:'priority',label:'修改优先级',disabled:actions.busy(id),run:()=>{quickAction.value={...actions.source(id),mode:'priority'}} },
    { id: 'open', label: '打开任务详情', run: () => actions.open(id) },
    { id: 'delete', label: actions.isSub(id) ? '删除子任务' : '删除任务', danger: true, separatorBefore: true,
      disabled: deleting.value || props.busy || actions.busy(id), run: () => requestDelete(id, task.title || '未命名任务') },
  ])
}
async function uploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) { done(null, file.name); return }
  const image = await data.uploadImage(file, `任务 · ${selected.value?.title ?? ''}`)
  done(image?.url ?? null, image?.name ?? file.name)
}
</script>

<template>
  <div v-if="split.error.value" class="split-sync" role="status">{{ split.error.value }}<button @click="split.retry()">重试布局同步</button></div>
  <div ref="layout" @scroll.capture="rememberScroll" class="checklist-layout" :class="{ 'is-resizing': splitPointer !== null, 'is-compact': compact }" :style="splitStyle">
    <WaoCard class="checklist-tasks" data-task-scroll="list">

      <p v-if="!rows.length" class="empty">{{ query ? '没有匹配的任务，试试其他关键词' : '当前没有待办任务' }}</p>
      <div v-if="dragging" class="root-drop" @dragover.prevent @drop.prevent="drop(null, $event)">拖到这里移为顶层任务</div>
      <section v-for="section in groupedRows" :key="section.id">
      <div v-if="section.header" class="group-header">
        <button class="group-heading" :aria-expanded="!section.collapsed" @click="emit('toggleGroup', section.id)"><i :class="section.collapsed ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>{{ section.name }} <small>{{ section.rows.length }}</small></button>
        <button class="group-add-button" :aria-label="`在 ${section.name} 新建任务`" title="快速新建任务" @click="emit('add', section.id)"><i class="ri-add-line" aria-hidden="true"></i></button>
      </div>
      <div v-show="!section.collapsed">
      <slot name="group-add" :section="section" />
      <div v-for="{ task, depth } in section.rows" :key="task.id" class="checklist-row" :data-task-id="task.id" @contextmenu.capture="openChecklistMenu($event, task.id)" @keydown="openChecklistMenu($event, task.id)" :draggable="!busy && !disableHierarchyDrag" @dragstart="startDrag(task.id, $event)" @dragend="dragging = null; over = null" @dragover.prevent="over = { id: task.id, side: taskHierarchyDrop($event) }" @drop.prevent.stop="drop(task.id, $event)" :class="{ selected: selectedId === task.id, 'drop-inside': dragging && over?.id === task.id && over.side === 'inside', 'drop-before': dragging && over?.id === task.id && over.side === 'before', 'drop-after': dragging && over?.id === task.id && over.side === 'after' }" :style="{ paddingInlineStart: `${Math.min(depth, 6) * 16 + 8}px` }">
        <button v-if="hasChildren(task.id)" class="tree-toggle" :aria-label="`${collapsed.includes(task.id) ? '展开' : '收起'} ${task.title}`" :aria-expanded="!collapsed.includes(task.id)" @click="$emit('toggle', task.id)"><i :class="collapsed.includes(task.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
        <span v-else class="tree-spacer"></span>
        <input v-if="selectionMode" type="checkbox" :checked="selectedIds?.includes(task.id)" :disabled="!matches.some(item=>item.id===task.id)" :aria-label="`选择任务 ${task.title}`" @change="emit('select', task.id)" />
        <input v-else type="checkbox" :style="{ '--task-priority-color': priorityColor(task) }" :checked="isComplete(task)" :disabled="!pendingCompletion.has(task.id) && actions.busy(task.id)" :aria-label="`完成 ${task.title}`" @change="toggleCompletion(task.id)" />
        <button class="task-select" :aria-pressed="selectedId === task.id" @click="detailOnlySelection = false; selectedId = task.id">
          <span class="task-title" :title="task.title" :class="{ finished: isComplete(task) }">{{ task.title || '未命名任务' }}</span>
          <span v-if="tagsFor(task.id).length" class="task-meta" :title="tagsFor(task.id).join('，')"># {{ tagsFor(task.id)[0] }}</span><span v-if="task.due" class="task-meta" :class="{ overdue:overdue(task) > 0 }" :title="overdue(task) ? `已延期 ${overdue(task)} 天` : '截止日期'">{{ task.due }}</span>
        </button>
      </div>
      </div></section>
    </WaoCard>
    <div class="checklist-divider" role="separator" tabindex="0" aria-label="调整任务清单和详情宽度" aria-orientation="vertical" :aria-valuenow="Math.round(splitRatio * 100)" :aria-valuemin="Math.round(boundSplit(.25) * 100)" :aria-valuemax="Math.round(boundSplit(.75) * 100)" title="拖动调整宽度；方向键微调，双击恢复默认" @pointerdown="startSplit" @pointermove="moveSplit" @pointerup="endSplit()" @pointercancel="endSplit(false)" @lostpointercapture="splitPointer !== null && endSplit(false)" @keydown="keySplit" @dblclick="split.set(.395)"></div>
    <WaoCard :data-task-scroll="`detail:${selectedId}`" class="checklist-detail" padding="roomy" aria-label="任务详情卡片">
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
            <CellMenu v-if="propertyMenu === 'priority'" :anchor="priorityAnchor" @close="propertyMenu = null"><button v-if="props.itemRefs[selected.id]?.subId" role="menuitemradio" :aria-checked="!!props.itemRefs[selected.id]?.priorityUnset" @click="changePriority(undefined)">跟随父任务</button><button v-for="priority in priorities" :key="priority" class="priority-option" role="menuitemradio" :aria-checked="!props.itemRefs[selected.id]?.priorityUnset && selected.pr === priority" @click="changePriority(priority)"><i class="ri-flag-fill" :style="{ color: PROJECT_PRIORITY_COLORS[priority] }"></i><span class="priority-code">{{ priority }}</span><span class="priority-label">{{ PROJECT_PRIORITY_LABELS[priority] }}</span></button></CellMenu>
          </span><WaoButton class="more-actions" variant="ghost" aria-label="更多操作" title="更多操作" @click="actions.open(selected.id)"><i class="ri-more-line"></i></WaoButton></div>
        <div class="detail-secondary-actions">
        <RouterLink :to="{ path: '/focus', query: { focusTask: props.itemRefs[selected.id]?.taskId ?? selected.id, focusSub: props.itemRefs[selected.id]?.subId } }"><i class="ri-timer-line"></i> 开始专注</RouterLink>
        <TaskLabelEditor :key="selected.id" :task-id="props.itemRefs[selected.id]?.taskId ?? selected.id" :sub-id="props.itemRefs[selected.id]?.subId" />
        </div>
        <h2><input class="detail-title-input" aria-label="任务标题" :value="titleDraft" placeholder="任务标题" @input="editTitle" @blur="finishTitle" @keydown.enter="($event.target as HTMLInputElement).blur()" /></h2>
        <div class="description-outline-anchor" @mouseleave="outlineOpen = false" @keydown.esc.stop.prevent="outlineOpen = false">
          <button v-if="outline.length" class="outline-trigger" aria-label="任务描述目录" :aria-expanded="outlineOpen" @mouseenter="outlineOpen = true" @focus="outlineOpen = true" @click="outlineOpen = true"><i class="ri-list-unordered"></i></button>
          <nav v-if="outlineOpen && outline.length" class="description-outline" aria-label="任务描述目录">
            <button v-for="(heading, index) in outline" :key="index" :style="{ paddingInlineStart: `calc(var(--wao-space-1) * ${heading.level})` }" @click="jumpToHeading(index)">{{ heading.text }}</button>
          </nav>
        </div>
        <div ref="descriptionHost" :key="selected.id" class="checklist-description">
          <MarkdownEditor v-model="description" bare allow-h1 slash-commands :polishable="false" :min-height="directChildren.length ? 180 : 360" placeholder="记录任务详情，输入 / 插入标题、列表等…" @upload-image="uploadImage" />
        </div>
        <section v-if="directChildren.length" class="detail-subtasks" aria-label="直属子任务">
          <div v-for="child in directChildren" :key="child.id" class="detail-subtask" @contextmenu.capture="openChecklistMenu($event, child.id)" @keydown="openChecklistMenu($event, child.id)">
            <input type="checkbox" :style="{ '--task-priority-color': priorityColor(child) }" :checked="isComplete(child)" :disabled="!pendingCompletion.has(child.id) && actions.busy(child.id)" :aria-label="`完成子任务 ${child.title}`" @change="toggleCompletion(child.id)" />
            <button class="child-title" :class="{ finished: isComplete(child) }" @click="selectChild(child.id)">{{ child.title || '未命名任务' }}</button>
            <span v-if="child.due" class="task-meta" :class="{ overdue:overdue(child) > 0 }">{{ child.due }}</span>
          </div>
        </section>
        <form v-if="addingChild" class="child-composer" @submit.prevent="addChild" @keydown.esc.stop.prevent="addingChild = false">
          <input ref="childInput" :value="childDraft" aria-label="子任务名称" placeholder="输入子任务，回车添加" :disabled="busy || actions.busy(selected.id)" @input="storeChildDraft" />
          <WaoButton type="submit" variant="primary" :disabled="!childDraft.trim() || busy || actions.busy(selected.id)">添加</WaoButton>
          <WaoButton type="button" variant="ghost" @click="addingChild = false">收起</WaoButton>
          <p v-if="childDraftError" class="child-draft-error" role="alert">{{ childDraftError }}</p>
        </form>
        <WaoButton v-else class="add-child-button" variant="ghost" :disabled="busy || actions.busy(selected.id)" @click="startChild(selected.id)"><i class="ri-add-line" aria-hidden="true"></i>添加子任务</WaoButton>
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
  <TaskQuickActionDialog v-if="quickAction" v-bind="quickAction" @close="quickAction=null" /><ContextActionMenu ref="contextMenu" />
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
.delete-content { display: flex; flex-direction: column; gap: var(--wao-space-1); padding: var(--wao-space-3); overflow-wrap: anywhere; }
.delete-description { margin: 0; color: var(--wao-text); font-size: var(--wao-font-body); }
.delete-consequence { margin: 0; color: var(--wao-text-3); font-size: var(--wao-font-help); }
.delete-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: var(--wao-space-1); }
@media (max-width: 650px) { .delete-content { padding: var(--wao-space-2); } }
.detail-secondary-actions { display: flex; align-items: center; gap: var(--wao-space-2); min-width: 0; }
.detail-secondary-actions > a { flex-shrink: 0; white-space: nowrap; }
.description-outline-anchor { position: sticky; top: var(--wao-space-1); height: 0; z-index: 3; margin-left: auto; width: fit-content; }
.outline-trigger { position: absolute; right: 0; width: 44px; height: 44px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.description-outline { position: absolute; right: 0; top: 44px; width: min(280px, 65vw); max-height: 50vh; overflow: auto; padding: var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-card); background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop); }
.description-outline button { display: block; width: 100%; min-height: 44px; border: 0; background: transparent; color: var(--wao-text); text-align: left; overflow-wrap: anywhere; cursor: pointer; }
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
.checklist-layout { display:grid; grid-template-columns:minmax(240px, 0.85fr) minmax(0, 1.3fr); align-items:start; font-family:var(--wao-font); font-size:var(--wao-font-body); line-height:1.5; }
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
.child-composer { display:flex; flex-wrap:wrap; align-items:center; gap:var(--wao-space-1); margin-top:var(--wao-space-2); }
.child-composer input { flex:1; min-width:120px; min-height:var(--wao-control-height); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); padding:var(--wao-space-1); background:var(--wao-bg-card); color:var(--wao-text); font:inherit; }
.child-draft-error { width:100%; margin:0; color:var(--wao-danger-strong); font-size:var(--wao-font-caption); }
.add-child-button { margin-top:var(--wao-space-1); color:var(--wao-primary-text); }
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
.task-title { font-family:var(--wao-font); font-weight:400; color:var(--wao-text-2); flex:1; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; line-height:1.5; }
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
  .child-composer input { font-size:var(--wao-font-card-title); }
  .group-add-button { width:44px; height:44px; }
  .checklist-layout { grid-template-columns:minmax(0,1fr) !important; }
  .checklist-divider { display:none; }
  .checklist-tasks { max-height:260px; padding:0; }
  .checklist-detail { position:static; max-height:none; min-height:300px; border-left:0; border-top:1px solid var(--wao-line); padding:var(--wao-space-2) 0 0; }
  .task-select { min-height:44px; }
}
</style>
