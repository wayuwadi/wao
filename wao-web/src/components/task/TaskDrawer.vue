<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useDeco } from '@/composables/deco'
import { statusMeta, priorityMeta } from '@/utils/domain'
import { fmtDate } from '@/utils/domain'
import { toast } from '@/composables/toast'
// 异步加载：编辑器带着 ProseMirror（gzip 约 110KB），只有真正打开任务详情时才需要，
// 不该压在首屏包里。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
import InlineSubtasks from '@/components/task/InlineSubtasks.vue'
import SubtaskProperties from '@/components/task/SubtaskProperties.vue'
import WorkItemIcon from '@/components/task/WorkItemIcon.vue'
import { AI_RUN_ENABLED, selectableTaskStatuses } from '@/config/features'
import SaveHint from '@/components/ui/SaveHint.vue'
import { chatShareLink, shareMarkdownUrl, shareUrl } from '@/utils/share'
import { copyToClipboard } from '@/utils/clipboard'
import { createAiRuns, fetchTaskAiRuns, pauseAiRun } from '@/api/ai'
import AiRunArchive from '@/components/ai/AiRunArchive.vue'
import AiRunLauncher from '@/components/ai/AiRunLauncher.vue'
import TaskRelationPicker from '@/components/task/TaskRelationPicker.vue'
import { engineLabel } from '@/composables/aiEngines'
import { useNow } from '@/composables/now'
import { absoluteTime, nowIso, relativeTime } from '@/utils/time'
import { activitySentence } from '@/utils/activity'
import { fileDisplayName } from '@/utils/files'
import { useFavoritesStore } from '@/stores/favorites'
import { nextTaskAiRunPollDelay, taskAiRunFingerprint } from '@/utils/taskAiRunPolling'
import { apiGetTask, notifyTaskManually } from '@/api/board'
import CellMenu from '@/components/task/CellMenu.vue'
import { subtaskState, SUBTASK_STATE_COLOR, SUBTASK_STATES } from '@/utils/subtaskTree'
import type { AiEngine, AiRun, Comment, Priority, SubtaskState, TaskStatus, TaskStep } from '@/types'

const ui = useUiStore()
const data = useDataStore()
const session = useSessionStore()
const favorites = useFavoritesStore()
const router = useRouter()
const route = useRoute()
const { decoTask } = useDeco()

/** 复用任务已有的持久化自定义属性，不新增一套会漂移的验收数据模型。 */
const ACCEPTANCE_FIELD_NAMES = ['成功条件', '验收证据', '端到端耗时', '异常与恢复', '通知结果'] as const

const task = computed(() => (ui.openTaskId ? data.taskById(ui.openTaskId) : undefined))
const tv = computed(() => (task.value ? decoTask(task.value) : null))
const acceptanceFieldsComplete = computed(() => Boolean(task.value)
  && ACCEPTANCE_FIELD_NAMES.every((name) => task.value!.custom.some((item) => item.name === name)))
const isAcceptanceField = (name: string) => (ACCEPTANCE_FIELD_NAMES as readonly string[]).includes(name)

const viewingServerTask = ref(false)
const serverTaskText = ref('')
async function viewServerTask() {
  if (!task.value) return
  try {
    const remote = await apiGetTask(task.value.id)
    serverTaskText.value = `${remote.title}\n状态：${remote.status} · 优先级：${remote.pr}\n\n${remote.desc ?? ''}`
    viewingServerTask.value = true
  } catch { toast('服务器版本读取失败，请重试', 'ri-error-warning-line') }
}
async function loadServerTask() {
  if (!task.value || !window.confirm('放弃本机修改并载入服务器版？请先复制需要保留的本机内容。')) return
  if (!await data.useServerTask(task.value.id)) toast('读取失败或期间有新输入，本机内容已保留，请重试')
}
watch(() => ui.openTaskId, () => { viewingServerTask.value = false; serverTaskText.value = '' })
async function copyLocalTask() {
  if (!task.value) return
  const ok = await copyToClipboard(JSON.stringify(task.value, null, 2))
  toast(ok ? '本机任务内容已复制' : '复制失败，请重试')
}

const menu = ref<'status' | 'pr' | 'owner' | 'participants' | 'prop' | 'share' | 'group' | null>(null)
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
const requestedRunId = computed(() => typeof route.query.run === 'string' ? route.query.run : '')
const aiRunsLoading = ref(false)
/** 首次打开可显示加载态；后续轮询不得反复推挤评论区。 */
const aiRunsLoaded = ref(false)
const pausingRunId = ref<string | null>(null)
const notifyingTask = ref(false)
let aiRunsTimer: ReturnType<typeof setTimeout> | null = null
let aiRunsUnchangedRounds = 0
let lastAiRunsFingerprint = ''
let aiRunsRequestTaskId: string | null = null
let aiRunsRequestGeneration = 0
let aiRunsPollingStopped = false
/** 当前在弹框里查看的子任务；为 null 时查看主任务。与 ui.openTaskSubId 同步，面包屑与列表都通过 ui.openTask 切换。 */
const openSubId = ref<string | null>(null)

function clearAiRunsTimer() {
  if (aiRunsTimer) clearTimeout(aiRunsTimer)
  aiRunsTimer = null
}

watch(
  () => ui.openTaskId,
  () => {
    menu.value = null
    commentText.value = ''
    openSubId.value = null
    aiLauncherOpen.value = false
    aiLauncherSubId.value = null
    relationOpen.value = false
    editingComment.value = null
    confirmingDelete.value = false
    aiRuns.value = []
    aiRunsLoaded.value = false
    aiRunsUnchangedRounds = 0
    lastAiRunsFingerprint = ''
    clearAiRunsTimer()
  },
)

async function refreshTaskAiRuns() {
  const taskId = ui.openTaskId
  // AI Run 入口下线期间不再轮询运行档案，接口与逻辑保留，开关打开即恢复。
  if (!AI_RUN_ENABLED || !taskId) {
    aiRuns.value = []
    return
  }
  // 同一任务的慢请求尚未结束时不再叠一层；切换任务则允许新任务立即读取，旧响应会被代次丢弃。
  if (aiRunsRequestTaskId === taskId) return
  clearAiRunsTimer()
  const generation = ++aiRunsRequestGeneration
  aiRunsRequestTaskId = taskId
  aiRunsLoading.value = true
  let failed = false
  try {
    const next = await fetchTaskAiRuns(taskId)
    if (ui.openTaskId === taskId && generation === aiRunsRequestGeneration) {
      const fingerprint = taskAiRunFingerprint(next)
      aiRunsUnchangedRounds = fingerprint === lastAiRunsFingerprint
        ? aiRunsUnchangedRounds + 1
        : 0
      lastAiRunsFingerprint = fingerprint
      aiRuns.value = next
      data.applyAiRunStatuses(next)
    }
  } catch {
    failed = true
    aiRunsUnchangedRounds = 0
    // 保留上一帧，网络抖动不应让已展示的 AI 档案消失。
  } finally {
    if (generation === aiRunsRequestGeneration) {
      aiRunsRequestTaskId = null
    }
    if (ui.openTaskId === taskId && generation === aiRunsRequestGeneration) {
      aiRunsLoading.value = false
      // 请求失败同样结束首次加载态；下一轮会重试，但页面布局保持稳定。
      aiRunsLoaded.value = true
      // setTimeout 在本次请求结束后才启动，P95 超过轮询间隔时也不会堆积并发请求。
      if (!aiRunsPollingStopped && !document.hidden) {
        aiRunsTimer = setTimeout(
          () => void refreshTaskAiRuns(),
          nextTaskAiRunPollDelay(aiRuns.value, aiRunsUnchangedRounds, failed),
        )
      }
    }
  }
}

watch(() => ui.openTaskId, (taskId) => {
  void refreshTaskAiRuns()
  if (taskId) void data.ensureDocsLoaded()
}, { immediate: true })

watch(() => [ui.openTaskId, ui.openTaskSubId], () => {
  openSubId.value = ui.openTaskSubId ?? null
}, { flush: 'post', immediate: true })

const activeAiRun = computed(() => aiRuns.value.find((run) => ['queued', 'claimed', 'running'].includes(run.status)))
/** 已经单独派过 AI 工的子任务：行上标一下，分享给 AI 时它们也会被自动摘掉。 */
const dispatchedSubIds = computed(
  () => new Set(aiRuns.value.map((run) => run.subId).filter((id): id is string => !!id)),
)
/** 手机子任务详情里的派工状态与列表行共用同一份运行档案，避免出现两个可重复点击的入口。 */
const openSubDispatched = computed(() => !!openSubId.value && dispatchedSubIds.value.has(openSubId.value))
/** 子任务详情只展示属于当前子任务的真实运行，避免混入整条任务和其它子任务的档案。 */
const openSubAiRuns = computed(() => openSubId.value
  ? aiRuns.value.filter((run) => run.subId === openSubId.value)
  : [])

async function pauseRun(runId: string) {
  if (pausingRunId.value) return
  if (!window.confirm('停止当前 AI 任务？执行电脑会终止当前进程，已产生的未提交结果可能丢失。')) return
  pausingRunId.value = runId
  try {
    await pauseAiRun(runId)
    toast('已停止 AI 任务，执行电脑正在结束当前进程', 'ri-stop-circle-line')
    await refreshTaskAiRuns()
  } catch (error) {
    toast(error instanceof Error ? error.message : '停止失败，请重试', 'ri-error-warning-line')
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

/** 评论跟着当前查看的对象走：看子任务时是子任务自己的评论，否则是主任务的评论。 */
const commentSource = computed<Comment[]>(() => (openSub.value ? openSub.value.comments ?? [] : task.value?.comments ?? []))
const comments = computed(() =>
  commentSource.value.map((c, index) => {
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
/** 子任务评论随任务全文保存；只能改删自己的，与主任务评论同一规则。 */
function writeSubComments(next: Comment[]) {
  const t = task.value
  if (t && openSubId.value) data.updateSub(t.id, openSubId.value, { comments: next })
}
async function saveComment() {
  const t = task.value
  const index = editingComment.value
  if (!t || index === null) return
  if (openSub.value) {
    const text = editingText.value.trim()
    if (text) writeSubComments(commentSource.value.map((item, i) => (i === index ? { ...item, text } : item)))
    cancelEditComment()
    return
  }
  const ok = await data.updateTaskComment(t.id, index, editingText.value)
  cancelEditComment()
  toast(ok ? '评论已更新' : '评论修改失败，请重试', ok ? 'ri-check-line' : 'ri-error-warning-line')
}
async function removeComment(index: number) {
  const t = task.value
  if (!t) return
  if (!window.confirm('删除这条评论？此操作不可恢复。')) return
  if (editingComment.value === index) cancelEditComment()
  if (openSub.value) {
    writeSubComments(commentSource.value.filter((_, i) => i !== index))
    toast('评论已删除', 'ri-delete-bin-line')
    return
  }
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
/** 关联文档跟着当前查看的对象走：子任务有自己的关联文档。 */
const currentDocIds = computed(() => (openSub.value ? openSub.value.docs ?? [] : task.value?.docs ?? []))
const linkedDocs = computed(() =>
  currentDocIds.value.flatMap((id) => {
    const document = data.docById(id)
    return document && !data.isDocArchived(id) ? [{ id, title: document.title }] : []
  }),
)
const manualHostedPages = computed(() => data.hostedPages.filter((page) =>
  (page.sourceType ?? (page.id.startsWith('run-report-') ? 'AI_RUN_REPORT' : 'MANUAL_UPLOAD')) === 'MANUAL_UPLOAD'))
const linkedHostedPages = computed(() => (task.value?.hostedPages ?? []).map((id) => {
  const page = data.hostedPages.find((item) => item.id === id)
  return { id, title: page?.title ?? 'HTML 已删除或暂不可见', url: page?.url ?? null }
}))
const relationDocs = computed(() => Object.values(data.docs).filter((doc) => !data.isDocArchived(doc.id)).map((doc) => {
  const kb = data.kbs.find((item) => item.id === doc.kbId || item.folders.some((folder) => folder.docs.includes(doc.id)))
  return { id: doc.id, title: doc.title, kbName: kb?.name ?? '知识文档', createdAt: doc.created }
}))
const relationTasks = computed(() => data.tasks
  .filter((item) => item.id !== task.value?.id && item.status !== '已完成')
  .map((item) => ({
    id: item.id,
    title: item.title,
    projectName: data.projectById(item.project)?.name ?? '未关联项目',
    createdAt: item.createdAt,
  })))
// 没有稳定 id 的历史展示附件并不存在可下载正文，不能把它伪装成可关联文件。
const relationFiles = computed(() => data.files
  .filter((file) => typeof file.id === 'string' && file.id.length > 0)
  .map((file) => ({
    id: file.id!, name: fileDisplayName(file), size: file.size, icon: file.icon, createdAt: file.createdAt,
  })))
const selectedFileIds = computed(() => (task.value?.files ?? [])
  .map((file) => file.id)
  .filter((id): id is string => !!id))
const taskFileDisplayName = (file: { id?: string | null; name: string }) => {
  const current = file.id ? data.files.find((item) => item.id === file.id) : undefined
  return current ? fileDisplayName(current) : file.name
}
async function openRelationPicker() {
  relationOpen.value = true
  await data.refreshHostedPages()
}
const saveState = computed(() => (task.value ? data.taskSaveState(task.value.id) : 'idle'))
/** 上一次写透成功的时刻，给「自动保存成功 14:32」用。 */
const savedAt = computed(() => (task.value ? data.taskSavedAt(task.value.id) : null))

const statusOptions = computed<TaskStatus[]>(() => selectableTaskStatuses(task.value?.status))
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
/**
 * 弹框里的浏览足迹：从表格点开时为空；在弹框里点开下一级（子任务、子任务的子任务、关联任务）才入栈。
 * Esc 按足迹往回退一层——从表格直接点开的深层子任务按 Esc 回到表格；从子任务里再点开的下级，
 * 按 Esc 回到上一个子任务。点面包屑回到足迹里已有的一层时，视为后退，截掉之后的足迹。
 */
const visitTrail: { taskId: string; subId: string | null }[] = []
let steppingBack = false
watch(() => [ui.openTaskId, ui.openTaskSubId] as const, ([taskId, subId], [prevTaskId, prevSubId]) => {
  if (!taskId) {
    visitTrail.length = 0
    return
  }
  if (steppingBack) {
    steppingBack = false
    return
  }
  const at = visitTrail.findIndex((item) => item.taskId === taskId && item.subId === (subId ?? null))
  if (at >= 0) visitTrail.splice(at)
  else if (prevTaskId) visitTrail.push({ taskId: prevTaskId, subId: prevSubId ?? null })
})
/** Esc：先关弹出的菜单，再按足迹退回上一层，没有上一层才关闭弹框回到表格。 */
function onKey(e: KeyboardEvent) {
  if (e.key !== 'Escape' || !task.value || e.defaultPrevented || ui.chatOpen) return
  if (relationOpen.value) relationOpen.value = false
  else if (menu.value) menu.value = null
  else if (subStateOpen.value) subStateOpen.value = false
  else {
    const previous = visitTrail.pop()
    if (!previous) return close()
    if (previous.taskId !== ui.openTaskId || previous.subId !== (ui.openTaskSubId ?? null)) steppingBack = true
    if (previous.taskId !== ui.openTaskId) data.flushTask(ui.openTaskId!)
    ui.openTask(previous.taskId, previous.subId ?? undefined)
  }
}
function syncTaskAiRunsImmediately() {
  if (document.hidden || !ui.openTaskId) return
  aiRunsUnchangedRounds = 0
  clearAiRunsTimer()
  void refreshTaskAiRuns()
}
function onTaskAiRunsVisibilityChange() {
  if (document.hidden) clearAiRunsTimer()
  else syncTaskAiRunsImmediately()
}
onMounted(() => {
  aiRunsPollingStopped = false
  window.addEventListener('keydown', onKey)
  window.addEventListener('focus', syncTaskAiRunsImmediately)
  document.addEventListener('visibilitychange', onTaskAiRunsVisibilityChange)
})
onBeforeUnmount(() => {
  aiRunsPollingStopped = true
  aiRunsRequestGeneration += 1
  aiRunsRequestTaskId = null
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('focus', syncTaskAiRunsImmediately)
  document.removeEventListener('visibilitychange', onTaskAiRunsVisibilityChange)
  clearAiRunsTimer()
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
// ---- 子任务详情：与主任务同一版式，在弹框主区展示 ----

/** 回到主任务视图。走 ui.openTask 而不是只改本地状态，再次点开同一子任务时才会响应。 */
function backToTask() {
  if (task.value) ui.openTask(task.value.id)
}
const openSub = computed(() =>
  task.value && openSubId.value ? data.subById(task.value.id, openSubId.value) : undefined,
)

/** 面包屑：主任务 → 各级父子任务 → 当前子任务，按 parentId 向上追溯。 */
const subChain = computed(() => {
  const t = task.value
  if (!t || !openSubId.value) return []
  const byId = new Map<string, TaskStep>(t.subs.map((sub, idx) => [data.subKey(t.id, sub, idx), sub]))
  const chain: { id: string; number?: number; text: string }[] = []
  const seen = new Set<string>()
  let id: string | null | undefined = openSubId.value
  while (id && byId.has(id) && !seen.has(id)) {
    seen.add(id)
    const sub: TaskStep = byId.get(id)!
    chain.unshift({ id, number: sub.number, text: sub.text })
    id = sub.parentId
  }
  return chain
})
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
const subStateOpen = ref(false)
const subStateAnchor = ref<HTMLElement | null>(null)
function setSubState(state: SubtaskState) {
  const t = task.value
  if (t && openSubId.value) data.setSubState(t.id, openSubId.value, state)
  subStateOpen.value = false
}
const subCreator = computed(() => (openSub.value?.createdBy ? session.memberById(openSub.value.createdBy) : null))
const onSubImage = (file: File, done: (url: string | null, name: string) => void) =>
  uploadInline(file, done, `子任务 · ${openSub.value?.text ?? ''}`)

function removeSubImage(idx: number) {
  const t = task.value
  if (t && openSubId.value) data.removeSubImage(t.id, openSubId.value, idx)
}
function deleteSub() {
  const t = task.value
  const sub = openSub.value
  if (!t || !openSubId.value || !sub) return
  if (!window.confirm(`删除子任务「${sub.text}」？其描述和执行记录关联也会一并移除。`)) return
  data.removeSub(t.id, openSubId.value)
  backToTask()
  toast('子任务已删除', 'ri-delete-bin-line')
}

// ---- 分享链接 ----

/** 分享时是否过滤掉已完成的子任务（只对主任务链接有意义）。 */
const shareOpenOnly = ref(false)
const shareDoneCount = computed(() => (task.value?.subs ?? []).filter((s) => s.done).length)

/** 把当前任务分享追加到聊天草稿，不离开任务编辑现场。 */
function shareToChat() {
  const t = task.value
  if (!t) return
  const sub = t.subs.find((item) => item.id === openSubId.value)
  const title = (sub?.text || t.title).replace(/[\\[\]`*_<>]/g, ' ').replace(/\s+/g, ' ')
  ui.shareToChat(chatShareLink(`${sub ? '子任务' : '任务'}：${title}`, shareUrl(t.id, openSubId.value, !sub && shareOpenOnly.value)))
  menu.value = null
}

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

/** 复制任务的稳定标识，方便用户在对话里准确告诉 AI 要处理哪一条任务。 */
async function copyJobId() {
  const id = task.value?.id
  if (!id) return
  const copied = await copyToClipboard(id)
  toast(
    copied ? `JOBID ${id} 已复制` : `复制失败，请手动复制 JOBID：${id}`,
    copied ? 'ri-file-copy-line' : 'ri-error-warning-line',
  )
}

async function notifyTask() {
  const id = task.value?.id
  if (!id || notifyingTask.value) return
  notifyingTask.value = true
  try {
    const sent = await notifyTaskManually(id)
    toast(sent ? '任务已通知到 WAO' : '通知未发送，请检查通知策略和项目静音',
      sent ? 'ri-notification-3-line' : 'ri-error-warning-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '人工通知失败，请重试', 'ri-error-warning-line')
  } finally {
    notifyingTask.value = false
  }
}
async function runWithAi(engine: AiEngine, runnerId: string | null, accountId: string | null,
  model: string | null, reasoningEffort: string | null, planMode: boolean, goalMode: boolean) {
  const t = task.value
  if (!t || aiStarting.value) return
  // 子任务派工是独立的一条运行，任务整体正在跑也不该拦着
  const subId = aiLauncherSubId.value
  if (!subId && t.status === 'AI 进行中') return
  aiStarting.value = true
  try {
    const created = await createAiRuns([t.id], {
      subId, engine, runnerId, accountId, model: model || null, reasoningEffort: reasoningEffort || null,
      planMode, goalMode,
    })
    if (created.length) {
      // 接口响应本身就是已落库的真实运行，先显示它，再由轮询补齐后续状态。
      // 这样刚派工后不必等待第二次网络请求，慢网或短暂抖动下也不会出现“已派工但没有记录”。
      aiRuns.value = [...created, ...aiRuns.value.filter((run) => !created.some((item) => item.id === run.id))]
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

function runOpenSubWithAi() {
  if (openSubId.value) runSubWithAi(openSubId.value)
}

/** 选择器上要写清楚这次到底跑什么，否则从子任务点开会以为要跑整个任务。 */
const aiLauncherScope = computed(() => {
  const key = aiLauncherSubId.value
  if (!key) return '整个任务'
  const sub = task.value ? data.subById(task.value.id, key) : undefined
  return `子任务「${sub?.text ?? ''}」（带主任务与已完成子任务历史）`
})
async function sendComment() {
  if (sendingComment.value) return
  const t = task.value
  const v = commentText.value.trim()
  if (!t || !v) return
  if (openSub.value) {
    writeSubComments([...commentSource.value, { id: `c${Date.now()}`, who: session.currentUser.id, time: nowIso(), text: v }])
    commentText.value = ''
    return
  }
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

function toggleParticipant(id: string) {
  const t = task.value
  if (!t) return
  const currentParts = t.parts ?? []
  const selected = currentParts.includes(id)
  const memberName = session.memberById(id).name
  data.patchTask(t.id, {
    parts: selected ? currentParts.filter((memberId) => memberId !== id) : [...currentParts, id],
  }, `${selected ? '移除了' : '添加了'}参与人「${memberName}」`)
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
function addAcceptanceFields() {
  const t = task.value
  if (!t) return
  const existing = new Set(t.custom.map((item) => item.name))
  const missing = ACCEPTANCE_FIELD_NAMES.filter((name) => !existing.has(name))
  if (!missing.length) return
  data.patchTask(t.id, {
    custom: [...t.custom, ...missing.map((name) => ({ name, value: '' }))],
  }, '补齐了任务验收记录字段')
  toast(`已补齐 ${missing.length} 个验收字段，请按真实结果填写`, 'ri-clipboard-line')
}
function updateCustom(index: number, field: 'name' | 'value', event: Event) {
  const t = task.value
  if (!t) return
  const value = (event.target as HTMLInputElement).value.trim()
  const current = t.custom[index]
  if (!current || (field === 'name' && !value) || value === current[field]) return
  const custom = t.custom.map((item, i) => i === index ? { ...item, [field]: value } : item)
  data.patchTask(t.id, { custom }, '修改了自定义属性')
}
function removeCustom(index: number) {
  const t = task.value
  const item = t?.custom[index]
  if (!t || !item) return
  if (!window.confirm(`删除自定义属性「${item.name}」？`)) return
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
function openHostedPage(url: string | null) {
  if (!url) return
  window.open(url, '_blank', 'noopener,noreferrer')
}
function removeAttachedFile(index: number) {
  const t = task.value
  const file = t?.files[index]
  if (!t || !file) return
  data.patchTask(t.id, { files: t.files.filter((_, itemIndex) => itemIndex !== index) },
    `取消关联了文件「${taskFileDisplayName(file)}」`)
}
function toggleRelation(type: 'doc' | 'task' | 'file' | 'page', id: string) {
  const t = task.value
  if (!t) return
  if (type === 'doc' && openSub.value && openSubId.value) {
    const docs = openSub.value.docs ?? []
    const selected = docs.includes(id)
    data.updateSub(t.id, openSubId.value, { docs: selected ? docs.filter((item) => item !== id) : [...docs, id] })
    return
  }
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
  if (type === 'page') {
    const selected = (t.hostedPages ?? []).includes(id)
    const title = data.hostedPages.find((item) => item.id === id)?.title ?? 'HTML'
    data.patchTask(t.id, {
      hostedPages: selected
        ? (t.hostedPages ?? []).filter((item) => item !== id)
        : [...(t.hostedPages ?? []), id],
    }, `${selected ? '取消关联' : '关联了'} HTML「${title}」`)
    return
  }
  const file = data.files.find((item) => item.id === id)
  if (!file) return
  const selected = t.files.some((item) => item.id === id)
  const displayName = fileDisplayName(file)
  data.patchTask(t.id, {
    files: selected
      ? t.files.filter((item) => item.id !== id)
      : [...t.files, { id, name: displayName, size: file.size, icon: file.icon }],
  }, `${selected ? '取消关联' : '关联了'}文件「${displayName}」`)
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
</script>

<template>
  <template v-if="task && tv">
    <div class="mask" @click="close"></div>
    <aside class="drawer" role="dialog" aria-modal="true" :aria-label="openSub ? `子任务 ${openSub.text}` : `任务 ${task.title}`" :inert="data.movingSubtasks">
      <!-- 顶栏 -->
      <div class="dhead">
        <nav class="crumbs" aria-label="任务层级">
          <button class="proj-link" @click="gotoProject">
            <span class="pdot" :style="{ background: tv.projColor }"></span>
            <span class="ellip">{{ tv.projName }}</span>
          </button>
          <i class="ri-arrow-right-s-line sep"></i>
          <button class="crumb-item" :class="{ current: !openSub }" :title="task.title" @click="backToTask">
            <WorkItemIcon kind="task" /><span class="wao-tabular">#{{ task.number ?? '…' }}</span>
          </button>
          <template v-for="item in subChain" :key="item.id">
            <i class="ri-arrow-right-s-line sep"></i>
            <button class="crumb-item" :class="{ current: item.id === openSubId }" :title="item.text" @click="ui.openTask(task.id, item.id)">
              <WorkItemIcon kind="subtask" /><span class="wao-tabular">#{{ item.number ?? '…' }}</span>
            </button>
          </template>
        </nav>
        <span v-if="task.waiting" class="wait-badge">{{ task.waiting }}</span>
        <div class="spacer"></div>
        <template v-if="AI_RUN_ENABLED">
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
          title="停止当前 AI 任务"
          @click="pauseRun(activeAiRun.id)"
        >
          <i :class="pausingRunId === activeAiRun.id ? 'ri-loader-4-line spin' : 'ri-stop-circle-line'"></i>
          {{ pausingRunId === activeAiRun.id ? '停止中' : '停止 AI' }}
        </button>
        </template>
        <button
          class="icon-btn"
          :class="{ faved: favorites.isFav('task', task.id) }"
          :title="favorites.isFav('task', task.id) ? '取消收藏' : '收藏到左侧菜单'"
          @click="favorites.toggle('task', task.id, task.title, task.project)"
        >
          <i :class="favorites.isFav('task', task.id) ? 'ri-star-fill' : 'ri-star-line'"></i>
        </button>
        <button class="icon-btn" :disabled="notifyingTask" title="通知到 WAO" @click="notifyTask">
          <i :class="notifyingTask ? 'ri-loader-4-line spin' : 'ri-notification-2-line'"></i>
        </button>
        <div class="share-wrap">
          <button class="icon-btn" title="复制分享链接" @click="menu = menu === 'share' ? null : 'share'"><i class="ri-links-line"></i></button>
          <div v-if="menu === 'share'" class="menu-mask" @click="menu = null"></div>
          <div v-if="menu === 'share'" class="share-menu">
            <div class="share-scope">
              {{ openSubId ? '分享范围：当前子任务' : shareOpenOnly ? '分享范围：本任务 + 还没人做的子任务' : '分享范围：本任务 + 全部子任务' }}
            </div>
            <label v-if="!openSubId && (shareDoneCount || (AI_RUN_ENABLED && dispatchedSubIds.size))" class="share-filter">
              <input v-model="shareOpenOnly" type="checkbox" />
              <span class="share-filter-main">
                <b>只分享还没人做的子任务</b>
                <em>过滤掉已完成的 {{ shareDoneCount }} 条{{ AI_RUN_ENABLED && dispatchedSubIds.size ? `和已单独派给 AI 的 ${dispatchedSubIds.size} 条` : '' }}，避免重复派工</em>
              </span>
            </label>
            <button class="share-item" @click="shareToChat">
              <i class="ri-chat-forward-line"></i>
              <span class="share-main"><b>分享到聊天</b><em>放入聊天草稿，可补充说明后发送</em></span>
            </button>
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

      <div v-if="data.taskConflicts[task.id]" class="task-save-conflict" role="alert">
        任务已在其他窗口更新，本机内容已保留，自动保存已暂停。
        <button type="button" @click="viewServerTask">查看服务器版</button>
        <button type="button" @click="copyLocalTask">复制本机内容</button>
        <button type="button" @click="loadServerTask">放弃本机修改并载入服务器版</button>
        <pre v-if="viewingServerTask">{{ serverTaskText }}</pre>
      </div>
      <div class="dbody">
        <!-- 主区 -->
        <div v-if="openSub" class="main">
          <div class="type-line">
            <WorkItemIcon kind="subtask" :size="18" />子任务
            <span class="type-parent">属于 <button @click="backToTask">#{{ task.number ?? '…' }} {{ task.title }}</button></span>
          </div>
          <textarea
            ref="subTitleArea"
            class="title-input"
            :class="{ done: openSub.done }"
            rows="1"
            :value="openSub.text"
            placeholder="子任务名称"
            @input="editSubTitle"
            @keydown.enter.prevent
          ></textarea>

          <div class="field">
            <div class="field-headline">
              <div class="field-label">描述</div>
              <SaveHint :saving="saveState === 'saving'" :saved-at="savedAt" :failed="saveState === 'error'" />
            </div>
            <MarkdownEditor
              v-model="subDesc"
              placeholder="补充这个子任务的做法、验收标准…敲 ## 变标题、- 变列表，可直接粘贴截图"
              :min-height="200"
              @upload-image="onSubImage"
            />
          </div>

          <!-- 历史数据里挂在子任务上的图片；新粘贴的图现在直接进描述正文 -->
          <div v-if="openSub.images?.length" class="field">
            <div class="field-label">图片</div>
            <div class="img-grid">
              <div v-for="(im, i) in openSub.images" :key="i" class="img-cell">
                <img :src="im.url" :alt="im.name" @click="viewImage(im.url)" />
                <button class="img-del" title="移除" @click="removeSubImage(i)"><i class="ri-close-line"></i></button>
              </div>
            </div>
          </div>

          <div class="field">
            <InlineSubtasks :task-id="task.id" :parent-id="openSubId" />
          </div>

          <AiRunArchive
            v-if="AI_RUN_ENABLED"
            class="sub-ai-archive"
            :runs="openSubAiRuns"
            :loading="aiRunsLoading"
            :loaded="aiRunsLoaded"
            :requested-run-id="requestedRunId"
            :show-scope="false"
            @refresh="refreshTaskAiRuns"
            @upload-image="onContinueImage"
          />
        </div>

        <div v-else class="main">
          <div class="type-line"><WorkItemIcon kind="task" :size="18" />任务</div>
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
            <InlineSubtasks :task-id="task.id" />
          </div>

          <div v-if="task.files.length" class="field">
            <div class="field-label">附件</div>
            <div class="attach-list">
              <div
                v-for="(f, i) in task.files"
                :key="f.id ?? i"
                class="attach"
                :class="{ legacy: !f.id }"
              >
                <button
                  type="button"
                  class="attach-open"
                  :disabled="!f.id"
                  :title="f.id ? '打开真实文件下载页' : '历史附件没有可下载的文件正文'"
                  @click="openLinkedFile(f.id)"
                >
                  <i :class="f.icon"></i>
                  <span class="attach-name ellip">{{ taskFileDisplayName(f) }}</span>
                  <span class="attach-size">{{ f.id ? f.size : `${f.size} · 仅历史记录` }}</span>
                  <i :class="f.id ? 'ri-arrow-right-s-line dl' : 'ri-information-line dl'"></i>
                </button>
                <button class="relation-unlink" :aria-label="`取消关联文件 ${taskFileDisplayName(f)}`" title="取消关联" @click="removeAttachedFile(i)"><i class="ri-link-unlink-m"></i></button>
              </div>
            </div>
            <button class="text-btn" :disabled="attachmentUploading" @click="attachmentInput?.click()">
              <i :class="attachmentUploading ? 'ri-loader-4-line spin' : 'ri-upload-2-line'"></i>
              {{ attachmentUploading ? '上传中…' : '上传并关联附件' }}
            </button>
            <input ref="attachmentInput" type="file" hidden @change="uploadAttachment" />
          </div>

          <AiRunArchive
            v-if="AI_RUN_ENABLED"
            :runs="aiRuns"
            :loading="aiRunsLoading"
            :loaded="aiRunsLoaded"
            :requested-run-id="requestedRunId"
            @refresh="refreshTaskAiRuns"
            @upload-image="onContinueImage"
          />

        </div>

        <!-- 子任务属性：与任务属性栏同一位置、同一版式 -->
        <div v-if="openSub" class="props">
          <div class="props-label">子任务属性</div>
          <div class="prop-row">
            <span class="prop-key">编号</span>
            <span class="prop-val wao-tabular">#{{ openSub.number ?? '…' }}</span>
          </div>
          <div class="prop-row">
            <span class="prop-key">状态</span>
            <button
              ref="subStateAnchor"
              class="state-pill"
              :style="{ color: SUBTASK_STATE_COLOR[subtaskState(openSub)], borderColor: SUBTASK_STATE_COLOR[subtaskState(openSub)] }"
              aria-haspopup="menu"
              :aria-expanded="subStateOpen"
              @click="subStateOpen = !subStateOpen"
            >
              {{ subtaskState(openSub) }}<i class="ri-arrow-down-s-line"></i>
            </button>
            <CellMenu v-if="subStateOpen" :anchor="subStateAnchor" :min-width="110" @close="subStateOpen = false">
              <button v-for="state in SUBTASK_STATES" :key="state" role="menuitemradio" :aria-checked="subtaskState(openSub) === state" :class="{ on: subtaskState(openSub) === state }" @click="setSubState(state)">
                <span class="state-pill sm" :style="{ color: SUBTASK_STATE_COLOR[state], borderColor: SUBTASK_STATE_COLOR[state] }">{{ state }}</span>
                <i v-if="subtaskState(openSub) === state" class="ri-check-line"></i>
              </button>
            </CellMenu>
          </div>
          <div class="prop-row">
            <span class="prop-key">所属任务</span>
            <button class="proj-btn" :title="task.title" @click="backToTask"><WorkItemIcon kind="task" :size="14" /><span class="ellip">{{ task.title }}</span></button>
          </div>
          <SubtaskProperties :task-id="task.id" :sub-id="openSubId!" />
          <div class="prop-row">
            <span class="prop-key">创建者</span>
            <span class="prop-val">
              <template v-if="subCreator"><span class="mini-av" :style="{ background: subCreator.color }">{{ subCreator.initial }}</span>{{ subCreator.name }}</template>
              <span v-else class="parts-empty">历史子任务未记录</span>
            </span>
          </div>
          <div v-if="openSub.createdAt" class="prop-row">
            <span class="prop-key">创建日期</span>
            <span class="prop-val wao-tabular">{{ openSub.createdAt.slice(0, 10).replace(/-/g, '/') }}</span>
          </div>
          <!-- 关联：任务与子任务都能关联文档，放在属性栏里随手可见 -->
          <div class="prop-section side-relations">
            <div class="props-label sm">关联</div>
            <div class="attach-list">
              <div v-for="ld in linkedDocs" :key="ld.id" class="attach link" @click="openDoc(ld.id)">
                <i class="ri-book-2-line" style="color: #7a5cc6"></i>
                <span class="attach-name ellip">{{ ld.title }}</span>
                <button class="relation-unlink" :aria-label="`取消关联知识文档 ${ld.title}`" title="取消关联" @click.stop="toggleRelation('doc', ld.id)"><i class="ri-link-unlink-m"></i></button>
              </div>
              <template v-if="!openSub">
                <div v-for="lk in linkTasks" :key="lk.id" class="attach link" @click="openLinkTask(lk.id)">
                  <i class="ri-checkbox-circle-line" style="color: #5a5e6b"></i>
                  <span class="attach-name ellip">{{ lk.title }}</span>
                  <span class="mini" :style="{ background: lk.stBg, color: lk.stColor }">{{ lk.status }}</span>
                  <button class="relation-unlink" :aria-label="`取消关联任务 ${lk.title}`" title="取消关联" @click.stop="toggleRelation('task', lk.id)"><i class="ri-link-unlink-m"></i></button>
                </div>
                <div v-for="page in linkedHostedPages" :key="page.id" class="attach link" :class="{ legacy: !page.url }" @click="openHostedPage(page.url)">
                  <i class="ri-html5-line" style="color: #d55c22"></i>
                  <span class="attach-name ellip">{{ page.title }}</span>
                  <button class="relation-unlink" :aria-label="`取消关联 HTML ${page.title}`" title="取消关联" @click.stop="toggleRelation('page', page.id)"><i class="ri-link-unlink-m"></i></button>
                </div>
              </template>
            </div>
            <button class="text-btn small" @click="openRelationPicker"><i class="ri-links-line"></i>{{ openSub ? '关联知识文档' : '关联知识、任务、文件或 HTML' }}</button>
          </div>

          <!-- 评论：跟着当前查看的任务 / 子任务 -->
          <div class="prop-section side-comments">
            <div class="props-label sm">评论<span v-if="comments.length" class="side-count wao-tabular">{{ comments.length }}</span></div>
            <div class="comment-list">
              <div v-for="cm in comments" :key="cm.index" class="comment">
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
                    <textarea v-model="editingText" class="c-edit-input" rows="2" @keydown.esc.stop="cancelEditComment"></textarea>
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
              <div class="ci-wrap">
                <input v-model="commentText" :disabled="sendingComment" :placeholder="openSub ? '写评论，回车发送' : '写评论，回车发送；@成员名 会通知对方'" @focus="mentionOpen = true" @input="mentionOpen = true" @keydown="commentKey" />
                <div v-if="!openSub && mentionOpen && mentionCandidates.length" class="mention-menu">
                  <button v-for="member in mentionCandidates" :key="member.id" type="button" @mousedown.prevent="insertMention(member.name)">
                    <span :style="{ background: member.color }">{{ member.initial }}</span>{{ member.name }}
                  </button>
                </div>
                <button class="send" :disabled="!commentText.trim() || sendingComment" :aria-label="sendingComment ? '发送中' : '发送评论'" @click="sendComment"><i :class="sendingComment ? 'ri-loader-4-line spin' : 'ri-send-plane-2-line'"></i></button>
              </div>
            </div>
          </div>

          <div v-if="AI_RUN_ENABLED && !openSub.done" class="prop-section">
            <button v-if="openSubDispatched" class="sub-detail-action" type="button" disabled>
              <i class="ri-robot-2-line"></i>已派给 AI
            </button>
            <button v-else class="sub-detail-action" type="button" @click="runOpenSubWithAi">
              <i class="ri-robot-2-line"></i>交给 AI 跑
            </button>
          </div>
          <div class="prop-section sub-detail-actions">
            <button class="sub-detail-action" type="button" @click="copyShare('page')">
              <i class="ri-links-line"></i>复制该子任务分享链接
            </button>
            <button class="sub-detail-action danger" type="button" @click="deleteSub">
              <i class="ri-delete-bin-line"></i>删除子任务
            </button>
          </div>
        </div>

                <!-- 属性栏 -->
        <div v-else class="props">
          <div v-if="menu && menu !== 'share'" class="menu-mask" @click="menu = null"></div>
          <div class="props-label">属性</div>

          <div class="prop-row">
            <span class="prop-key">编号</span>
            <button class="job-id-copy" type="button" :title="`与子任务一样显示井号编号；点击复制 JOBID ${task.id}`" @click="copyJobId">
              <span class="wao-tabular">#{{ task.number ?? '…' }}</span><i class="ri-file-copy-line"></i>
            </button>
          </div>

          <div class="prop-row">
            <span class="prop-key">状态</span>
            <button class="pill" :style="{ background: tv.stBg, color: tv.stColor }" @click="menu = menu === 'status' ? null : 'status'">{{ tv.status }}<i class="ri-arrow-down-s-line"></i></button>
            <div v-if="menu === 'status'" class="pmenu w150">
              <button v-for="o in statusOptions" :key="o" class="pmenu-item" @click="setStatus(o)">
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
          <!-- 关联：任务与子任务都能关联文档，放在属性栏里随手可见 -->
          <div class="prop-section side-relations">
            <div class="props-label sm">关联</div>
            <div class="attach-list">
              <div v-for="ld in linkedDocs" :key="ld.id" class="attach link" @click="openDoc(ld.id)">
                <i class="ri-book-2-line" style="color: #7a5cc6"></i>
                <span class="attach-name ellip">{{ ld.title }}</span>
                <button class="relation-unlink" :aria-label="`取消关联知识文档 ${ld.title}`" title="取消关联" @click.stop="toggleRelation('doc', ld.id)"><i class="ri-link-unlink-m"></i></button>
              </div>
              <template v-if="!openSub">
                <div v-for="lk in linkTasks" :key="lk.id" class="attach link" @click="openLinkTask(lk.id)">
                  <i class="ri-checkbox-circle-line" style="color: #5a5e6b"></i>
                  <span class="attach-name ellip">{{ lk.title }}</span>
                  <span class="mini" :style="{ background: lk.stBg, color: lk.stColor }">{{ lk.status }}</span>
                  <button class="relation-unlink" :aria-label="`取消关联任务 ${lk.title}`" title="取消关联" @click.stop="toggleRelation('task', lk.id)"><i class="ri-link-unlink-m"></i></button>
                </div>
                <div v-for="page in linkedHostedPages" :key="page.id" class="attach link" :class="{ legacy: !page.url }" @click="openHostedPage(page.url)">
                  <i class="ri-html5-line" style="color: #d55c22"></i>
                  <span class="attach-name ellip">{{ page.title }}</span>
                  <button class="relation-unlink" :aria-label="`取消关联 HTML ${page.title}`" title="取消关联" @click.stop="toggleRelation('page', page.id)"><i class="ri-link-unlink-m"></i></button>
                </div>
              </template>
            </div>
            <button class="text-btn small" @click="openRelationPicker"><i class="ri-links-line"></i>{{ openSub ? '关联知识文档' : '关联知识、任务、文件或 HTML' }}</button>
          </div>

          <!-- 评论：跟着当前查看的任务 / 子任务 -->
          <div class="prop-section side-comments">
            <div class="props-label sm">评论<span v-if="comments.length" class="side-count wao-tabular">{{ comments.length }}</span></div>
            <div class="comment-list">
              <div v-for="cm in comments" :key="cm.index" class="comment">
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
                    <textarea v-model="editingText" class="c-edit-input" rows="2" @keydown.esc.stop="cancelEditComment"></textarea>
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
              <div class="ci-wrap">
                <input v-model="commentText" :disabled="sendingComment" :placeholder="openSub ? '写评论，回车发送' : '写评论，回车发送；@成员名 会通知对方'" @focus="mentionOpen = true" @input="mentionOpen = true" @keydown="commentKey" />
                <div v-if="!openSub && mentionOpen && mentionCandidates.length" class="mention-menu">
                  <button v-for="member in mentionCandidates" :key="member.id" type="button" @mousedown.prevent="insertMention(member.name)">
                    <span :style="{ background: member.color }">{{ member.initial }}</span>{{ member.name }}
                  </button>
                </div>
                <button class="send" :disabled="!commentText.trim() || sendingComment" :aria-label="sendingComment ? '发送中' : '发送评论'" @click="sendComment"><i :class="sendingComment ? 'ri-loader-4-line spin' : 'ri-send-plane-2-line'"></i></button>
              </div>
            </div>
          </div>
          <details class="optional-props"><summary>更多属性 · 参与人、排期与自定义</summary>
          <div class="prop-row">
            <span class="prop-key">参与人</span>
            <span class="parts">
              <span v-for="(p, i) in parts" :key="i" class="mini-av bordered" :title="p.name" :style="{ background: p.color }">{{ p.initial }}</span>
              <span v-if="parts.length === 0" class="parts-empty">未添加</span>
            </span>
            <button
              class="add-part"
              type="button"
              title="管理参与人"
              aria-label="管理参与人"
              @click="menu = menu === 'participants' ? null : 'participants'"
            ><i class="ri-add-line"></i></button>
            <div v-if="menu === 'participants'" class="pmenu w190 participant-menu">
              <div v-if="session.members.length === 0" class="pmenu-empty">没有可选成员</div>
              <button
                v-for="m in session.members"
                :key="m.id"
                class="pmenu-item"
                type="button"
                @click="toggleParticipant(m.id)"
              >
                <span class="mini-av" :style="{ background: m.color }">{{ m.initial }}</span>
                <span class="pmenu-name">{{ m.name }}</span>
                <i v-if="(task.parts ?? []).includes(m.id)" class="ri-check-line chk"></i>
              </button>
            </div>
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
            <span class="prop-key">时段开始</span>
            <input class="datetime-input" type="datetime-local" :value="task.startAt || ''" @change="setDateTime('startAt', $event)" />
          </div>
          <div class="prop-row">
            <span class="prop-key">时段结束</span>
            <input class="datetime-input" type="datetime-local" :value="task.endAt || ''" :min="task.startAt || undefined" @change="setDateTime('endAt', $event)" />
          </div>
          <div class="prop-row">
            <span class="prop-key">可见范围</span>
            <span class="prop-val"><i class="ri-eye-line"></i>项目成员可见</span>
          </div>

          <div class="prop-section">
            <div class="props-label sm acceptance-head">
              <span>自定义属性</span>
              <button
                class="acceptance-template-btn"
                :disabled="acceptanceFieldsComplete"
                title="补齐成功条件、验收证据、端到端耗时、异常与恢复、通知结果"
                @click="addAcceptanceFields"
              ><i class="ri-clipboard-line"></i>{{ acceptanceFieldsComplete ? '验收字段已齐' : '补齐验收记录' }}</button>
            </div>
            <div v-for="(cp, i) in task.custom" :key="i" class="custom-row">
              <input class="custom-name" :value="cp.name" aria-label="属性名称" @change="updateCustom(i, 'name', $event)" />
              <input class="custom-val" :value="cp.value" :placeholder="isAcceptanceField(cp.name) ? '待填写真实结果' : ''" aria-label="属性值" @change="updateCustom(i, 'value', $event)" />
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

          </details>
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
      :tasks="openSub ? [] : relationTasks"
      :files="openSub ? [] : relationFiles"
      :pages="openSub ? [] : manualHostedPages"
      :selected-doc-ids="currentDocIds"
      :selected-task-ids="task.linkTasks.map((item) => item.id)"
      :selected-file-ids="selectedFileIds"
      :selected-page-ids="task.hostedPages ?? []"
      :page-load-state="data.hostedPageLoadState"
      :page-load-error="data.hostedPageLoadError"
      @toggle="toggleRelation"
      @retry-pages="data.refreshHostedPages"
      @close="relationOpen = false"
    />
  </template>
</template>

<style scoped>
.task-save-conflict { padding: 12px 20px; color: var(--wao-danger-strong); background: var(--wao-danger-bg); }
.task-save-conflict button { margin-left: 10px; }
.task-save-conflict pre { max-height: 180px; overflow: auto; white-space: pre-wrap; }
.optional-props > summary { padding: 12px 0; font-size: 12px; color: var(--wao-text-weak); cursor: pointer; }
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
  border: 1px solid var(--wao-danger-line);
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
/* 居中弹框（参照 ONES 工作项详情）：任务与子任务都在这里打开 */
.drawer {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: min(1120px, calc(100vw - 64px));
  height: min(860px, calc(100vh - 48px));
  background: var(--wao-bg-card);
  z-index: 81;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 16px 48px rgba(16, 18, 28, 0.22);
  display: flex;
  flex-direction: column;
  animation: waoTaskModal 0.18s ease;
}
@keyframes waoTaskModal {
  from { opacity: 0; transform: translate(-50%, calc(-50% + 12px)); }
  to { opacity: 1; transform: translate(-50%, -50%); }
}
.crumbs {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
  overflow: hidden;
}
.crumb-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  padding: 4px 6px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--wao-primary-text);
  cursor: pointer;
  flex-shrink: 0;
}
.crumb-item:hover {
  background: var(--wao-bg-hover-2);
}
.crumb-item.current {
  color: var(--wao-text);
  cursor: default;
}
.crumb-item.current:hover {
  background: none;
}
.type-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--wao-text-weak);
  margin-bottom: 6px;
  min-width: 0;
}
.type-parent {
  margin-left: 6px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.type-parent button {
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  color: var(--wao-primary-text);
  cursor: pointer;
}
.title-input.done {
  color: var(--wao-text-faint);
  text-decoration: line-through;
}
.state-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 22px;
  padding: 0 9px;
  border: 1px solid #338fe5;
  border-radius: 11px;
  background: none;
  color: #338fe5;
  font-size: 12px;
  cursor: pointer;
}
.state-pill.completed {
  border-color: #3fb96f;
  color: #3fb96f;
}
.state-pill i {
  font-size: 11px;
  opacity: 0.7;
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
  color: var(--wao-warning-strong);
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
  border: 1px solid var(--wao-primary-line);
  border-radius: 8px;
  background: linear-gradient(135deg, var(--wao-primary-soft), var(--wao-bg-card));
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
  border-color: var(--wao-warning-line);
  background: var(--wao-warning-bg);
}
.ai-run-btn:disabled {
  cursor: default;
}
.pause-ai-btn {
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--wao-danger-line);
  border-radius: 8px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
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
  border: 1.5px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.cbox.sq {
  border-radius: 5px;
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
.cbox.big {
  width: 18px;
  height: 18px;
  padding: 0;
  cursor: pointer;
  margin-top: 4px;
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
.attach-open {
  min-width: 0; flex: 1; display: flex; align-items: center; gap: 9px; padding: 0; border: 0;
  background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer;
}
.attach-open:disabled { cursor: default; }
.attach:hover {
  border-color: var(--wao-line);
  background: var(--wao-bg-sub);
}
.attach.link:hover {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-tint);
}
.attach.legacy { cursor: default; opacity: .72; }
.attach.legacy:hover { border-color: var(--wao-divider); background: var(--wao-bg-card); }
.attach > i, .attach-open > i {
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
.relation-unlink {
  width: 30px; height: 30px; flex: 0 0 30px; display: grid; place-items: center;
  border: 1px solid transparent; border-radius: 8px; background: transparent;
  color: var(--wao-text-weak); cursor: pointer;
}
.relation-unlink:hover, .relation-unlink:focus-visible {
  border-color: var(--wao-danger-line); background: var(--wao-danger-bg); color: var(--wao-danger-strong);
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
  color: var(--wao-primary-text);
}
.text-btn i {
  font-size: 13px;
}
.text-btn.small {
  padding: 5px 0;
}

.comments {
  margin-top: 18px;
  border-top: 1px solid var(--wao-divider);
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

/* 属性栏：关联与评论也在这里，比原来宽一点 */
.props {
  width: 330px;
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
.job-id-copy {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: calc(100% - 68px);
  padding: 3px 7px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--wao-text-2);
  cursor: pointer;
}
.job-id-copy:hover,
.job-id-copy:focus-visible {
  border-color: var(--wao-line-control);
  background: var(--wao-primary-soft);
}
.job-id-copy code {
  overflow: hidden;
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.job-id-copy i {
  flex-shrink: 0;
  color: var(--wao-text-weak);
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
.parts-empty { color: var(--wao-text-faint); font-size: 11.5px; }
.add-part {
  width: 20px;
  height: 20px;
  border: 1px dashed var(--wao-line-control);
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
  color: var(--wao-primary-text);
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
  background: var(--wao-primary-soft);
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
.pmenu-empty { padding: 8px; color: var(--wao-text-faint); font-size: 12px; text-align: center; }
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
  color: var(--wao-primary-text);
}

.prop-section {
  margin-top: 10px;
  border-top: 1px solid var(--wao-divider);
  padding-top: 10px;
  position: relative;
}
.sub-detail-actions {
  display: grid;
  gap: 8px;
}
.sub-detail-action {
  width: 100%;
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid var(--wao-primary-line);
  border-radius: 9px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.sub-detail-action:hover {
  border-color: var(--wao-primary);
  background: var(--wao-primary-soft);
}
.sub-detail-action:focus-visible {
  outline: 3px solid rgba(67, 88, 214, .16);
  outline-offset: 1px;
}
.sub-detail-action.danger {
  border-color: var(--wao-danger-line);
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.sub-detail-action.danger:hover {
  border-color: var(--wao-danger-line);
  background: var(--wao-danger-bg);
}
.custom-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
}
.acceptance-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.acceptance-template-btn {
  min-height: 28px;
  padding: 0 8px;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-primary-text);
  font-size: 11.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.acceptance-template-btn:hover:not(:disabled) { background: var(--wao-primary-soft); }
.acceptance-template-btn:disabled { color: var(--wao-text-faint); cursor: default; }
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
.custom-delete:hover { color: var(--wao-danger); }

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
  border: 2px solid var(--wao-line);
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
@keyframes waoSubPage {
  from { transform: translateX(14px); opacity: 0.4; }
}

@media (max-width: 760px) {
  /* 当前抽屉的主体是 .dbody / .main / .props，不要沿用旧版的 drawer-body 类名。
     手机上改为一个连续的纵向滚动区，避免主内容和属性栏同时被压成窄列。 */
  .drawer {
    width: 100%;
    max-width: none;
    height: 100dvh;
    border-radius: 0;
  }
  .crumb-item {
    padding-inline: 4px;
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
  .check-row {
    min-height: 32px;
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
/* 属性栏里的关联与评论：窄栏排版 */
.side-relations .attach-list { margin: 4px 0 2px; }
.side-relations .attach { padding: 6px 8px; }
.side-count { margin-left: 6px; color: var(--wao-text-faint); letter-spacing: 0; }
.side-comments .comment-list { gap: 10px; margin-top: 6px; }
.side-comments .c-av { width: 22px; height: 22px; font-size: 10px; }
.side-comments .c-text { font-size: 12.5px; line-height: 1.6; overflow-wrap: anywhere; }
.side-comments .comment-input { margin-top: 10px; }
.side-comments .ci-wrap { gap: 6px; }
.side-comments .ci-wrap input { height: 32px; font-size: 12.5px; }
.side-comments .send { width: 34px; height: 32px; padding: 0; justify-content: center; flex-shrink: 0; }
.side-comments .mention-menu { right: 40px; }
.state-pill.sm { cursor: inherit; }
</style>
