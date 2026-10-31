<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { usePreferenceStore } from '@/stores/preferences'
import { useTaskPreferencesStore } from '@/stores/taskPreferences'
import { selectTaskProject } from '@/utils/taskProjectSelection'
import { useDayWorkspaceStore } from '@/stores/dayWorkspace'
import { isTaskFinished } from '@/utils/projectCompletion'
import { legacyProjection } from '@/utils/richDoc'
import { PreviousTodoWriteRecovered, saveTodoWithReceipt, writeDayNote, type DayNoteWrite, type DayTask, type NoteSubmission } from '@/api/dayWorkspace'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import TodoTaskChecklist from './TodoTaskChecklist.vue'
import TaskOrderStatus from '@/components/task/TaskOrderStatus.vue'
import { useTaskOrderStore } from '@/stores/taskOrder'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import TaskSchedulePopover from './TaskSchedulePopover.vue'
import DailyRoutineSection from './DailyRoutineSection.vue'
import TodoDayLayout from './TodoDayLayout.vue'
import WaoCard from '@/components/ui/WaoCard.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import SaveHint from '@/components/ui/SaveHint.vue'
import { TASK_QUADRANTS } from '@/utils/taskQuadrants'
import type { Priority } from '@/types'

const props = defineProps<{ date: string }>()
const emit = defineEmits<{ 'update:date': [date: string]; all: [] }>()
const data = useDataStore(), session = useSessionStore(), ui = useUiStore(), days = useDayWorkspaceStore(), router = useRouter()
const preferences = usePreferenceStore()
const taskPreferences = useTaskPreferencesStore()
const taskOrder = useTaskOrderStore()
const availableProjects = computed(() => data.projects.filter(project => project.status !== '已完成'))
const day = computed(() => days.days[props.date])
const past = computed(() => props.date < data.today), future = computed(() => props.date > data.today)
const heading = computed(() => new Date(`${props.date}T12:00:00+08:00`).toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long', timeZone: 'Asia/Shanghai' }))
const noteFor = ref<string | null>(null), expanded = ref<Record<string, boolean>>({}), title = ref(''), createError = ref(''), creating = ref(false)
const taskMenu = ref<InstanceType<typeof ContextActionMenu> | null>(null)
const checklistOpen = (id: string) => expanded.value[id] !== false
const createProject = ref('')
const draftError = ref('')
const identity = () => `${session.account?.accountId ?? ''}:${session.userId}`
const routineRemaining = ref<number | null>(null)
const routineCountState = ref<'loading' | 'ready' | 'error'>('loading')
watch(() => [props.date, identity()], () => { routineRemaining.value = null; routineCountState.value = 'loading' }, { flush: 'sync' })
function updateRoutineSummary(date: string, member: string, remaining: number | null, state: 'loading' | 'ready' | 'error') {
  if (date !== props.date || member !== identity()) return
  routineRemaining.value = remaining; routineCountState.value = state
}
const taskView = ref<'list' | 'quadrant'>('list')
const viewError = ref('')
const viewKey = () => `wao-todo-day-view-v1:${identity()}`
watch(() => identity(), () => {
  try { taskView.value = localStorage.getItem(viewKey()) === 'quadrant' ? 'quadrant' : 'list'; viewError.value = '' }
  catch { taskView.value = 'list'; viewError.value = '无法读取本机视图偏好，仍可切换视图。' }
}, { immediate: true })
function switchView(view: 'list' | 'quadrant') {
  taskView.value = view
  try { localStorage.setItem(viewKey(), view); viewError.value = '' }
  catch { viewError.value = '无法记住本机视图选择，当前视图仍保留。' }
}
const newKey = () => `wao-day-task-draft-v1:${identity()}:${props.date}`
const selectionProtected = ref(false)
function automaticProject() {
  return selectTaskProject(availableProjects.value.map(project => project.id), { preferred: preferences.defaultProject, recent: taskPreferences.recentProject })
}
let createId = ''
let restoringDraft = false
watch(() => [props.date, identity()], () => {
  restoringDraft = true
  selectionProtected.value = false
  taskMenu.value?.close(); expanded.value = {}; noteFor.value = null; title.value = ''; createError.value = ''; draftError.value = ''; createId = ''; createProject.value = data.loadStates.projects === 'loaded' ? automaticProject() : ''
  try { const draft = JSON.parse(localStorage.getItem(newKey()) || 'null') as { id: string; title: string; project?: string } | null; if (draft) { selectionProtected.value = true; title.value = draft.title; createId = draft.id; createProject.value = draft.project || '' } } catch { selectionProtected.value = true /* Preserve corrupted draft without overwriting it. */ }
  restoringDraft = false
  void days.load(props.date)
}, { immediate: true })
watch(() => [data.loadStates.projects, availableProjects.value.map(project => project.id), preferences.defaultProject, taskPreferences.recentProject, data.apiLive] as const, () => {
  if (data.loadStates.projects !== 'loaded') return
  // Hydration marks the request loaded before publishing the aggregate snapshot.
  if (!data.apiLive && !data.projects.length) return
  if (selectionProtected.value || title.value.trim()) {
    if (createProject.value && !availableProjects.value.some(project => project.id === createProject.value)) createProject.value = ''
  } else createProject.value = automaticProject()
}, { immediate: true })
function protectDraft() {
  if (restoringDraft) return
  try {
    if (title.value.trim()) {
      createId ||= crypto.randomUUID().replace(/-/g, '')
      localStorage.setItem(newKey(), JSON.stringify({ id: createId, title: title.value, project: createProject.value }))
    } else {
      localStorage.removeItem(newKey())
      createId = ''
    }
    draftError.value = ''
  } catch {
    draftError.value = title.value.trim() ? '无法保护本机标题草稿，请保持页面并重试' : '标题已清空，但本机旧草稿尚未清除，请重试'
  }
}
watch([title, createProject], ([value], [previousTitle]) => {
  if (value.trim() || value !== previousTitle) protectDraft()
}, { flush: 'sync' })
const current = (entry: DayTask) => data.taskById(entry.task.id) ?? entry.task
const shown = (entry: DayTask) => past.value ? { ...entry.task, ...entry.snapshot } : current(entry)
const recordsOpen = ref(false), allNotesOpen = ref(false)
const recordsId = useId()
watch(() => [props.date, identity()], () => { recordsOpen.value = past.value; allNotesOpen.value = false }, { immediate: true })
const taskGroups = computed(() => {
  const entries = day.value?.tasks ?? []
  const planned = entries.filter(entry => entry.planned && !isTaskFinished(shown(entry)))
  const progress = entries.filter(entry => !entry.planned && !isTaskFinished(shown(entry)))
  const records = entries.filter(entry => isTaskFinished(shown(entry)))
  return [
    { id: 'planned', label: past.value ? '当日安排' : future.value ? '计划安排' : '今日安排', items: planned },
    { id: 'progress', label: past.value ? '当日推进过' : '今天推进过', items: progress },
    { id: 'records', label: '已完成', items: records },
  ]
})
const recordsVisible = computed(() => recordsOpen.value || taskGroups.value[2]!.items.some(entry =>
  noteFor.value === entry.task.id || data.taskSaveState(entry.task.id) === 'saving' || data.taskSaveState(entry.task.id) === 'error'))
const visibleGroups = computed(() => taskView.value === 'list'
  ? taskGroups.value.filter(item => item.id !== 'progress' || item.items.length)
  : [
    ...TASK_QUADRANTS.map(quadrant => ({ id: quadrant.priority, label: `${quadrant.label} · ${quadrant.priority}`,
      items: [...taskGroups.value[0]!.items, ...taskGroups.value[1]!.items].filter(entry => current(entry).pr === quadrant.priority) })),
    taskGroups.value[2]!,
  ])
async function changePriority(entry: DayTask, priority: Priority) {
  if (past.value || !data.taskById(entry.task.id) || data.taskSaveState(entry.task.id) === 'saving' || current(entry).pr === priority) return
  data.patchTask(entry.task.id, { pr: priority })
  await data.flushTask(entry.task.id)
}
const draggedTask = ref<{ id: string; date: string; member: string } | null>(null)
const dropPriority = ref<Priority | null>(null)
function clearDrag() { draggedTask.value = null; dropPriority.value = null }
watch(() => [props.date, identity(), taskView.value], clearDrag, { flush: 'sync' })
const canDrag = (entry: DayTask) => taskView.value === 'quadrant' && !past.value && !isTaskFinished(shown(entry))
  && !!data.taskById(entry.task.id) && data.taskSaveState(entry.task.id) !== 'saving' && noteFor.value !== entry.task.id
function startTaskDrag(event: DragEvent, entry: DayTask) {
  if (!canDrag(entry)) { event.preventDefault(); return }
  const selection = window.getSelection()
  if (selection && !selection.isCollapsed && event.currentTarget instanceof Node && selection.anchorNode && event.currentTarget.contains(selection.anchorNode)) { event.preventDefault(); return }
  draggedTask.value = { id: entry.task.id, date: props.date, member: identity() }
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('application/x-wao-day-task', entry.task.id)
    event.dataTransfer.setData('text/plain', entry.task.id)
  }
}
function dragEntry() {
  const source = draggedTask.value
  if (!source || source.date !== props.date || source.member !== identity()) return
  const entry = day.value?.tasks.find(item => item.task.id === source.id)
  return entry && canDrag(entry) ? entry : undefined
}
function isPriority(value: string): value is Priority { return TASK_QUADRANTS.some(item => item.priority === value) }
function editingDropTarget(event: DragEvent) {
  return event.target instanceof Element && !!event.target.closest('input, textarea, select, [contenteditable="true"], .task-note-composer')
}
function overQuadrant(event: DragEvent, group: string) {
  if (!draggedTask.value) return
  event.preventDefault()
  if (!isPriority(group) || !dragEntry() || editingDropTarget(event)) {
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'none'
    dropPriority.value = null
    if (editingDropTarget(event)) event.stopPropagation()
    return
  }
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  dropPriority.value = group
}
function leaveQuadrant(event: DragEvent, group: string) {
  if (event.currentTarget instanceof Node && event.relatedTarget instanceof Node && event.currentTarget.contains(event.relatedTarget)) return
  if (dropPriority.value === group) dropPriority.value = null
}
async function dropOnQuadrant(event: DragEvent, group: string) {
  if (!draggedTask.value) return
  const entry = dragEntry()
  clearDrag()
  event.preventDefault()
  event.stopPropagation()
  if (!entry || !isPriority(group) || editingDropTarget(event)) return
  await changePriority(entry, group)
}
function openTaskMenu(event: MouseEvent, entry: DayTask) {
  taskMenu.value?.openAt(event, `任务：${shown(entry).title}`, [
    { id: 'detail', label: '查看任务详情', run: () => ui.openTask(entry.task.id) },
    ...(!future.value ? [{ id: 'note', label: past.value ? '补记进展' : '记进展', run: () => { noteFor.value = entry.task.id } }] : []),
    ...(shown(entry).subs?.length ? [{ id: 'subs', label: checklistOpen(entry.task.id) ? '收起子事项' : '展开子事项', run: () => { expanded.value[entry.task.id] = !checklistOpen(entry.task.id) } }] : []),
  ], event.currentTarget as HTMLElement)
}
async function toggleSubtask(entry: DayTask, subId: string) {
  if (past.value || future.value || data.movingSubtasks || data.taskSaveState(entry.task.id) === 'saving') return
  const sub = data.subById(entry.task.id, subId)
  if (!sub) return
  const date = props.date
  data.setSubState(entry.task.id, subId, sub.done ? '未开始' : '已完成')
  await data.flushTask(entry.task.id)
  if (data.taskSaveState(entry.task.id) === 'saved') await refresh(date)
}
function move(offset: number) { emit('update:date', new Date(Date.parse(`${props.date}T00:00:00Z`) + offset * 86400000).toISOString().slice(0, 10)) }
async function refresh(date = props.date) { days.invalidate(); await days.load(date, true) }
async function createTask() {
  if (creating.value || !title.value.trim()) return
  const date = props.date, key = newKey(), account = identity(), submitted = title.value.trim(), project = createProject.value
  createId ||= crypto.randomUUID().replace(/-/g, '')
  creating.value = true; createError.value = ''
  try {
    if (!data.apiLive) throw new Error('服务尚未连接，标题草稿已保留')
    if (!project || !availableProjects.value.some(item => item.id === project)) throw new Error('请选择任务所属项目，标题草稿已保留')
    const existing = data.taskById(createId)
    if (existing) {
      if (existing.title !== submitted || existing.project !== project) data.patchTask(createId, { title: submitted, project })
      await data.flushTask(createId)
      if (data.taskSaveState(createId) !== 'saved') throw new Error(data.taskSaveError(createId) || '任务尚未保存，请重试')
      if (data.taskById(createId)?.title !== submitted) {
        data.patchTask(createId, { title: submitted })
        await data.flushTask(createId)
        if (data.taskSaveState(createId) !== 'saved') throw new Error(data.taskSaveError(createId) || '新标题尚未保存，请重试')
      }
    }
    else {
      const result = await data.addTask({ title: submitted, project, pr: 'P2', owner: session.userId, due: '', start: date, desc: '' }, createId)
      if (!result.persisted) throw new Error(data.taskSaveError(createId) || '任务尚未保存，原地重试不会重复创建')
    }
    if (identity() === account && props.date === date && title.value.trim() === submitted) {
      void taskPreferences.rememberProject(project)
      localStorage.removeItem(key); title.value = ''; createId = ''; selectionProtected.value = false; createProject.value = automaticProject()
    }
    await refresh(date)
  } catch (cause) { if (identity() === account && props.date === date) createError.value = cause instanceof Error ? cause.message : '任务保存失败，标题已保留' }
  finally { creating.value = false }
}
async function toggle(entry: DayTask) {
  if (past.value || future.value) return
  const date = props.date
  data.toggleComplete(entry.task.id)
  await data.flushTask(entry.task.id)
  if (data.taskSaveState(entry.task.id) === 'saved') await refresh(date)
}
async function submitNote(request: NoteSubmission, taskId?: string) {
  const date = props.date, account = identity()
  const perform = async (version?: string) => {
    const payload = { ...request, blocks: legacyProjection(request.content), businessDate: date, ...(taskId ? { taskId, expectedTaskEditVersion: version } : {}),
      ...(data.docById(request.docId)?.rev ? { expectedDocRev: data.docById(request.docId)!.rev } : {}) }
    try {
      return await saveTodoWithReceipt<DayNoteWrite>(`note:${request.docId}`, account, payload,
        (operation, body) => writeDayNote(operation, body as typeof payload), identity)
    } catch (cause) {
      if (cause instanceof PreviousTodoWriteRecovered) {
        const recovered = cause.result as DayNoteWrite
        data.applyRemoteDoc(recovered.doc)
      }
      throw cause
    }
  }
  const result = taskId ? await data.runTaskOperation(taskId, base => perform(base.editVersion)) : await perform()
  if (identity() !== account) throw new Error('成员已切换，原保存结果仍保留')
  data.applyRemoteDoc(result.doc)
  void data.refreshKnowledge()
  await refresh(date)
  return result.doc
}
let refreshTimer: ReturnType<typeof setTimeout>
const unsubscribe = data.$subscribe((_mutation, state) => {
  if (days.states[props.date] === 'loaded' && state.tasks.some(task => task.editVersion !== day.value?.tasks.find(item => item.task.id === task.id)?.task.editVersion
    && day.value?.tasks.some(item => item.task.id === task.id))) {
    clearTimeout(refreshTimer); refreshTimer = setTimeout(() => void days.load(props.date, true), 700)
  }
})
onBeforeUnmount(() => { unsubscribe(); clearTimeout(refreshTimer) })
</script>

<template>
  <section class="day-workspace" aria-label="待办按天视图">
    <header class="day-heading">
      <div>
        <h2>{{ heading }}</h2>
        <div v-if="day" class="day-overview" aria-label="当天概况">
          <span><b>{{ taskGroups[0]!.items.length + taskGroups[1]!.items.length }}</b> {{ past ? '件未收尾任务' : future ? '件计划' : '件待办' }}</span>
          <span><b>{{ day.summary.finishedCount }}</b> 件完成记录</span>
          <span><b>{{ day.summary.stepDoneCount }}</b> 项步骤完成</span>
          <span><b>{{ day.summary.noteCount }}</b> 条记录</span>
          <span v-if="days.states[date] === 'loading'" role="status">正在刷新…</span>
        </div>
      </div>
      <nav aria-label="切换日期">
        <WaoButton class="date-arrow" aria-label="前一天" @click="move(-1)"><i class="ri-arrow-left-s-line" aria-hidden="true"></i></WaoButton>
        <WaoButton @click="emit('update:date', data.today)">今天</WaoButton>
        <WaoButton class="date-arrow" aria-label="后一天" @click="move(1)"><i class="ri-arrow-right-s-line" aria-hidden="true"></i></WaoButton>
        <input :value="date" type="date" aria-label="选择待办日期" @change="emit('update:date', ($event.target as HTMLInputElement).value || date)" />
      </nav>
    </header>
    <div v-if="days.states[date] === 'loading' && !day" class="day-state" role="status">正在读取当天记录…</div>
    <div v-else-if="days.states[date] === 'error'" class="day-state" role="alert">
      <b>当天记录暂时没有加载出来</b><p>{{ days.errors[date] }}</p>
      <WaoButton @click="days.load(date, true)">重新加载</WaoButton><WaoButton variant="ghost" @click="emit('all')">查看全部待办</WaoButton>
    </div>
    <template v-else-if="day">
      <p v-if="day.historyCoverage === 'BEFORE_BASELINE'" class="coverage" role="status">这一天早于可靠记录起点。可查看有依据的小记，不根据当前状态补造任务历史。</p>
      <p v-else-if="past || future" class="day-context">{{ past ? '回看这一天留下的真实记录，任务与固定事项只读，可以补记。' : '先安排，实际进展留给当天。' }}</p>
      <TodoDayLayout :reset-key="`${identity()}:${date}`" :routine-remaining="routineRemaining" :routine-count-state="routineCountState" :note-count="day.notes.length">
        <template #default>
          <nav class="day-view-switch" aria-label="当天任务布局">
            <WaoButton :aria-pressed="taskView === 'list'" variant="ghost" @click="switchView('list')"><i class="ri-list-check-2" aria-hidden="true"></i>清单</WaoButton>
            <WaoButton :aria-pressed="taskView === 'quadrant'" variant="ghost" @click="switchView('quadrant')"><i class="ri-layout-grid-line" aria-hidden="true"></i>四象限</WaoButton>
          </nav>
          <p v-if="viewError" class="task-error" role="status">{{ viewError }} <button type="button" @click="switchView(taskView)">重试记住视图</button></p>
          <p v-if="past && taskView === 'quadrant'" class="day-context">按任务现有优先级分组，正文与进展仍展示当日快照。</p>
          <p v-if="!past && taskView === 'quadrant'" class="day-context">拖动任务标题或左侧拖动柄到其他象限，优先级会自动调整并保存。</p>
          <div class="day-task-groups" :class="{ 'day-quadrants': taskView === 'quadrant' }">
          <WaoCard v-for="group in visibleGroups" :key="group.id" class="day-task-group" :class="{ 'day-records': group.id === 'records', 'drop-target': dropPriority === group.id }" :data-group="group.id" @dragover.capture="overQuadrant($event, group.id)" @dragleave="leaveQuadrant($event, group.id)" @drop.capture="dropOnQuadrant($event, group.id)">
            <header v-if="group.id !== 'records'" class="section-heading">
              <h3>{{ group.label }} <span>{{ group.items.length }} 件</span></h3>
            </header>
            <header v-else class="section-heading">
              <button type="button" class="records-toggle" :aria-expanded="recordsVisible" :aria-controls="recordsId" @click="recordsOpen = !recordsVisible">
                <i :class="recordsVisible ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'" aria-hidden="true"></i>{{ group.label }}<span>{{ group.items.length }} 件</span>
              </button>
            </header>
            <div v-show="group.id !== 'records' || recordsVisible" :id="group.id === 'records' ? recordsId : undefined">
              <p v-if="!group.items.length" class="day-empty">{{ group.id === 'records' ? '这一天还没有已收尾的任务' : taskView === 'quadrant' ? '这个象限没有当天待办' : past ? '这一天还没有可靠的任务安排记录' : '这一天还没有安排，写下一件想做的事' }}</p>
              <article v-for="entry in group.items" :key="entry.task.id" class="day-task" :data-task-id="entry.task.id" :class="{ 'task-finished': isTaskFinished(shown(entry)), dragging: draggedTask?.id === entry.task.id }">
                <div class="day-task-main">
                  <div class="task-heading">
                    <span v-if="taskView === 'quadrant' && !past && group.id !== 'records'" class="task-drag-handle" :class="{ 'drag-ready': canDrag(entry) }" :draggable="canDrag(entry)" title="拖到其他象限，自动保存优先级" aria-hidden="true" @dragstart="startTaskDrag($event, entry)" @dragend="clearDrag"><i class="ri-draggable"></i></span>
                    <button v-if="!past && !future" type="button" class="task-check" :aria-label="`完成或重新打开：${shown(entry).title}`" :aria-pressed="isTaskFinished(current(entry))" @click="toggle(entry)">{{ isTaskFinished(current(entry)) ? '✓' : '○' }}</button>
                    <button class="task-title" type="button" :draggable="canDrag(entry)" @dragstart="startTaskDrag($event, entry)" @dragend="clearDrag" @click="ui.openTask(entry.task.id)">{{ shown(entry).title }}</button>
                    <span class="task-status">{{ shown(entry).status }}</span>
                    <WaoButton class="task-more" variant="ghost" :aria-label="`更多操作：${shown(entry).title}`" aria-haspopup="menu" @click="openTaskMenu($event, entry)"><i class="ri-more-line" aria-hidden="true"></i></WaoButton>
                  </div>
                  <div class="task-meta">
                    <span v-if="data.projectById(entry.task.project)" class="task-project">{{ data.projectById(entry.task.project)?.name }}</span>
                    <select v-if="!past && taskView === 'quadrant'" class="task-priority-select" :aria-label="`调整象限：${shown(entry).title}`" :value="current(entry).pr" :disabled="data.taskSaveState(entry.task.id) === 'saving'" @change="changePriority(entry, ($event.target as HTMLSelectElement).value as Priority)"><option v-for="quadrant in TASK_QUADRANTS" :key="quadrant.priority" :value="quadrant.priority">{{ quadrant.priority }} · {{ quadrant.label }}</option></select>
                    <span v-else-if="!past" class="task-priority">{{ current(entry).pr }}</span>
                    <TaskSchedulePopover v-if="!past" :key="`${identity()}:${entry.task.id}`" :task="current(entry)" :day="date" @saved="refresh()" />
                    <span v-else class="past-schedule">当日安排：{{ entry.snapshot.startAt ? entry.snapshot.startAt.slice(11, 16) : entry.snapshot.schedulePeriod === 'MORNING' ? '早晨' : entry.snapshot.schedulePeriod === 'AFTERNOON' ? '下午' : entry.snapshot.schedulePeriod === 'EVENING' ? '晚上' : entry.snapshot.start ? '不定时' : '未排期' }}</span>
                    <span v-if="!entry.planned" class="task-trace">当天留下了进展，未安排在这一天</span>
                  </div>
                  <TaskOrderStatus v-if="!past && !future && shown(entry).subs?.length > 1" :scope="`subs:${entry.task.id}`" />
                  <TodoTaskChecklist v-if="shown(entry).subs?.length" v-show="checklistOpen(entry.task.id)" :task-id="entry.task.id" :subs="shown(entry).subs" :order="!past && !future ? taskOrder.state(`subs:${entry.task.id}`).ids : undefined" :read-only="past || future || !data.taskById(entry.task.id)" :saving="data.taskSaveState(entry.task.id) === 'saving' || data.movingSubtasks" @toggle="toggleSubtask(entry, $event)" />
                  <button v-if="shown(entry).subs?.length && !checklistOpen(entry.task.id)" class="checklist-reopen" type="button" @click="expanded[entry.task.id] = true">展开 {{ shown(entry).subs.length }} 项子事项</button>
                  <p v-if="data.taskSaveState(entry.task.id) === 'saving'" role="status">正在保存任务…</p>
                  <SaveHint v-if="taskView === 'quadrant' && data.taskSaveState(entry.task.id) === 'saved'" :saved-at="data.taskSavedAt(entry.task.id)" />
                  <p v-if="data.taskSaveState(entry.task.id) === 'error'" class="task-error" role="alert">{{ data.taskSaveError(entry.task.id) }} <button v-if="taskView === 'quadrant'" type="button" @click="data.flushTask(entry.task.id)">重试保存</button> <button type="button" @click="ui.openTask(entry.task.id)">处理保存</button></p>
                  <div v-if="noteFor === entry.task.id" class="task-note-composer"><WaoButton variant="ghost" @click="noteFor = null">收起记录</WaoButton><NoteComposer :key="`${identity()}:${date}:${entry.task.id}`" compact autofocus :draft-scope="`day:${identity()}:${date}:${entry.task.id}`" :submit-note="request => submitNote(request, entry.task.id)" /></div>
                  <p v-if="entry.events.length" class="latest-progress">最近进展：{{ entry.events[entry.events.length - 1]!.summary }}</p>
                  <details v-if="entry.events.length" class="day-events">
                    <summary>查看当天进展 · {{ entry.events.length }} 条</summary>
                    <div><span v-for="event in entry.events" :key="event.id">{{ event.summary }}<small>{{ new Date(event.occurredAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai' }) }}{{ event.late ? ' · 后来补记' : '' }}</small></span></div>
                  </details>
                </div>
              </article>
            </div>
          </WaoCard>
          </div>
              <div class="day-add-task">
                <form v-if="!past" class="day-create" @submit.prevent="createTask">
                  <input v-model="title" aria-label="当天要做的事" placeholder="＋ 写下当天要做的一件事" :disabled="creating" maxlength="500" />
                  <select v-model="createProject" aria-label="任务所属项目" :disabled="creating" @change="selectionProtected = true"><option value="">选择项目</option><option v-for="project in availableProjects" :key="project.id" :value="project.id">{{ project.name }}</option></select>
                  <WaoButton type="submit" variant="primary" :loading="creating" :disabled="!title.trim()">{{ creating ? '保存中…' : '添加' }}</WaoButton>
                </form>
                <p v-if="createError" class="task-error" role="alert">{{ createError }}</p>
                <p v-if="draftError" class="task-error" role="alert">{{ draftError }} <button type="button" @click="protectDraft">重试保护草稿</button></p>
                <p v-if="taskPreferences.recentError" class="task-error" role="status">最近使用项目：{{ taskPreferences.recentError }} <button type="button" @click="taskPreferences.retryRecent()">重试同步最近项目</button></p>
              </div>
        </template>
        <template #routine>
          <DailyRoutineSection :date="date" @summary="updateRoutineSummary" />
        </template>
        <template #notes>
          <WaoCard class="day-notes">
            <header class="section-heading"><h3>{{ past ? '当天记录与补记' : '随手记' }} <span>{{ day.notes.length }}</span></h3><WaoButton v-if="!future" class="section-action" variant="ghost" :aria-expanded="noteFor === ''" @click="noteFor = noteFor === '' ? null : ''">{{ noteFor === '' ? '收起输入' : past ? '补记这一天' : '写一条' }}</WaoButton></header>
            <NoteComposer v-if="noteFor === '' && !future" :key="`${identity()}:${date}:free`" compact :draft-scope="`day:${identity()}:${date}:free`" :submit-note="request => submitNote(request)" />
            <button v-for="(note, index) in day.notes" v-show="allNotesOpen || index < 3" :key="note.docId" class="day-note-link" type="button" @click="router.push({ path: '/notes', query: { note: note.docId } })"><span>{{ data.docById(note.docId)?.title || note.title }}</span><small>{{ note.late ? '补记 · ' : '' }}{{ note.updatedAt !== note.createdAt ? '后来有修改' : '已保存' }}</small></button>
            <WaoButton v-if="day.notes.length > 3" class="section-action notes-more" variant="ghost" :aria-expanded="allNotesOpen" @click="allNotesOpen = !allNotesOpen">{{ allNotesOpen ? '收起记录' : `展开全部 ${day.notes.length} 条记录` }}</WaoButton>
            <p v-if="!day.notes.length" class="day-empty">{{ future ? '实际记录留给当天' : '还没有记录，想到什么就记下来' }}</p>
          </WaoCard>
        </template>
      </TodoDayLayout>
      <ContextActionMenu ref="taskMenu" />
    </template>
  </section>
</template>

<style scoped>
.day-workspace { container: todo-day / inline-size; width: 100%; min-width: 0; margin: var(--wao-space-2) auto var(--wao-space-3); }
.day-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--wao-space-2); flex-wrap: wrap; margin-bottom: var(--wao-space-3); }
h2 { margin: 0; color: var(--wao-text); font-size: var(--wao-font-title); font-weight: 700; }
.day-overview { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 16px; margin-top: var(--wao-space-1); color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.day-overview b { color: var(--wao-text-2); font-weight: 600; }
.day-heading nav { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.date-arrow { width: 40px; padding: 0; }
button, input, select { font: inherit; }
.day-heading input { width: 154px; min-width: 0; min-height: var(--wao-control-height); padding: 7px 10px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text); }
.day-task-group { padding: 0; border: 0; border-radius: 0; }
.day-view-switch { display: flex; gap: var(--wao-space-1); }
.day-view-switch button[aria-pressed='true'] { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.day-task-groups { display: grid; min-width: 0; gap: var(--wao-space-2); }
.day-quadrants { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.day-quadrants .day-task-group:not(.day-records) { padding: 0 var(--wao-space-2) var(--wao-space-1); border: 1px solid var(--wao-line); border-radius: var(--wao-radius-card); }
.day-quadrants .day-records { grid-column: 1 / -1; }
.day-quadrants .day-task-group.drop-target { border-color: var(--wao-primary-line); background: var(--wao-primary-soft); }
.day-task.dragging { opacity: .55; }
.task-drag-handle { flex: none; color: var(--wao-text-3); padding-top: 4px; }
.task-drag-handle.drag-ready, .task-title[draggable='true'] { cursor: grab; }
.task-drag-handle.drag-ready:active, .task-title[draggable='true']:active { cursor: grabbing; }
.task-priority-select { min-width: 0; max-width: 100%; min-height: 32px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text-2); font-size: var(--wao-font-caption); padding: 4px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: var(--wao-space-1); min-height: 52px; }
h3 { margin: 0; font-size: var(--wao-font-body); font-weight: 650; color: var(--wao-text); }
.section-heading span { margin-left: var(--wao-space-1); color: var(--wao-text-3); font-size: var(--wao-font-caption); font-weight: 400; }
.records-toggle { display: inline-flex; gap: 5px; align-items: center; min-height: 40px; padding: 4px 0; border: 0; background: transparent; color: var(--wao-text-2); font-size: var(--wao-font-body); text-align: left; }
.day-records { border-color: transparent; background: transparent; }
.day-task { padding: 14px 0; border-top: 1px solid var(--wao-line); }
.day-task-main { min-width: 0; }
.task-heading { display: flex; align-items: flex-start; gap: var(--wao-space-1); }
.task-title { flex: 1; min-width: 0; padding: 2px 0; border: 0; background: transparent; color: var(--wao-text); font-size: var(--wao-font-body); font-weight: 650; text-align: left; overflow-wrap: anywhere; line-height: 1.65; }
.task-title:hover { color: var(--wao-primary-text); }
.task-check { flex: none; min-width: 24px; padding: 0; border: 0; background: transparent; color: var(--wao-primary-text); font-size: 20px; line-height: 1.4; }
.task-check[aria-pressed='true'] { color: var(--wao-success-strong); }
.task-finished .task-title { font-weight: 500; color: var(--wao-text-2); }
.task-status { flex: none; padding-top: 4px; font-size: var(--wao-font-help); color: var(--wao-text-3); }
.task-more { flex: none; min-width: 32px; min-height: 32px; padding: 4px; }
.task-project, .task-priority { min-width: 0; overflow-wrap: anywhere; font-size: var(--wao-font-caption); color: var(--wao-text-3); }
.checklist-reopen, .latest-progress { margin: var(--wao-space-1) 0 0 32px; color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.checklist-reopen { padding: 4px 0; min-height: 32px; border: 0; background: transparent; }
.task-note-composer { margin: var(--wao-space-1) 0 0 32px; }
.task-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 12px; margin: 6px 0 0 32px; }
.task-trace, .past-schedule { margin: 0; color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.task-error button { min-height: 28px; padding: 3px 0; border: 0; background: transparent; color: var(--wao-primary-text); font-size: var(--wao-font-help); }
.day-create { display: flex; flex-wrap: wrap; gap: var(--wao-space-1); margin: 0; padding: 12px 0 var(--wao-space-2); border-top: 1px solid var(--wao-line); }
.day-create input { flex: 1; min-width: 130px; padding: 10px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text); }
.day-create select { min-width: 0; max-width: 180px; min-height: var(--wao-control-height); padding: 5px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text); }
.day-events { margin: var(--wao-space-1) 0 0 32px; color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.day-events summary { width: fit-content; cursor: pointer; }
.day-events > div { display: grid; gap: 6px; padding: var(--wao-space-1) 0 0; }
.day-events small { margin-left: var(--wao-space-1); font-size: var(--wao-font-help); }
.day-notes { padding-top: 0; }
.section-action { min-height: 32px; padding: 4px 8px; font-size: var(--wao-font-caption); }
.day-note-link { display: flex; flex-direction: column; gap: 6px; width: 100%; padding: 12px 0; border: 0; border-top: 1px solid var(--wao-line); background: transparent; color: var(--wao-text); text-align: left; }
.day-note-link span { min-width: 0; overflow-wrap: anywhere; font-size: var(--wao-font-body); }
.day-note-link small { color: var(--wao-text-3); font-size: var(--wao-font-help); }
.notes-more { margin-top: var(--wao-space-1); }
.coverage, .day-context { margin: 0 0 var(--wao-space-2); color: var(--wao-text-2); font-size: var(--wao-font-body); }
.coverage { padding: var(--wao-space-2); border-radius: var(--wao-radius-control); background: var(--wao-bg-sub); }
.day-empty, .day-state { margin: 0; padding: var(--wao-space-2) 0; color: var(--wao-text-3); font-size: var(--wao-font-help); }
.day-state p { margin: var(--wao-space-1) 0; }
.day-state button { margin-right: var(--wao-space-1); }
.task-error { margin: var(--wao-space-1) 0; color: var(--wao-danger-strong); font-size: var(--wao-font-help); }
button { cursor: pointer; }
button:focus-visible, input:focus-visible, select:focus-visible, summary:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
button:disabled { opacity: .55; cursor: default; }
@container todo-day (max-width: 600px) {
  .day-quadrants { grid-template-columns: minmax(0, 1fr); }
  h2 { font-size: var(--wao-font-title); }
  .day-heading nav { width: 100%; }
  .day-heading input { flex: 1; }
  .day-create { display: grid; grid-template-columns: minmax(0, 1fr) auto; }
  .day-create input { grid-column: 1 / -1; min-width: 0; width: 100%; }
  .day-create select { width: 100%; max-width: none; }
  .day-heading input, .day-create input, .day-create select, .day-create button { min-height: 44px; font-size: var(--wao-font-card-title); }
  .task-check, .task-more, .checklist-reopen, .section-action, .records-toggle { min-height: 44px; }
  .task-check { min-width: 32px; }
  .task-meta, .day-events, .checklist-reopen, .latest-progress, .task-note-composer { margin-left: 40px; }
  .task-status { max-width: 65px; overflow-wrap: anywhere; }
}
@media (pointer: coarse) { .task-check, .task-more, .checklist-reopen, .section-action, .records-toggle { min-height: 44px; } .task-check { min-width: 44px; } }
</style>
