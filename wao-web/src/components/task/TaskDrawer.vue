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
import { continueAiRun, createAiRuns, fetchTaskAiRuns } from '@/api/ai'
import AiRunProgressTimeline from '@/components/ai/AiRunProgressTimeline.vue'
import AiRunLauncher from '@/components/ai/AiRunLauncher.vue'
import { usageSummary } from '@/composables/aiUsage'
import { engineIcon, engineLabel } from '@/composables/aiEngines'
import { useSettle } from '@/composables/completion'
import { useNow } from '@/composables/now'
import { absoluteTime, relativeTime } from '@/utils/time'
import type { AiEngine, AiRun, AiRunStatus, Priority, TaskStatus } from '@/types'

const ui = useUiStore()
const data = useDataStore()
const session = useSessionStore()
const router = useRouter()
const { decoTask } = useDeco()

const task = computed(() => (ui.openTaskId ? data.taskById(ui.openTaskId) : undefined))
const tv = computed(() => (task.value ? decoTask(task.value) : null))

const menu = ref<'status' | 'pr' | 'owner' | 'prop' | 'share' | null>(null)
const newSub = ref('')
const commentText = ref('')
const sendingComment = ref(false)
const aiStarting = ref(false)
const aiLauncherOpen = ref(false)
const aiRuns = ref<AiRun[]>([])
const aiRunsLoading = ref(false)
const aiContinuation = ref<Record<string, string>>({})
const continuingRunId = ref<string | null>(null)
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
    editingComment.value = null
    confirmingDelete.value = false
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
    if (ui.openTaskId === taskId) aiRuns.value = next
  } catch {
    // 保留上一帧，网络抖动不应让已展示的 AI 档案消失。
  } finally {
    if (ui.openTaskId === taskId) aiRunsLoading.value = false
  }
}

watch(() => ui.openTaskId, () => void refreshTaskAiRuns(), { immediate: true })

const aiRunLabel: Record<AiRunStatus, string> = {
  queued: '等待领取', claimed: '准备中', running: '正在运行', succeeded: '等待验收',
  failed: '执行失败', cancelled: '已取消',
}
const activeAiRun = computed(() => aiRuns.value.find((run) => ['queued', 'claimed', 'running'].includes(run.status)))

async function continueRun(runId: string) {
  const input = aiContinuation.value[runId]?.trim()
  if (!input) return toast('请先输入要补充给 AI 的信息', 'ri-information-line')
  continuingRunId.value = runId
  try {
    await continueAiRun(runId, input)
    aiContinuation.value[runId] = ''
    toast('补充已发送，AI 将在原会话中继续执行', 'ri-robot-2-line')
    await refreshTaskAiRuns()
  } catch (error) {
    toast(error instanceof Error ? error.message : '发送补充失败，请重试', 'ri-error-warning-line')
  } finally {
    continuingRunId.value = null
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
/** 活动时间线，时间统一转成相对描述。 */
const activities = computed(() =>
  (task.value?.activity ?? []).map((a) => ({
    ...a, time: showTime(a.time), fullTime: absoluteTime(a.time),
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
  if (menu.value) menu.value = null
  else if (openSubId.value) openSubId.value = null
  else close()
}
onMounted(() => {
  window.addEventListener('keydown', onKey)
  aiRunsTimer = setInterval(() => {
    if (ui.openTaskId && activeAiRun.value) void refreshTaskAiRuns()
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
async function runWithAi(engine: AiEngine, runnerId: string | null) {
  const t = task.value
  if (!t || aiStarting.value || t.status === 'AI 进行中') return
  aiStarting.value = true
  try {
    const created = await createAiRuns([t.id], { engine, runnerId })
    if (created.length) {
      data.patchTask(t.id, { status: 'AI 进行中' }, `你已把任务交给 ${engineLabel(engine)}`)
      toast(`已交给 ${engineLabel(engine)}，可在首页 AI 控制台查看运行状态`, 'ri-robot-2-line')
      aiLauncherOpen.value = false
      void refreshTaskAiRuns()
    } else {
      toast('这个任务已经在 AI Run 队列中', 'ri-information-line')
    }
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI Run 启动失败', 'ri-error-warning-line')
  } finally {
    aiStarting.value = false
  }
}
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
  data.patchTask(t.id, { status: s }, `你将状态改为「${s}」`)
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
  data.patchTask(t.id, { pr: p }, `你将优先级改为 ${p}`)
  menu.value = null
}
function setOwner(id: string) {
  const t = task.value
  if (!t) return
  const m = session.memberById(id)
  data.patchTask(t.id, { owner: id }, `你将负责人改为 ${m.name}`)
  menu.value = null
}
function addCustom(type: string) {
  const t = task.value
  if (!t) return
  data.patchTask(t.id, { custom: [...t.custom, { name: `未命名属性（${type}）`, value: '—' }] }, '你添加了自定义属性')
  menu.value = null
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
            v-if="aiLauncherOpen && task.status !== 'AI 进行中'"
            :project-id="task.project"
            :starting="aiStarting"
            @close="aiLauncherOpen = false"
            @start="runWithAi"
          />
        </div>
        <div class="share-wrap">
          <button class="icon-btn" title="复制分享链接" @click="menu = menu === 'share' ? null : 'share'"><i class="ri-links-line"></i></button>
          <div v-if="menu === 'share'" class="menu-mask" @click="menu = null"></div>
          <div v-if="menu === 'share'" class="share-menu">
            <div class="share-scope">
              {{ openSubId ? '分享范围：当前子任务' : shareOpenOnly ? '分享范围：本任务 + 未完成的子任务' : '分享范围：本任务 + 全部子任务' }}
            </div>
            <label v-if="!openSubId && shareDoneCount" class="share-filter">
              <input v-model="shareOpenOnly" type="checkbox" />
              <span class="share-filter-main">
                <b>只分享未完成的子任务</b>
                <em>过滤掉已完成的 {{ shareDoneCount }} 条，便于持续迭代时直接丢给 AI</em>
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
                :class="{ active: openSubId === r.key, settling: isSettling(r.key) }"
                @click="showSub(r.idx)"
              >
                <span class="cbox circle" :class="{ done: r.sub.done }" @click.stop="toggleSub(r.idx)"><i class="ri-check-line"></i></span>
                <span class="check-text" :class="{ done: r.sub.done }">{{ r.sub.text }}</span>
                <i v-if="r.sub.desc || r.sub.images?.length" class="ri-file-text-line sub-flag" title="有描述 / 图片"></i>
                <span class="spacer"></span>
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
              <div v-for="(f, i) in task.files" :key="i" class="attach" @click="demo('演示：下载在正式版本提供')">
                <i :class="f.icon"></i>
                <span class="attach-name ellip">{{ f.name }}</span>
                <span class="attach-size">{{ f.size }}</span>
                <i class="ri-download-2-line dl"></i>
              </div>
            </div>
            <button class="text-btn" @click="demo('演示：附件上传在正式版本提供')"><i class="ri-upload-2-line"></i>上传附件</button>
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
            <button class="text-btn" @click="demo('演示：关联选择器在下一轮迭代提供')"><i class="ri-links-line"></i>关联知识、任务或文件</button>
          </div>

          <div v-if="aiRunsLoading || aiRuns.length" class="ai-archive">
            <div class="field-label ai-archive-title">
              <span>AI 执行记录</span>
              <em v-if="activeAiRun"><i></i>实时更新中</em>
            </div>
            <div v-if="aiRunsLoading && !aiRuns.length" class="ai-archive-loading"><i class="ri-loader-4-line"></i>正在读取 AI 记录…</div>
            <details v-for="(run, index) in aiRuns" v-else :key="run.id" class="ai-run-entry" :open="index === 0">
              <summary>
                <span class="ai-run-state" :class="`state-${run.status}`">{{ aiRunLabel[run.status] }}</span>
                <span class="ai-run-engine" :title="`执行工具：${engineLabel(run.engine)}`"><i :class="engineIcon(run.engine)"></i></span>
                <span class="ai-run-latest">{{ run.latestProgress || run.resultSummary || '暂无执行摘要' }}</span>
                <time :title="absoluteTime(run.startedAt || run.createdAt)">{{ showTime(run.startedAt || run.createdAt) }}</time>
                <i class="ri-arrow-down-s-line"></i>
              </summary>
              <div class="ai-run-body">
                <div v-if="usageSummary(run.usage)" class="ai-run-usage" :title="run.usage.models || ''">
                  <i class="ri-copper-coin-line"></i>{{ usageSummary(run.usage) }}
                </div>
                <AiRunProgressTimeline :run="run" />
                <div v-if="run.resultSummary" class="ai-final-result">
                  <b>{{ run.status === 'failed' ? '失败原因' : '最终结果' }}</b>
                  <p>{{ run.resultSummary }}</p>
                </div>
                <div v-if="run.status === 'failed' && run.sessionId && run.runnerId" class="ai-continue">
                  <textarea v-model="aiContinuation[run.id]" rows="2" placeholder="补充信息后，让 AI 在原会话中继续执行"></textarea>
                  <button :disabled="continuingRunId === run.id" @click="continueRun(run.id)">
                    {{ continuingRunId === run.id ? '发送中…' : '继续对话' }}
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
                <input v-model="commentText" :disabled="sendingComment" placeholder="写评论，回车发送；@ 成员会收到通知（演示）" @keydown="commentKey" />
                <button class="send" :disabled="!commentText.trim() || sendingComment" @click="sendComment"><i :class="sendingComment ? 'ri-loader-4-line spin' : 'ri-send-plane-2-line'"></i>{{ sendingComment ? '发送中' : '发送' }}</button>
              </div>
            </div>
          </div>
        </div>

        <!-- 子任务详情：展开子任务时顶掉属性栏 -->
        <div v-if="openSub" class="props">
          <button class="sub-back" @click="openSubId = null"><i class="ri-arrow-left-line"></i>返回任务属性</button>
          <div class="props-label">子任务 {{ openSubIndex + 1 }} / {{ task.subs.length }}</div>

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
            <span class="prop-val">{{ task.start ? fmtDate(task.start) : '—' }}</span>
          </div>
          <div class="prop-row">
            <span class="prop-key">截止</span>
            <span class="prop-val due" :style="{ color: tv.dueColor }"><i class="ri-calendar-line"></i>{{ tv.dueStr }}</span>
          </div>
          <div class="prop-row">
            <span class="prop-key">所属项目</span>
            <button class="proj-btn" @click="gotoProject"><span class="pdot" :style="{ background: tv.projColor }"></span><span class="ellip">{{ tv.projName }}</span></button>
          </div>
          <div class="prop-row">
            <span class="prop-key">可见范围</span>
            <span class="prop-val"><i class="ri-eye-line"></i>项目成员可见</span>
          </div>

          <div class="prop-section">
            <div class="props-label sm">自定义属性</div>
            <div v-for="(cp, i) in task.custom" :key="i" class="custom-row">
              <span class="custom-name ellip">{{ cp.name }}</span>
              <span class="custom-val ellip">{{ cp.value }}</span>
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
  background: #fdf7f7;
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
  background: #fff;
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
  background: #f7f8fb;
  box-shadow: 0 0 0 3px #f7f8fb;
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
  color: #8a5b0d;
  border-color: #efd69a;
  background: #fffaf0;
}
.ai-run-btn:disabled {
  cursor: default;
}
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
  background: #fff;
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
  background: #fff;
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
  background: #fff;
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
  background: #fff;
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
  display: flex;
  align-items: center;
  gap: 9px;
  border: 1px solid var(--wao-divider);
  border-radius: 9px;
  padding: 8px 11px;
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
.ai-archive-loading { padding: 12px; border-radius: 9px; background: #f7f8fb; color: var(--wao-text-weak); font-size: 12px; }
.ai-run-entry { margin-top: 7px; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 10px; background: #fff; }
.ai-run-entry summary { min-height: 44px; padding: 8px 10px; cursor: pointer; list-style: none; display: flex; align-items: center; gap: 8px; }
.ai-run-entry summary::-webkit-details-marker { display: none; }
.ai-run-entry summary > i { color: var(--wao-text-weak); transition: transform .15s; }
.ai-run-entry[open] summary > i { transform: rotate(180deg); }
.ai-run-state { flex: 0 0 auto; padding: 3px 7px; border-radius: 5px; background: #eef1ff; color: #4056c5; font-size: 10px; }
.ai-run-state.state-succeeded { background: #e8f7ef; color: #237a4b; }
.ai-run-state.state-failed { background: #fff0f0; color: #b33a3a; }
.ai-run-latest { flex: 1; min-width: 0; overflow: hidden; color: var(--wao-text-2); font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.ai-run-entry summary time { flex: 0 0 auto; color: var(--wao-text-faint); font-size: 10px; }
.ai-run-body { padding: 0 10px 10px; }
.ai-run-usage { margin-bottom: 8px; padding: 6px 9px; border-radius: 7px; background: #f7f8fd; color: var(--wao-text-3); font-size: 10.5px; }
.ai-run-usage i { margin-right: 5px; color: #a8862f; }
.ai-final-result { margin-top: 10px; padding: 10px 12px; border-radius: 9px; background: #f7f8fb; }
.ai-final-result b { font-size: 11px; }
.ai-final-result p { max-height: 180px; margin: 5px 0 0; overflow: auto; color: var(--wao-text-3); font-size: 11px; line-height: 1.65; white-space: pre-wrap; }
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
}
.ci-wrap input {
  flex: 1;
  height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 0 11px;
  font-size: 13px;
  background: #fff;
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
  background: #f0f1f4;
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
  background: #fff;
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
}

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
</style>
