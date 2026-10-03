<script setup lang="ts">
import TaskOrderStatus from '@/components/task/TaskOrderStatus.vue'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { taskDropAfter } from '@/utils/taskDrop'
import { taskTreeRows } from '@/utils/taskHierarchy'
import { isTaskFinished as isFinished, projectCompletion, projectCompletionLabel } from '@/utils/projectCompletion'
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore, type TaskView } from '@/stores/ui'
import { useTabsStore } from '@/stores/tabs'
import { useDeco } from '@/composables/deco'
import { useCompletionSettle } from '@/composables/completion'
import { useNow } from '@/composables/now'
import { useClientPagination } from '@/composables/clientPagination'
import WaoPagination from '@/components/ui/WaoPagination.vue'
import { absoluteTime, relativeTime } from '@/utils/time'
import { fmtDate, TASK_STATUSES, TASK_STATUS_DOTS } from '@/utils/domain'
import { readSubtaskDrag } from '@/utils/subtaskTree'
import { toast } from '@/composables/toast'
import { createAiRuns } from '@/api/ai'
import ProjectSettingsModal from '@/components/overlays/ProjectSettingsModal.vue'
import { useFavoritesStore } from '@/stores/favorites'
import type { AiEngine, Task, TaskStatus } from '@/types'
import ProjectMemoryPanel from '@/components/ai/ProjectMemoryPanel.vue'
import AiRunLauncher from '@/components/ai/AiRunLauncher.vue'
import ProjectAssetsPanel from '@/components/files/ProjectAssetsPanel.vue'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import TaskBatchCopy from '@/components/task/TaskBatchCopy.vue'
import InlineSubtasks from '@/components/task/InlineSubtasks.vue'
import ProjectTaskRow from '@/components/task/ProjectTaskRow.vue'
import { AI_RUN_ENABLED } from '@/config/features'

const route = useRoute()
const router = useRouter()
const data = useDataStore()
const taskOrder = useTaskOrderStore()
const session = useSessionStore()
const ui = useUiStore()
const tabs = useTabsStore()
const favorites = useFavoritesStore()
const { decoProj, decoTask } = useDeco()

// 动态时间随时间推移自己变新
const now = useNow()
const showTime = (t: string) => relativeTime(t, now.value)

const collapsedSubTasks = ref<string[]>([])
function toggleSubtasks(id: string) {
  collapsedSubTasks.value = collapsedSubTasks.value.includes(id)
    ? collapsedSubTasks.value.filter((value) => value !== id)
    : [...collapsedSubTasks.value, id]
}
const pid = computed(() => String(route.params.id))
const orderScope = computed(() => `project:${pid.value}`)
const project = computed(() => data.projectById(pid.value))
// 「总览」标签页已下线：老链接归到任务。文件页签已经接入真实资产 id 后重新开放。
const projTab = computed(() => {
  const t = route.query.tab as string | undefined
  return t && t !== '总览' ? t : '任务'
})
// 普通入口统一打开表格；显式视图链接及当前页面的手动切换仍由 URL 指定。
const pdTaskView = computed<TaskView>(() => {
  const q = route.query.view
  return q === 'list' || q === 'board' || q === 'group' || q === 'quadrant' ? q : 'list'
})

function setTab(t: string) {
  router.replace({ query: { ...route.query, tab: t === '任务' ? undefined : t } })
}
function setView(v: TaskView) {
  if (v === 'list') finishSelection()
  ui.setTaskView(pid.value, v)
  router.replace({ query: { ...route.query, view: v } })
}

// 项目名解析 / 变更后回填页签标签（修复刷新后页签仍显示「项目 <id>」占位）。
watch(
  () => project.value?.name,
  (name) => {
    if (name) tabs.setLabel(`proj:${pid.value}`, name)
  },
  { immediate: true },
)

const settingsOpen = ref(false)
const projectInfoOpen = ref(false)
const projectMenuOpen = ref(false)
/** 批量选择在看板 / 分组 / 四象限视图提供；表格视图按用户要求去掉了。 */
const batchSelectionOpen = ref(false)
const columnPreferenceMemory = ref<Record<string, boolean>>({})
function columnPreference(kind: string) {
  return computed({
    get: () => {
      const memoryKey = `${kind}:${pid.value}`
      if (memoryKey in columnPreferenceMemory.value) return columnPreferenceMemory.value[memoryKey]!
      try { return JSON.parse(localStorage.getItem(`wao-board-${kind}`) || '{}')[pid.value] === true }
      catch { return false }
    },
    set: (value: boolean) => {
      columnPreferenceMemory.value[`${kind}:${pid.value}`] = value
      try {
        const stored = JSON.parse(localStorage.getItem(`wao-board-${kind}`) || '{}')
        localStorage.setItem(`wao-board-${kind}`, JSON.stringify({ ...stored, [pid.value]: value }))
      } catch { /* Storage restrictions must not prevent interaction. */ }
    },
  })
}
const showEmptyColumns = columnPreference('empty-columns-v1')
/** 表格视图是否按任务分组分段展示（与「分组」视图共用同一份分组数据，可在表格里直接改）。 */
const tableGrouped = columnPreference('table-grouped-v1')
type TableSortColumn = 'number' | 'title' | 'group' | 'priority' | 'status' | 'owner' | 'creator' | 'created' | 'start' | 'due'
const tableSort = ref<{ column: TableSortColumn; direction: 'asc' | 'desc' } | null>(null)
function toggleTableSort(column: TableSortColumn) {
  tableSort.value = { column, direction: tableSort.value?.column === column && tableSort.value.direction === 'asc' ? 'desc' : 'asc' }
}
function sortDirection(column: TableSortColumn) {
  return tableSort.value?.column === column ? (tableSort.value.direction === 'asc' ? 'ascending' : 'descending') : 'none'
}
function tableSortValue(task: Task, column: TableSortColumn): string | number | null {
  const memberName = (id?: string | null) => session.members.find(member => member.id === id)?.name ?? id ?? ''
  switch (column) {
    case 'number': return task.number ?? null
    case 'title': return task.title
    case 'group': return groups.value.find(group => group.id === groupIdOf(task))?.name ?? ''
    case 'priority': return Number(task.pr.slice(1))
    case 'status': return TASK_STATUSES.indexOf(task.status)
    case 'owner': return memberName(task.owner)
    case 'creator': return task.createdBy ? memberName(task.createdBy) : null
    case 'created': return task.createdAt ?? null
    case 'start': return task.startAt || task.start || null
    case 'due': return task.due || null
  }
}
function compareTableTasks(a: Task, b: Task) {
  if (!tableSort.value) return 0
  const { column, direction } = tableSort.value
  const left = tableSortValue(a, column), right = tableSortValue(b, column)
  if (left === null || left === '') return right === null || right === '' ? 0 : 1
  if (right === null || right === '') return -1
  const compared = typeof left === 'number' && typeof right === 'number'
    ? left - right : String(left).localeCompare(String(right), 'zh-CN', { numeric: true })
  return direction === 'asc' ? compared : -compared
}
/** 表格里正在录入子任务的位置：点行尾「+」才出现输入行，并且紧跟在要挂的那一行下面。 */
const addingSub = ref<{ taskId: string; parentId: string | null } | null>(null)
function startAddSub(taskId: string, parentId: string | null) {
  collapsedSubTasks.value = collapsedSubTasks.value.filter((id) => id !== taskId)
  addingSub.value = { taskId, parentId }
}
function finishSelection() {
  batchSelectionOpen.value = false
  selectedTaskIds.value = []
  aiBatchMode.value = null
}
const selectedTaskIds = ref<string[]>([])
const aiBatchStarting = ref(false)
const aiBatchMode = ref<'separate' | 'merge' | null>(null)
const creatorFilterOpen = ref(false)
const ownerFilterOpen = ref(false)
/** 负责人筛选：同样按项目记在本地，「只看我的」刷新后还在。 */
const ownerFilter = computed<string | null>({
  get: () => ui.ownerFilterOf(pid.value) || null,
  set: (v) => ui.setOwnerFilter(pid.value, v ?? ''),
})
/** 创建人筛选：按项目记在本地存储里，下次进来还是上次选的（此前刷新即丢）。 */
const creatorFilter = computed<string | null>({
  get: () => ui.creatorFilterOf(pid.value) || null,
  set: (v) => ui.setCreatorFilter(pid.value, v ?? ''),
})
function onProjectDeleted() {
  settingsOpen.value = false
  const next = tabs.close(`proj:${pid.value}`)
  router.push(next?.path ?? '/projects')
  toast('项目已删除', 'ri-delete-bin-line')
}

// ---- 拖拽：看板改状态 / 列表排序 ----
const dragId = ref<string | null>(null)
function onDragStart(id: string, event: DragEvent) {
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', id)
  }
  dragId.value = id
}
function onDropStatus(status: TaskStatus) {
  const id = dragId.value
  dragId.value = null
  if (!id) return
  const t = data.taskById(id)
  if (t && t.status !== status) data.patchTask(id, { status }, `将状态改为「${status}」`)
}
function onDropReorder(overId: string, event?: DragEvent) {
  const payload = event && readSubtaskDrag(event)
  if (payload) {
    event?.preventDefault()
    event?.stopPropagation()
    toast('请在子任务列表内调整顺序，更改归属请使用移动操作', 'ri-information-line')
    return
  }
  const id = dragId.value
  dragId.value = null
  if (id) void taskOrder.move(orderScope.value, ptasks.value.map(t => t.id), id, overId)
}

const pd = computed(() => (project.value ? decoProj(project.value) : null))
const activity = computed(() => pd.value?.activity ?? [])
const { page: activityPage, pageCount: activityPageCount, visible: visibleActivity } = useClientPagination(activity, [pid])
const ptasks = computed(() => taskOrder.sort(orderScope.value, project.value ? data.tasksByProject(project.value.id) : [], t => t.id))
const linkedDocs = computed(() => {
  const ids = new Set(ptasks.value.flatMap(task => [
    ...(task.docs ?? []), ...task.subs.flatMap(sub => sub.docs ?? []),
  ]))
  return [...ids].flatMap((id) => {
    const doc = data.docById(id)
    return doc && !data.isDocArchived(id)
      ? [{ id, title: doc.title || '未命名文档', updated: doc.updated }]
      : []
  })
})
// ---- 已完成任务的收起 / 缓冲 ----
// 默认收起已完成，点击展开；刚勾完成的任务先原地停留 3 秒再归档，给操作一个反应时间。
const showDone = columnPreference('done-expanded-v1')
const { toggle: toggleTask, isSettling } = useCompletionSettle()

const creatorOptions = computed(() => {
  const ids = [...new Set(ptasks.value.map((task) => task.createdBy).filter((id): id is string => Boolean(id)))]
  return ids.map((id) => ({ id, name: session.members.find((member) => member.id === id)?.name ?? id }))
})
/** 记住的创建人已经不在当前任务列表里（任务删光了）时视为未筛选，避免整页空白。 */
const effectiveCreator = computed(() =>
  creatorFilter.value && creatorOptions.value.some((c) => c.id === creatorFilter.value) ? creatorFilter.value : null,
)
const activeCreatorName = computed(() =>
  effectiveCreator.value ? creatorOptions.value.find((creator) => creator.id === effectiveCreator.value)?.name ?? effectiveCreator.value : '全部创建人',
)
const creatorTasks = computed(() => ptasks.value
  .filter((task) => !effectiveCreator.value || task.createdBy === effectiveCreator.value))
/** 负责人选项：项目成员全部列出，当前登录人排第一并标成「我」，一点就是「只看我的」。 */
const ownerOptions = computed(() => {
  const me = session.currentUser.id
  const list = session.members.map((member) => ({ id: member.id, name: member.id === me ? `${member.name}（我）` : member.name }))
  return [...list.filter((item) => item.id === me), ...list.filter((item) => item.id !== me)]
})
const effectiveOwner = computed(() =>
  ownerFilter.value && ownerOptions.value.some((option) => option.id === ownerFilter.value) ? ownerFilter.value : null,
)
const activeOwnerName = computed(() =>
  effectiveOwner.value ? ownerOptions.value.find((option) => option.id === effectiveOwner.value)?.name ?? effectiveOwner.value : '全部负责人',
)
/** 主任务归这个人，或者有子任务派给了这个人，都算「他的任务」。 */
const ownsTask = (task: { owner: string; subs: { owner?: string | null }[] }, owner: string) =>
  task.owner === owner || task.subs.some((sub) => sub.owner === owner)
const allTasks = computed(() => creatorTasks.value
  .filter((task) => !effectiveOwner.value || ownsTask(task, effectiveOwner.value))
  .map(decoTask))
/**
 * 真正收尾的任务：主任务已完成且子任务全部完成。主任务点了完成、子任务还有没做完的，
 * 仍算进行中的事，留在列表里展示，不跟着收进「已完成」。
 */
const filteredCompletion = computed(() => projectCompletion(allTasks.value))
const openTasks = computed(() => allTasks.value.filter((t) => !isFinished(t) || isSettling(t.id)))
/** 已完成倒序：最新完成的排最前；老数据没有完成时间时按 id（含创建时间戳）倒序兜底。 */
const doneDesc = (a: { completedAt?: string | null; id: string }, b: { completedAt?: string | null; id: string }) =>
  (b.completedAt ?? '').localeCompare(a.completedAt ?? '') || b.id.localeCompare(a.id)
const doneTasks = computed(() =>
  taskOrder.sort(orderScope.value, allTasks.value.filter((t) => isFinished(t) && !isSettling(t.id)).sort(doneDesc), t => t.id))
/** 「显示已完成的任务」开关。 */
const showFinished = computed(() => showDone.value)
// 看板没有勾选框，状态只能靠拖拽改变，所以不需要缓冲；「已完成」列默认收起，点列头展开。
const boardStatuses = computed(() => TASK_STATUSES.filter((status) => AI_RUN_ENABLED || status !== 'AI 进行中'
  || allTasks.value.some((task) => task.status === status)))
const board = computed(() =>
  boardStatuses.value.map((st) => ({
    label: st,
    dot: TASK_STATUS_DOTS[st],
    collapsible: st === '已完成',
    // 新任务一律落在「待办」，所以加号只出现在这一列——省掉一次把鼠标甩到右上角的来回。
    addable: st === '待办',
    items: st === '已完成'
      ? allTasks.value.filter((t) => t.status === st).sort(doneDesc)
      : allTasks.value.filter((t) => t.status === st),
  })),
)
const emptyBoardColumns = computed(() => board.value.filter((col) => !col.items.length && !col.collapsible && !col.addable))
const visibleBoard = computed(() => board.value.filter((col) => showEmptyColumns.value || col.items.length || col.collapsible || col.addable))
const pdTabs = computed(() => {
  // 任务角标只数未完成的：一个跑了半年的项目里「25」几乎全是已完成，看不出还剩多少事。
  const openCount = projectCompletion(ptasks.value).open
  const counts: Record<string, number> = { 任务: openCount, 文档: linkedDocs.value.length }
  return (['任务', '文档', '文件', '记忆', '动态'] as const).map((t) => ({
    label: t, count: counts[t], showCount: ['任务', '文档'].includes(t) && (t !== '任务' || data.taskLoadState === 'loaded'), on: projTab.value === t,
    title: t === '任务' ? `整个项目：${projectCompletionLabel(ptasks.value)}；不随负责人或创建人筛选变化` : '',
  }))
})

function openTask(id: string) {
  ui.openTask(id)
}
function toggleSelection(id: string) {
  selectedTaskIds.value = selectedTaskIds.value.includes(id)
    ? selectedTaskIds.value.filter((item) => item !== id)
    : [...selectedTaskIds.value, id]
}
const visibleTaskIds = computed(() => {
  if (pdTaskView.value === 'quadrant') return allTasks.value
    .filter((task) => showDone.value || !isFinished(task) || data.taskSaveState(task.id) === 'saving' || data.taskSaveState(task.id) === 'error')
    .map((task) => task.id)
  if (pdTaskView.value === 'list') return tableSections.value.flatMap((section) => section.collapsed ? [] : [
    ...section.open, ...(section.doneOpen ? section.done : []),
  ]).map((task) => task.id)
  if (pdTaskView.value === 'group') return groupSections.value.flatMap((group) => [
    ...group.open, ...(openedDoneGroups.value.includes(group.id) ? group.done : []),
  ]).map((task) => task.id)
  return allTasks.value.filter((task) => !isFinished(task) || showDone.value).map((task) => task.id)
})
const selectedCopyTasks = computed(() => allTasks.value.filter((task) => selectedTaskIds.value.includes(task.id)))
const selectedTaskScope = computed(() => selectedTaskIds.value
  .map((id) => data.taskById(id)?.title)
  .filter((title): title is string => !!title)
  .join('、'))

function openBatchAiLauncher(mergeTasks: boolean) {
  if (!selectedTaskIds.value.length || aiBatchStarting.value) return
  aiBatchMode.value = mergeTasks ? 'merge' : 'separate'
}

async function runSelectedWithAi(
  engine: AiEngine,
  runnerId: string | null,
  accountId: string | null,
  model: string | null,
  reasoningEffort: string | null,
  planMode: boolean,
  goalMode: boolean,
) {
  if (!selectedTaskIds.value.length || aiBatchStarting.value) return
  const taskIds = [...selectedTaskIds.value]
  const mergeTasks = aiBatchMode.value === 'merge'
  aiBatchStarting.value = true
  try {
    const created = await createAiRuns(taskIds, {
      mergeTasks, engine, runnerId, accountId, model, reasoningEffort, planMode, goalMode,
    })
    taskIds.forEach((taskId) => data.patchTask(taskId, { status: 'AI 进行中' },
      mergeTasks ? '已合并到同一次 AI Run' : '已单独创建 AI Run'))
    selectedTaskIds.value = []
    aiBatchMode.value = null
    toast(mergeTasks
      ? `已将 ${taskIds.length} 个任务合并为 ${created.length} 次 AI Run，可在首页查看`
      : `已将 ${taskIds.length} 个任务单独创建为 ${created.length} 次 AI Run，可在首页查看`,
    'ri-robot-2-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '批量 AI Run 启动失败', 'ri-error-warning-line')
  } finally {
    aiBatchStarting.value = false
  }
}
function openDoc(id: string) {
  const k = data.kbs.find((kk) => kk.folders.some((f) => f.docs.includes(id)))
  if (k) router.push({ path: `/kb/${k.id}`, query: { doc: id } })
  else router.push('/knowledge')
}
// ---- 分组视图 ----
// 一个项目里同时压着好几个 domain 的事：按状态切的看板帮不上忙，得按「这是哪一摊事」分。
// 分组里只关心做没做完，所以行上不再堆状态 / 优先级 / 负责人这些标签。

/** 分组列表；项目还没建过分组时是一个「未分组」兜底（不落库，改名或加组才写透）。 */
const groups = computed(() => data.taskGroupsOf(pid.value))
const groupsSaving = ref(false)
const collapsedGroups = ref<string[]>([])
const collapseKey = computed(() => `wao-project-collapsed-groups-v1:${session.currentUser.id}:${pid.value}`)
watch(collapseKey, (key) => {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(key) || '[]')
    collapsedGroups.value = Array.isArray(saved) ? saved.filter((id): id is string => typeof id === 'string') : []
  } catch { collapsedGroups.value = [] }
}, { immediate: true })
function toggleGroup(id: string) {
  collapsedGroups.value = collapsedGroups.value.includes(id)
    ? collapsedGroups.value.filter((value) => value !== id) : [...collapsedGroups.value, id]
  try { localStorage.setItem(collapseKey.value, JSON.stringify(collapsedGroups.value)) } catch { /* 存储不可用时仍可折叠 */ }
}
/** 分组视图里正在改名的分组 id。 */
const renamingGroup = ref<string | null>(null)
const renameText = ref('')
/** 分组视图里正在快速新建任务的分组 id，以及输入框内容。 */
const quickAddGroup = ref<string | null>(null)
const quickAddText = ref('')
const quickAddState = ref<'idle' | 'submitting' | 'error'>('idle')
const quickAddPendingId = ref<string | null>(null)
// v-for 里的模板 ref 是数组（一次只会渲染一个输入框，取第一个即可）
const quickAddInput = ref<HTMLInputElement | HTMLInputElement[] | null>(null)
const renameInput = ref<HTMLInputElement | HTMLInputElement[] | null>(null)
const firstOf = (r: HTMLInputElement | HTMLInputElement[] | null) => (Array.isArray(r) ? r[0] : r)
/** 每个分组的已完成是否展开（分组之间互不影响）。 */
const openedDoneGroups = ref<string[]>([])

/** 任务归属分组：没归过组、或归到已删除的分组，都算落在第一个分组里。 */
function groupIdOf(task: { id?: string; groupId?: string | null; parentTaskId?: string | null }): string {
  const seen = new Set<string>()
  while (task.parentTaskId && !seen.has(task.parentTaskId)) {
    seen.add(task.parentTaskId)
    const parent = ptasks.value.find(t => t.id === task.parentTaskId)
    if (!parent) break
    task = parent
  }
  const id = task.groupId
  return id && groups.value.some((g) => g.id === id) ? id : groups.value[0]!.id
}

/** 分组视图的数据：每个分组一段，未完成在前、已完成收起在后。 */
const groupSections = computed(() =>
  groups.value.map((g) => {
    const mine = allTasks.value.filter((t) => groupIdOf(t) === g.id)
    return {
      ...g,
      open: mine.filter((t) => !isFinished(t)),
      done: taskOrder.sort(orderScope.value, mine.filter((t) => isFinished(t)).sort(doneDesc), t => t.id),
      total: mine.length,
    }
  }),
)

async function persistGroups(next: { id: string; name: string }[], failHint: string) {
  if (groupsSaving.value) return false
  groupsSaving.value = true
  try {
    const saved = await data.setTaskGroups(pid.value, next)
    if (!saved) toast(failHint, 'ri-error-warning-line')
    return saved
  } finally { groupsSaving.value = false }
}
async function addGroup() {
  const created = { id: `tg${Date.now()}`, name: `分组 ${groups.value.length + 1}` }
  if (await persistGroups([...groups.value, created], '分组创建失败，请重试')) startRename(created.id, created.name)
}
function startRename(id: string, name: string) {
  renamingGroup.value = id
  renameText.value = name
  nextTick(() => firstOf(renameInput.value)?.select())
}
async function commitRename() {
  const id = renamingGroup.value
  const name = renameText.value.trim()
  if (!id || !name || groupsSaving.value) return
  // 先结束本次编辑，避免输入框卸载时的 blur 重复提交或覆盖下一次改名。
  renamingGroup.value = null
  if (!(await persistGroups(groups.value.map((g) => (g.id === id ? { ...g, name } : g)), '分组改名失败，名称仍保留，请重试'))) {
    startRename(id, name)
  }
}
/** 删掉一个分组：里面的任务不删，落回第一个分组。最后一个分组不给删，否则没地方放任务。 */
async function removeGroup(id: string) {
  if (groups.value.length <= 1) return toast('至少保留一个分组', 'ri-information-line')
  const group = groups.value.find((item) => item.id === id)
  if (!group || !window.confirm(`删除分组“${group.name}”？其中任务会归入第一个分组。`)) return
  const rest = groups.value.filter((g) => g.id !== id)
  if (!(await persistGroups(rest, '分组删除失败，请重试'))) return
  ptasks.value.filter((t) => t.groupId === id).forEach((t) => data.patchTask(t.id, { groupId: rest[0]!.id }))
  toast('分组已删除，里面的任务已归到第一个分组', 'ri-inbox-unarchive-line')
}
function toggleGroupDone(id: string) {
  openedDoneGroups.value = openedDoneGroups.value.includes(id)
    ? openedDoneGroups.value.filter((x) => x !== id)
    : [...openedDoneGroups.value, id]
}
/** 分组头上的「+」：就地展开一行输入框，回车即建，手机上不用再进弹窗。 */
function startQuickAdd(groupId: string) {
  if (quickAddState.value === 'submitting') return
  if (quickAddGroup.value !== null && (quickAddText.value.trim() || quickAddState.value === 'error')) {
    toast('请先完成当前输入，内容已保留', 'ri-information-line')
    return
  }
  if (collapsedGroups.value.includes(groupId)) toggleGroup(groupId)
  quickAddGroup.value = groupId
  quickAddText.value = ''
  quickAddState.value = 'idle'
  quickAddPendingId.value = null
  nextTick(() => firstOf(quickAddInput.value)?.focus())
}
async function submitQuickAdd(groupId: string) {
  if (quickAddState.value === 'submitting') return
  const title = quickAddText.value.trim()
  if (!title) return
  // 快速新建不替用户排期：截止日期留空，需要时再在表格里点选。
  const payload = {
    title, project: pid.value, pr: 'P2' as const, owner: session.currentUser.id,
    due: '', desc: '', images: [], groupId: groupId === '__all' ? groups.value[0]!.id : groupId, startAt: null, endAt: null,
  }
  quickAddState.value = 'submitting'
  let persisted = false
  if (quickAddPendingId.value) {
    data.patchTask(quickAddPendingId.value, payload)
    await data.flushTask(quickAddPendingId.value)
    persisted = data.taskSaveState(quickAddPendingId.value) === 'saved'
  } else {
    const result = await data.addTask(payload)
    quickAddPendingId.value = result.persisted ? null : result.task.id
    persisted = result.persisted
  }
  if (!persisted) {
    quickAddState.value = 'error'
    toast('任务没有保存到服务器，输入内容仍在', 'ri-error-warning-line')
    return
  }
  quickAddText.value = ''
  quickAddState.value = 'idle'
  quickAddPendingId.value = null
  toast(`任务「${title}」已创建`, 'ri-checkbox-circle-fill')
}
/**
 * 表格分段：不分组时是一整段；按分组时每个分组一段，带分组头（可折叠、改名、快速新建、删除）。
 * 已完成仍然默认收起，不分组时沿用原来的「已完成」开关，分组时各组独立展开。
 */
const tableSections = computed(() => {
  if (!tableGrouped.value) {
    return [{
      id: '__all', name: '', header: false, collapsed: false,
      open: openTasks.value, done: doneTasks.value, total: allTasks.value.length,
      doneOpen: showFinished.value,
    }]
  }
  const sections = groupSections.value.map((group) => ({
    ...group,
    header: true,
    collapsed: collapsedGroups.value.includes(group.id),
    open: allTasks.value.filter((task) => groupIdOf(task) === group.id && (!isFinished(task) || isSettling(task.id))),
    doneOpen: showFinished.value,
  }))
  return tableSort.value?.column === 'group'
    ? sections.sort((a, b) => (tableSort.value!.direction === 'asc' ? 1 : -1) * a.name.localeCompare(b.name, 'zh-CN'))
    : sections
})
const orderingOver = ref<{ id: string; after: boolean } | null>(null)
function onTableDragOver(id: string, event: DragEvent) {
  orderingOver.value = dragId.value && dragId.value !== id ? { id, after: taskDropAfter(event) } : null
}
async function moveUnder(id: string, parentId: string | null) {
  try {
    await data.moveTask(id, parentId)
    if (parentId) collapsedSubTasks.value = collapsedSubTasks.value.filter(value => value !== parentId)
    toast(parentId ? '已移为子任务，内容与编号保持不变' : '已移回顶层', 'ri-node-tree')
  } catch (error) { toast(error instanceof Error ? error.message : '移动失败，请重试', 'ri-error-warning-line') }
}
async function onTableDrop(overId: string, _sectionId: string, event: DragEvent) {
  orderingOver.value = null
  if (readSubtaskDrag(event)) return
  const id = dragId.value
  dragId.value = null
  const source = id ? data.taskById(id) : undefined, target = data.taskById(overId)
  if (!source || !target || source.id === target.id) return
  if ((source.parentTaskId || null) !== (target.parentTaskId || null)) {
    toast('只能在同一层级内排序，拖拽不会更改父子关系', 'ri-information-line'); return
  }
  await taskOrder.move(orderScope.value, ptasks.value.map(t => t.id), source.id, overId, taskDropAfter(event))
}
function tableRows(section: typeof tableSections.value[number]) {
  const candidates = [...section.open, ...(section.doneOpen ? section.done : [])]
  // 未完成后代仍需展示已完成父任务作为上下文。
  const ids = new Set(candidates.map(t => t.id))
  for (let i = 0; i < candidates.length; i++) {
    const parent = allTasks.value.find(t => t.id === candidates[i]!.parentTaskId)
    if (parent && !ids.has(parent.id)) { ids.add(parent.id); candidates.push(parent) }
  }
  return taskTreeRows(tableSort.value ? candidates.sort(compareTableTasks) : candidates, collapsedSubTasks.value)
}
function childTaskCount(id: string) { return ptasks.value.filter(task => task.parentTaskId === id).length }
async function onDropGroup(groupId: string, overId?: string) {
  const id = dragId.value
  dragId.value = null
  if (!id) return
  try {
    if (data.taskById(id)?.parentTaskId) { toast('请先通过移回顶层操作更改层级', 'ri-information-line'); return }
  } catch (error) {
    toast(error instanceof Error ? error.message : '移动失败', 'ri-error-warning-line')
    return
  }
  data.moveTaskToGroup(id, groupId)
  // 同组内落到某条任务上就是排序；空白处只改变分组，保持该组已有顺序。
  if (overId && overId !== id) void taskOrder.move(orderScope.value, ptasks.value.map(t => t.id), id, overId)
}
watch(visibleTaskIds, (ids) => {
  selectedTaskIds.value = selectedTaskIds.value.filter((id) => ids.includes(id))
  aiBatchMode.value = null
})
</script>

<template>
  <div v-if="!pd" class="missing">项目不存在或已被移除。</div>
  <div v-else class="detail" :class="{ 'detail-quadrant': projTab === '任务' && pdTaskView === 'quadrant', 'selecting-tasks': pdTaskView !== 'list' && (batchSelectionOpen || selectedTaskIds.length) }">

    <div class="phead">
      <button class="back" aria-label="全部项目" title="全部项目" @click="router.push('/projects')"><i class="ri-arrow-left-line"></i></button>
      <div class="phead-icon" :style="{ background: pd.areaColor }"><i :class="pd.icon || 'ri-stack-line'"></i></div>
      <div class="phead-name-wrap">
        <div class="phead-name-row">
          <div class="phead-name">{{ pd.name }}</div>
        </div>
      </div>
      <div class="spacer"></div>
      <div class="phead-avs">
        <span v-for="(mv, i) in pd.memberAvs" :key="i" class="phead-av" :style="{ background: mv.color }">{{ mv.initial }}</span>
      </div>
      <div class="project-more">
        <button class="btn-icon" aria-label="项目更多操作" :aria-expanded="projectMenuOpen" @click="projectMenuOpen = !projectMenuOpen"><i class="ri-more-line"></i></button>
        <div v-if="projectMenuOpen" class="project-more-menu" @keydown.esc="projectMenuOpen = false">
          <button @click="projectInfoOpen = !projectInfoOpen; projectMenuOpen = false"><i class="ri-information-line"></i>项目简介与周期</button>
          <button @click="settingsOpen = true; projectMenuOpen = false"><i class="ri-settings-3-line"></i>项目设置</button>
          <button @click="favorites.toggle('project', pid, pd.name); projectMenuOpen = false"><i class="ri-star-line"></i>{{ favorites.isFav('project', pid) ? '取消收藏' : '收藏到左侧菜单' }}</button>
          <button @click="setTab('记忆'); projectMenuOpen = false"><i class="ri-brain-line"></i>项目记忆</button>
          <button @click="setTab('动态'); projectMenuOpen = false"><i class="ri-history-line"></i>项目动态</button>
        </div>
      </div>
      <button class="btn-primary sm" @click="ui.openNewTask(pid)"><i class="ri-add-line"></i>新建任务</button>
    </div>

    <div v-if="projectInfoOpen" class="project-info">{{ pd.areaName }} · {{ fmtDate(pd.start) }} – {{ fmtDate(pd.due) }}</div>
    <div class="ptabs">
      <button v-for="tb in pdTabs" :key="tb.label" class="ptab" :class="{ on: tb.on }" :title="tb.title" @click="setTab(tb.label)">
        {{ tb.label }}<span v-if="tb.showCount" class="ptab-count wao-tabular">{{ tb.count }}</span>
      </button>
      <div v-if="projTab === '任务'" class="tab-toolbar task-view-toolbar">
        <div class="seg">
          <button v-for="v in ([['表格', 'ri-table-line', 'list'], ['看板', 'ri-layout-column-line', 'board'], ['分组', 'ri-folder-2-line', 'group'], ['四象限', 'ri-layout-grid-line', 'quadrant']] as const)" :key="v[2]" class="seg-btn icon" :class="{ on: pdTaskView === v[2] }" @click="setView(v[2])">
            <i :class="v[1]"></i>{{ v[0] }}
          </button>
        </div>
        <div class="creator-filter">
          <button
            class="creator-filter-btn"
            :class="{ on: effectiveOwner }"
            title="按负责人筛选；主任务或其中子任务派给了此人都算"
            @click="ownerFilterOpen = !ownerFilterOpen; creatorFilterOpen = false"
          >
            <i class="ri-user-follow-line"></i>{{ activeOwnerName }}<i class="ri-arrow-down-s-line"></i>
          </button>
          <div v-if="ownerFilterOpen" class="creator-filter-menu">
            <button :class="{ on: !effectiveOwner }" @click="ownerFilter = null; ownerFilterOpen = false">全部负责人</button>
            <button
              v-for="owner in ownerOptions"
              :key="owner.id"
              :class="{ on: effectiveOwner === owner.id }"
              @click="ownerFilter = owner.id; ownerFilterOpen = false"
            >{{ owner.name }}</button>
          </div>
        </div>
        <div class="creator-filter">
          <button
            class="creator-filter-btn"
            :class="{ on: creatorFilter }"
            :disabled="!creatorOptions.length"
            :title="creatorOptions.length ? '按创建人筛选任务' : '历史任务没有可靠的创建人记录'"
            @click="creatorFilterOpen = !creatorFilterOpen; ownerFilterOpen = false"
          >
            <i class="ri-user-search-line"></i>{{ activeCreatorName }}<i class="ri-arrow-down-s-line"></i>
          </button>
          <div v-if="creatorFilterOpen" class="creator-filter-menu">
            <button :class="{ on: !creatorFilter }" @click="creatorFilter = null; creatorFilterOpen = false">全部创建人</button>
            <button
              v-for="creator in creatorOptions"
              :key="creator.id"
              :class="{ on: creatorFilter === creator.id }"
              @click="creatorFilter = creator.id; creatorFilterOpen = false"
            >{{ creator.name }}</button>
          </div>
        </div>
        <button v-if="pdTaskView !== 'list' && !batchSelectionOpen && !selectedTaskIds.length" class="creator-filter-btn batch-selection-toggle" @click="batchSelectionOpen = true"><i class="ri-checkbox-multiple-line"></i>批量选择</button>
        <!-- 表格的分组方式与已完成开关放在原「批量选择」的位置，和筛选同一行 -->
        <template v-if="pdTaskView === 'list'">
          <div class="table-seg" role="group" aria-label="表格分组方式">
            <button :class="{ on: !tableGrouped }" :aria-pressed="!tableGrouped" @click="tableGrouped = false">不分组</button>
            <button :class="{ on: tableGrouped }" :aria-pressed="tableGrouped" @click="tableGrouped = true">按分组</button>
          </div>
          <button v-if="tableGrouped" class="table-toolbar-btn" :disabled="groupsSaving" @click="addGroup">
            <i class="ri-add-line"></i>{{ groupsSaving ? '保存中…' : '新建分组' }}
          </button>
          <button
            class="done-switch"
            role="switch"
            :aria-checked="showFinished"
            title="是否显示已完成的任务与子任务；主任务完成但子任务未做完的始终显示"
            @click="showDone = !showDone"
          ><span class="track"><span class="thumb"></span></span>显示已完成<em v-if="doneTasks.length" class="done-switch-count wao-tabular">{{ doneTasks.length }}</em></button>
        </template>
      </div>
    </div>

    <!-- 任务 -->
    <div v-if="projTab === '任务'" class="project-task-content">
      <div v-if="data.taskLoadState === 'error'" class="task-scope" role="alert">
        任务加载失败，当前列表可能不完整，本机草稿已保留。{{ data.taskLoadError }}
        <button @click="data.hydrate()">重新加载任务</button>
      </div>
      <div v-else-if="data.taskLoadState === 'loading'" class="task-scope" role="status">正在加载任务，已显示的任务和本机草稿会继续保留。</div>
      <div v-if="effectiveCreator || effectiveOwner" class="task-scope" role="status">
        当前仅显示{{ effectiveOwner ? ` ${activeOwnerName} 负责的` : '' }}{{ effectiveCreator ? ` ${activeCreatorName} 创建的` : '' }}任务 · 筛选内未收尾 {{ filteredCompletion.open }}／共 {{ allTasks.length }} 项任务（项目共 {{ ptasks.length }} 项；任务按全部子项判定收尾）
        <button v-if="effectiveOwner" @click="ownerFilter = null">查看全部负责人</button>
        <button v-if="effectiveCreator" @click="creatorFilter = null">查看全部创建人</button>
      </div>
      <!-- 表格视图不再提供批量选择；看板、分组和四象限视图仍可多选后复制 -->
      <div v-if="pdTaskView !== 'list' && (batchSelectionOpen || selectedTaskIds.length)" class="copy-selection-actions">
        <button class="creator-filter-btn" :disabled="!visibleTaskIds.length" @click="selectedTaskIds = [...visibleTaskIds]">全选当前可见 · {{ visibleTaskIds.length }}</button>
        <button v-if="selectedTaskIds.length" class="creator-filter-btn" @click="selectedTaskIds = []">清空选择</button>
        <span class="selection-count">已选 {{ selectedTaskIds.length }} 项</span>
        <button class="creator-filter-btn" :disabled="aiBatchStarting" @click="finishSelection">完成选择</button>
        <div v-if="AI_RUN_ENABLED && selectedTaskIds.length" class="batch-ai-actions" aria-label="批量 AI Run 执行方式">
          <button
            class="batch-ai separate"
            :disabled="aiBatchStarting"
            @click="openBatchAiLauncher(false)"
          >
            <i :class="aiBatchStarting ? 'ri-loader-4-line spin' : 'ri-send-plane-line'"></i>
            单独给 AI · {{ selectedTaskIds.length }}
          </button>
          <button
            v-if="selectedTaskIds.length > 1"
            class="batch-ai merge"
            :disabled="aiBatchStarting"
            @click="openBatchAiLauncher(true)"
          >
            <i :class="aiBatchStarting ? 'ri-loader-4-line spin' : 'ri-node-tree'"></i>
            合并给 AI · {{ selectedTaskIds.length }}
          </button>
          <AiRunLauncher
            v-if="aiBatchMode"
            :project-id="pid"
            :starting="aiBatchStarting"
            :scope="`${aiBatchMode === 'merge' ? '合并' : '单独'}派发 ${selectedTaskIds.length} 个任务：${selectedTaskScope}`"
            @close="aiBatchMode = null"
            @start="runSelectedWithAi"
          />
        </div>
        <TaskBatchCopy :tasks="selectedCopyTasks" />
      </div>
      <template v-if="pdTaskView === 'list'">
      <div class="task-list-card task-table-scroll">
        <TaskOrderStatus :scope="orderScope" />
        <table class="project-task-table" aria-label="项目任务表格">
          <colgroup>
            <col style="width: 64px" />
            <col style="width: auto" />
            <col style="width: 112px" />
            <col style="width: 64px" />
            <col style="width: 92px" />
            <col style="width: 88px" />
            <col style="width: 88px" />
            <col style="width: 96px" />
            <col style="width: 124px" />
            <col style="width: 124px" />
          </colgroup>
          <thead>
            <tr>
              <th v-for="column in ([
                { key: 'number', label: 'ID' }, { key: 'title', label: '标题' },
                { key: 'group', label: '分组' }, { key: 'priority', label: '优先级' },
                { key: 'status', label: '状态' }, { key: 'owner', label: '负责人' },
                { key: 'creator', label: '创建者' }, { key: 'created', label: '创建日期' },
                { key: 'start', label: '计划开始' }, { key: 'due', label: '截止日期' },
              ] as { key: TableSortColumn; label: string }[])" :key="column.key" scope="col" :aria-sort="sortDirection(column.key)">
                <div class="table-title-heading">
                  <button class="table-sort" type="button" :aria-label="`按${column.label}${sortDirection(column.key) === 'ascending' ? '降序' : '升序'}排序`" @click="toggleTableSort(column.key)">
                    {{ column.label }}<i :class="sortDirection(column.key) === 'ascending' ? 'ri-arrow-up-line' : sortDirection(column.key) === 'descending' ? 'ri-arrow-down-line' : 'ri-expand-up-down-line'" aria-hidden="true"></i>
                  </button>
                  <button v-if="column.key === 'title'" class="group-op" aria-label="快速新建任务" title="只输入标题新建任务" @click="startQuickAdd(tableGrouped ? groups[0]!.id : '__all')"><i class="ri-add-line"></i></button>
                </div>
              </th>
            </tr>
          </thead>
          <template v-for="section in tableSections" :key="section.id">
            <tbody v-if="section.header" class="table-group">
              <tr class="table-group-row" :class="{ 'drop-hint': dragId }" @dragover.prevent @drop="onDropGroup(section.id)">
                <td colspan="10">
                  <div class="table-group-head">
                    <button class="group-collapse" :aria-label="`${section.collapsed ? '展开' : '折叠'}分组 ${section.name}`" :aria-expanded="!section.collapsed" @click="toggleGroup(section.id)"><i :class="section.collapsed ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
                    <input
                      v-if="renamingGroup === section.id"
                      ref="renameInput"
                      v-model="renameText"
                      class="group-name-input"
                      maxlength="20"
                      placeholder="分组名称"
                      @keydown.enter="commitRename"
                      @keydown.esc="renamingGroup = null"
                      @blur="commitRename"
                    />
                    <button v-else class="group-name" title="点击改名" @click="startRename(section.id, section.name)">
                      {{ section.name }}<i class="ri-pencil-line"></i>
                    </button>
                    <span class="table-group-count wao-tabular" :title="`${section.open.length} 个未完成 / 共 ${section.total} 个`">({{ section.total }})</span>
                    <button class="group-op" title="在这个分组里新建任务" aria-label="在这个分组里新建任务" @click="startQuickAdd(section.id)"><i class="ri-add-line"></i></button>
                    <button v-if="groups.length > 1" class="group-op danger" title="删除分组（里面的任务会归到第一个分组）" aria-label="删除分组" @click="removeGroup(section.id)"><i class="ri-delete-bin-line"></i></button>
                  </div>
                </td>
              </tr>
            </tbody>
            <tbody v-if="quickAddGroup === section.id && !section.collapsed">
              <tr class="table-quick-add">
                <td colspan="10">
                  <div class="group-add-row" :class="{ error: quickAddState === 'error' }">
                    <i :class="quickAddState === 'submitting' ? 'ri-loader-4-line spinning' : 'ri-add-line'"></i>
                    <input
                      ref="quickAddInput"
                      v-model="quickAddText"
                      :disabled="quickAddState === 'submitting'"
                      placeholder="任务标题，回车创建，连着敲可以一次录好几条"
                      @keydown.enter.prevent="!$event.isComposing && submitQuickAdd(section.id)"
                      @keydown.esc="quickAddGroup = null"
                    />
                    <button class="group-add-done" :disabled="quickAddState === 'submitting'" @click="quickAddState === 'error' ? submitQuickAdd(section.id) : (quickAddGroup = null)">
                      {{ quickAddState === 'submitting' ? '提交中' : quickAddState === 'error' ? '重试' : '完成' }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
            <template v-if="!section.collapsed">
              <template v-for="{ task: t, depth } in tableRows(section)" :key="t.id">
                <tbody>
                  <ProjectTaskRow
                    :task="t"
                    :depth="depth"
                    :child-task-count="childTaskCount(t.id)"
                    :order-side="orderingOver?.id === t.id ? (orderingOver.after ? 'after' : 'before') : undefined"
                    @detach="moveUnder(t.id, null)"
                    :groups="groups"
                    :group-id="groupIdOf(t)"
                    :expanded="!collapsedSubTasks.includes(t.id)"
                    :dragging="dragId === t.id"
                    :class="{ settling: isSettling(t.id) }"
                    @toggle-subs="toggleSubtasks(t.id)"
                    @add-sub="startAddSub(t.id, null)"
                    @open="openTask(t.id)"
                    @dragstart="onDragStart(t.id, $event)"
                    @dragend="dragId = null; orderingOver = null"
                    @dragleave="orderingOver = null"
                    @dragover.prevent="onTableDragOver(t.id, $event)"
                    @drop.prevent.stop="onTableDrop(t.id, section.id, $event)"
                  />
                </tbody>
                <InlineSubtasks
                  v-if="(t.subs.length && !collapsedSubTasks.includes(t.id)) || addingSub?.taskId === t.id"
                  :task-id="t.id"
                  :task-depth="depth"
                  :hide-done="!showFinished"
                  :adding="addingSub?.taskId === t.id"
                  :add-parent-id="addingSub?.taskId === t.id ? addingSub.parentId : null"
                  :owner-filter="effectiveOwner && t.owner !== effectiveOwner ? effectiveOwner : null"
                  table
                  @add-sub="(parentId) => startAddSub(t.id, parentId)"
                  @close-add="addingSub = null"
                />
              </template>
              <tbody v-if="section.header && !section.total && quickAddGroup !== section.id">
                <tr><td colspan="10" class="empty-inline">{{ data.taskLoadState === 'error' ? '任务加载失败，请重新加载' : data.taskLoadState === 'loading' ? '正在加载任务…' : '这个分组还没有任务，点分组名旁的「+」添加' }}</td></tr>
              </tbody>
            </template>
          </template>
          <tbody v-if="!allTasks.length && !tableGrouped">
            <tr>
              <td colspan="10" class="empty-inline">
                {{
                  data.taskLoadState === 'error'
                    ? '任务加载失败，暂时无法确认任务数量，请重新加载'
                    : data.taskLoadState === 'loading'
                    ? '正在加载任务…'
                    : ptasks.length
                    ? '当前筛选没有任务，换个负责人或创建人再看'
                    : '还没有任务——点右上角「新建任务」开始拆解'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      </template>

      <!-- 分组：按自己定义的 domain 分堆，组内只看做没做完 -->
      <div v-else-if="pdTaskView === 'group'" class="groups">
        <div
          v-for="g in groupSections"
          :key="g.id"
          class="group-card"
          :class="{ 'drop-hint': dragId }"
          @dragover.prevent
          @drop="onDropGroup(g.id)"
        >
          <div class="group-head">
            <button class="group-collapse" :aria-label="`${collapsedGroups.includes(g.id) ? '展开' : '折叠'}分组 ${g.name}`" :aria-expanded="!collapsedGroups.includes(g.id)" @click="toggleGroup(g.id)"><i :class="collapsedGroups.includes(g.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
            <input
              v-if="renamingGroup === g.id"
              ref="renameInput"
              v-model="renameText"
              class="group-name-input"
              maxlength="20"
              placeholder="分组名称"
              @keydown.enter="commitRename"
              @keydown.esc="renamingGroup = null"
              @blur="commitRename"
            />
            <button v-else class="group-name" title="点击改名" @click="startRename(g.id, g.name)">
              {{ g.name }}<i class="ri-pencil-line"></i>
            </button>
            <span class="group-count wao-tabular" :title="`${g.open.length} 个未完成 / 共 ${g.total} 个`">
              {{ g.open.length }} / {{ g.total }}
            </span>
            <span class="spacer"></span>
            <button class="group-op" title="在这个分组里新建任务" aria-label="新建任务" @click="startQuickAdd(g.id)">
              <i class="ri-add-line"></i>
            </button>
            <button
              v-if="groups.length > 1"
              class="group-op danger"
              title="删除分组（里面的任务会归到第一个分组）"
              @click="removeGroup(g.id)"
            >
              <i class="ri-delete-bin-line"></i>
            </button>
          </div>

          <div v-show="!collapsedGroups.includes(g.id)" class="group-body">
          <div v-if="quickAddGroup === g.id" class="group-add-wrap">
            <div class="group-add-row" :class="{ error: quickAddState === 'error' }">
              <i :class="quickAddState === 'submitting' ? 'ri-loader-4-line spinning' : 'ri-add-line'"></i>
              <input
                ref="quickAddInput"
                v-model="quickAddText"
                :disabled="quickAddState === 'submitting'"
                placeholder="任务标题，回车创建，连着敲可以一次录好几条"
                @keydown.enter.prevent="submitQuickAdd(g.id)"
                @keydown.esc="quickAddGroup = null"
              />
              <button class="group-add-done" :disabled="quickAddState === 'submitting'" @click="quickAddState === 'error' ? submitQuickAdd(g.id) : (quickAddGroup = null)">
                {{ quickAddState === 'submitting' ? '提交中' : quickAddState === 'error' ? '重试' : '完成' }}
              </button>
            </div>
            <span v-if="quickAddState === 'error'" class="group-add-error" role="alert">未保存到服务器，输入仍保留</span>
          </div>

          <div
            v-for="t in g.open"
            :key="t.id"
            class="grow"
            :class="{ dragging: dragId === t.id }"
            draggable="true"
            @click="openTask(t.id)"
            @dragstart="onDragStart(t.id, $event)"
            @dragend="dragId = null"
            @dragover.prevent.stop
            @drop.stop="readSubtaskDrag($event) ? onDropReorder(t.id, $event) : onDropGroup(g.id, t.id)"
          >
            <button class="select-dot" :class="{ on: selectedTaskIds.includes(t.id) }" :aria-label="`选择任务 ${t.title}`" :aria-pressed="selectedTaskIds.includes(t.id)" @click.stop="toggleSelection(t.id)"><i class="ri-checkbox-blank-circle-line"></i></button>
            <button class="check" :style="{ borderColor: t.checkBorder, background: t.checkBg }" @click.stop="toggleTask(t.id)">
              <i class="ri-check-line" :style="{ opacity: t.checkOp }"></i>
            </button>
            <span class="grow-title">{{ t.title }}</span>
            <span v-if="t.unfinishedSubCount" class="wait-badge">仍有 {{ t.unfinishedSubCount }} 项子项未收尾</span>
            <i class="ri-arrow-right-s-line grow-chev"></i>
          </div>

          <button v-if="g.done.length" class="group-done-toggle" @click="toggleGroupDone(g.id)">
            <i :class="openedDoneGroups.includes(g.id) ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'"></i>
            已完成<span class="done-count wao-tabular">{{ g.done.length }}</span>
          </button>
          <template v-if="openedDoneGroups.includes(g.id)">
            <div v-for="t in g.done" :key="t.id" class="grow done" @click="openTask(t.id)">
              <button class="select-dot" :class="{ on: selectedTaskIds.includes(t.id) }" :aria-label="`选择任务 ${t.title}`" :aria-pressed="selectedTaskIds.includes(t.id)" @click.stop="toggleSelection(t.id)"><i class="ri-checkbox-blank-circle-line"></i></button>
              <button class="check" :style="{ borderColor: t.checkBorder, background: t.checkBg }" @click.stop="toggleTask(t.id)">
                <i class="ri-check-line" :style="{ opacity: t.checkOp }"></i>
              </button>
              <span class="grow-title">{{ t.title }}</span>
              <i class="ri-arrow-right-s-line grow-chev"></i>
            </div>
          </template>

          <div v-if="!g.total" class="group-empty">点右上角的「+」往这里加第一条任务</div>
          </div>
        </div>

        <button class="group-add" :disabled="groupsSaving" @click="addGroup">
          <i class="ri-add-line"></i>{{ groupsSaving ? '保存中…' : '新建分组' }}
        </button>
      </div>

      <TaskQuadrantView v-else-if="pdTaskView === 'quadrant'" :key="pid"
        v-model:show-done="showDone" :tasks="allTasks" :project-id="pid"
        :selected-ids="selectedTaskIds" :selection-mode="batchSelectionOpen || selectedTaskIds.length > 0"
        @select="toggleSelection" />
      <div v-else class="board">
        <div
          v-for="col in visibleBoard"
          :key="col.label"
          class="board-col"
          :class="{ 'drop-hint': dragId, 'is-collapsed': col.collapsible && !showDone && col.items.every(isFinished) }"
          @dragover.prevent
          @drop="onDropStatus(col.label)"
        >
          <div class="board-col-head" :class="{ clickable: col.collapsible }" @click="col.collapsible && (showDone = !showDone)">
            <span class="board-dot" :style="{ background: col.dot }"></span>
            <span class="board-col-label">{{ col.label }}</span>
            <span class="board-col-count wao-tabular">{{ col.items.length }}</span>
            <button
              v-if="col.addable"
              class="board-col-add"
              title="在这里新建任务"
              aria-label="新建任务"
              @click.stop="ui.openModal('task', pid)"
            >
              <i class="ri-add-line"></i>
            </button>
            <i v-if="col.collapsible" class="board-col-chev" :class="showDone ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'"></i>
          </div>
          <div v-if="col.collapsible && !showDone" class="board-collapsed">已收起 {{ col.items.filter(isFinished).length }} 项已收尾任务 · 点标题展开</div>
          <div v-if="!col.collapsible || showDone || col.items.some(t => !isFinished(t))" class="board-cards">
            <div
              v-for="t in col.items.filter(t => !col.collapsible || showDone || !isFinished(t))"
              :key="t.id"
              class="board-card" @dragover.prevent @drop.stop="onDropReorder(t.id, $event)"
              :class="{ dragging: dragId === t.id }"
              draggable="true"
              @click="openTask(t.id)"
              @dragstart="onDragStart(t.id, $event)"
              @dragend="dragId = null"
            >
              <button
                class="select-dot board-select"
                :class="{ on: selectedTaskIds.includes(t.id) }"
                :aria-label="`选择任务 ${t.title}`"
                :aria-pressed="selectedTaskIds.includes(t.id)"
                title="选择后可合并复制或交给 AI"
                @click.stop="toggleSelection(t.id)"
              >
                <i :class="selectedTaskIds.includes(t.id) ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i>
              </button>
              <div class="bc-title" :title="t.title" :style="{ color: t.titleColor, textDecoration: t.deco }">{{ t.title }}</div>
              <span v-if="t.unfinishedSubCount" class="wait-badge">仍有 {{ t.unfinishedSubCount }} 项子项未收尾</span>
              <div class="bc-foot">
                <span class="mini" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
                <span class="bc-due" :style="{ color: t.dueColor }">{{ t.dueStr }}</span>
                <span class="spacer"></span>
                <span class="owner-av small" :title="t.ownerName" :style="{ background: t.ownerColor }">{{ t.ownerInitial }}</span>
              </div>
            </div>
            <div v-if="!col.items.length" class="board-empty">拖到此处标记为「{{ col.label }}」</div>
          </div>
        </div>
      </div>
      <div v-if="pdTaskView === 'board' && emptyBoardColumns.length" class="empty-columns-control">
        <span>空列：</span>
        <button v-for="col in emptyBoardColumns" :key="col.label" class="empty-column-target"
          @dragover.prevent @drop.prevent.stop="onDropStatus(col.label)"
          :title="`拖到此处标记为「${col.label}」`">{{ col.label }} 0</button>
        <button :aria-expanded="showEmptyColumns" @click="showEmptyColumns = !showEmptyColumns">{{ showEmptyColumns ? '折叠空列' : '显示空列' }}</button>
      </div>
    </div>

    <!-- 文档 -->
    <div v-else-if="projTab === '文档'">
      <div class="tab-toolbar gap">
        <span class="no-kb">仅展示本项目任务关联的文档</span>
        <div class="spacer"></div>
        <button class="btn-primary xs" @click="router.push('/knowledge')"><i class="ri-book-2-line"></i>前往知识库</button>
      </div>
      <div class="doc-list-card">
        <div v-for="d in linkedDocs" :key="d.id" class="doc-row" @click="openDoc(d.id)">
          <i class="ri-file-text-line"></i>
          <span class="doc-title">{{ d.title }}</span>
          <span class="doc-time">{{ d.updated }}</span>
          <i class="ri-arrow-right-s-line chev"></i>
        </div>
        <div v-if="!linkedDocs.length" class="doc-empty">
          <i class="ri-file-add-line"></i>
          <span>在任务或子任务中关联文档后，会展示在这里。</span>
        </div>
      </div>
    </div>

    <!-- 项目文件、图床与 HTML 复用全局真实存储 -->
    <ProjectAssetsPanel v-else-if="projTab === '文件'" :project-id="pid" :project-name="project?.name || pid" />

    <!-- 项目级工程记忆与个人生活记忆严格分域 -->
    <ProjectMemoryPanel v-else-if="projTab === '记忆'" :project-id="pid" />

    <!-- 动态 -->
    <div v-else class="feed-card">
      <div v-for="(ac, i) in visibleActivity" :key="`${activityPage}-${i}`" class="feed-row bordered">
        <i :class="ac.icon" :style="{ color: ac.color }"></i>
        <div class="feed-main">
          <div class="feed-text lg">{{ ac.text }}</div>
          <div class="feed-time" :title="absoluteTime(ac.time)">{{ showTime(ac.time) }}</div>
        </div>
      </div>
      <div v-if="!pd.activity.length" class="feed-empty">还没有动态——创建任务、完成任务、沉淀文档都会记录在这里。</div>
      <WaoPagination v-if="activityPageCount > 1" v-model:page="activityPage" :page-count="activityPageCount" :total="activity.length" aria-label="项目动态分页" />
    </div>

    <ProjectSettingsModal
      v-if="settingsOpen && project"
      :project="project"
      @close="settingsOpen = false"
      @deleted="onProjectDeleted"
    />
  </div>
</template>

<style scoped>
.task-scope { padding:8px 16px; color:var(--wao-text-weak); background:var(--wao-bg-sub); font-size:12px; }.task-scope button { margin-left:12px; border:0; background:none; color:var(--wao-primary-text); cursor:pointer; }

.copy-selection-actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0 6px; }
.missing {
  padding: 60px 24px;
  text-align: center;
  color: var(--wao-text-weak);
}
.detail {
  padding: 6px 16px 16px;
  max-width: none;
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.detail.detail-quadrant { display: flex; flex-direction: column; flex: 1; width: 100%; min-width: 0; min-height: 0; box-sizing: border-box; margin: 0; }
.detail-quadrant > :not(.project-task-content) { flex-shrink: 0; }
.detail-quadrant .project-task-content { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; }
.detail-quadrant .project-task-content > :not(.quadrant-view) { flex-shrink: 0; }
.detail-quadrant :deep(.copy-preview) { max-height: 25vh; overflow: auto; }
.spacer {
  flex: 1;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  color: #7a7d89;
  font-size: 12.5px;
  cursor: pointer;
  padding: 4px 6px;
  margin-left: -6px;
  border-radius: 6px;
}
.back:hover {
  background: var(--wao-divider);
  color: var(--wao-text-2);
}
.back i {
  font-size: 14px;
}

.phead {
  margin-top: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.phead-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  opacity: 0.92;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 19px;
}
.phead-name-wrap {
  min-width: 0;
}
.phead-name-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.phead-name {
  font-size: 20px;
  font-weight: 700;
}
.phead-sub {
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--wao-text-weak);
}
.phead-sub i {
  font-size: 12px;
}
.phead-avs {
  display: flex;
  margin-right: 2px;
}
.phead-av {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  color: #fff;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 2px solid var(--wao-bg-page);
  margin-left: -7px;
}
.btn-primary {
  height: 33px;
  padding: 0 14px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-primary:hover {
  background: var(--wao-primary-hover);
}
.btn-primary i {
  font-size: 14px;
}
.btn-primary.sm {
  height: 32px;
  padding: 0 13px;
  font-size: 12.5px;
}
.btn-primary.xs {
  height: 30px;
  padding: 0 12px;
  font-size: 12.5px;
}
.btn-icon {
  width: 32px;
  height: 32px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 8px;
  color: var(--wao-text-3);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.btn-icon:hover {
  background: var(--wao-bg-hover);
  color: var(--wao-text-2);
}
.btn-icon i {
  font-size: 15px;
}
.btn-icon.faved,
.btn-icon.faved:hover {
  color: #b9821f;
  border-color: #e5d3a8;
  background: var(--wao-warning-bg);
}

.ptabs {
  margin-top: 16px;
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--wao-line);
}
.ptab {
  height: 36px;
  padding: 0 13px;
  border: none;
  background: none;
  border-bottom: 2px solid transparent;
  color: #7a7d89;
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-bottom: -1px;
}
.ptab.on {
  color: var(--wao-text);
  font-weight: 600;
  border-bottom-color: var(--wao-primary);
}
.ptab-count {
  font-size: 11px;
  color: var(--wao-text-weak);
}
.check {
  width: 17px;
  height: 17px;
  border-radius: 5px;
  border: 1.5px solid #c9cdda;
  background: var(--wao-bg-card);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  flex-shrink: 0;
}
.select-dot {
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--wao-bg-tab);
  color: #6b6e79;
  cursor: pointer;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.select-dot:hover {
  border-color: var(--wao-primary);
}
.select-dot.on {
  color: #fff;
  background: var(--wao-primary);
}
.select-dot i {
  font-size: 14px;
}
.board-select {
  position: absolute;
  top: 10px;
  right: 10px;
}
.check i {
  font-size: 12px;
  color: #fff;
}
.wait-badge {
  font-size: 11px;
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  border-radius: 5px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.mini {
  font-size: 11px;
  border-radius: 5px;
  padding: 1px 6px;
  flex-shrink: 0;
}

/* 动态 */
.feed-row {
  display: flex;
  gap: 10px;
  padding: 7px 0;
}
.feed-row.bordered {
  padding: 8px 0;
  border-bottom: 1px solid #f6f6f8;
}
.feed-row > i {
  font-size: 14px;
  flex-shrink: 0;
  margin-top: 1px;
}
.feed-main {
  flex: 1;
  min-width: 0;
}
.feed-text {
  font-size: 12.5px;
  color: var(--wao-text-2);
  line-height: 1.6;
}
.feed-text.lg {
  font-size: 13px;
}
.feed-time {
  font-size: 11px;
  color: var(--wao-text-faint);
  margin-top: 1px;
}

/* 任务/文档/文件 tabs */
.tab-toolbar {
  margin-top: 8px;
  display: flex;
  align-items: center;
}
.batch-ai-actions {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.batch-ai {
  height: 32px;
  padding: 0 11px;
  border-radius: 8px;
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.batch-ai.separate {
  border-color: var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
.batch-ai {
  border: 1px solid #bec8f5;
  background: linear-gradient(135deg, var(--wao-primary-tint), var(--wao-bg-card));
  color: var(--wao-primary-text);
  font-weight: 650;
}
.batch-ai:disabled {
  opacity: 0.6;
}
.spin {
  display: inline-block;
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.tab-toolbar.gap {
  gap: 10px;
}
.seg {
  display: flex;
  background: var(--wao-divider);
  border-radius: 9px;
  padding: 3px;
  gap: 2px;
}
.seg-btn {
  height: 26px;
  padding: 0 12px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: #7a7d89;
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.seg-btn.icon i {
  font-size: 13px;
}
.seg-btn.on {
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(16, 18, 28, 0.1);
}
.creator-filter {
  position: relative;
  margin-left: 8px;
}
.creator-filter-btn {
  height: 32px;
  padding: 0 9px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.creator-filter-btn:hover:not(:disabled),
.creator-filter-btn.on {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.creator-filter-btn:disabled { cursor: not-allowed; opacity: 0.55; }
.creator-filter-menu {
  position: absolute;
  z-index: 5;
  top: calc(100% + 5px);
  left: 0;
  min-width: 132px;
  padding: 4px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  box-shadow: var(--wao-shadow-modal);
}
.creator-filter-menu button {
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: 6px;
  background: none;
  color: var(--wao-text-2);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}
.creator-filter-menu button:hover,
.creator-filter-menu button.on { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.task-list-card,
.doc-list-card,
.file-list-card {
  margin-top: 10px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 6px 8px;
}
.trow {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
}
.trow:hover {
  background: var(--wao-bg-hover);
}
.trow.dragging {
  opacity: 0.5;
}
/* 刚勾完成、还在 3 秒缓冲里的任务：淡出但不移位，给操作一个反应时间 */
.trow.settling {
  opacity: 0.55;
  transition: opacity 0.4s ease;
}
.trow.done {
  cursor: pointer;
}
.done-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  background: none;
  border-top: 1px solid var(--wao-divider);
  margin-top: 6px;
  padding: 10px;
  font-size: 12.5px;
  color: var(--wao-text-weak);
  cursor: pointer;
  text-align: left;
}
.done-toggle:hover {
  color: var(--wao-text-2);
}
.done-toggle.fixed,
.done-toggle.fixed:hover {
  color: var(--wao-text-weak);
  cursor: default;
}
.done-toggle > i {
  font-size: 15px;
}
.done-count {
  background: var(--wao-divider);
  border-radius: 999px;
  padding: 1px 8px;
  font-size: 11.5px;
}
.done-hint {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
.drag-grip.placeholder {
  visibility: hidden;
}
.drag-grip {
  font-size: 15px;
  color: var(--wao-text-faint-2);
  cursor: grab;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.12s;
}
.trow:hover .drag-grip {
  opacity: 1;
}
.trow-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-status {
  position: relative;
  flex-shrink: 0;
}
.task-status-trigger {
  border: 1px solid transparent;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.task-status-trigger:hover,
.task-status-trigger[aria-expanded='true'] {
  border-color: currentColor;
}
.task-status-trigger.saving {
  cursor: wait;
  opacity: 0.72;
}
.task-status-trigger.error {
  box-shadow: 0 0 0 1px #bc4141;
}
.task-status-menu {
  position: absolute;
  z-index: 8;
  top: calc(100% + 5px);
  right: 0;
  min-width: 132px;
  padding: 4px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
}
.task-status-menu button {
  width: 100%;
  min-height: 32px;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  background: none;
  color: var(--wao-text-2);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  text-align: left;
}
.task-status-menu button:hover,
.task-status-menu button.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.task-status-menu button > i { margin-left: auto; }
.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.trow-due {
  font-size: 12px;
  width: 62px;
  text-align: right;
  flex-shrink: 0;
}
.owner-av {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  color: #fff;
  font-size: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.owner-av.small {
  width: 19px;
  height: 19px;
  font-size: 9.5px;
}
.empty-inline {
  padding: 34px;
  text-align: center;
  color: var(--wao-text-weak);
  font-size: 13px;
}

.doc-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border-radius: 8px;
  cursor: pointer;
}
.doc-row:hover {
  background: var(--wao-bg-hover);
}
.doc-row > i:first-child {
  font-size: 15px;
  color: var(--wao-text-weak);
}
.doc-title {
  flex: 1;
  font-size: 13px;
  color: var(--wao-text);
}
.doc-time {
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.doc-row .chev {
  font-size: 14px;
  color: var(--wao-text-faint-2);
}

.no-kb {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.doc-empty {
  min-height: 108px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 7px;
  color: var(--wao-text-weak);
  font-size: 12.5px;
  text-align: center;
}
.doc-empty i {
  font-size: 22px;
  color: var(--wao-text-faint);
}

/* 分组视图：纵向堆叠的卡片，宽屏两列、手机一列都成立 */
.groups {
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
  align-items: start;
}
.group-card {
  align-self: start;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 8px 10px 10px;
  transition: box-shadow 0.12s, border-color 0.12s;
}
.group-card.drop-hint {
  border-color: var(--wao-primary-line);
  box-shadow: inset 0 0 0 1px var(--wao-primary-line);
}
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 2px 8px;
  border-bottom: 1px solid var(--wao-divider);
}
.group-collapse {
  flex: 0 0 28px;
  width: 28px;
  height: 30px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--wao-text-2);
  cursor: pointer;
}
.group-name {
  border: none;
  background: none;
  padding: 2px 4px;
  margin-left: -4px;
  border-radius: 6px;
  font-size: 13.5px;
  font-weight: 650;
  color: var(--wao-text);
  cursor: text;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 60%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-name i {
  font-size: 12px;
  color: var(--wao-text-faint-2);
  opacity: 0;
}
.group-name:hover {
  background: var(--wao-bg-hover);
}
.group-name:hover i {
  opacity: 1;
}
.group-name-input {
  flex: 1;
  min-width: 0;
  height: 28px;
  border: 1px solid var(--wao-primary);
  border-radius: 7px;
  padding: 0 8px;
  font-size: 13.5px;
  font-weight: 650;
  outline: none;
}
.group-count {
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.group-op {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 7px;
  background: none;
  color: var(--wao-text-3);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.group-op i {
  font-size: 16px;
}
.group-op:hover {
  background: var(--wao-primary);
  color: #fff;
}
.group-op.danger:hover {
  background: var(--wao-danger);
}
.group-add-wrap { margin-top: 8px; }
.group-add-row {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 8px;
  height: 38px;
  border: 1px solid var(--wao-primary);
  border-radius: 9px;
  background: var(--wao-primary-soft);
}
.group-add-row.error { border-color: var(--wao-danger); background: var(--wao-danger-bg); }
.group-add-row > i {
  font-size: 15px;
  color: var(--wao-primary-text);
}
.group-add-row input {
  flex: 1;
  min-width: 0;
  border: none;
  background: none;
  font-size: 13px;
  outline: none;
  color: var(--wao-text);
}
.group-add-done {
  border: none;
  background: none;
  color: var(--wao-primary-text);
  font-size: 12px;
  cursor: pointer;
  padding: 4px 6px;
}
.group-add-done:disabled { cursor: wait; opacity: .6; }
.group-add-error {
  display: block;
  margin: 5px 8px 0;
  color: var(--wao-danger);
  font-size: 11.5px;
}
.grow {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border-radius: 8px;
  cursor: pointer;
  min-height: 38px;
}
.grow:hover {
  background: var(--wao-bg-hover);
}
.grow.dragging {
  opacity: 0.5;
}
.grow-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--wao-text);
  word-break: break-word;
}
.grow.done .grow-title {
  color: var(--wao-text-faint);
  text-decoration: line-through;
}
.grow-chev {
  font-size: 15px;
  color: var(--wao-text-faint-2);
  flex-shrink: 0;
}
.group-done-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  background: none;
  border-top: 1px solid var(--wao-divider);
  margin-top: 6px;
  padding: 9px 8px;
  font-size: 12px;
  color: var(--wao-text-weak);
  cursor: pointer;
  text-align: left;
}
.group-done-toggle > i {
  font-size: 15px;
}
.group-empty {
  padding: 18px 8px;
  text-align: center;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.group-add {
  height: 44px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 12px;
  background: none;
  color: var(--wao-text-3);
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
}
.group-add:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
  background: var(--wao-primary-tint);
}
.group-add em {
  font-style: normal;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
.group-full {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}

/* board 复用 */
.board {
  margin-top: 10px;
  display: flex;
  gap: 12px;
  overflow-x: auto;
  align-items: flex-start;
  padding-bottom: 8px;
}
.board-col {
  /* 六个状态列平分宽度，宽屏一屏放下；窄到放不下（min-width 兜底）再横向滚动 */
  flex: 1 1 0;
  min-width: 172px;
  max-width: 262px;
  background: var(--wao-bg-hover-2);
  border-radius: 12px;
  padding: 10px;
  transition: background 0.12s, box-shadow 0.12s;
}
.board-col.drop-hint {
  background: var(--wao-primary-soft);
  box-shadow: inset 0 0 0 1.5px var(--wao-primary-line);
}
.board-empty {
  border: 1px dashed #cfd3de;
  border-radius: 9px;
  padding: 14px 8px;
  text-align: center;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.board-col-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 2px 4px 8px;
}
.board-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.board-col-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wao-text-2);
}
.board-col-count {
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.board-col-add {
  width: 22px;
  height: 22px;
  margin-left: auto;
  border: none;
  border-radius: 6px;
  background: none;
  color: var(--wao-text-3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.board-col-add:hover {
  background: var(--wao-primary);
  color: #fff;
}
.board-col-add i {
  font-size: 15px;
}
.board-col-head.clickable {
  cursor: pointer;
  border-radius: 7px;
}
.board-col-head.clickable:hover {
  background: var(--wao-bg-hover);
}
.board-col-chev {
  margin-left: auto;
  font-size: 15px;
  color: var(--wao-text-faint-2);
}
.board-collapsed {
  border: 1px dashed var(--wao-line-strong);
  border-radius: 10px;
  padding: 14px 10px;
  text-align: center;
  font-size: 12px;
  color: var(--wao-text-faint);
}
.board-cards {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 8px;
}
.board-card {
  position: relative;
  box-sizing: border-box;
  min-width: 0;
  max-width: 100%;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  padding: 11px 12px;
  cursor: grab;
}
.board-card.dragging {
  opacity: 0.5;
}
.board-card:hover {
  border-color: #c9cdda;
  box-shadow: 0 2px 8px rgba(16, 18, 28, 0.06);
}
.bc-title {
  min-width: 0;
  font-size: 13px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.bc-foot {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 7px;
}
.bc-due {
  font-size: 11.5px;
}

.feed-card {
  margin-top: 16px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 14px 18px;
}
.feed-empty {
  padding: 28px 0;
  text-align: center;
  font-size: 12.5px;
  color: var(--wao-text-weak);
}

@media (max-width: 760px) {
  .detail { width: 100%; box-sizing: border-box; overflow-x: hidden; padding: 10px 12px 28px; }
  .back { min-height: 36px; margin-left: -4px; padding: 5px 4px; font-size: 12px; }

  /* 项目头压成一行主信息 + 一行日期，避免桌面布局在窄屏上散成四五行。 */
  .phead {
    margin-top: 2px; display: grid; grid-template-columns: 38px minmax(0, 1fr) auto auto;
    align-items: center; gap: 7px;
  }
  .phead-icon { grid-column: 1; width: 36px; height: 36px; border-radius: 10px; font-size: 17px; }
  .phead-name-wrap { grid-column: 2; }
  .phead-name-row { flex-wrap: nowrap; gap: 6px; }
  .phead-name { min-width: 0; overflow: hidden; font-size: 17px; line-height: 1.25; text-overflow: ellipsis; white-space: nowrap; }
  .phead-sub { margin-top: 2px; overflow: hidden; font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
  .phead > .spacer { display: none; }
  .phead-avs { grid-column: 3; margin: 0; padding-left: 5px; }
  .phead-av { width: 24px; height: 24px; border-color: var(--wao-bg-page); }
  .phead .btn-icon { grid-column: 4; width: 38px; height: 38px; }
  .phead > .btn-primary { display: none; }

  .ptabs { margin: 13px -12px 0; padding: 0 8px; overflow-x: auto; scrollbar-width: none; }
  .ptabs::-webkit-scrollbar { display: none; }
  .ptab { min-width: max-content; height: 42px; padding: 0 12px; }

  .tab-toolbar { margin-top: 10px; flex-wrap: wrap; align-items: center; gap: 8px; }
  .tab-toolbar > .seg { order: 1; width: 100%; }
  .seg-btn { flex: 1; justify-content: center; height: 36px; }
  .creator-filter { order: 2; margin-left: 0; }
  .creator-filter-btn, .batch-ai { min-height: 40px; }
  .tab-toolbar > .spacer { order: 3; }
  .batch-ai-actions { order: 5; width: 100%; }
  .batch-ai-actions .batch-ai { flex: 1; justify-content: center; }
  .creator-filter-menu { min-width: 160px; }

  .task-list-card, .doc-list-card, .file-list-card { margin-top: 8px; padding: 4px; border-radius: 11px; }
  .trow { min-height: 58px; align-items: center; flex-wrap: wrap; gap: 6px 8px; padding: 10px 8px; }
  .drag-grip { display: none; }
  .trow .select-dot { order: 1; width: 30px; height: 30px; }
  .trow .check { order: 2; width: 22px; height: 22px; }
  .trow-title { order: 3; flex: 1 1 calc(100% - 68px); min-width: calc(100% - 68px); white-space: normal; overflow: visible; line-height: 1.45; text-overflow: clip; }
  .trow.done .trow-title { flex-basis: calc(100% - 32px); min-width: calc(100% - 32px); }
  .trow .wait-badge { order: 4; margin-left: 60px; }
  .trow .task-status { order: 5; }
  .trow .mini { order: 5; }
  .trow .owner-av { order: 6; }
  .trow-due { order: 7; width: auto; margin-left: auto; }
  .done-toggle { min-height: 44px; }

  /* 看板改成纵向状态分区：手机不再露半张下一列，也不用横拖。 */
  .board { display: grid; grid-template-columns: 1fr; gap: 10px; overflow: visible; padding-bottom: 0; }
  .board-col { width: auto; min-width: 0; max-width: none; padding: 9px; }
  .board-col-head { min-height: 34px; padding-bottom: 7px; }
  .board-col-add { width: 32px; height: 32px; }
  .board-card { min-height: 62px; padding: 11px 12px; cursor: pointer; }
  .bc-title { padding-right: 28px; font-size: 13.5px; overflow-wrap: anywhere; }

  /* 分组 / 内容也只走单列，触控按钮统一放大。 */
  .groups { grid-template-columns: minmax(0, 1fr); }
  .group-card { min-width: 0; }
  .grow { min-height: 46px; }
  .group-op { width: 36px; height: 36px; }
  .group-add-row { min-height: 44px; height: auto; }
  .doc-row { min-height: 48px; padding: 9px 7px; }
  .doc-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .doc-time { flex-shrink: 0; max-width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
/* 任务优先：项目标题、页签与视图、交付筛选合为三条紧凑工具栏。 */
.detail { padding-top: 12px; }
/* 头部压紧：项目名一行 + 页签/工具一行，把高度让给任务表格 */
.phead { margin-top: 0; gap: 8px; }
.phead .back { padding: 2px 4px; }
.phead-icon { width: 24px; height: 24px; font-size: 13px; border-radius: 6px; }
.phead-name { font-size: 15px; overflow-wrap: anywhere; }
.phead-name-wrap { flex: 0 1 auto; }
.phead-name-row { gap: 7px; }
.phead-av { width: 22px; height: 22px; font-size: 10px; }
.phead .btn-icon { width: 28px; height: 28px; }
.phead .btn-primary.sm { height: 28px; padding: 0 11px; }
.ptabs { margin-top: 4px; align-items: center; gap: 6px; flex-wrap: wrap; overflow: visible; padding-bottom: 4px; }
.ptab { height: 30px; padding: 4px 8px; }
.task-view-toolbar { margin: 0 0 0 auto; flex-wrap: wrap; gap: 6px; }
.task-view-toolbar .creator-filter { margin-left: 0; }
.task-view-toolbar .seg { padding: 2px; border-radius: 7px; }
.task-view-toolbar .seg-btn { height: 24px; padding: 0 9px; font-size: 12px; }
.task-view-toolbar .creator-filter-btn { height: 28px; font-size: 12px; }
.task-view-toolbar .table-toolbar-btn { height: 28px; }
.copy-selection-actions { align-items: center; margin: 8px 0; padding: 8px; border-radius: 8px; background: var(--wao-primary-soft); }
.copy-selection-actions .selection-count { font-size: 12px; color: var(--wao-primary-text); }
.copy-selection-actions :deep(.task-batch-copy) { display: contents; }
.copy-selection-actions :deep(.copy-preview) { flex-basis: 100%; }
.detail:not(.selecting-tasks) .select-dot { display: none; }
.project-more { position: relative; }
.project-more-menu { position: absolute; z-index: 20; top: calc(100% + 6px); right: 0; min-width: 168px; padding: 5px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-modal); }
.project-more-menu button { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px; border: 0; background: none; color: var(--wao-text-2); text-align: left; font-size: 12px; cursor: pointer; }
.project-more-menu button:hover { background: var(--wao-primary-soft); }
.project-info { margin-top: 9px; padding: 9px 12px; background: var(--wao-bg-card); border: 1px solid var(--wao-line); border-radius: 7px; color: var(--wao-text-weak); font-size: 12px; }
.board { margin-top: 8px; }
.board-col { max-width: 440px; }
.board-col.is-collapsed { flex: 0 0 148px; min-width: 148px; }
.bc-title { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
.empty-columns-control { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; color: var(--wao-text-weak); font-size: 11px; margin-top: 8px; }
.empty-columns-control button { padding: 3px; border: 0; background: none; color: var(--wao-primary-text); cursor: pointer; font: inherit; }
@media (max-width: 760px) {
  .phead-name-wrap { flex: 1; }
  .phead .phead-avs { display: none; }
  .phead-name { font-size: 16px; }
  .phead { gap: 6px; }
  .ptabs { margin: 9px 0 0; padding: 0 0 6px; }
  .task-view-toolbar { margin-left: 0; flex-basis: 100%; }
  .task-view-toolbar .seg-btn { padding: 0 9px; }
  .task-view-toolbar .creator-filter { margin-left: auto; }
  .board-col.is-collapsed { min-width: 0; }
  .project-more-menu { right: 0; }
}
.task-list-card.task-table-scroll {
  padding: 0;
  border-radius: 6px;
  overflow: auto;
  max-height: calc(100dvh - 190px);
  min-height: 180px;
}
.project-task-table {
  border-collapse: separate;
  border-spacing: 0;
  width: 100%;
  min-width: 1100px;
  table-layout: fixed;
  font-size: 12px;
}
.project-task-table th {
  position: sticky;
  top: 0;
  z-index: 3;
  background: var(--wao-bg-sub);
  text-align: left;
  height: 34px;
  font-weight: 500;
  color: var(--wao-text-weak);
}
.project-task-table .table-sort { display: inline-flex; align-items: center; gap: 4px; max-width: 100%; padding: 3px 2px; border: 0; border-radius: 4px; background: transparent; color: inherit; font: inherit; white-space: nowrap; cursor: pointer; }
.project-task-table .table-sort:hover, .project-task-table .table-sort:focus-visible { color: var(--wao-primary-text); background: var(--wao-bg-hover); }
.project-task-table .table-sort i { font-size: 12px; opacity: .55; }
.project-task-table th[aria-sort="ascending"] .table-sort i, .project-task-table th[aria-sort="descending"] .table-sort i { opacity: 1; }
.project-task-table :is(th, td) {
  padding: 5px 9px;
  border-right: 1px solid var(--wao-line);
  border-bottom: 1px solid var(--wao-line);
}
.project-task-table th:last-child,
.project-task-table td:last-child {
  border-right: 0;
}
.project-task-table .settling {
  opacity: 0.55;
  transition: opacity 0.4s ease;
}
.project-task-table tbody.table-group {
  background: var(--wao-bg-sub);
}
.project-task-table .table-group-row td {
  height: 34px;
  padding: 0 6px;
}
.project-task-table .table-group-row.drop-hint td {
  box-shadow: inset 0 0 0 1px var(--wao-primary);
}
.table-group-head {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.table-group-head .group-name {
  font-weight: 600;
  font-size: 13px;
}
.table-group-count {
  font-size: 12px;
  color: var(--wao-text-weak);
  margin-right: 4px;
}
.table-group-head .group-op {
  opacity: 0;
}
.table-group-row:hover .group-op {
  opacity: 1;
}
.project-task-table .table-quick-add td {
  padding: 4px 8px 4px 40px;
}
.table-seg {
  display: inline-flex;
  padding: 2px;
  border-radius: 6px;
  background: var(--wao-bg-sub);
}
.table-seg button {
  height: 24px;
  padding: 0 10px;
  border: none;
  border-radius: 4px;
  background: none;
  color: var(--wao-text-weak);
  font-size: 12px;
  cursor: pointer;
}
.table-seg button.on {
  background: var(--wao-bg-card);
  color: var(--wao-text);
  box-shadow: 0 1px 2px rgba(16, 18, 28, 0.08);
}
.table-toolbar-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 26px;
  padding: 0 9px;
  border: 1px solid var(--wao-line-control);
  border-radius: 6px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font-size: 12px;
  cursor: pointer;
}
.done-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: none;
  color: var(--wao-text-weak);
  font-size: 12px;
  cursor: pointer;
}
.done-switch:disabled {
  opacity: 0.6;
  cursor: default;
}
.done-switch-count {
  font-style: normal;
  padding: 0 6px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  font-size: 11px;
}
.done-switch .track {
  position: relative;
  width: 26px;
  height: 14px;
  border-radius: 7px;
  background: var(--wao-line-control);
  transition: background 0.15s;
}
.done-switch .thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
  transition: transform 0.15s;
}
.done-switch[aria-checked='true'] .track {
  background: var(--wao-primary);
}
.done-switch[aria-checked='true'] .thumb {
  transform: translateX(12px);
}
.project-task-table .done-toggle {
  padding: 3px 0;
  margin: 0;
  border: 0;
}
.project-task-table .table-section {
  background: var(--wao-bg-sub);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
.table-title-heading { display: flex; align-items: center; justify-content: space-between; }
</style>
