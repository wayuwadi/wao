<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useDeco } from '@/composables/deco'
import { statusMeta, priorityMeta, TASK_STATUSES } from '@/utils/domain'
import { fmtDate } from '@/utils/domain'
import { toast } from '@/composables/toast'
// 异步加载：编辑器带着 ProseMirror（gzip 约 110KB），只有真正打开任务详情时才需要，
// 不该压在首屏包里。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
import SaveHint from '@/components/ui/SaveHint.vue'
import { shareMarkdownUrl, shareUrl } from '@/utils/share'
import { copyToClipboard } from '@/utils/clipboard'
import { continueAiRun, createAiRuns, fetchTaskAiRuns, pauseAiRun } from '@/api/ai'
import AiRunProgressTimeline from '@/components/ai/AiRunProgressTimeline.vue'
import AiRunLauncher from '@/components/ai/AiRunLauncher.vue'
import TaskRelationPicker from '@/components/task/TaskRelationPicker.vue'
import { formatCost, formatTokens, shortModel } from '@/composables/aiUsage'
import { engineIcon, engineLabel } from '@/composables/aiEngines'
import { useSettle } from '@/composables/completion'
import { useNow } from '@/composables/now'
import { absoluteTime, nowIso, relativeTime } from '@/utils/time'
import { activitySentence } from '@/utils/activity'
import { useIsMobile } from '@/composables/viewport'
import { useFavoritesStore } from '@/stores/favorites'
import type { AiEngine, AiRun, AiRunStatus, PdcaCycle, PdcaOutcome, Priority, TaskStatus } from '@/types'

const ui = useUiStore()
const data = useDataStore()
const session = useSessionStore()
const favorites = useFavoritesStore()
const router = useRouter()
const { decoTask } = useDeco()

const isMobile = useIsMobile()

const task = computed(() => (ui.openTaskId ? data.taskById(ui.openTaskId) : undefined))
const tv = computed(() => (task.value ? decoTask(task.value) : null))

const menu = ref<'status' | 'pr' | 'owner' | 'prop' | 'share' | 'group' | null>(null)
const newSub = ref('')
const commentText = ref('')
const mentionOpen = ref(false)
const sendingComment = ref(false)
const aiStarting = ref(false)
const aiLauncherOpen = ref(false)
const relationOpen = ref(false)
const attachmentInput = ref<HTMLInputElement | null>(null)
const attachmentUploading = ref(false)
/** 执行方式选择器当前是为哪条子任务打开的；null 表示整个任务。 */
const aiLauncherSubId = ref<string | null>(null)
const aiRuns = ref<AiRun[]>([])
const aiRunsLoading = ref(false)
/** 首次打开可显示加载态；后续轮询不得反复推挤评论区。 */
const aiRunsLoaded = ref(false)
const aiContinuation = ref<Record<string, string>>({})
const continuingRunId = ref<string | null>(null)
const pausingRunId = ref<string | null>(null)
let aiRunsTimer: ReturnType<typeof setInterval> | null = null
/** 右侧栏当前展开的子任务标识；为 null 时显示任务属性栏。 */
const openSubId = ref<string | null>(null)

watch(
  () => ui.openTaskId,
  () => {
    menu.value = null
    newSub.value = ''
    commentText.value = ''
    openSubId.value = null
    aiLauncherOpen.value = false
    aiLauncherSubId.value = null
    relationOpen.value = false
    editingComment.value = null
    confirmingDelete.value = false
    aiRuns.value = []
    aiRunsLoaded.value = false
  },
)

async function refreshTaskAiRuns() {
  const taskId = ui.openTaskId
  if (!taskId) {
    aiRuns.value = []
    return
  }
  aiRunsLoading.value = true
  try {
    const next = await fetchTaskAiRuns(taskId)
    if (ui.openTaskId === taskId) {
      aiRuns.value = next
      data.applyAiRunStatuses(next)
    }
  } catch {
    // 保留上一帧，网络抖动不应让已展示的 AI 档案消失。
  } finally {
    if (ui.openTaskId === taskId) {
      aiRunsLoading.value = false
      // 请求失败同样结束首次加载态；下一轮会重试，但页面布局保持稳定。
      aiRunsLoaded.value = true
    }
  }
}

watch(() => ui.openTaskId, () => void refreshTaskAiRuns(), { immediate: true })

const aiRunLabel: Record<AiRunStatus, string> = {
  queued: '等待领取', claimed: '准备中', running: '正在运行', succeeded: '等待验收',
  failed: '执行失败', cancelled: '已取消',
}
// 退出码 3 是 Runner 的约定：AI 结束了会话但没报告任务状态，成败需要人判断，
// 不能直接挂一个「执行失败」让人分不清到底成没成。
function aiRunStatusUnreported(run: AiRun) {
  return run.status === 'failed' && run.exitCode === 3
}
function aiRunStateLabel(run: AiRun) {
  return aiRunStatusUnreported(run) ? '结果待确认' : aiRunLabel[run.status]
}
function aiRunResultTitle(run: AiRun) {
  if (aiRunStatusUnreported(run)) return '结果待确认（AI 未报告任务状态）'
  return run.status === 'failed' ? '失败原因' : '最终结果'
}
const activeAiRun = computed(() => aiRuns.value.find((run) => ['queued', 'claimed', 'running'].includes(run.status)))
/** 已经单独派过 AI 工的子任务：行上标一下，分享给 AI 时它们也会被自动摘掉。 */
const dispatchedSubIds = computed(
  () => new Set(aiRuns.value.map((run) => run.subId).filter((id): id is string => !!id)),
)

async function continueRun(runId: string) {
  const input = aiContinuation.value[runId]?.trim()
  if (!input) return toast('请先输入要补充给 AI 的信息', 'ri-information-line')
  const optimizing = aiRuns.value.find((run) => run.id === runId)?.status === 'succeeded'
  continuingRunId.value = runId
  try {
    await continueAiRun(runId, input)
    aiContinuation.value[runId] = ''
    toast(optimizing ? '已发送，AI 将带着这次的记忆继续优化' : '补充已发送，AI 将在原会话中继续执行',
      'ri-robot-2-line')
    await refreshTaskAiRuns()
  } catch (error) {
    toast(error instanceof Error ? error.message : '发送补充失败，请重试', 'ri-error-warning-line')
  } finally {
    continuingRunId.value = null
  }
}

async function pauseRun(runId: string) {
  if (pausingRunId.value) return
  pausingRunId.value = runId
  try {
    await pauseAiRun(runId)
    toast('已暂停 AI 任务，执行电脑将停止当前执行', 'ri-pause-circle-line')
    await refreshTaskAiRuns()
  } catch (error) {
    toast(error instanceof Error ? error.message : '暂停失败，请重试', 'ri-error-warning-line')
  } finally {
    pausingRunId.value = null
  }
}

const stepsDone = computed(() => task.value?.steps.filter((s) => s.done).length ?? 0)
const stepsTotal = computed(() => task.value?.steps.length ?? 0)
const stepPct = computed(() => (stepsTotal.value ? Math.round((stepsDone.value / stepsTotal.value) * 100) : 0))

// 时间文案要随时间推移自己变新，否则抽屉一直开着就永远停在「刚刚」
const now = useNow()
const showTime = (t: string) => relativeTime(t, now.value)

const comments = computed(() =>
  (task.value?.comments ?? []).map((c, index) => {
    const m = session.memberById(c.who)
    return {
      index, name: m.name, initial: m.initial, color: m.color,
      time: showTime(c.time), fullTime: absoluteTime(c.time), text: c.text,
      // 只有自己的评论能改能删，服务端同样会拦
      mine: c.who === session.currentUser.id,
    }
  }),
)
const mentionQuery = computed(() => {
  const match = commentText.value.match(/(?:^|\s)@([^\s@]*)$/)
  return match ? (match[1] ?? '').toLowerCase() : null
})
const mentionCandidates = computed(() => mentionQuery.value === null ? [] : session.members
  .filter((m) => m.name.toLowerCase().includes(mentionQuery.value!)))
function insertMention(name: string) {
  commentText.value = commentText.value.replace(/(^|\s)@[^\s@]*$/, `$1@${name} `)
  mentionOpen.value = false
}

// ---- 评论的修改 / 删除 ----
/** 正在编辑的评论下标；null 表示没有在编辑。 */
const editingComment = ref<number | null>(null)
const editingText = ref('')

function startEditComment(index: number, text: string) {
  editingComment.value = index
  editingText.value = text
}
function cancelEditComment() {
  editingComment.value = null
  editingText.value = ''
}
async function saveComment() {
  const t = task.value
  const index = editingComment.value
  if (!t || index === null) return
  const ok = await data.updateTaskComment(t.id, index, editingText.value)
  cancelEditComment()
  toast(ok ? '评论已更新' : '评论修改失败，请重试', ok ? 'ri-check-line' : 'ri-error-warning-line')
}
async function removeComment(index: number) {
  const t = task.value
  if (!t) return
  if (editingComment.value === index) cancelEditComment()
  const ok = await data.deleteTaskComment(t.id, index)
  toast(ok ? '评论已删除' : '评论删除失败，请重试', ok ? 'ri-delete-bin-line' : 'ri-error-warning-line')
}

// ---- 删除任务 ----
/** 二次确认状态：删任务不可撤销，点一次先变成「确认删除」。 */
const confirmingDelete = ref(false)
async function removeTask() {
  const t = task.value
  if (!t) return
  const title = t.title
  confirmingDelete.value = false
  ui.closeTask()
  const ok = await data.deleteTask(t.id)
  toast(ok ? `已删除任务「${title}」` : '任务删除失败，请重试', ok ? 'ri-delete-bin-line' : 'ri-error-warning-line')
}
/**
 * 活动时间线：时间转成相对描述，主语按「看的人是谁」现算。
 *
 * <p>正文里不再写死「你」——娃哥打开娃弟建的任务时，那条记录说的是娃弟干的事。
 */
const activities = computed(() =>
  (task.value?.activity ?? []).map((a) => ({
    ...a,
    text: activitySentence(a, session.currentUser.id, (id) => session.memberById(id).name),
    time: showTime(a.time),
    fullTime: absoluteTime(a.time),
  })),
)
const parts = computed(() =>
  (task.value?.parts ?? []).map((id) => {
    const m = session.memberById(id)
    return { initial: m.initial, color: m.color, name: m.name }
  }),
)
const linkTasks = computed(() =>
  (task.value?.linkTasks ?? []).map((l) => {
    const lt = data.taskById(l.id)
    const st = statusMeta(lt?.status ?? '待办')
    return { id: l.id, title: lt?.title ?? '', rel: l.rel, status: lt?.status ?? '', stBg: st.bg, stColor: st.color }
  }),
)
const linkedDocs = computed(() =>
  (task.value?.docs ?? []).map((id) => ({ id, title: data.docById(id)?.title ?? '' })),
)
const relationDocs = computed(() => Object.values(data.docs).map((doc) => {
  const kb = data.kbs.find((item) => item.id === doc.kbId || item.folders.some((folder) => folder.docs.includes(doc.id)))
  return { id: doc.id, title: doc.title, kbName: kb?.name ?? '知识文档' }
}))
const relationTasks = computed(() => data.tasks
  .filter((item) => item.id !== task.value?.id)
  .map((item) => ({
    id: item.id,
    title: item.title,
    projectName: data.projectById(item.project)?.name ?? '未关联项目',
  })))
// 没有稳定 id 的历史展示附件并不存在可下载正文，不能把它伪装成可关联文件。
const relationFiles = computed(() => data.files
  .filter((file) => typeof file.id === 'string' && file.id.length > 0)
  .map((file) => ({ id: file.id!, name: file.name, size: file.size, icon: file.icon })))
const selectedFileIds = computed(() => (task.value?.files ?? [])
  .map((file) => file.id)
  .filter((id): id is string => !!id))
const saveState = computed(() => (task.value ? data.taskSaveState(task.value.id) : 'idle'))
/** 上一次写透成功的时刻，给「自动保存成功 14:32」用。 */
const savedAt = computed(() => (task.value ? data.taskSavedAt(task.value.id) : null))

const STATUS_OPTS: TaskStatus[] = TASK_STATUSES
const PR_OPTS: { key: Priority; sub: string }[] = [
  { key: 'P0', sub: '当天必须处理' },
  { key: 'P1', sub: '影响里程碑' },
  { key: 'P2', sub: '常规' },
  { key: 'P3', sub: '有空再说' },
]
const PROP_TYPES = ['文本', '数字', '单选', '链接']

function close() {
  if (task.value) data.flushTask(task.value.id)
  ui.closeTask()
}
/** Esc 逐层关闭：先关下拉菜单，再收子任务详情，最后关抽屉。 */
function onKey(e: KeyboardEvent) {
  if (e.key !== 'Escape' || !task.value) return
  if (relationOpen.value) relationOpen.value = false
  else if (menu.value) menu.value = null
  else if (openSubId.value) openSubId.value = null
  else close()
}
onMounted(() => {
  window.addEventListener('keydown', onKey)
  aiRunsTimer = setInterval(() => {
    // 抽屉打开期间始终轮询：运行可能从另一个页面 / 另一台电脑发起，不能因为首帧没有活动运行就停掉。
    if (ui.openTaskId) void refreshTaskAiRuns()
  }, 5_000)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  if (aiRunsTimer) clearInterval(aiRunsTimer)
})
function editTitle(e: Event) {
  if (task.value) data.patchTask(task.value.id, { title: (e.target as HTMLTextAreaElement).value })
  growTaskTitle()
}
const taskTitleArea = ref<HTMLTextAreaElement | null>(null)
function growTaskTitle() {
  const el = taskTitleArea.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = el.scrollHeight + 'px'
}
watch([() => ui.openTaskId, () => task.value?.title], () => nextTick(growTaskTitle), { immediate: true })
/** 任务描述（Markdown）。写回走 patchTask，编辑器只管文本。 */
const desc = computed({
  get: () => task.value?.desc ?? '',
  set: (v: string) => {
    if (task.value) data.patchTask(task.value.id, { desc: v })
  },
})

// ---- PDCA 闭环 ----
const pdcaCycles = computed(() => task.value?.pdcaCycles ?? [])
const currentPdca = computed(() => pdcaCycles.value[pdcaCycles.value.length - 1])
const previousPdca = computed(() => pdcaCycles.value.slice(0, -1).reverse())
const pdcaPhase = computed(() => {
  if (currentPdca.value?.reviewedAt) return 'A · 改进'
  if (task.value?.status === '待验证' || task.value?.status === '已完成') return 'C · 检查'
  if (task.value?.status === '进行中' || task.value?.status === 'AI 进行中') return 'D · 执行'
  return 'P · 计划'
})
const pdcaDoSummary = computed(() => {
  const t = task.value
  if (!t) return ''
  const all = [...t.steps, ...t.subs]
  const done = all.filter((item) => item.done).length
  return all.length ? `${t.status} · 已完成 ${done}/${all.length} 个步骤` : `${t.status} · 暂无拆分步骤`
})
const pdcaInCheck = computed(() => task.value?.status === '待验证' || task.value?.status === '已完成')
const canReviewPdca = computed(() => {
  const cycle = currentPdca.value
  return pdcaInCheck.value && !!cycle && !cycle.reviewedAt && !!cycle.plan.trim() && !!cycle.outcome
    && !!cycle.check.trim() && !!cycle.act.trim()
})

function enablePdca() {
  const t = task.value
  if (!t || currentPdca.value) return
  const cycle: PdcaCycle = {
    id: `pc${Date.now()}`,
    plan: '', check: '', act: '', outcome: null,
    startedAt: nowIso(), reviewedAt: null,
  }
  data.patchTask(t.id, { pdcaCycles: [cycle] }, '启用了 PDCA 跟踪')
}
function updatePdca(field: 'plan' | 'check' | 'act', event: Event) {
  const t = task.value
  const cycle = currentPdca.value
  if (!t || !cycle || cycle.reviewedAt) return
  const value = (event.target as HTMLTextAreaElement).value
  data.patchTask(t.id, {
    pdcaCycles: pdcaCycles.value.map((item) => item.id === cycle.id ? { ...item, [field]: value } : item),
  })
}
function setPdcaOutcome(outcome: PdcaOutcome) {
  const t = task.value
  const cycle = currentPdca.value
  if (!t || !cycle || cycle.reviewedAt) return
  data.patchTask(t.id, {
    pdcaCycles: pdcaCycles.value.map((item) => item.id === cycle.id ? { ...item, outcome } : item),
  })
}
function completePdcaReview() {
  const t = task.value
  const cycle = currentPdca.value
  if (!t || !cycle || !canReviewPdca.value) return
  data.patchTask(t.id, {
    status: '已完成',
    pdcaCycles: pdcaCycles.value.map((item) => item.id === cycle.id
      ? { ...item, reviewedAt: nowIso() }
      : item),
  }, `完成了第 ${pdcaCycles.value.length} 轮 PDCA 复盘`)
  toast('本轮 PDCA 已闭环，改进行动可直接带入下一轮', 'ri-checkbox-circle-fill')
  ui.celebrate()
}
function startNextPdcaCycle() {
  const t = task.value
  const cycle = currentPdca.value
  if (!t || !cycle?.reviewedAt) return
  const next: PdcaCycle = {
    id: `pc${Date.now()}`,
    plan: cycle.act.trim(), check: '', act: '', outcome: null,
    startedAt: nowIso(), reviewedAt: null,
  }
  data.patchTask(t.id, { status: '待办', pdcaCycles: [...pdcaCycles.value, next] },
    `根据改进行动开启了第 ${pdcaCycles.value.length + 1} 轮 PDCA`)
  toast('已把改进行动带入新一轮计划', 'ri-loop-right-line')
}

/**
 * 编辑器里粘贴 / 插入图片：存进图床，再把地址回传给编辑器内联到光标处。
 *
 * <p>图片不再进 `task.images` 附件区——它属于描述正文的一部分，位置由用户
 * 敲下 Ctrl+V 的地方决定。
 */
async function uploadInline(file: File, done: (url: string | null, name: string) => void, source: string) {
  if (file.size > data.MAX_IMAGE_BYTES) {
    toast('图片超过 10MB，已跳过', 'ri-error-warning-line')
    done(null, '')
    return
  }
  const ref = await data.uploadImage(file, source)
  if (!ref) toast('图片上传失败，请重试', 'ri-error-warning-line')
  done(ref?.url ?? null, ref?.name ?? file.name)
}
const onTaskImage = (file: File, done: (url: string | null, name: string) => void) =>
  uploadInline(file, done, `任务 · ${task.value?.title ?? ''}`)
const onContinueImage = (file: File, done: (url: string | null, name: string) => void) =>
  uploadInline(file, done, `AI 继续优化 · ${task.value?.title ?? ''}`)

function removeImage(idx: number) {
  const t = task.value
  if (!t?.images) return
  data.patchTask(t.id, { images: t.images.filter((_, i) => i !== idx) })
}
function viewImage(url: string) {
  window.open(url, '_blank')
}
function toggleStep(idx: number, kind: 'steps' | 'subs') {
  if (task.value) data.toggleStep(task.value.id, idx, kind)
}
function addSub() {
  const t = task.value
  if (!t) return
  const id = data.addSub(t.id, newSub.value)
  if (id) {
    newSub.value = ''
    // 新建即展开，方便顺手补描述
    openSubId.value = id
  }
}

// ---- 子任务详情（右侧栏切换成子任务视图） ----

const subKeyOf = (idx: number) => (task.value ? data.subKey(task.value.id, task.value.subs[idx]!, idx) : '')

// 已完成的子任务沉到底部并默认收起；刚勾完成的先原地停留 3 秒再沉下去。
const showDoneSubs = ref(false)
const { begin: beginSettle, cancel: cancelSettle, isSettling } = useSettle()

/** 带上原始下标的子任务视图 —— 过滤后仍要能定位回 task.subs 里的那一条。 */
const subRows = computed(() =>
  (task.value?.subs ?? []).map((sub, idx) => ({ sub, idx, key: subKeyOf(idx) })),
)
const openSubRows = computed(() => subRows.value.filter((r) => !r.sub.done || isSettling(r.key)))
const doneSubRows = computed(() => subRows.value.filter((r) => r.sub.done && !isSettling(r.key)))
const draggingSubId = ref<string | null>(null)
const dragOverSubId = ref<string | null>(null)

function startSubDrag(subId: string, event: DragEvent) {
  draggingSubId.value = subId
  event.dataTransfer?.setData('text/plain', subId)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function overSub(subId: string) {
  if (draggingSubId.value && draggingSubId.value !== subId) dragOverSubId.value = subId
}
function dropSub(overId: string) {
  const t = task.value
  const from = draggingSubId.value
  draggingSubId.value = null
  dragOverSubId.value = null
  if (t && from) data.reorderSub(t.id, from, overId)
}
function endSubDrag() {
  draggingSubId.value = null
  dragOverSubId.value = null
}

/** 勾选子任务：完成时进入缓冲，取消完成时立即撤销缓冲。 */
function toggleSub(idx: number) {
  const t = task.value
  if (!t) return
  const key = subKeyOf(idx)
  const wasDone = t.subs[idx]?.done === true
  data.toggleStep(t.id, idx, 'subs')
  if (wasDone) cancelSettle(key)
  else beginSettle(key)
}
const openSub = computed(() =>
  task.value && openSubId.value ? data.subById(task.value.id, openSubId.value) : undefined,
)
const openSubIndex = computed(() =>
  task.value && openSubId.value
    ? task.value.subs.findIndex((s, i) => data.subKey(task.value!.id, s, i) === openSubId.value)
    : -1,
)

function showSub(idx: number) {
  const key = subKeyOf(idx)
  openSubId.value = openSubId.value === key ? null : key
  menu.value = null
}
/**
 * 子任务标题用自动撑高的 textarea：标题写长了要能整段看到，
 * 原来的单行 input 会把后半截截掉。
 */
const subTitleArea = ref<HTMLTextAreaElement | null>(null)
function growSubTitle() {
  const el = subTitleArea.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = el.scrollHeight + 'px'
}
function editSubTitle(e: Event) {
  const t = task.value
  const v = (e.target as HTMLTextAreaElement).value
  if (t && openSubId.value) data.updateSub(t.id, openSubId.value, { text: v })
  growSubTitle()
}
// 换一个子任务 / 标题被别处改动时，重新按内容量一次高度
watch([openSubId, () => openSub.value?.text], () => nextTick(growSubTitle))
/** 子任务描述（Markdown）。 */
const subDesc = computed({
  get: () => openSub.value?.desc ?? '',
  set: (v: string) => {
    const t = task.value
    if (t && openSubId.value) data.updateSub(t.id, openSubId.value, { desc: v })
  },
})
function toggleSubDone() {
  const t = task.value
  const s = openSub.value
  if (t && s && openSubId.value) data.updateSub(t.id, openSubId.value, { done: !s.done })
}
const onSubImage = (file: File, done: (url: string | null, name: string) => void) =>
  uploadInline(file, done, `子任务 · ${openSub.value?.text ?? ''}`)

function removeSubImage(idx: number) {
  const t = task.value
  if (t && openSubId.value) data.removeSubImage(t.id, openSubId.value, idx)
}
function deleteSub() {
  const t = task.value
  if (!t || !openSubId.value) return
  data.removeSub(t.id, openSubId.value)
  openSubId.value = null
  toast('子任务已删除', 'ri-delete-bin-line')
}

// ---- 分享链接 ----

/** 分享时是否过滤掉已完成的子任务（只对主任务链接有意义）。 */
const shareOpenOnly = ref(false)
const shareDoneCount = computed(() => (task.value?.subs ?? []).filter((s) => s.done).length)

/** 复制当前上下文（任务或已展开的子任务）的分享链接。 */
async function copyShare(kind: 'page' | 'md') {
  const t = task.value
  if (!t) return
  const sub = openSubId.value
  const only = !sub && shareOpenOnly.value
  const url = kind === 'md' ? shareMarkdownUrl(t.id, sub, only) : shareUrl(t.id, sub, only)
  const what = sub ? '子任务' : '任务'
  const suffix = sub ? '（只含该子任务）' : only ? '（只含未完成的子任务）' : '（含全部子任务）'
  menu.value = null
  toast(
    (await copyToClipboard(url))
      ? `${what}${kind === 'md' ? ' Markdown 地址' : '链接'}已复制${suffix}`
      : '复制失败，请检查浏览器剪贴板权限',
    'ri-links-line',
  )
}
async function runWithAi(engine: AiEngine, runnerId: string | null, accountId: string | null) {
  const t = task.value
  if (!t || aiStarting.value) return
  // 子任务派工是独立的一条运行，任务整体正在跑也不该拦着
  const subId = aiLauncherSubId.value
  if (!subId && t.status === 'AI 进行中') return
  aiStarting.value = true
  try {
    const created = await createAiRuns([t.id], { subId, engine, runnerId, accountId })
    if (created.length) {
      const what = subId ? `子任务「${created[0]!.subTitle ?? ''}」` : '任务'
      data.patchTask(t.id, { status: 'AI 进行中' }, `已把${what}交给 ${engineLabel(engine)}`)
      toast(`已把${what}交给 ${engineLabel(engine)}，可在首页 AI 控制台查看运行状态`, 'ri-robot-2-line')
      aiLauncherOpen.value = false
      aiLauncherSubId.value = null
      void refreshTaskAiRuns()
    } else {
      toast(subId ? '这条子任务已经在 AI Run 队列中' : '这个任务已经在 AI Run 队列中', 'ri-information-line')
    }
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI Run 启动失败', 'ri-error-warning-line')
  } finally {
    aiStarting.value = false
  }
}

/** 给某条子任务单独派工：打开同一个执行方式选择器，只是这次带上 subId。 */
function runSubWithAi(key: string) {
  aiLauncherSubId.value = key
  aiLauncherOpen.value = true
}

/** 选择器上要写清楚这次到底跑什么，否则从子任务点开会以为要跑整个任务。 */
const aiLauncherScope = computed(() => {
  const key = aiLauncherSubId.value
  if (!key) return '整个任务'
  const row = subRows.value.find((r) => r.key === key)
  return `子任务「${row?.sub.text ?? ''}」`
})
async function sendComment() {
  if (sendingComment.value) return
  const t = task.value
  const v = commentText.value.trim()
  if (!t || !v) return
  sendingComment.value = true
  try {
    if (await data.addTaskComment(t.id, v)) commentText.value = ''
    else toast('评论发送失败，请重试', 'ri-error-warning-line')
  } finally {
    sendingComment.value = false
  }
}
function commentKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendComment()
  }
}
function setStatus(s: TaskStatus) {
  const t = task.value
  if (!t) return
  const justFinished = s === '已完成' && t.status !== '已完成'
  data.patchTask(t.id, { status: s }, `将状态改为「${s}」`)
  menu.value = null
  if (justFinished) {
    toast(`已完成「${t.title}」`, 'ri-checkbox-circle-fill')
    // 完成和创建一样值得庆祝一下
    ui.celebrate()
  }
}
function setPriority(p: Priority) {
  const t = task.value
  if (!t) return
  data.patchTask(t.id, { pr: p }, `将优先级改为 ${p}`)
  menu.value = null
}
function setOwner(id: string) {
  const t = task.value
  if (!t) return
  const m = session.memberById(id)
  data.patchTask(t.id, { owner: id }, `将负责人改为 ${m.name}`)
  menu.value = null
}

// ---- 任务分组 ----
// 手机上没法拖拽，分组只能在这里改；桌面上从属性栏改也比拖回列表快。
const taskGroups = computed(() => (task.value ? data.taskGroupsOf(task.value.project) : []))
const currentGroup = computed(() => {
  const id = task.value?.groupId
  return taskGroups.value.find((g) => g.id === id) ?? taskGroups.value[0]
})
function setGroup(id: string) {
  const t = task.value
  if (t) data.moveTaskToGroup(t.id, id)
  menu.value = null
}
/**
 * 改开始 / 截止日期。
 *
 * <p>这两个字段以前只是属性栏里的一行只读文字：截止日期只能在新建弹窗里从「今天 / 明天 /
 * 周日 / 下周一」里挑一次，之后就再也改不动，排期一变只能重建任务。改成日期输入框后，
 * 清空（选空值）也是合法操作，表示这条暂时不排期。
 */
const dueHint = computed(() => {
  // 日期框只显示绝对日期，紧迫感（逾期 / 今天 / 明天）由这行小字补上，不改也不遮输入框
  const t = task.value
  if (!t?.due || !tv.value) return ''
  return tv.value.dueStr === fmtDate(t.due) ? '' : tv.value.dueStr
})
function setDate(field: 'start' | 'due', event: Event) {
  const t = task.value
  if (!t) return
  const value = (event.target as HTMLInputElement).value
  const label = field === 'start' ? '开始日期' : '截止日期'
  if ((t[field] ?? '') === value) return
  const activity = value ? `将${label}改为 ${fmtDate(value)}` : `清空了${label}`
  data.patchTask(t.id, field === 'start' ? { start: value } : { due: value }, activity)
}
function setDateTime(field: 'startAt' | 'endAt', event: Event) {
  const t = task.value
  if (!t) return
  const value = (event.target as HTMLInputElement).value
  if (!value) {
    data.patchTask(t.id, { startAt: null, endAt: null }, '清空了任务时间段')
    return
  }
  if (field === 'endAt' && !t.startAt) {
    toast('请先填写时间段开始时间', 'ri-error-warning-line')
    ;(event.target as HTMLInputElement).value = ''
    return
  }
  const other = field === 'startAt' ? t.endAt : t.startAt
  if (value && other && (field === 'startAt' ? value >= other : value <= other)) {
    toast('结束时间必须晚于开始时间', 'ri-error-warning-line')
    ;(event.target as HTMLInputElement).value = t[field] ?? ''
    return
  }
  const label = field === 'startAt' ? '时间段开始' : '时间段结束'
  const automaticEnd = field === 'startAt' && !t.endAt ? addHour(value) : t.endAt
  data.patchTask(t.id, {
    [field]: value || null,
    ...(field === 'startAt' ? { endAt: automaticEnd } : {}),
    ...(field === 'startAt' && value ? { due: value.slice(0, 10) } : {}),
  }, value ? `将${label}改为 ${value.replace('T', ' ')}` : `清空了${label}`)
}
function addHour(value: string) {
  const date = new Date(value)
  date.setHours(date.getHours() + 1)
  const p = (number: number) => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}`
}
function addCustom(type: string) {
  const t = task.value
  if (!t) return
  data.patchTask(t.id, { custom: [...t.custom, { name: `未命名属性（${type}）`, value: '—' }] }, '添加了自定义属性')
  menu.value = null
}
function updateCustom(index: number, field: 'name' | 'value', event: Event) {
  const t = task.value
  if (!t) return
  const value = (event.target as HTMLInputElement).value.trim()
  const current = t.custom[index]
  if (!current || !value || value === current[field]) return
  const custom = t.custom.map((item, i) => i === index ? { ...item, [field]: value } : item)
  data.patchTask(t.id, { custom }, '修改了自定义属性')
}
function removeCustom(index: number) {
  const t = task.value
  if (!t) return
  data.patchTask(t.id, { custom: t.custom.filter((_, i) => i !== index) }, '删除了自定义属性')
}
function gotoProject() {
  const pid = task.value?.project
  close()
  if (pid) router.push(`/projects/${pid}`)
}
function openDoc(id: string) {
  const k = data.kbs.find((kk) => kk.folders.some((f) => f.docs.includes(id)))
  close()
  if (k) router.push({ path: `/kb/${k.id}`, query: { doc: id } })
  else router.push('/knowledge')
}
function openLinkTask(id: string) {
  ui.openTask(id)
}
function openLinkedFile(id: string | null | undefined) {
  if (!id) return
  close()
  router.push(`/f/${encodeURIComponent(id)}`)
}
function toggleRelation(type: 'doc' | 'task' | 'file', id: string) {
  const t = task.value
  if (!t) return
  if (type === 'doc') {
    const selected = t.docs.includes(id)
    const title = data.docById(id)?.title || '未命名文档'
    data.patchTask(t.id, { docs: selected ? t.docs.filter((item) => item !== id) : [...t.docs, id] },
      `${selected ? '取消关联' : '关联了'}知识文档「${title}」`)
    return
  }
  if (type === 'task') {
    const selected = t.linkTasks.some((item) => item.id === id)
    const title = data.taskById(id)?.title ?? id
    data.patchTask(t.id, {
      linkTasks: selected ? t.linkTasks.filter((item) => item.id !== id) : [...t.linkTasks, { id, rel: '相关任务' }],
    }, `${selected ? '取消关联' : '关联了'}任务「${title}」`)
    return
  }
  const file = data.files.find((item) => item.id === id)
  if (!file) return
  const selected = t.files.some((item) => item.id === id)
  data.patchTask(t.id, {
    files: selected
      ? t.files.filter((item) => item.id !== id)
      : [...t.files, { id, name: file.name, size: file.size, icon: file.icon }],
  }, `${selected ? '取消关联' : '关联了'}文件「${file.name}」`)
}
async function uploadAttachment(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const t = task.value
  if (!file || !t || attachmentUploading.value) return
  if (file.size > data.MAX_FILE_BYTES) return toast('文件超过 50MB，无法上传', 'ri-error-warning-line')
  attachmentUploading.value = true
  try {
    const uploaded = await data.uploadFile(file)
    if (!uploaded?.id) return toast('文件上传失败，请重试', 'ri-error-warning-line')
    if (!t.files.some((item) => item.id === uploaded.id)) {
      data.patchTask(t.id, {
        files: [...t.files, { id: uploaded.id, name: uploaded.name, size: uploaded.size, icon: uploaded.icon }],
      }, `上传并关联了文件「${uploaded.name}」`)
    }
    toast('文件已上传并关联到任务', 'ri-check-line')
  } finally {
    attachmentUploading.value = false
  }
}
function demo(msg: string) {
  toast(msg)
}
</script>

<template>
  <template v-if="task && tv">
    <div class="mask" @click="close"></div>
    <aside class="drawer">
      <!-- 顶栏 -->
      <div class="dhead">
        <button class="proj-link" @click="gotoProject">
          <span class="pdot" :style="{ background: tv.projColor }"></span>
          <span class="ellip">{{ tv.projName }}</span>
        </button>
        <i class="ri-arrow-right-s-line sep"></i>
        <span class="crumb">任务详情</span>
        <span v-if="task.waiting" class="wait-badge">{{ task.waiting }}</span>
        <div class="spacer"></div>
        <div class="ai-run-wrap">
          <button
            class="ai-run-btn"
            :class="{ running: task.status === 'AI 进行中' }"
            :disabled="aiStarting || task.status === 'AI 进行中'"
            title="选择电脑和 AI 工具后自动执行"
            @click="aiLauncherOpen = !aiLauncherOpen"
          >
            <i :class="aiStarting ? 'ri-loader-4-line spin' : task.status === 'AI 进行中' ? 'ri-loader-2-line spin' : 'ri-robot-2-line'"></i>
            {{ task.status === 'AI 进行中' ? 'AI 运行中' : 'AI run' }}
            <i v-if="task.status !== 'AI 进行中'" class="ri-arrow-down-s-line caret"></i>
          </button>
          <AiRunLauncher
            v-if="aiLauncherOpen && (aiLauncherSubId || task.status !== 'AI 进行中')"
            :project-id="task.project"
            :starting="aiStarting"
            :scope="aiLauncherScope"
            @close="aiLauncherOpen = false; aiLauncherSubId = null"
            @start="runWithAi"
          />
        </div>
        <button
          v-if="activeAiRun"
          class="pause-ai-btn"
          type="button"
          :disabled="pausingRunId === activeAiRun.id"
          title="暂停当前 AI 任务"
          @click="pauseRun(activeAiRun.id)"
        >
          <i :class="pausingRunId === activeAiRun.id ? 'ri-loader-4-line spin' : 'ri-pause-circle-line'"></i>
          {{ pausingRunId === activeAiRun.id ? '暂停中' : '暂停 AI' }}
        </button>
        <button
          class="icon-btn"
          :class="{ faved: favorites.isFav('task', task.id) }"
          :title="favorites.isFav('task', task.id) ? '取消收藏' : '收藏到左侧菜单'"
          @click="favorites.toggle('task', task.id, task.title, task.project)"
        >
          <i :class="favorites.isFav('task', task.id) ? 'ri-star-fill' : 'ri-star-line'"></i>
        </button>
        <div class="share-wrap">
          <button class="icon-btn" title="复制分享链接" @click="menu = menu === 'share' ? null : 'share'"><i class="ri-links-line"></i></button>
          <div v-if="menu === 'share'" class="menu-mask" @click="menu = null"></div>
          <div v-if="menu === 'share'" class="share-menu">
            <div class="share-scope">
              {{ openSubId ? '分享范围：当前子任务' : shareOpenOnly ? '分享范围：本任务 + 还没人做的子任务' : '分享范围：本任务 + 全部子任务' }}
            </div>
            <label v-if="!openSubId && (shareDoneCount || dispatchedSubIds.size)" class="share-filter">
              <input v-model="shareOpenOnly" type="checkbox" />
              <span class="share-filter-main">
                <b>只分享还没人做的子任务</b>
                <em>过滤掉已完成的 {{ shareDoneCount }} 条{{ dispatchedSubIds.size ? `和已单独派给 AI 的 ${dispatchedSubIds.size} 条` : '' }}，避免重复派工</em>
              </span>
            </label>
            <button class="share-item" @click="copyShare('page')">
              <i class="ri-external-link-line"></i>
              <span class="share-main"><b>复制分享页链接</b><em>公开只读页面，给人看</em></span>
            </button>
            <button class="share-item" @click="copyShare('md')">
              <i class="ri-robot-2-line"></i>
              <span class="share-main"><b>复制 Markdown 地址</b><em>一次 GET 即全文，给 AI 用</em></span>
            </button>
          </div>
        </div>
        <button class="icon-btn" title="关闭（Esc）" @click="close"><i class="ri-close-line"></i></button>
      </div>

      <div class="dbody">
        <!-- 主区 -->
        <div class="main">
          <textarea ref="taskTitleArea" class="title-input" rows="1" :value="task.title" @input="editTitle"></textarea>

          <div class="field">
            <div class="field-headline">
              <div class="field-label">描述</div>
              <SaveHint :saving="saveState === 'saving'" :saved-at="savedAt" :failed="saveState === 'error'" />
            </div>
            <MarkdownEditor
              v-model="desc"
              placeholder="补充背景、目标与边界…敲 ## 变标题、- 变列表，可直接粘贴截图"
              :min-height="84"
              @upload-image="onTaskImage"
            />
          </div>

          <div v-if="task.images && task.images.length" class="field">
            <div class="field-label">图片</div>
            <div class="img-grid">
              <div v-for="(im, i) in task.images" :key="i" class="img-cell">
                <img :src="im.url" :alt="im.name" @click="viewImage(im.url)" />
                <button class="img-del" title="移除" @click="removeImage(i)"><i class="ri-close-line"></i></button>
              </div>
            </div>
          </div>

          <section class="pdca" :class="{ empty: !currentPdca }">
            <div class="pdca-head">
              <div>
                <div class="field-label nomb">PDCA 闭环</div>
                <p v-if="currentPdca">第 {{ pdcaCycles.length }} 轮 · {{ pdcaPhase }}</p>
                <p v-else>让任务完成后留下经验，并把改进行动带进下一轮</p>
              </div>
              <div v-if="currentPdca" class="pdca-save">
                <SaveHint :saving="saveState === 'saving'" :saved-at="savedAt" :failed="saveState === 'error'" />
                <button v-if="saveState === 'error'" type="button" @click="data.flushTask(task.id)">立即重试</button>
              </div>
            </div>
            <button v-if="!currentPdca" class="pdca-enable" type="button" @click="enablePdca">
              <i class="ri-loop-right-line"></i>启用 PDCA 跟踪
            </button>
            <template v-else>
              <div class="pdca-stages" aria-label="当前 PDCA 阶段">
                <span v-for="stage in ['P', 'D', 'C', 'A']" :key="stage" :class="{ on: pdcaPhase.startsWith(stage) }">{{ stage }}</span>
              </div>
              <label class="pdca-field">
                <b><em>P</em>计划与判定标准</b>
                <textarea
                  :value="currentPdca.plan"
                  :disabled="!!currentPdca.reviewedAt"
                  rows="2"
                  placeholder="这轮要改变什么？做到什么程度算达成？"
                  @input="updatePdca('plan', $event)"
                ></textarea>
              </label>
              <div class="pdca-do">
                <b><em>D</em>执行进展</b>
                <span>{{ pdcaDoSummary }}</span>
              </div>
              <div class="pdca-field">
                <b><em>C</em>检查与复盘</b>
                <div class="pdca-outcomes">
                  <button
                    v-for="outcome in (['达成', '部分达成', '未达成'] as PdcaOutcome[])"
                    :key="outcome"
                    type="button"
                    :class="{ on: currentPdca.outcome === outcome }"
                    :disabled="!!currentPdca.reviewedAt"
                    @click="setPdcaOutcome(outcome)"
                  >{{ outcome }}</button>
                </div>
                <textarea
                  :value="currentPdca.check"
                  :disabled="!!currentPdca.reviewedAt"
                  rows="3"
                  placeholder="实际发生了什么？偏差在哪里？为什么？"
                  @input="updatePdca('check', $event)"
                ></textarea>
              </div>
              <label class="pdca-field">
                <b><em>A</em>改进行动</b>
                <textarea
                  :value="currentPdca.act"
                  :disabled="!!currentPdca.reviewedAt"
                  rows="2"
                  placeholder="下一轮继续什么、停止什么、尝试什么？"
                  @input="updatePdca('act', $event)"
                ></textarea>
              </label>
              <div class="pdca-actions">
                <span v-if="!currentPdca.reviewedAt && !pdcaInCheck">任务进入“待验证”或“已完成”后即可完成复盘</span>
                <span v-else-if="!currentPdca.reviewedAt && !canReviewPdca">填写计划，选择结果，并补全复盘与改进行动后即可闭环</span>
                <span v-else-if="currentPdca.reviewedAt"><i class="ri-checkbox-circle-fill"></i>本轮已于 {{ showTime(currentPdca.reviewedAt) }}闭环</span>
                <button v-if="!currentPdca.reviewedAt" type="button" :disabled="!canReviewPdca" @click="completePdcaReview">完成本轮复盘</button>
                <button v-else type="button" @click="startNextPdcaCycle"><i class="ri-loop-right-line"></i>开启下一轮</button>
              </div>
              <details v-if="previousPdca.length" class="pdca-history">
                <summary>查看前 {{ previousPdca.length }} 轮复盘</summary>
                <article v-for="(cycle, index) in previousPdca" :key="cycle.id">
                  <b>第 {{ pdcaCycles.length - index - 1 }} 轮 · {{ cycle.outcome }}</b>
                  <p><em>P</em>{{ cycle.plan }}</p>
                  <p><em>C</em>{{ cycle.check }}</p>
                  <p><em>A</em>{{ cycle.act }}</p>
                </article>
              </details>
            </template>
          </section>

          <div v-if="stepsTotal" class="field">
            <div class="steps-head">
              <div class="field-label nomb">步骤</div>
              <span class="steps-count wao-tabular">{{ stepsDone }} / {{ stepsTotal }}</span>
              <span class="steps-bar"><span class="steps-fill" :style="{ width: stepPct + '%' }"></span></span>
            </div>
            <div>
              <div v-for="(sp, i) in task.steps" :key="i" class="check-row" @click="toggleStep(i, 'steps')">
                <span class="cbox sq" :class="{ done: sp.done }"><i class="ri-check-line"></i></span>
                <span class="check-text" :class="{ done: sp.done }">{{ sp.text }}</span>
              </div>
            </div>
          </div>

          <div class="field">
            <!-- 输入框放在标题行上：子任务多的时候不必拉到最底下才能加一条 -->
            <div class="sub-head">
              <div class="field-label nomb">子任务</div>
              <div class="sub-add">
                <i class="ri-add-line"></i>
                <input v-model="newSub" placeholder="添加子任务，回车确认" @keydown.enter="addSub" />
              </div>
            </div>
            <div>
              <div
                v-for="r in openSubRows"
                :key="r.key"
                class="check-row sub-row"
                :class="{
                  active: openSubId === r.key,
                  settling: isSettling(r.key),
                  dragging: draggingSubId === r.key,
                  'drag-over': dragOverSubId === r.key,
                }"
                draggable="true"
                @click="showSub(r.idx)"
                @dragstart="startSubDrag(r.key, $event)"
                @dragover.prevent="overSub(r.key)"
                @drop.prevent="dropSub(r.key)"
                @dragend="endSubDrag"
              >
                <i class="ri-draggable sub-grip" title="拖拽调整顺序"></i>
                <span class="cbox circle" :class="{ done: r.sub.done }" @click.stop="toggleSub(r.idx)"><i class="ri-check-line"></i></span>
                <span class="check-text" :class="{ done: r.sub.done }">{{ r.sub.text }}</span>
                <i v-if="r.sub.desc || r.sub.images?.length" class="ri-file-text-line sub-flag" title="有描述 / 图片"></i>
                <span class="spacer"></span>
                <span v-if="dispatchedSubIds.has(r.key)" class="sub-dispatched" title="已单独派给 AI，主任务再派工时不会重复纳入">
                  <i class="ri-robot-2-line"></i>已派工
                </span>
                <button
                  v-else
                  class="sub-ai"
                  title="只把这一条子任务交给 AI 跑"
                  @click.stop="runSubWithAi(r.key)"
                >
                  <i class="ri-robot-2-line"></i>AI run
                </button>
                <i class="ri-arrow-right-s-line sub-chev"></i>
              </div>
            </div>

            <!-- 已完成的沉到底部，默认收起 -->
            <template v-if="doneSubRows.length">
              <button class="sub-done-toggle" @click="showDoneSubs = !showDoneSubs">
                <i :class="showDoneSubs ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'"></i>
                已完成<span class="sub-done-count wao-tabular">{{ doneSubRows.length }}</span>
                <span class="sub-done-hint">{{ showDoneSubs ? '点击收起' : '点击展开' }}</span>
              </button>
              <div v-if="showDoneSubs">
                <div
                  v-for="r in doneSubRows"
                  :key="r.key"
                  class="check-row sub-row"
                  :class="{ active: openSubId === r.key }"
                  @click="showSub(r.idx)"
                >
                  <span class="cbox circle done" @click.stop="toggleSub(r.idx)"><i class="ri-check-line"></i></span>
                  <span class="check-text done">{{ r.sub.text }}</span>
                  <i v-if="r.sub.desc || r.sub.images?.length" class="ri-file-text-line sub-flag" title="有描述 / 图片"></i>
                  <span class="spacer"></span>
                  <i class="ri-arrow-right-s-line sub-chev"></i>
                </div>
              </div>
            </template>
          </div>

          <div v-if="task.files.length" class="field">
            <div class="field-label">附件</div>
            <div class="attach-list">
              <button
                v-for="(f, i) in task.files"
                :key="f.id ?? i"
                type="button"
                class="attach"
                :class="{ legacy: !f.id }"
                :disabled="!f.id"
                :title="f.id ? '打开真实文件下载页' : '历史附件没有可下载的文件正文'"
                @click="openLinkedFile(f.id)"
              >
                <i :class="f.icon"></i>
                <span class="attach-name ellip">{{ f.name }}</span>
                <span class="attach-size">{{ f.id ? f.size : `${f.size} · 仅历史记录` }}</span>
                <i :class="f.id ? 'ri-arrow-right-s-line dl' : 'ri-information-line dl'"></i>
              </button>
            </div>
            <button class="text-btn" :disabled="attachmentUploading" @click="attachmentInput?.click()">
              <i :class="attachmentUploading ? 'ri-loader-4-line spin' : 'ri-upload-2-line'"></i>
              {{ attachmentUploading ? '上传中…' : '上传并关联附件' }}
            </button>
            <input ref="attachmentInput" type="file" hidden @change="uploadAttachment" />
          </div>

          <div class="field">
            <div class="field-label">关联</div>
            <div class="attach-list">
              <div v-for="ld in linkedDocs" :key="ld.id" class="attach link" @click="openDoc(ld.id)">
                <i class="ri-book-2-line" style="color: #7a5cc6"></i>
                <span class="attach-name ellip">{{ ld.title }}</span>
                <span class="attach-size">知识库</span>
                <i class="ri-arrow-right-s-line dl"></i>
              </div>
              <div v-for="lk in linkTasks" :key="lk.id" class="attach link" @click="openLinkTask(lk.id)">
                <i class="ri-checkbox-circle-line" style="color: #5a5e6b"></i>
                <span class="attach-name ellip">{{ lk.title }}</span>
                <span class="rel">{{ lk.rel }}</span>
                <span class="mini" :style="{ background: lk.stBg, color: lk.stColor }">{{ lk.status }}</span>
              </div>
            </div>
            <button class="text-btn" @click="relationOpen = true"><i class="ri-links-line"></i>关联知识、任务或文件</button>
          </div>

          <div v-if="aiRuns.length || (aiRunsLoading && !aiRunsLoaded)" class="ai-archive">
            <div class="field-label ai-archive-title">
              <span>AI 执行记录</span>
              <em v-if="activeAiRun"><i></i>实时更新中</em>
            </div>
            <div v-if="aiRunsLoading && !aiRuns.length" class="ai-archive-loading"><i class="ri-loader-4-line"></i>正在读取 AI 记录…</div>
            <details v-for="(run, index) in aiRuns" v-else :key="run.id" class="ai-run-entry" :open="index === 0">
              <summary>
                <span class="ai-run-state" :class="aiRunStatusUnreported(run) ? 'state-unconfirmed' : `state-${run.status}`">{{ aiRunStateLabel(run) }}</span>
                <span class="ai-run-engine" :title="`执行工具：${engineLabel(run.engine)}`"><i :class="engineIcon(run.engine)"></i></span>
                <span v-if="run.subId" class="ai-run-scope" :title="`只跑子任务：${run.subTitle ?? run.subId}`">
                  子任务 · {{ run.subTitle ?? run.subId }}
                </span>
                <span class="ai-run-latest">{{ run.latestProgress || run.resultSummary || '暂无执行摘要' }}</span>
                <time :title="absoluteTime(run.startedAt || run.createdAt)">{{ showTime(run.startedAt || run.createdAt) }}</time>
                <i class="ri-arrow-down-s-line"></i>
              </summary>
              <div class="ai-run-body">
                <div v-if="run.usage?.recorded" class="ai-run-usage" :title="run.usage.models || ''">
                  <i class="ri-copper-coin-line"></i>
                  <span v-if="shortModel(run.usage.model)" class="usage-model">{{ shortModel(run.usage.model) }}</span>
                  <span class="usage-part">输入 <b>{{ formatTokens(run.usage.inputTokens) }}</b></span>
                  <span class="usage-part">输出 <b>{{ formatTokens(run.usage.outputTokens) }}</b></span>
                  <span class="usage-part">缓存 <b>{{ formatTokens(run.usage.cachedInputTokens) }}</b></span>
                  <span class="usage-part">合计 <b>{{ formatTokens(run.usage.totalTokens) }}</b></span>
                  <span v-if="formatCost(run.usage.costUsd)" class="usage-part">{{ formatCost(run.usage.costUsd) }}</span>
                </div>
                <AiRunProgressTimeline :run="run" />
                <div v-if="run.resultSummary" class="ai-final-result">
                  <b>{{ aiRunResultTitle(run) }}</b>
                  <p>{{ run.resultSummary }}</p>
                </div>
                <div
                  v-if="['failed', 'succeeded'].includes(run.status) && run.sessionId && run.runnerId"
                  class="ai-continue"
                >
                  <div class="ai-continue-head">
                    <span class="ai-continue-icon"><i class="ri-chat-1-line"></i></span>
                    <div><b>{{ run.status === 'succeeded' ? '继续优化这次结果' : '在原会话中补充信息' }}</b><small>将固定回到 {{ run.runnerName || '原执行电脑' }}，保留本次上下文</small></div>
                  </div>
                  <div class="ai-continue-input">
                    <MarkdownEditor
                      :model-value="aiContinuation[run.id] ?? ''"
                      :min-height="56"
                      :placeholder="run.status === 'succeeded'
                        ? '跑完了但不符合预期？写下要优化的地方，AI 带着这次的记忆接着改；截图可直接粘贴'
                        : '补充信息后，让 AI 在原会话中继续执行；截图可直接粘贴'"
                      @update:model-value="(v: string) => (aiContinuation[run.id] = v)"
                      @upload-image="onContinueImage"
                    />
                  </div>
                  <button :disabled="continuingRunId === run.id || !aiContinuation[run.id]?.trim()" @click="continueRun(run.id)">
                    <i :class="continuingRunId === run.id ? 'ri-loader-4-line spin' : 'ri-send-plane-2-line'"></i>{{ continuingRunId === run.id ? '发送中…' : run.status === 'succeeded' ? '继续优化' : '继续对话' }}
                  </button>
                </div>
              </div>
            </details>
          </div>

          <div class="comments">
            <div class="field-label">评论</div>
            <div class="comment-list">
              <div v-for="(cm, i) in comments" :key="i" class="comment">
                <span class="c-av" :style="{ background: cm.color }">{{ cm.initial }}</span>
                <div class="c-main">
                  <div class="c-head">
                    <b>{{ cm.name }}</b><span class="c-time" :title="cm.fullTime">{{ cm.time }}</span>
                    <span class="spacer"></span>
                    <template v-if="cm.mine && editingComment !== cm.index">
                      <button class="c-op" title="修改评论" @click="startEditComment(cm.index, cm.text)"><i class="ri-edit-line"></i></button>
                      <button class="c-op danger" title="删除评论" @click="removeComment(cm.index)"><i class="ri-delete-bin-line"></i></button>
                    </template>
                  </div>
                  <div v-if="editingComment === cm.index" class="c-edit">
                    <textarea v-model="editingText" class="c-edit-input" rows="2" @keydown.esc="cancelEditComment"></textarea>
                    <div class="c-edit-ops">
                      <button class="text-btn small" @click="cancelEditComment">取消</button>
                      <button class="text-btn small primary" :disabled="!editingText.trim()" @click="saveComment">保存</button>
                    </div>
                  </div>
                  <div v-else class="c-text">{{ cm.text }}</div>
                </div>
              </div>
            </div>
            <div class="comment-input">
              <span class="c-av" :style="{ background: session.currentUser.color }">{{ session.currentUser.initial }}</span>
              <div class="ci-wrap">
                <input v-model="commentText" :disabled="sendingComment" placeholder="写评论，回车发送；@成员名 对方会收到飞书通知" @focus="mentionOpen = true" @input="mentionOpen = true" @keydown="commentKey" />
                <div v-if="mentionOpen && mentionCandidates.length" class="mention-menu">
                  <button v-for="member in mentionCandidates" :key="member.id" type="button" @mousedown.prevent="insertMention(member.name)">
                    <span :style="{ background: member.color }">{{ member.initial }}</span>{{ member.name }}
                  </button>
                </div>
                <button class="send" :disabled="!commentText.trim() || sendingComment" @click="sendComment"><i :class="sendingComment ? 'ri-loader-4-line spin' : 'ri-send-plane-2-line'"></i>{{ sendingComment ? '发送中' : '发送' }}</button>
              </div>
            </div>
          </div>
        </div>

        <!--
          子任务详情：桌面上展开子任务时顶掉属性栏；手机上盖成一整页。
          手机窗口小，原来排在正文下面，点开还得往下滑一段才找得到，滑到了也不知道
          看的是哪一条 —— 现在直接盖满一屏，顶上写清楚是第几条，返回退回任务。
        -->
        <div v-if="openSub" class="props" :class="{ 'sub-page': isMobile }">
          <div v-if="isMobile" class="sub-page-head">
            <button class="sub-page-back" @click="openSubId = null"><i class="ri-arrow-left-line"></i></button>
            <div class="sub-page-title">
              <b>子任务 {{ openSubIndex + 1 }} / {{ task.subs.length }}</b>
              <em class="ellip">{{ task.title }}</em>
            </div>
            <button class="sub-page-done" :class="{ on: openSub.done }" @click="toggleSubDone">
              <i class="ri-check-line"></i>{{ openSub.done ? '已完成' : '标记完成' }}
            </button>
          </div>
          <button v-if="!isMobile" class="sub-back" @click="openSubId = null"><i class="ri-arrow-left-line"></i>返回任务属性</button>
          <div v-if="!isMobile" class="props-label">子任务 {{ openSubIndex + 1 }} / {{ task.subs.length }}</div>

          <div class="sub-title-row">
            <button class="cbox circle big" :class="{ done: openSub.done }" title="标记完成" @click="toggleSubDone"><i class="ri-check-line"></i></button>
            <textarea
              ref="subTitleArea"
              class="sub-title-input"
              :class="{ done: openSub.done }"
              rows="1"
              :value="openSub.text"
              placeholder="子任务名称"
              @input="editSubTitle"
              @keydown.enter.prevent
            ></textarea>
          </div>

          <div class="sub-field">
            <div class="field-headline">
              <div class="props-label sm">描述</div>
              <SaveHint :saving="saveState === 'saving'" :saved-at="savedAt" :failed="saveState === 'error'" />
            </div>
            <MarkdownEditor
              v-model="subDesc"
              placeholder="补充这个子任务的做法、验收标准…敲 ## 变标题、- 变列表，可直接粘贴截图"
              :min-height="120"
              @upload-image="onSubImage"
            />
          </div>

          <!-- 历史数据里挂在子任务上的图片；新粘贴的图现在直接进描述正文 -->
          <div v-if="openSub.images?.length" class="sub-field">
            <div class="props-label sm">图片</div>
            <div class="img-grid sub-img-grid">
              <div v-for="(im, i) in openSub.images" :key="i" class="img-cell">
                <img :src="im.url" :alt="im.name" @click="viewImage(im.url)" />
                <button class="img-del" title="移除" @click="removeSubImage(i)"><i class="ri-close-line"></i></button>
              </div>
            </div>
          </div>

          <div class="prop-section">
            <button class="text-btn small" @click="copyShare('page')"><i class="ri-links-line"></i>复制该子任务分享链接</button>
            <button class="text-btn small danger" @click="deleteSub"><i class="ri-delete-bin-line"></i>删除子任务</button>
          </div>
        </div>

        <!-- 属性栏 -->
        <div v-else class="props">
          <div v-if="menu && menu !== 'share'" class="menu-mask" @click="menu = null"></div>
          <div class="props-label">属性</div>

          <div class="prop-row">
            <span class="prop-key">状态</span>
            <button class="pill" :style="{ background: tv.stBg, color: tv.stColor }" @click="menu = menu === 'status' ? null : 'status'">{{ tv.status }}<i class="ri-arrow-down-s-line"></i></button>
            <div v-if="menu === 'status'" class="pmenu w150">
              <button v-for="o in STATUS_OPTS" :key="o" class="pmenu-item" @click="setStatus(o)">
                <span class="mini pill-mini" :style="{ background: statusMeta(o).bg, color: statusMeta(o).color }">{{ o }}</span>
                <span class="spacer"></span>
                <i v-if="task.status === o" class="ri-check-line chk"></i>
              </button>
            </div>
          </div>

          <div class="prop-row">
            <span class="prop-key">优先级</span>
            <button class="pill sq" :style="{ background: tv.prBg, color: tv.prColor }" @click="menu = menu === 'pr' ? null : 'pr'">{{ tv.pr }}<i class="ri-arrow-down-s-line"></i></button>
            <div v-if="menu === 'pr'" class="pmenu w190">
              <button v-for="o in PR_OPTS" :key="o.key" class="pmenu-item" @click="setPriority(o.key)">
                <span class="mini" :style="{ background: priorityMeta(o.key).bg, color: priorityMeta(o.key).color }">{{ o.key }}</span>
                <span class="pmenu-sub">{{ o.sub }}</span>
                <i v-if="task.pr === o.key" class="ri-check-line chk"></i>
              </button>
            </div>
          </div>

          <div class="prop-row">
            <span class="prop-key">负责人</span>
            <button class="owner-btn" @click="menu = menu === 'owner' ? null : 'owner'">
              <span class="mini-av" :style="{ background: tv.ownerColor }">{{ tv.ownerInitial }}</span>{{ tv.ownerName }}<i class="ri-arrow-down-s-line"></i>
            </button>
            <div v-if="menu === 'owner'" class="pmenu w150">
              <button v-for="m in session.members" :key="m.id" class="pmenu-item" @click="setOwner(m.id)">
                <span class="mini-av" :style="{ background: m.color }">{{ m.initial }}</span>
                <span class="pmenu-name">{{ m.name }}</span>
                <i v-if="task.owner === m.id" class="ri-check-line chk"></i>
              </button>
            </div>
          </div>

          <div class="prop-row">
            <span class="prop-key">参与人</span>
            <span class="parts">
              <span v-for="(p, i) in parts" :key="i" class="mini-av bordered" :title="p.name" :style="{ background: p.color }">{{ p.initial }}</span>
            </span>
            <button class="add-part" title="添加参与人" @click="demo('演示：成员选择器在下一轮迭代提供')"><i class="ri-add-line"></i></button>
          </div>

          <div class="prop-row">
            <span class="prop-key">开始</span>
            <input
              class="date-input"
              type="date"
              :value="task.start || ''"
              :max="task.due || undefined"
              title="开始日期，留空表示未排期"
              @change="setDate('start', $event)"
            />
          </div>
          <div class="prop-row">
            <span class="prop-key">截止</span>
            <input
              class="date-input due"
              type="date"
              :value="task.due || ''"
              :min="task.start || undefined"
              :style="{ color: tv.dueColor }"
              title="截止日期，留空表示未排期"
              @change="setDate('due', $event)"
            />
            <span v-if="dueHint" class="due-hint" :style="{ color: tv.dueColor }">{{ dueHint }}</span>
          </div>
          <div class="prop-row">
            <span class="prop-key">时段开始</span>
            <input class="datetime-input" type="datetime-local" :value="task.startAt || ''" @change="setDateTime('startAt', $event)" />
          </div>
          <div class="prop-row">
            <span class="prop-key">时段结束</span>
            <input class="datetime-input" type="datetime-local" :value="task.endAt || ''" :min="task.startAt || undefined" @change="setDateTime('endAt', $event)" />
          </div>
          <div class="prop-row">
            <span class="prop-key">所属项目</span>
            <button class="proj-btn" @click="gotoProject"><span class="pdot" :style="{ background: tv.projColor }"></span><span class="ellip">{{ tv.projName }}</span></button>
          </div>
          <div class="prop-row">
            <span class="prop-key">分组</span>
            <button class="owner-btn" title="分组视图里这条任务归在哪一堆" @click="menu = menu === 'group' ? null : 'group'">
              <i class="ri-folder-2-line group-ico"></i>{{ currentGroup?.name ?? '未分组' }}<i class="ri-arrow-down-s-line"></i>
            </button>
            <div v-if="menu === 'group'" class="pmenu w150">
              <button v-for="g in taskGroups" :key="g.id" class="pmenu-item" @click="setGroup(g.id)">
                <span class="pmenu-name">{{ g.name }}</span>
                <i v-if="currentGroup?.id === g.id" class="ri-check-line chk"></i>
              </button>
            </div>
          </div>
          <div class="prop-row">
            <span class="prop-key">可见范围</span>
            <span class="prop-val"><i class="ri-eye-line"></i>项目成员可见</span>
          </div>

          <div class="prop-section">
            <div class="props-label sm">自定义属性</div>
            <div v-for="(cp, i) in task.custom" :key="i" class="custom-row">
              <input class="custom-name" :value="cp.name" aria-label="属性名称" @change="updateCustom(i, 'name', $event)" />
              <input class="custom-val" :value="cp.value" aria-label="属性值" @change="updateCustom(i, 'value', $event)" />
              <button class="custom-delete" title="删除属性" @click="removeCustom(i)"><i class="ri-delete-bin-line"></i></button>
            </div>
            <button class="text-btn small" @click="menu = menu === 'prop' ? null : 'prop'"><i class="ri-add-line"></i>添加属性</button>
            <div v-if="menu === 'prop'" class="pmenu w130 up">
              <button v-for="o in PROP_TYPES" :key="o" class="pmenu-item plain" @click="addCustom(o)">{{ o }}</button>
            </div>
          </div>

          <div class="prop-section">
            <div class="props-label sm">活动</div>
            <div class="act-wrap">
              <div class="act-line"></div>
              <div v-for="(av, i) in activities" :key="i" class="act-row">
                <span class="act-dot"></span>
                <div class="act-main">
                  <div class="act-text">{{ av.text }}</div>
                  <div class="act-time" :title="av.fullTime">{{ av.time }}</div>
                </div>
              </div>
            </div>
          </div>

          <!-- 删除任务：明摆在属性栏底部，不藏在右键菜单里 -->
          <div class="danger-zone">
            <template v-if="!confirmingDelete">
              <div class="dz-text">
                <div class="dz-title">删除任务</div>
                <div class="dz-sub">连同子任务、评论与活动一起删除，不可恢复。</div>
              </div>
              <button class="btn-danger" @click="confirmingDelete = true"><i class="ri-delete-bin-line"></i>删除</button>
            </template>
            <template v-else>
              <div class="dz-text">
                <div class="dz-title">确认删除「{{ task.title }}」？</div>
                <div class="dz-sub">此操作不可撤销。</div>
              </div>
              <button class="text-btn small" @click="confirmingDelete = false">取消</button>
              <button class="btn-danger" @click="removeTask"><i class="ri-delete-bin-line"></i>确认删除</button>
            </template>
          </div>
        </div>
      </div>
    </aside>
    <TaskRelationPicker
      v-if="relationOpen"
      :docs="relationDocs"
      :tasks="relationTasks"
      :files="relationFiles"
      :selected-doc-ids="task.docs"
      :selected-task-ids="task.linkTasks.map((item) => item.id)"
      :selected-file-ids="selectedFileIds"
      @toggle="toggleRelation"
      @close="relationOpen = false"
    />
  </template>
</template>

<style scoped>
/* 评论操作 / 编辑态 */
.c-op {
  width: 22px;
  height: 22px;
  border: none;
  background: none;
  border-radius: 6px;
  color: var(--wao-text-faint);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s;
}
.comment:hover .c-op {
  opacity: 1;
}
.c-op:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-text-2);
}
.c-op.danger:hover {
  color: var(--wao-danger-strong);
}
.c-op i {
  font-size: 13px;
}
.c-edit {
  margin-top: 4px;
}
.c-edit-input {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 7px 9px;
  font-size: 12.5px;
  line-height: 1.7;
  resize: vertical;
  outline: none;
  font-family: inherit;
}
.c-edit-input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.c-edit-ops {
  margin-top: 4px;
  display: flex;
  gap: 6px;
}
.text-btn.primary {
  color: var(--wao-primary-text);
}
.text-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 删除任务 */
.danger-zone {
  margin-top: 14px;
  border: 1px solid #f0d9d9;
  background: var(--wao-danger-bg);
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.dz-text {
  flex: 1;
  min-width: 0;
}
.dz-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wao-danger-strong);
}
.dz-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--wao-text-weak);
}
.btn-danger {
  height: 30px;
  padding: 0 11px;
  border: none;
  background: var(--wao-danger);
  color: #fff;
  border-radius: 8px;
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}
.btn-danger:hover {
  filter: brightness(0.95);
}
.btn-danger i {
  font-size: 13px;
}

.mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 80;
}
.drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(920px, calc(100vw - 48px));
  background: var(--wao-bg-card);
  z-index: 81;
  box-shadow: -12px 0 40px rgba(16, 18, 28, 0.16);
  display: flex;
  flex-direction: column;
  animation: waoDrawer 0.2s ease;
}
.spacer {
  flex: 1;
}
.ellip {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dhead {
  height: 50px;
  border-bottom: 1px solid var(--wao-divider);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
  flex-shrink: 0;
}
.proj-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: none;
  color: var(--wao-text-3);
  font-size: 12.5px;
  cursor: pointer;
  padding: 4px 7px;
  border-radius: 6px;
  max-width: 280px;
}
.proj-link:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-primary-text);
}
.pdot {
  width: 6px;
  height: 6px;
  border-radius: 2px;
  flex-shrink: 0;
}
.sep {
  color: var(--wao-text-faint-2);
  font-size: 14px;
}
.crumb {
  font-size: 12.5px;
  color: var(--wao-text-weak);
}
.wait-badge {
  font-size: 11px;
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  border-radius: 5px;
  padding: 1.5px 7px;
}
.icon-btn {
  width: 30px;
  height: 30px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.icon-btn:hover {
  background: var(--wao-bg-hover-2);
}
.icon-btn i {
  font-size: 15px;
}
.icon-btn.faved,
.icon-btn.faved:hover {
  color: #b9821f;
}

.dbody {
  flex: 1;
  display: flex;
  min-height: 0;
}
.main {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 22px 28px 30px;
}
.title-input {
  width: 100%;
  border: none;
  outline: none;
  font-size: 21px;
  font-weight: 700;
  color: var(--wao-text);
  background: none;
  padding: 2px 0;
  border-radius: 6px;
  resize: none;
  overflow: hidden;
  line-height: 1.4;
  font-family: inherit;
}
.title-input:focus {
  background: var(--wao-bg-sub);
  box-shadow: 0 0 0 3px var(--wao-bg-sub);
}
.field {
  margin-top: 16px;
}
.field-headline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.field-label {
  font-size: 12px;
  color: var(--wao-text-weak);
  letter-spacing: 1px;
  margin-bottom: 6px;
}
.field-label.nomb {
  margin-bottom: 0;
}
/* PDCA 是任务正文的一部分：P/C/A 可编辑，D 直接投影真实状态和步骤进度。 */
.pdca {
  margin-top: 18px;
  padding: 14px;
  border: 1px solid #d7ddf5;
  border-radius: 12px;
  background: linear-gradient(145deg, var(--wao-bg-card), var(--wao-primary-soft));
}
.pdca.empty { background: var(--wao-bg-sub); border-style: dashed; }
.pdca-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.pdca-head p {
  margin: 4px 0 0;
  color: var(--wao-text-weak);
  font-size: 11.5px;
}
.pdca-save { display: flex; align-items: center; gap: 7px; }
.pdca-save button {
  padding: 2px 6px;
  border: 1px solid var(--wao-danger-line);
  border-radius: 5px;
  background: var(--wao-bg-card);
  color: var(--wao-danger-strong);
  font-size: 10.5px;
  cursor: pointer;
}
.pdca-enable {
  margin-top: 10px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid #c7d0f1;
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-primary-text);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.pdca-stages {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 5px;
  margin: 12px 0;
}
.pdca-stages span {
  height: 5px;
  border-radius: 999px;
  background: var(--wao-line-strong);
  color: transparent;
  font-size: 0;
}
.pdca-stages span.on { background: var(--wao-primary); }
.pdca-field,
.pdca-do {
  display: grid;
  gap: 6px;
  margin-top: 10px;
}
.pdca-field b,
.pdca-do b {
  color: var(--wao-text-2);
  font-size: 12px;
}
.pdca-field b em,
.pdca-do b em,
.pdca-history p em {
  margin-right: 6px;
  color: var(--wao-primary-text);
  font-style: normal;
  font-weight: 750;
}
.pdca-field textarea {
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 8px 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 400;
  line-height: 1.65;
  outline: none;
}
.pdca-field textarea:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, .1);
}
.pdca-field textarea:disabled { color: var(--wao-text-3); background: var(--wao-bg-sub); }
.pdca-do span {
  padding: 8px 9px;
  border-radius: 8px;
  background: rgba(255, 255, 255, .65);
  color: var(--wao-text-3);
  font-size: 12px;
}
.pdca-outcomes { display: flex; gap: 6px; }
.pdca-outcomes button {
  height: 26px;
  padding: 0 9px;
  border: 1px solid var(--wao-line-control);
  border-radius: 999px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  font-size: 11.5px;
  cursor: pointer;
}
.pdca-outcomes button.on { border-color: var(--wao-primary); background: var(--wao-primary); color: #fff; }
.pdca-outcomes button:disabled { cursor: default; }
.pdca-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.pdca-actions span {
  flex: 1;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.pdca-actions span i { color: var(--wao-success-strong); margin-right: 4px; }
.pdca-actions button {
  height: 30px;
  padding: 0 11px;
  border: none;
  border-radius: 8px;
  background: var(--wao-primary);
  color: #fff;
  font-size: 11.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.pdca-actions button:disabled { opacity: .45; cursor: not-allowed; }
.pdca-history { margin-top: 12px; border-top: 1px solid var(--wao-divider); padding-top: 10px; }
.pdca-history summary { color: var(--wao-text-3); font-size: 11.5px; cursor: pointer; }
.pdca-history article { margin-top: 9px; padding: 9px; border-radius: 8px; background: rgba(255, 255, 255, .7); }
.pdca-history article > b { font-size: 11.5px; }
.pdca-history p { margin: 5px 0 0; color: var(--wao-text-3); font-size: 11.5px; line-height: 1.5; white-space: pre-wrap; }
/* 分享菜单 */
.share-wrap {
  position: relative;
}
.ai-run-wrap {
  position: relative;
}
.ai-run-btn .caret {
  margin-left: -2px;
  opacity: .6;
}
.ai-run-engine {
  flex: 0 0 auto;
  color: var(--wao-text-3);
  font-size: 12px;
}
.ai-run-btn {
  height: 30px;
  padding: 0 11px;
  border: 1px solid #c9d1f5;
  border-radius: 8px;
  background: linear-gradient(135deg, #eef1ff, #fff);
  color: var(--wao-primary-text);
  font-size: 11.5px;
  font-weight: 650;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.ai-run-btn:hover:not(:disabled) {
  border-color: var(--wao-primary);
  box-shadow: 0 3px 10px rgba(67, 88, 214, 0.14);
}
.ai-run-btn.running {
  color: var(--wao-warning-strong);
  border-color: #efd69a;
  background: var(--wao-warning-bg);
}
.ai-run-btn:disabled {
  cursor: default;
}
.pause-ai-btn {
  height: 30px;
  padding: 0 10px;
  border: 1px solid #efc9c9;
  border-radius: 8px;
  background: var(--wao-danger-bg);
  color: #b64141;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
}
.pause-ai-btn:hover:not(:disabled) { background: var(--wao-danger-bg); border-color: var(--wao-danger-line); }
.pause-ai-btn:disabled { cursor: default; opacity: .7; }
.spin {
  display: inline-block;
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.share-menu {
  position: absolute;
  right: 0;
  top: 34px;
  z-index: 89;
  width: 236px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop-2);
  padding: 5px;
  animation: waoPop 0.14s ease;
}
.share-scope {
  font-size: 11px;
  color: var(--wao-text-weak);
  padding: 5px 8px 6px;
  border-bottom: 1px solid var(--wao-divider);
  margin-bottom: 3px;
}
.share-filter {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 7px 8px;
  margin-bottom: 3px;
  border-radius: 7px;
  cursor: pointer;
}
.share-filter:hover {
  background: var(--wao-bg-hover-2);
}
.share-filter input {
  margin-top: 2px;
  cursor: pointer;
  flex-shrink: 0;
}
.share-filter-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.share-filter-main b {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--wao-text);
}
.share-filter-main em {
  font-size: 11px;
  font-style: normal;
  color: var(--wao-text-weak);
  line-height: 1.5;
}
.share-item {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 9px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 8px;
  cursor: pointer;
  text-align: left;
}
.share-item:hover {
  background: var(--wao-bg-hover-2);
}
.share-item > i {
  font-size: 15px;
  color: var(--wao-text-3);
  margin-top: 1px;
}
.share-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.share-main b {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--wao-text);
}
.share-main em {
  font-size: 11px;
  font-style: normal;
  color: var(--wao-text-weak);
}

.steps-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.steps-count {
  font-size: 11.5px;
  color: var(--wao-text-3);
}
.steps-bar {
  flex: 1;
  max-width: 140px;
  height: 4px;
  border-radius: 99px;
  background: var(--wao-divider);
  overflow: hidden;
}
.steps-fill {
  display: block;
  height: 100%;
  border-radius: 99px;
  background: var(--wao-primary);
}
.check-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  margin: 0 -8px;
  border-radius: 7px;
  cursor: pointer;
}
.check-row:hover {
  background: var(--wao-bg-hover);
}
.cbox {
  width: 16px;
  height: 16px;
  border: 1.5px solid #c9cdda;
  background: var(--wao-bg-card);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.cbox.sq {
  border-radius: 5px;
}
.cbox.circle {
  border-radius: 50%;
}
.cbox i {
  font-size: 11px;
  color: #fff;
  opacity: 0;
}
.cbox.done {
  background: var(--wao-primary);
  border-color: var(--wao-primary);
}
.cbox.done i {
  opacity: 1;
}
.check-text {
  font-size: 13px;
  color: var(--wao-text-2);
}
.check-text.done {
  color: var(--wao-text-weak);
  text-decoration: line-through;
}
.sub-row .cbox {
  cursor: pointer;
}
.sub-row[draggable='true'] { cursor: grab; }
.sub-row.dragging { opacity: .45; }
.sub-row.drag-over { box-shadow: inset 0 2px var(--wao-primary); }
.sub-grip {
  color: var(--wao-text-faint);
  font-size: 14px;
  flex: 0 0 auto;
}
.sub-row.active {
  background: var(--wao-primary-tint);
}
.sub-flag {
  font-size: 12px;
  color: var(--wao-text-faint-2);
}
.sub-chev {
  font-size: 15px;
  color: var(--wao-text-faint-2);
  opacity: 0;
}
/* 单条子任务派工：常态隐形，鼠标移到这一行才出现，不给静态列表添噪音 */
.sub-ai {
  flex: 0 0 auto;
  padding: 2px 7px;
  border: 1px solid var(--wao-line);
  border-radius: 6px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  font-size: 10.5px;
  cursor: pointer;
  opacity: 0;
}
.sub-ai i { margin-right: 3px; }
.sub-row:hover .sub-ai,
.sub-row.active .sub-ai { opacity: 1; }
.sub-ai:hover { border-color: #b7c0ea; color: #4358d6; }
.sub-dispatched {
  flex: 0 0 auto;
  padding: 2px 7px;
  border-radius: 6px;
  background: var(--wao-bg-tab);
  color: var(--wao-area-finance);
  font-size: 10.5px;
}
.sub-dispatched i { margin-right: 3px; }
.sub-row:hover .sub-chev,
.sub-row.active .sub-chev {
  opacity: 1;
}
/* 刚勾完成、还在 3 秒缓冲里：淡出但不移位 */
.sub-row.settling {
  opacity: 0.55;
  transition: opacity 0.4s ease;
}
.sub-done-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: none;
  padding: 7px 8px;
  margin: 2px -8px 0;
  border-radius: 7px;
  font-size: 12px;
  color: var(--wao-text-weak);
  cursor: pointer;
  text-align: left;
}
.sub-done-toggle:hover {
  background: var(--wao-bg-hover);
  color: var(--wao-text-2);
}
.sub-done-toggle > i {
  font-size: 15px;
}
.sub-done-count {
  background: var(--wao-divider);
  border-radius: 999px;
  padding: 1px 7px;
  font-size: 11px;
}
.sub-done-hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--wao-text-faint);
}

/* 子任务详情栏 */
.sub-back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  color: var(--wao-text-3);
  font-size: 12px;
  cursor: pointer;
  padding: 3px 6px;
  margin: 0 0 8px -6px;
  border-radius: 6px;
}
.sub-back:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-primary-text);
}
.sub-back i {
  font-size: 13px;
}
.sub-title-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 4px 0 4px;
}
.cbox.big {
  width: 18px;
  height: 18px;
  padding: 0;
  cursor: pointer;
  margin-top: 4px;
}
.sub-title-input {
  flex: 1;
  min-width: 0;
  border: 1px solid transparent;
  outline: none;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--wao-text);
  background: none;
  border-radius: 7px;
  padding: 3px 6px;
  margin-left: -6px;
  /* 长标题要整段展开，不能截断也不能出现内部滚动条 */
  font-family: inherit;
  resize: none;
  overflow: hidden;
  word-break: break-word;
}
.sub-title-input:focus {
  border-color: var(--wao-line-control);
  background: var(--wao-bg-card);
}
.sub-title-input.done {
  color: var(--wao-text-weak);
  text-decoration: line-through;
}
.sub-field {
  margin-top: 14px;
}
.sub-img-grid {
  margin-bottom: 4px;
  grid-template-columns: repeat(auto-fill, minmax(72px, 1fr));
}
.text-btn.danger:hover {
  color: #bc4141;
}

.sub-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}
.sub-add {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 7px;
  border: 1px solid transparent;
  border-radius: 8px;
  padding: 3px 8px;
}
.sub-add:focus-within {
  border-color: var(--wao-line-control);
  background: var(--wao-bg-card);
}
.sub-add i {
  font-size: 14px;
  color: var(--wao-text-faint);
}
.sub-add input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 13px;
  color: var(--wao-text-2);
  background: none;
  padding: 4px 0;
}

.img-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(88px, 1fr));
  gap: 8px;
}
.img-cell {
  position: relative;
  aspect-ratio: 1;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  overflow: hidden;
  background: var(--wao-divider);
}
.img-cell img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  cursor: zoom-in;
}
.img-del {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 5px;
  background: rgba(23, 25, 35, 0.6);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.img-del i {
  font-size: 12px;
}
.attach-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.attach {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 9px;
  border: 1px solid var(--wao-divider);
  border-radius: 9px;
  padding: 8px 11px;
  background: var(--wao-bg-card);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.attach:hover {
  border-color: #d9dce5;
  background: var(--wao-bg-sub);
}
.attach.link:hover {
  border-color: #c7d0f1;
  background: var(--wao-primary-tint);
}
.attach.legacy { cursor: default; opacity: .72; }
.attach.legacy:hover { border-color: var(--wao-divider); background: var(--wao-bg-card); }
.attach > i {
  font-size: 15px;
  color: var(--wao-text-3);
}
.attach-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--wao-text);
}
.attach-size {
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.attach .dl {
  font-size: 14px;
  color: var(--wao-text-faint-2);
}
.rel {
  font-size: 11px;
  color: var(--wao-warning-strong);
  background: var(--wao-warning-bg);
  border-radius: 5px;
  padding: 1px 6px;
}
.mini {
  font-size: 11px;
  border-radius: 5px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.text-btn {
  margin-top: 6px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: none;
  color: var(--wao-text-weak);
  font-size: 12.5px;
  cursor: pointer;
  padding: 4px 0;
}
.text-btn:hover {
  color: var(--wao-primary);
}
.text-btn i {
  font-size: 13px;
}
.text-btn.small {
  padding: 5px 0;
}

.ai-archive { margin-top: 18px; padding-top: 14px; border-top: 1px solid #f0f0f3; }
.ai-archive-title { display: flex; align-items: center; }
.ai-archive-title span { flex: 1; }
.ai-archive-title em { color: #278159; font-size: 10.5px; font-style: normal; }
.ai-archive-title em i { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: #35a86e; animation: aiLive 1.5s infinite; }
.ai-archive-loading { padding: 12px; border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 12px; }
.ai-run-entry { margin-top: 7px; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); }
.ai-run-entry summary { min-height: 44px; padding: 8px 10px; cursor: pointer; list-style: none; display: flex; align-items: center; gap: 8px; }
.ai-run-entry summary::-webkit-details-marker { display: none; }
.ai-run-entry summary > i { color: var(--wao-text-weak); transition: transform .15s; }
.ai-run-entry[open] summary > i { transform: rotate(180deg); }
.ai-run-state { flex: 0 0 auto; padding: 3px 7px; border-radius: 5px; background: #eef1ff; color: #4056c5; font-size: 10px; }
.ai-run-state.state-succeeded { background: #e8f7ef; color: #237a4b; }
.ai-run-state.state-failed { background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
.ai-run-state.state-unconfirmed { background: var(--wao-warning-bg); color: var(--wao-warning-strong); }
.ai-run-scope { flex: 0 0 auto; max-width: 130px; overflow: hidden; padding: 3px 7px; border-radius: 5px; background: var(--wao-bg-tab); color: var(--wao-area-finance); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.ai-run-latest { flex: 1; min-width: 0; overflow: hidden; color: var(--wao-text-2); font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.ai-run-entry summary time { flex: 0 0 auto; color: var(--wao-text-faint); font-size: 10px; }
.ai-run-body { padding: 0 10px 10px; }
.ai-run-usage { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; margin-bottom: 8px; padding: 6px 9px; border-radius: 7px; background: var(--wao-bg-sub); color: var(--wao-text-3); font-size: 10.5px; }
.ai-run-usage i { margin-right: -3px; color: #a8862f; }
.ai-run-usage .usage-model { color: var(--wao-text-2); font-weight: 600; }
.ai-run-usage .usage-part b { font-weight: 600; color: var(--wao-text-2); }
.ai-final-result { margin-top: 10px; padding: 10px 12px; border-radius: 9px; background: var(--wao-bg-sub); }
.ai-final-result b { font-size: 11px; }
.ai-final-result p { max-height: 180px; margin: 5px 0 0; overflow: auto; color: var(--wao-text-3); font-size: 11px; line-height: 1.65; white-space: pre-wrap; }
.ai-continue { margin-top: 10px; padding: 11px; border: 1px solid #dce2fa; border-radius: 10px; background: linear-gradient(135deg, #f7f8ff, #fff); }
.ai-continue-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.ai-continue-icon { width: 25px; height: 25px; border-radius: 8px; background: #e8ecff; color: #4358d6; display: grid; place-items: center; }
.ai-continue-head b, .ai-continue-head small { display: block; }
.ai-continue-head b { color: var(--wao-text); font-size: 11.5px; }
.ai-continue-head small { margin-top: 2px; color: var(--wao-text-weak); font-size: 10px; }
.ai-continue-input { box-sizing: border-box; width: 100%; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); }
.ai-continue-input:focus-within { border-color: var(--wao-primary); box-shadow: 0 0 0 3px rgba(67, 88, 214, .1); }
.ai-continue-input :deep(.md .md-body), .ai-continue-input :deep(.md:not(.ro) .md-body:hover), .ai-continue-input :deep(.md:not(.ro) .md-body:focus-within) { border-color: transparent; }
.ai-continue-input :deep(.md .tiptap) { padding: 8px 9px; font-size: 11.5px; line-height: 1.55; }
.ai-continue > button { float: right; margin-top: 8px; padding: 6px 10px; border: 0; border-radius: 7px; background: var(--wao-primary); color: #fff; cursor: pointer; font-size: 11px; display: inline-flex; align-items: center; gap: 5px; }
.ai-continue > button:disabled { cursor: not-allowed; opacity: .55; }
.ai-continue::after { content: ''; display: block; clear: both; }
@keyframes aiLive { 50% { opacity: .35; transform: scale(.85); } }

.comments {
  margin-top: 18px;
  border-top: 1px solid #f0f0f3;
  padding-top: 14px;
}
.comment-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.comment {
  display: flex;
  gap: 10px;
}
.c-av {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  color: #fff;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.c-main {
  flex: 1;
  min-width: 0;
}
.c-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.c-head b {
  font-size: 12.5px;
}
.c-time {
  font-size: 11px;
  color: var(--wao-text-faint);
}
.c-text {
  margin-top: 3px;
  font-size: 13px;
  color: var(--wao-text-2);
  line-height: 1.7;
}
.comment-input {
  margin-top: 12px;
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.ci-wrap {
  flex: 1;
  display: flex;
  gap: 8px;
  position: relative;
}
.ci-wrap input {
  flex: 1;
  height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 0 11px;
  font-size: 13px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.ci-wrap input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.send {
  height: 34px;
  padding: 0 13px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 9px;
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.send:hover:not(:disabled) {
  background: var(--wao-primary-hover);
}
.send i {
  font-size: 13px;
}
.mention-menu { position: absolute; z-index: 8; left: 0; right: 62px; bottom: 38px; background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); border-radius: 8px; box-shadow: 0 8px 20px rgba(16,18,28,.14); padding: 4px; }
.mention-menu button { width: 100%; border: 0; background: transparent; text-align: left; padding: 6px; display:flex; align-items:center; gap: 7px; border-radius: 6px; cursor:pointer; }
.mention-menu button:hover { background: var(--wao-primary-soft); }
.mention-menu span { width: 20px; height:20px; border-radius:50%; color:#fff; display:inline-flex; align-items:center; justify-content:center; font-size:10px; }

/* 属性栏 */
.props {
  width: 290px;
  flex-shrink: 0;
  border-left: 1px solid var(--wao-divider);
  background: var(--wao-bg-sub-2);
  overflow-y: auto;
  padding: 16px 16px 24px;
}
.menu-mask {
  position: fixed;
  inset: 0;
  z-index: 88;
}
.props-label {
  font-size: 11px;
  color: var(--wao-text-weak);
  letter-spacing: 1.5px;
  margin-bottom: 4px;
}
.props-label.sm {
  margin-bottom: 2px;
}
.prop-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 0;
  position: relative;
}
.prop-key {
  width: 60px;
  font-size: 12px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.prop-val {
  font-size: 12.5px;
  color: var(--wao-text-2);
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.prop-val i {
  font-size: 13px;
  color: var(--wao-text-weak);
}
.prop-val.due i {
  color: inherit;
}
/* 日期看着仍像一行属性文字，鼠标移上去才显出可点的边框——属性栏不该被输入框撑成表单 */
.date-input,
.datetime-input {
  flex: 1;
  min-width: 0;
  font: inherit;
  font-size: 12.5px;
  color: var(--wao-text-2);
  background: none;
  border: 1px solid transparent;
  border-radius: 6px;
  padding: 2px 6px;
  margin-left: -6px;
  cursor: pointer;
}
.due-hint {
  font-size: 11.5px;
  flex-shrink: 0;
}
.date-input:hover,
.datetime-input:hover {
  border-color: var(--wao-line-control);
}
.date-input:focus,
.datetime-input:focus {
  outline: none;
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, .12);
}
.date-input::-webkit-calendar-picker-indicator,
.datetime-input::-webkit-calendar-picker-indicator {
  cursor: pointer;
  opacity: .5;
}
.date-input:hover::-webkit-calendar-picker-indicator,
.datetime-input:hover::-webkit-calendar-picker-indicator {
  opacity: 1;
}
.pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  border: none;
  border-radius: 999px;
  padding: 3px 10px;
  cursor: pointer;
}
.pill.sq {
  border-radius: 6px;
  padding: 3px 9px;
}
.pill i {
  font-size: 13px;
}
.owner-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--wao-text);
  border: none;
  background: none;
  padding: 2px 6px;
  margin-left: -6px;
  border-radius: 7px;
  cursor: pointer;
}
.owner-btn:hover {
  background: var(--wao-bg-tab);
}
.owner-btn i {
  font-size: 13px;
  color: var(--wao-text-weak);
}
.mini-av {
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
.mini-av.bordered {
  border: 1.5px solid var(--wao-bg-sub-2);
  margin-right: -4px;
}
.parts {
  display: flex;
  align-items: center;
}
.add-part {
  width: 20px;
  height: 20px;
  border: 1px dashed #c9cdda;
  background: none;
  border-radius: 50%;
  cursor: pointer;
  color: var(--wao-text-weak);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-left: 6px;
}
.add-part:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary);
}
.add-part i {
  font-size: 11px;
}
.proj-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: none;
  color: var(--wao-primary-text);
  font-size: 12.5px;
  cursor: pointer;
  padding: 2px 6px;
  margin-left: -6px;
  border-radius: 6px;
  max-width: 190px;
}
.proj-btn:hover {
  background: #eef1fc;
}

.pmenu {
  position: absolute;
  left: 60px;
  top: 34px;
  z-index: 89;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop-2);
  padding: 5px;
  animation: waoPop 0.14s ease;
}
.pmenu.w150 {
  width: 150px;
}
.pmenu.w190 {
  width: 190px;
}
.pmenu.w130 {
  width: 130px;
}
.pmenu.up {
  top: auto;
  bottom: 34px;
  left: 0;
}
.pmenu-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 7px 8px;
  cursor: pointer;
}
.pmenu-item:hover {
  background: var(--wao-bg-hover-2);
}
.pmenu-item.plain {
  font-size: 13px;
  color: var(--wao-text);
}
.pill-mini {
  border-radius: 999px;
  padding: 2px 9px;
}
.pmenu-sub {
  flex: 1;
  text-align: left;
  font-size: 12px;
  color: var(--wao-text-3);
}
.pmenu-name {
  flex: 1;
  text-align: left;
  font-size: 13px;
}
.chk {
  font-size: 14px;
  color: var(--wao-primary);
}

.prop-section {
  margin-top: 10px;
  border-top: 1px solid var(--wao-divider);
  padding-top: 10px;
  position: relative;
}
.custom-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
}
.custom-name {
  width: 82px;
  font-size: 12px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.custom-val {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--wao-text-2);
  border: 0;
  background: transparent;
  padding: 3px;
}
.custom-name { border: 0; background: transparent; padding: 3px; min-width: 0; }
.custom-name:focus, .custom-val:focus { outline: 1px solid var(--wao-primary); border-radius: 4px; background: var(--wao-bg-card); }
.custom-delete { border: 0; background: transparent; color: var(--wao-text-faint); cursor: pointer; padding: 3px; }
.custom-delete:hover { color: #cf3b3b; }

.act-wrap {
  position: relative;
}
.act-line {
  position: absolute;
  left: 6px;
  top: 8px;
  bottom: 8px;
  width: 2px;
  background: var(--wao-divider);
}
.act-row {
  display: flex;
  gap: 10px;
  padding: 5px 0;
  position: relative;
}
.act-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--wao-bg-sub-2);
  border: 2px solid #d9dce5;
  flex-shrink: 0;
  z-index: 1;
  margin-top: 2px;
}
.act-main {
  flex: 1;
  min-width: 0;
}
.act-text {
  font-size: 12px;
  color: var(--wao-text-2);
  line-height: 1.6;
}
.act-time {
  font-size: 11px;
  color: var(--wao-text-faint);
}

/* 手机上的子任务详情：盖住整个抽屉的一页，自带顶栏 */
.props.sub-page {
  position: fixed;
  inset: 0;
  z-index: 82;
  width: auto;
  background: var(--wao-bg-card);
  overflow-y: auto;
  overscroll-behavior: contain;
  border: 0;
  padding: 0 16px calc(24px + env(safe-area-inset-bottom));
  animation: waoSubPage 0.16s ease;
}
@keyframes waoSubPage {
  from { transform: translateX(14px); opacity: 0.4; }
}
.sub-page-head {
  position: sticky;
  top: 0;
  z-index: 1;
  margin: 0 -16px;
  padding: 0 8px;
  height: 52px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--wao-bg-card);
  border-bottom: 1px solid var(--wao-line);
}
.sub-page-back {
  width: 36px;
  height: 36px;
  border: none;
  background: none;
  border-radius: 9px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.sub-page-back i {
  font-size: 20px;
}
.sub-page-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.sub-page-title b {
  font-size: 13px;
}
.sub-page-title em {
  font-style: normal;
  font-size: 11px;
  color: var(--wao-text-weak);
}
.sub-page-done {
  height: 32px;
  padding: 0 11px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 999px;
  font-size: 12.5px;
  color: var(--wao-text-3);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.sub-page-done.on {
  background: var(--wao-success-bg, #eaf8f0);
  border-color: #bfe6cf;
  color: var(--wao-success-strong);
}

@media (max-width: 760px) {
  /* 当前抽屉的主体是 .dbody / .main / .props，不要沿用旧版的 drawer-body 类名。
     手机上改为一个连续的纵向滚动区，避免主内容和属性栏同时被压成窄列。 */
  .drawer {
    width: 100%;
    max-width: none;
    height: 100dvh;
  }
  .dhead {
    height: 52px;
    gap: 4px;
    padding: 0 8px;
  }
  .proj-link {
    max-width: min(42vw, 180px);
    padding-inline: 5px;
  }
  .sep,
  .crumb,
  .wait-badge {
    display: none;
  }
  .ai-run-btn {
    width: 30px;
    padding: 0;
    justify-content: center;
    font-size: 0;
  }
  .ai-run-btn > i:first-child {
    font-size: 16px;
  }
  .ai-run-btn .caret {
    display: none;
  }
  .pause-ai-btn { width: 30px; padding: 0; justify-content: center; font-size: 0; }
  .pause-ai-btn i { font-size: 16px; }
  .share-menu {
    width: min(236px, calc(100vw - 16px));
  }
  .dbody {
    display: block;
    overflow-y: auto;
    overscroll-behavior: contain;
  }
  .main {
    overflow: visible;
    padding: 18px 16px 24px;
  }
  .title-input {
    font-size: 20px;
  }
  .props {
    width: auto;
    overflow: visible;
    border-top: 1px solid var(--wao-divider);
    border-left: none;
    padding: 16px 16px calc(20px + env(safe-area-inset-bottom));
  }
  .sub-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 5px;
  }
  .sub-add {
    width: 100%;
    box-sizing: border-box;
    background: var(--wao-bg-sub);
  }
  .check-row {
    min-height: 32px;
  }
  .sub-chev {
    opacity: 1;
  }
  .comment-input {
    align-items: flex-start;
  }
  .ci-wrap {
    flex-wrap: wrap;
  }
  .ci-wrap input {
    min-width: 0;
  }
  .send {
    margin-left: auto;
  }
}
</style>
