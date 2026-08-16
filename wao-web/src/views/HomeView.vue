<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createAiPairCode,
  fetchAiEvolution,
  fetchAiRunnerLog,
  fetchAiRunners,
  fetchAiRuns,
  fetchAiWorkflows,
  launchAutonomousGoal,
  launchEvolutionSuggestion,
  removeAiRunner,
  removeAiWorkflow,
  refreshEvolutionSuggestions,
  requestRunnerScriptUpdate,
  rerunAiRun,
  saveAiEvolutionSchedule,
  saveAiProjectConfig,
  updateRunnerMaxConcurrency,
  wakeAiRunner,
  type AiEvolutionOverview,
  type AiEvolutionSuggestion,
} from '@/api/ai'
import AiRunProgressTimeline from '@/components/ai/AiRunProgressTimeline.vue'
import { AI_ENGINES, engineColor, engineIcon, engineLabel } from '@/composables/aiEngines'
import { formatCost, formatTokens, shortModel, usageSummary } from '@/composables/aiUsage'
import { toast } from '@/composables/toast'
import { githubBrowseUrl, githubSubPage } from '@/config/quickLinks'
import {
  fetchDailyGuidance,
  generateDailyGuidance,
  type DailyGuidance,
} from '@/api/dailyGuidance'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import type { AiEngine, AiRun, AiRunStatus, AiRunner, AiRunnerLog, AiWorkflow } from '@/types'
import { renderMarkdown } from '@/utils/markdown'

type ConsoleView = 'runs' | 'runners' | 'workflows'

// 与任务描述等输入场景复用同一编辑器；保持异步加载，避免把 ProseMirror 合进首页主包。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))

const route = useRoute()
const router = useRouter()
const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const runs = ref<AiRun[]>([])
const runners = ref<AiRunner[]>([])
const workflows = ref<AiWorkflow[]>([])
const loading = ref(true)
const workflowsLoading = ref(false)
const goal = ref('')
const goalProjectId = ref('')
const goalRunnerId = ref('')
const goalRunnerManuallySelected = ref(false)
const launchingGoal = ref(false)
const goalImageUploads = ref(0)
const selectedRun = ref<AiRun | null>(null)
const logRunner = ref<AiRunner | null>(null)
const runnerLog = ref<AiRunnerLog | null>(null)
const runnerLogLoading = ref(false)
const runnerLogError = ref('')
function consoleViewFromQuery(value: unknown): ConsoleView {
  return value === 'runners' || value === 'workflows' ? value : 'runs'
}
const view = ref<ConsoleView>(consoleViewFromQuery(route.query.console))
const pairCode = ref('')
const pairExpiresAt = ref('')
const editingProjectId = ref<string | null>(null)
const saving = ref(false)
const rerunning = ref(false)
const editingRunnerId = ref<string | null>(null)
const editingRunnerValue = ref(2)
const editingRunnerSaving = ref(false)
const updatingRunners = ref(false)
const evolution = ref<AiEvolutionOverview | null>(null)
const evolutionLoading = ref(true)
const evolutionError = ref('')
const launchingSuggestionId = ref<string | null>(null)
const suggestionsRefreshing = ref(false)
const suggestionError = ref('')
const evolutionScheduleOpen = ref(false)
const evolutionScheduleSaving = ref(false)
const evolutionScheduleError = ref('')
const evolutionScheduleSaved = ref(false)
const evolutionScheduleInitialized = ref(false)
const evolutionScheduleDraft = reactive({ intervalHours: 12, batchSize: 1 })
const wakingRunnerId = ref<string | null>(null)
const removingRunnerId = ref<string | null>(null)
const removingWorkflowId = ref<string | null>(null)
const dailyGuidance = ref<DailyGuidance | null>(readCachedDailyGuidance())
const dailyGuidanceLoading = ref(!dailyGuidance.value)
const dailyGuidanceRefreshing = ref(false)
const dailyGuidanceError = ref('')
const dailyGuidanceErrorSource = ref<'load' | 'refresh'>('load')
const form = reactive({
  enabled: false,
  repoUrl: '',
  defaultBranch: 'main',
  workspaceDir: '',
  defaultEngine: 'codex' as AiEngine,
  preferredRunnerId: '',
  maxParallelRuns: 2,
})
let timer: ReturnType<typeof setInterval> | null = null
let evolutionTimer: ReturnType<typeof setInterval> | null = null
let initialSuggestionRefreshStarted = false

const activeRuns = computed(() => runs.value.filter((run) => ['queued', 'claimed', 'running'].includes(run.status)))
const recentRuns = computed(() => runs.value.filter((run) => !['queued', 'claimed', 'running'].includes(run.status)).slice(0, 8))
const onlineRunners = computed(() => runners.value.filter((runner) => runner.online))
const totalSlots = computed(() => onlineRunners.value.reduce((sum, runner) => sum + runner.maxConcurrency, 0))
const usedSlots = computed(() => onlineRunners.value.reduce((sum, runner) => sum + runner.activeRuns, 0))
const activeWorkflows = computed(() => workflows.value.filter((workflow) => workflow.config.enabled))
const goalWorkflow = computed(() => activeWorkflows.value.find(
  (workflow) => workflow.projectId === goalProjectId.value,
) ?? null)
/** 自主目标沿用工作流默认 AI 工具，只列出真正装了该工具的执行电脑。 */
const goalRunnerCandidates = computed(() => {
  const engine = goalWorkflow.value?.config.defaultEngine
  return engine ? runners.value.filter((runner) => runner.engines.includes(engine)) : []
})
const goalRunnerHint = computed(() => {
  const picked = goalRunnerCandidates.value.find((runner) => runner.id === goalRunnerId.value)
  if (picked) {
    return picked.online
      ? `${picked.name} 在线，当前占用 ${picked.activeRuns}/${picked.maxConcurrency}`
      : `${picked.name} 当前离线，任务会排队等它上线`
  }
  const online = goalRunnerCandidates.value.filter((runner) => runner.online).length
  if (online) return `自动分配给 ${online} 台在线电脑中最先空闲的一台`
  return goalRunnerCandidates.value.length ? '可用电脑当前全部离线，任务会先排队' : '还没有电脑接入工作流使用的 AI 工具'
})
const visibleSessionQuotas = computed(() => evolution.value?.sessionQuotas.filter((quota) => quota.status === 'SYNCED') ?? [])
const primarySessionQuota = computed(() => visibleSessionQuotas.value[0] ?? null)
const visibleWeeklyQuotas = computed(() => evolution.value?.weeklyQuotas.filter((quota) => quota.status === 'SYNCED') ?? [])
const primaryWeeklyQuota = computed(() => visibleWeeklyQuotas.value[0] ?? null)
const showSessionQuota = computed(() => primarySessionQuota.value !== null)
const showWeeklyQuota = computed(() => primaryWeeklyQuota.value !== null || (evolutionLoading.value && !evolution.value))
const evolutionScheduleDirty = computed(() => Boolean(evolution.value)
  && (evolutionScheduleDraft.intervalHours !== evolution.value?.intervalHours
    || evolutionScheduleDraft.batchSize !== evolution.value?.batchSize))

/** 每种 AI 工具当前有几台电脑在线，决定 hero 上的接入状态。 */
const engineStatus = computed(() =>
  AI_ENGINES.map((engine) => ({
    ...engine,
    total: runners.value.filter((runner) => runner.engines.includes(engine.id)).length,
    online: onlineRunners.value.filter((runner) => runner.engines.includes(engine.id)).length,
  })),
)

const VIEWS: Array<{ id: ConsoleView; label: string; icon: string }> = [
  { id: 'runs', label: '运行状态', icon: 'ri-pulse-line' },
  { id: 'runners', label: '执行电脑', icon: 'ri-computer-line' },
  { id: 'workflows', label: 'AI 工作流', icon: 'ri-git-branch-line' },
]

const statusMeta: Record<AiRunStatus, { label: string; icon: string; color: string; bg: string }> = {
  queued: { label: '等待电脑领取', icon: 'ri-time-line', color: '#8a5b0d', bg: '#fff7df' },
  claimed: { label: '准备工作区', icon: 'ri-git-branch-line', color: '#3650b4', bg: '#eef1ff' },
  running: { label: 'AI 运行中', icon: 'ri-loader-4-line', color: '#3650b4', bg: '#eef1ff' },
  succeeded: { label: '等待验收', icon: 'ri-checkbox-circle-line', color: '#237a4b', bg: '#eaf8f0' },
  failed: { label: '运行失败', icon: 'ri-error-warning-line', color: '#b33a3a', bg: '#fff0f0' },
  cancelled: { label: '已取消', icon: 'ri-stop-circle-line', color: '#747989', bg: '#f1f2f5' },
}

// 退出码 3 是 Runner 的约定：AI 结束了会话但没报告任务状态，成败需要人判断，
// 不能直接挂一个「运行失败」让人分不清到底成没成。
const UNCONFIRMED_META = { label: '结果待确认', icon: 'ri-question-line', color: '#8a5b0d', bg: '#fff7df' }

function statusUnreported(run: AiRun) {
  return run.status === 'failed' && run.exitCode === 3
}

function metaFor(run: AiRun) {
  return statusUnreported(run) ? UNCONFIRMED_META : statusMeta[run.status]
}

function resultTitle(run: AiRun) {
  if (statusUnreported(run)) return '结果待确认（AI 未报告任务状态）'
  return run.status === 'failed' ? '失败原因' : '最终结果'
}

async function refresh() {
  try {
    const prevSucceededIds = new Set(runs.value.filter((run) => run.status === 'succeeded').map((run) => run.id))
    const [nextRuns, nextRunners] = await Promise.all([fetchAiRuns(false), fetchAiRunners()])
    runs.value = nextRuns
    runners.value = nextRunners
    if (selectedRun.value) selectedRun.value = nextRuns.find((run) => run.id === selectedRun.value?.id) ?? selectedRun.value
    // 首次加载时不庆祝；检测到本次轮询有运行刚变成 succeeded 时触发庆祝。
    if (!loading.value) {
      const newlySucceeded = nextRuns.filter((run) => run.status === 'succeeded' && !prevSucceededIds.has(run.id))
      if (newlySucceeded.length) ui.celebrate()
    }
  } catch {
    // 首页轮询失败时保留上一帧，避免网络抖动让运行面板闪空。
  } finally {
    loading.value = false
  }
}

async function refreshWorkflows() {
  workflowsLoading.value = true
  try {
    workflows.value = await fetchAiWorkflows()
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI 工作流读取失败', 'ri-error-warning-line')
  } finally {
    workflowsLoading.value = false
  }
}

async function refreshEvolution() {
  // 手动挖掘最长需要五分钟。此时继续轮询会把服务端尚未更新的旧批次写回页面，
  // 甚至可能在新批次响应之后覆盖刚换好的建议。
  if (suggestionsRefreshing.value) return
  evolutionLoading.value = true
  evolutionError.value = ''
  let needsInitialSuggestions = false
  try {
    const preserveScheduleDraft = evolutionScheduleInitialized.value && evolutionScheduleDirty.value
    const next = await fetchAiEvolution()
    // 请求发出后用户可能才点击“刷新换一批”；这份并发返回的概览仍属于旧批次。
    if (suggestionsRefreshing.value) return
    evolution.value = next
    if (!preserveScheduleDraft && !evolutionScheduleSaving.value) syncEvolutionScheduleDraft()
    needsInitialSuggestions = evolution.value.suggestions.length === 0 && !initialSuggestionRefreshStarted
  } catch (error) {
    evolutionError.value = error instanceof Error ? error.message : '周额度与自主进化状态读取失败'
  } finally {
    evolutionLoading.value = false
  }
  if (needsInitialSuggestions) {
    initialSuggestionRefreshStarted = true
    void refreshSuggestions(false)
  }
}

function syncEvolutionScheduleDraft() {
  if (!evolution.value) return
  evolutionScheduleDraft.intervalHours = evolution.value.intervalHours
  evolutionScheduleDraft.batchSize = evolution.value.batchSize
  evolutionScheduleInitialized.value = true
}

async function saveEvolutionSchedule() {
  if (evolutionScheduleSaving.value) return
  evolutionScheduleSaving.value = true
  evolutionScheduleError.value = ''
  evolutionScheduleSaved.value = false
  try {
    evolution.value = await saveAiEvolutionSchedule(
      evolutionScheduleDraft.intervalHours,
      evolutionScheduleDraft.batchSize,
    )
    syncEvolutionScheduleDraft()
    evolutionScheduleSaved.value = true
    toast('自动执行频率已保存', 'ri-checkbox-circle-line')
  } catch (error) {
    evolutionScheduleError.value = error instanceof Error ? error.message : '自动执行频率保存失败'
  } finally {
    evolutionScheduleSaving.value = false
  }
}

async function refreshSuggestions(showSuccess = true) {
  if (!evolution.value || suggestionsRefreshing.value) return
  suggestionsRefreshing.value = true
  suggestionError.value = ''
  try {
    const next = await refreshEvolutionSuggestions()
    evolution.value = { ...evolution.value, suggestions: next }
    if (showSuccess) toast('已从真实资料换了一批任务建议', 'ri-refresh-line')
  } catch (error) {
    suggestionError.value = error instanceof Error ? error.message : '真实资料挖掘失败，请重试'
  } finally {
    suggestionsRefreshing.value = false
  }
}

async function loadDailyGuidance() {
  dailyGuidanceLoading.value = !dailyGuidance.value?.content
  dailyGuidanceError.value = ''
  dailyGuidanceErrorSource.value = 'load'
  try {
    const current = await fetchDailyGuidance()
    dailyGuidance.value = current
    rememberDailyGuidance(current)
  } catch (error) {
    dailyGuidanceError.value = error instanceof Error ? error.message : '今天的建议读取失败'
  } finally {
    dailyGuidanceLoading.value = false
  }
}

async function refreshDailyGuidance(refresh = true) {
  if (dailyGuidanceRefreshing.value) return
  dailyGuidanceRefreshing.value = true
  dailyGuidanceError.value = ''
  dailyGuidanceErrorSource.value = 'refresh'
  try {
    dailyGuidance.value = await generateDailyGuidance(refresh)
    rememberDailyGuidance(dailyGuidance.value)
  } catch (error) {
    dailyGuidanceError.value = error instanceof Error ? error.message : '今天的建议生成失败，请重试'
  } finally {
    dailyGuidanceRefreshing.value = false
    dailyGuidanceLoading.value = false
  }
}

function guidanceBusinessDate() {
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date())
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${value.year}-${value.month}-${value.day}`
}

function dailyGuidanceCacheKey() {
  return `wao-daily-guidance-v1:${session.currentUser.id || 'wg'}`
}

function readCachedDailyGuidance(): DailyGuidance | null {
  try {
    const raw = sessionStorage.getItem(dailyGuidanceCacheKey())
    if (!raw) return null
    const cached = JSON.parse(raw) as DailyGuidance
    return cached.status === 'ready' && cached.date === guidanceBusinessDate() && cached.content ? cached : null
  } catch {
    return null
  }
}

function rememberDailyGuidance(value: DailyGuidance | null) {
  try {
    if (value?.status === 'ready' && value.content && value.date === guidanceBusinessDate()) {
      sessionStorage.setItem(dailyGuidanceCacheKey(), JSON.stringify(value))
    } else {
      sessionStorage.removeItem(dailyGuidanceCacheKey())
    }
  } catch {
    // 隐私模式禁用会话存储时仍以服务端预生成结果为准。
  }
}

async function launchSuggestion(suggestion: AiEvolutionSuggestion) {
  if (!goalProjectId.value) {
    toast('请先在 AI 工作流中接入一个项目', 'ri-information-line')
    view.value = 'workflows'
    return
  }
  launchingSuggestionId.value = suggestion.id
  try {
    const run = await launchEvolutionSuggestion(suggestion.id, goalProjectId.value)
    runs.value = [run, ...runs.value.filter((item) => item.id !== run.id)]
    view.value = 'runs'
    toast(`已让 AI 开始：${suggestion.title}`, 'ri-rocket-2-line')
    await refreshEvolution()
  } catch (error) {
    toast(error instanceof Error ? error.message : '建议派发失败', 'ri-error-warning-line')
  } finally {
    launchingSuggestionId.value = null
  }
}

function providerLabel(provider: string) {
  return provider === 'codex' ? 'Codex' : provider === 'claude' ? 'Claude' : provider
}

function quotaClass(remaining: number) {
  return remaining < 20 ? 'danger' : remaining < 50 ? 'warn' : 'good'
}

function resetText(value: string | null) {
  return value ? `${timeText(value)} 刷新` : '刷新时间未知'
}

watch(view, (next) => {
  if (next === 'workflows' && !workflows.value.length) void refreshWorkflows()
})

function revealAiArea() {
  const target = route.hash === '#ai-launcher' ? '#ai-launcher' : '#ai-console'
  void nextTick(() => document.querySelector(target)?.scrollIntoView({ block: 'start' }))
}

watch([() => route.query.console, () => route.hash], ([next]) => {
  view.value = consoleViewFromQuery(next)
  if (next) revealAiArea()
})

watch(activeWorkflows, (next) => {
  if (!next.some((workflow) => workflow.projectId === goalProjectId.value)) {
    goalProjectId.value = next[0]?.projectId ?? ''
  }
})

function syncGoalRunnerDefault() {
  const preferred = goalWorkflow.value?.config.preferredRunnerId ?? ''
  goalRunnerId.value = goalRunnerCandidates.value.some((runner) => runner.id === preferred) ? preferred : ''
}

watch(goalProjectId, () => {
  goalRunnerManuallySelected.value = false
  syncGoalRunnerDefault()
})

// 工作流和电脑列表并发加载；电脑晚到时仍应带出项目首选电脑，后续轮询不能覆盖用户手选。
watch(goalRunnerCandidates, () => {
  if (!goalRunnerManuallySelected.value) syncGoalRunnerDefault()
})

async function launchGoal() {
  const normalizedGoal = goal.value.trim()
  if (!normalizedGoal) {
    toast('请先写下你想让 AI 达成的目标', 'ri-information-line')
    return
  }
  if (!goalProjectId.value) {
    toast('请先圬「AI 工作流」中接入一个项目', 'ri-information-line')
    return
  }
  if (goalImageUploads.value) {
    toast('图片还在上传，请稍候再派发', 'ri-information-line')
    return
  }
  launchingGoal.value = true
  try {
    const run = await launchAutonomousGoal(goalProjectId.value, normalizedGoal, goalRunnerId.value || null)
    runs.value = [run, ...runs.value.filter((item) => item.id !== run.id)]
    goal.value = ''
    view.value = 'runs'
    toast('目标已交给 AI，它会先盘点资源并自己拆解推进', 'ri-rocket-2-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '目标派发失败', 'ri-error-warning-line')
  } finally {
    launchingGoal.value = false
  }
}

/** 自主目标里的图片走统一图床，编辑器拿到稳定地址后再内联进 Markdown。 */
async function onGoalImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) {
    toast('图片超过 10MB，已跳过', 'ri-error-warning-line')
    done(null, '')
    return
  }
  goalImageUploads.value += 1
  try {
    const image = await data.uploadImage(file, '自主目标')
    if (!image) toast('图片上传失败，请重试', 'ri-error-warning-line')
    done(image?.url ?? null, image?.name ?? file.name)
  } catch {
    toast('图片上传失败，请重试', 'ri-error-warning-line')
    done(null, '')
  } finally {
    goalImageUploads.value -= 1
  }
}

/** 某台电脑当前正在跑的任务，用于电脑卡片直接看到它在忙什么。 */
function runsOnRunner(runnerId: string) {
  return activeRuns.value.filter((run) => run.runnerId === runnerId)
}

function runnersFor(engine: AiEngine | null) {
  return runners.value.filter((runner) => !engine || runner.engines.includes(engine))
}

function startEditingRunner(runner: AiRunner) {
  editingRunnerId.value = runner.id
  editingRunnerValue.value = runner.maxConcurrency
  editingRunnerSaving.value = false
}

async function saveRunnerConcurrency(runner: AiRunner) {
  if (editingRunnerValue.value < 1 || editingRunnerValue.value > 8) {
    toast('并发数必须在 1 到 8 之间', 'ri-error-warning-line')
    return
  }
  editingRunnerSaving.value = true
  try {
    const updated = await updateRunnerMaxConcurrency(runner.id, editingRunnerValue.value)
    const index = runners.value.findIndex((r) => r.id === runner.id)
    if (index >= 0) runners.value[index] = { ...updated, configuredMaxConcurrency: updated.maxConcurrency }
    editingRunnerId.value = null
  } catch (error) {
    toast(error instanceof Error ? error.message : '保存失败', 'ri-error-warning-line')
  } finally {
    editingRunnerSaving.value = false
  }
}

function cancelEditingRunner() {
  editingRunnerId.value = null
}

/** 一键把最新 Runner 脚本下发给所有执行电脑；各电脑空闲后自动更新重启。 */
async function pushRunnerUpdate() {
  updatingRunners.value = true
  try {
    const next = await requestRunnerScriptUpdate()
    runners.value = next
    const pending = next.filter((runner) => runner.updateRequested).length
    toast(
      pending
        ? `已通知 ${pending} 台电脑更新，空闲后自动重启生效`
        : '所有执行电脑的 Runner 脚本已是最新版',
      'ri-download-cloud-2-line',
    )
  } catch (error) {
    toast(error instanceof Error ? error.message : 'Runner 更新下发失败', 'ri-error-warning-line')
  } finally {
    updatingRunners.value = false
  }
}
async function wakeRunner(runner: AiRunner) {
  if (!runner.wakeConfigured) {
    toast('这台电脑尚未自动上报唤醒信息，请先正常启动一次 Runner', 'ri-information-line')
    return
  }
  wakingRunnerId.value = runner.id
  try {
    const result = await wakeAiRunner(runner.id)
    toast(result.message, 'ri-send-plane-line')
    await refresh()
  } catch (error) {
    toast(error instanceof Error ? error.message : '唤醒包发送失败', 'ri-error-warning-line')
  } finally {
    wakingRunnerId.value = null
  }
}

async function loadRunnerLog() {
  if (!logRunner.value) return
  runnerLogLoading.value = true
  runnerLogError.value = ''
  try {
    runnerLog.value = await fetchAiRunnerLog(logRunner.value.id)
  } catch (error) {
    runnerLogError.value = error instanceof Error ? error.message : 'Runner 日志读取失败'
  } finally {
    runnerLogLoading.value = false
  }
}

async function showRunnerLog(runner: AiRunner) {
  logRunner.value = runner
  runnerLog.value = null
  await loadRunnerLog()
}

function closeRunnerLog() {
  logRunner.value = null
  runnerLog.value = null
  runnerLogError.value = ''
}

async function copyRunnerLog() {
  if (!runnerLog.value?.content) return
  try {
    await navigator.clipboard.writeText(runnerLog.value.content)
    toast('Runner 日志已复制', 'ri-file-copy-line')
  } catch {
    toast('复制失败，请在日志中手动选择文本', 'ri-error-warning-line')
  }
}

/** 解除一台电脑的接入：删掉节点记录并作废它的令牌，那台电脑上的 Runner 会自行退出。 */
async function removeRunner(runner: AiRunner) {
  const busy = runner.activeRuns
    ? `这台电脑上还有 ${runner.activeRuns} 条运行，解除前需要先取消或等它跑完。`
    : ''
  if (busy) {
    toast(busy, 'ri-information-line')
    return
  }
  if (
    !window.confirm(
      `解除「${runner.name}」的接入？\n它的节点令牌会立刻作废，那台电脑上的 Runner 会自动退出；` +
        `历史运行记录保留，需要再次接入得重新生成配对码。`,
    )
  ) {
    return
  }
  removingRunnerId.value = runner.id
  try {
    runners.value = await removeAiRunner(runner.id)
    toast(`已解除「${runner.name}」的接入`, 'ri-computer-line')
    await refresh()
  } catch (error) {
    toast(error instanceof Error ? error.message : '解除接入失败', 'ri-error-warning-line')
  } finally {
    removingRunnerId.value = null
  }
}

function startEditing(workflow: AiWorkflow) {
  editingProjectId.value = workflow.projectId
  Object.assign(form, {
    enabled: workflow.config.enabled,
    repoUrl: workflow.config.repoUrl,
    defaultBranch: workflow.config.defaultBranch,
    workspaceDir: workflow.config.workspaceDir,
    defaultEngine: workflow.config.defaultEngine,
    preferredRunnerId: workflow.config.preferredRunnerId ?? '',
    maxParallelRuns: workflow.config.maxParallelRuns,
  })
}

async function saveWorkflow(projectId: string) {
  saving.value = true
  try {
    await saveAiProjectConfig(projectId, {
      enabled: form.enabled,
      repoUrl: form.repoUrl.trim(),
      defaultBranch: form.defaultBranch.trim() || 'main',
      workspaceDir: form.workspaceDir.trim(),
      defaultEngine: form.defaultEngine,
      preferredRunnerId: form.preferredRunnerId || null,
      maxParallelRuns: form.maxParallelRuns,
    })
    editingProjectId.value = null
    await refreshWorkflows()
    toast('AI 工作流已保存', 'ri-check-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI 工作流保存失败', 'ri-error-warning-line')
  } finally {
    saving.value = false
  }
}

/** 删除工作流配置前明确告知影响；项目和历史运行不会被删除。 */
async function removeWorkflow(workflow: AiWorkflow) {
  if (
    !window.confirm(
      `删除“${workflow.projectName}”的 AI 工作流？\n仓库、执行电脑和并发配置会被删除，历史运行记录保留；` +
        (workflow.projectDeleted ? '该项目已删除，工作流删除后将从列表消失。' : '项目仍保留，以后可以重新接入。'),
    )
  ) {
    return
  }
  removingWorkflowId.value = workflow.projectId
  try {
    await removeAiWorkflow(workflow.projectId)
    if (editingProjectId.value === workflow.projectId) editingProjectId.value = null
    await refreshWorkflows()
    toast(`已删除“${workflow.projectName}”的 AI 工作流`, 'ri-delete-bin-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI 工作流删除失败', 'ri-error-warning-line')
  } finally {
    removingWorkflowId.value = null
  }
}

async function generatePairCode() {
  try {
    const result = await createAiPairCode()
    pairCode.value = result.code
    pairExpiresAt.value = result.expiresAt
    toast('已生成一次性配对码，十分钟内有效', 'ri-computer-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '配对码生成失败', 'ri-error-warning-line')
  }
}

function openRun(run: AiRun) {
  router.push({ path: `/projects/${run.projectId}`, query: { task: run.taskId } })
}

/** 根据 run 找到对应的工作流配置，用于拼接 GitHub 链接。 */
function findWorkflowForRun(run: AiRun) {
  return workflows.value.find((w) => w.projectId === run.projectId)
}

function inspectRun(run: AiRun) {
  selectedRun.value = run
}

/** 失败的运行可以带着报错重跑一次：AI 接着这个错往下修，不用从头再来。 */
async function rerun(run: AiRun) {
  if (rerunning.value) return
  rerunning.value = true
  try {
    const next = await rerunAiRun(run.id)
    selectedRun.value = null
    await refresh()
    toast(`已带着上次的报错重新排队：${next.taskTitle}`, 'ri-refresh-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '重跑失败', 'ri-error-warning-line')
  } finally {
    rerunning.value = false
  }
}

function resultPreview(run: AiRun) {
  if (!run.resultSummary) return run.status === 'failed' ? '执行节点没有返回错误详情' : ''
  const meaningfulLine = run.resultSummary
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line && !line.startsWith('工作目录：') && !line.startsWith('执行工具：') && !line.startsWith('本机日志：'))
  return meaningfulLine || run.resultSummary.trim()
}

function timeText(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function durationText(startedAt: string | null, finishedAt: string | null) {
  if (!startedAt) return '尚未开始'
  const end = finishedAt ? new Date(finishedAt).getTime() : Date.now()
  const seconds = Math.max(0, Math.floor((end - new Date(startedAt).getTime()) / 1000))
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  return minutes < 60 ? `${minutes} 分钟` : `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`
}

function progressAge(value: string | null) {
  if (!value) return '等待反馈'
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000))
  if (seconds < 10) return '刚刚更新'
  if (seconds < 60) return `${seconds} 秒前更新`
  return `${Math.floor(seconds / 60)} 分钟前更新`
}

onMounted(() => {
  void refresh()
  void refreshWorkflows()
  void refreshEvolution()
  void loadDailyGuidance()
  timer = setInterval(refresh, 5_000)
  evolutionTimer = setInterval(refreshEvolution, 60_000)
  if (route.query.console) revealAiArea()
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (evolutionTimer) clearInterval(evolutionTimer)
})
</script>

<template>
  <div class="home">
    <section class="hero">
      <div>
        <div class="eyebrow">AI CONTROL CENTER</div>
        <!-- 核心理念摆在首屏第一行：这套系统的用法就是「你敢想，AI 就敢做」 -->
        <h1 class="slogan">娃有多大胆，AI 有多大产</h1>
        <div class="hero-now"><i class="ri-pulse-line"></i>现在有 <b>{{ activeRuns.length }}</b> 个任务在跑</div>
        <p>同一个项目可以交给不同电脑上的不同 AI 工具，每个任务使用独立会话和 Git worktree。</p>
        <div class="engine-status">
          <span v-for="engine in engineStatus" :key="engine.id" :class="{ dim: !engine.online }">
            <i :class="engine.icon"></i>{{ engine.label }}
            <b>{{ engine.online ? `${engine.online} 台在线` : engine.total ? '全部离线' : '未接入' }}</b>
          </span>
        </div>
        <button class="hero-link" @click="router.push('/dashboard')">
          <i class="ri-line-chart-line"></i>查看数据仪表盘
        </button>
      </div>
      <div class="hero-metrics" :class="{ single: !showSessionQuota && !showWeeklyQuota, 'with-two-quotas': showSessionQuota && showWeeklyQuota }">
        <div v-if="primarySessionQuota" class="quota-card session-quota" :class="quotaClass(primarySessionQuota.remainingPercent)">
          <span><i class="ri-timer-line"></i>Codex 本轮剩余额度</span>
          <strong>{{ primarySessionQuota.remainingPercent }}<em>%</em></strong>
          <small>5 小时周期 · {{ resetText(primarySessionQuota.resetsAt) }}</small>
        </div>
        <div v-if="showWeeklyQuota" class="quota-card weekly-quota" :class="primaryWeeklyQuota ? quotaClass(primaryWeeklyQuota.remainingPercent) : 'unknown'">
          <span><i class="ri-speed-up-line"></i>周剩余额度</span>
          <template v-if="evolutionLoading && !evolution"><strong>···</strong><small>正在读取真实额度</small></template>
          <template v-else-if="primaryWeeklyQuota">
            <strong>{{ primaryWeeklyQuota.remainingPercent }}<em>%</em></strong>
            <small>
              {{ providerLabel(primaryWeeklyQuota.provider) }} · {{ resetText(primaryWeeklyQuota.resetsAt) }}
            </small>
          </template>
        </div>
        <div class="capacity" :style="{ '--capacity': `${totalSlots ? Math.min(100, usedSlots / totalSlots * 100) : 0}%` }">
          <div class="capacity-orbit" aria-hidden="true"><i></i><b></b><em></em></div>
          <div class="capacity-copy">
            <div class="capacity-num">{{ usedSlots }}<span>/ {{ totalSlots }}</span></div>
            <div>执行槽位占用</div>
            <div class="capacity-bar"><i></i></div>
          </div>
        </div>
      </div>
    </section>

    <section class="daily-guidance" aria-labelledby="daily-guidance-title">
      <header>
        <span class="guidance-mark"><i class="ri-compass-3-line"></i></span>
        <div>
          <div class="eyebrow dark">FOR THIS MOMENT</div>
          <h2 id="daily-guidance-title">给此刻的你</h2>
          <p>结合今天的真实任务，由后台提前整理；打开首页即可查看，换设备也能继续看。</p>
        </div>
        <button
          v-if="dailyGuidance?.status === 'ready'"
          class="guidance-refresh"
          :disabled="dailyGuidanceRefreshing"
          @click="refreshDailyGuidance(true)"
        >
          <i :class="dailyGuidanceRefreshing ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>
          {{ dailyGuidanceRefreshing ? '正在换一组…' : '换一组' }}
        </button>
      </header>
      <div v-if="dailyGuidanceLoading || (dailyGuidanceRefreshing && !dailyGuidance?.content)" class="guidance-state">
        <i class="ri-loader-4-line spin"></i>
        <div><b>正在读取今天的建议</b><span>后台已提前整理，当前只需取回保存的结果。</span></div>
      </div>
      <div v-else-if="dailyGuidance?.status === 'ready' && dailyGuidance.content" class="guidance-content">
        <div class="md-body" v-html="renderMarkdown(dailyGuidance.content)"></div>
        <small v-if="dailyGuidance.generatedAt"><i class="ri-shield-check-line"></i>{{ timeText(dailyGuidance.generatedAt) }} 生成 · 仅使用你的 WAO 任务上下文</small>
        <div v-if="dailyGuidanceError" class="guidance-inline-error" role="alert">
          <i class="ri-error-warning-line"></i><span>{{ dailyGuidanceErrorSource === 'refresh' ? '换一组' : '读取' }}失败：{{ dailyGuidanceError }}，现有建议已保留。</span>
          <button :disabled="dailyGuidanceRefreshing" @click="dailyGuidanceErrorSource === 'refresh' ? refreshDailyGuidance(true) : loadDailyGuidance()">重试</button>
        </div>
      </div>
      <div v-else-if="dailyGuidance?.status === 'missing'" class="guidance-state">
        <i class="ri-time-line"></i>
        <div>
          <b>今天的建议正在后台准备</b>
          <span>{{ dailyGuidance.message || '定时任务会在本地 Codex 可用后自动补齐，无需停留等待。' }}</span>
        </div>
        <button @click="loadDailyGuidance"><i class="ri-refresh-line"></i>重新读取</button>
      </div>
      <div v-else class="guidance-state error">
        <i class="ri-cloud-off-line"></i>
        <div>
          <b>{{ dailyGuidanceError ? '这次没有生成成功' : '本地 Codex 暂未连接' }}</b>
          <span>{{ dailyGuidanceError || dailyGuidance?.message || '请检查 AI 控制台的执行电脑后重试。' }}</span>
        </div>
        <button @click="loadDailyGuidance">
          <i class="ri-refresh-line"></i>重试
        </button>
      </div>
    </section>

    <section class="evolution-panel" aria-labelledby="evolution-title">
      <header>
        <span class="evolution-mark"><i class="ri-seedling-line"></i></span>
        <div>
          <div class="eyebrow dark">CONTINUOUS EVOLUTION</div>
          <h2 id="evolution-title">让剩余额度持续完善 WAO</h2>
          <div class="evolution-policy"><i class="ri-user-smile-line"></i>建议挖掘和自动派发只选择用户可感知的功能与提效改进</div>
          <p v-if="evolution">{{ evolution.autoMessage }}</p>
          <p v-else-if="evolutionError" class="evolution-error">{{ evolutionError }}</p>
          <p v-else>正在读取真实周额度和自动进化状态…</p>
        </div>
        <span v-if="evolution" class="auto-state" :class="evolution.autoStatus">
          <i :class="evolution.autoStatus === 'ready' ? 'ri-checkbox-circle-line' : 'ri-time-line'"></i>
          {{ evolution.autoStatus === 'ready' ? '可自动执行' : evolution.autoStatus === 'busy' ? 'AI 正忙' : '等待条件' }}
        </span>
        <button
          v-if="evolution"
          class="secondary suggestion-refresh"
          :disabled="suggestionsRefreshing"
          @click="refreshSuggestions()"
        >
          <i :class="suggestionsRefreshing ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>
          {{ suggestionsRefreshing ? '正在挖掘' : evolution.suggestions.length ? '刷新换一批' : '挖掘 5 个任务' }}
        </button>
        <button
          v-if="evolution"
          class="secondary schedule-toggle"
          :aria-expanded="evolutionScheduleOpen"
          aria-controls="evolution-schedule-form"
          @click="evolutionScheduleOpen = !evolutionScheduleOpen"
        >
          <i class="ri-settings-3-line"></i>自动执行设置
        </button>
        <button v-else-if="evolutionError" class="secondary" @click="refreshEvolution"><i class="ri-refresh-line"></i>重试</button>
      </header>
      <form
        v-if="evolution && evolutionScheduleOpen"
        id="evolution-schedule-form"
        class="evolution-schedule"
        @submit.prevent="saveEvolutionSchedule"
      >
        <div>
          <strong>自动检测与派发频率</strong>
          <span>从最近一次 AI 任务结束后开始计时；达到条件时按设置数量从真实建议中轮换派发。</span>
        </div>
        <label>
          <span>每隔</span>
          <input
            v-model.number="evolutionScheduleDraft.intervalHours"
            type="number"
            min="1"
            max="168"
            required
            :disabled="evolutionScheduleSaving"
            @input="evolutionScheduleSaved = false"
          >
          <span>小时检测</span>
        </label>
        <label>
          <span>每次最多触发</span>
          <select
            v-model.number="evolutionScheduleDraft.batchSize"
            :disabled="evolutionScheduleSaving"
            @change="evolutionScheduleSaved = false"
          >
            <option v-for="count in 5" :key="count" :value="count">{{ count }} 个任务</option>
          </select>
        </label>
        <div v-if="evolutionScheduleError" class="schedule-feedback error" role="alert">
          保存失败：{{ evolutionScheduleError }}。草稿仍在，可直接重试。
        </div>
        <div v-else-if="evolutionScheduleSaved" class="schedule-feedback saved">
          <i class="ri-checkbox-circle-line"></i>已保存
        </div>
        <button type="submit" :disabled="evolutionScheduleSaving || !evolutionScheduleDirty">
          <i :class="evolutionScheduleSaving ? 'ri-loader-4-line spin' : 'ri-save-3-line'"></i>
          {{ evolutionScheduleSaving ? '保存中…' : '保存设置' }}
        </button>
      </form>
      <div v-if="suggestionsRefreshing" class="suggestion-feedback loading" role="status" aria-live="polite">
        <i class="ri-loader-4-line spin"></i>
        <span>正在从真实资料生成新一批建议，当前建议会保留到生成成功。</span>
      </div>
      <div v-else-if="suggestionError" class="suggestion-feedback error" role="alert">
        <i class="ri-error-warning-line"></i>
        <span>换一批失败：{{ suggestionError }}。原建议已保留。</span>
        <button :disabled="suggestionsRefreshing" @click="refreshSuggestions(false)">重试</button>
      </div>
      <div v-if="evolutionLoading && !evolution" class="suggestion-loading">
        <i class="ri-loader-4-line spin"></i>正在整理改进建议…
      </div>
      <div v-else-if="suggestionsRefreshing && !evolution?.suggestions.length" class="suggestion-loading">
        <i class="ri-loader-4-line spin"></i>正在从知识库和飞书同步资料中挖掘 5 个任务，可能需要几分钟…
      </div>
      <div v-else-if="evolution?.suggestions.length" class="suggestion-grid">
        <article v-for="suggestion in evolution.suggestions" :key="suggestion.id" class="suggestion-card">
          <span><i :class="suggestion.icon"></i></span>
          <div>
            <h3>{{ suggestion.title }}</h3>
            <p>{{ suggestion.description }}</p>
            <a v-if="suggestion.sourceUrl" class="suggestion-source" :href="suggestion.sourceUrl">
              <i class="ri-links-line"></i>{{ suggestion.sourceTitle }}
            </a>
          </div>
          <button
            :disabled="launchingSuggestionId !== null || !activeWorkflows.length"
            @click="launchSuggestion(suggestion)"
          >
            <i :class="launchingSuggestionId === suggestion.id ? 'ri-loader-4-line spin' : 'ri-play-circle-line'"></i>
            {{ launchingSuggestionId === suggestion.id ? '正在派发' : '直接执行' }}
          </button>
        </article>
      </div>
      <div v-else-if="evolution" class="suggestion-empty">
        <i class="ri-database-2-line"></i>
        <strong>还没有真实资料建议</strong>
        <span>系统会读取知识库和已同步的飞书归档，不会用固定模板或演示数据凑数。</span>
      </div>
      <footer v-if="visibleWeeklyQuotas.length">
        <span v-for="quota in visibleWeeklyQuotas" :key="quota.accountId">
          {{ quota.label }} · 周剩余 <b>{{ quota.remainingPercent }}%</b>
        </span>
        <small>只使用供应商真实七天窗口；未知额度不会参与自动派发。</small>
      </footer>
    </section>

    <section id="ai-launcher" class="goal-launcher" aria-labelledby="goal-launcher-title">
      <div class="goal-heading">
        <span class="goal-icon"><i class="ri-sparkling-2-line"></i></span>
        <div>
          <div class="eyebrow dark">AUTONOMOUS GOAL</div>
          <h2 id="goal-launcher-title">给我一个目标，剩下的交给 AI</h2>
          <p>AI 会先盘点项目里的代码、文档和工具，自己拆解、执行和验证。</p>
        </div>
      </div>
      <div class="goal-compose">
        <MarkdownEditor
          v-model="goal"
          placeholder="例如：把《黄帝内经》和《易经》结合 AI，做成一个每天可以给自己算一卦的产品……可直接粘贴多张图片"
          :min-height="78"
          :readonly="launchingGoal"
          @keydown.ctrl.enter.prevent="launchGoal"
          @keydown.meta.enter.prevent="launchGoal"
          @upload-image="onGoalImage"
        />
        <div class="goal-actions">
          <label>
            <span>放进哪条工作流</span>
            <select
              v-model="goalProjectId"
              class="goal-workflow-select"
              :disabled="workflowsLoading || launchingGoal || !activeWorkflows.length"
            >
              <option v-if="!activeWorkflows.length" value="">还没有已启用的工作流</option>
              <option v-for="workflow in activeWorkflows" :key="workflow.projectId" :value="workflow.projectId">
                {{ workflow.projectName }} · {{ engineLabel(workflow.config.defaultEngine) }}
              </option>
            </select>
          </label>
          <label class="goal-runner-field" :title="goalRunnerHint">
            <span>执行电脑</span>
            <select
              v-model="goalRunnerId"
              class="goal-runner-select"
              :disabled="launchingGoal || !goalWorkflow"
              @change="goalRunnerManuallySelected = true"
            >
              <option value="">自动分配</option>
              <option v-for="runner in goalRunnerCandidates" :key="runner.id" :value="runner.id">
                {{ runner.name }}{{ runner.online ? ` · 在线 ${runner.activeRuns}/${runner.maxConcurrency}` : ' · 离线' }}
              </option>
            </select>
          </label>
          <span class="goal-shortcut">
            <template v-if="goalImageUploads">{{ goalImageUploads }} 张图片上传中 · </template>
            {{ goal.length }} 字 · Ctrl/Command + Enter
          </span>
          <button
            v-if="activeWorkflows.length"
            class="goal-submit"
            :disabled="launchingGoal || goalImageUploads > 0 || !goal.trim() || !goalProjectId"
            @click="launchGoal"
          >
            <i :class="launchingGoal ? 'ri-loader-4-line spin' : 'ri-rocket-2-line'"></i>
            {{ launchingGoal ? '正在派发…' : '让 AI 开始' }}
          </button>
          <button v-else class="goal-submit setup" :disabled="workflowsLoading" @click="view = 'workflows'">
            <i class="ri-settings-3-line"></i>{{ workflowsLoading ? '正在读取…' : '先接入工作流' }}
          </button>
        </div>
      </div>
    </section>

    <nav id="ai-console" class="console-tabs">
      <button
        v-for="item in VIEWS"
        :key="item.id"
        :class="{ on: view === item.id }"
        @click="view = item.id"
      >
        <i :class="item.icon"></i>{{ item.label }}
        <em v-if="item.id === 'runs' && activeRuns.length">{{ activeRuns.length }}</em>
        <em v-else-if="item.id === 'runners' && onlineRunners.length">{{ onlineRunners.length }}</em>
      </button>
      <span class="live"><i></i>每 5 秒刷新</span>
    </nav>

    <template v-if="view === 'runs'">
      <section class="run-section">
        <div class="section-head">
          <div>
            <h2>正在运行</h2>
            <p>点击卡片查看 AI 的实时反馈和执行档案。</p>
          </div>
        </div>
        <div v-if="loading" class="empty"><i class="ri-loader-4-line spin"></i>正在读取 AI 运行状态…</div>
        <div v-else-if="!activeRuns.length" class="empty ready">
          <i class="ri-robot-2-line"></i>
          <b>当前没有任务在运行</b>
          <span>打开项目任务，点击 “AI run” 选择电脑和 AI 工具即可开始。</span>
        </div>
        <div v-else class="run-grid">
          <button v-for="run in activeRuns" :key="run.id" class="run-card" @click="inspectRun(run)">
            <div class="run-top">
              <span class="status" :style="{ color: statusMeta[run.status].color, background: statusMeta[run.status].bg }">
                <i :class="[statusMeta[run.status].icon, { spin: run.status === 'running' }]"></i>
                {{ statusMeta[run.status].label }}
              </span>
              <span class="run-time">{{ timeText(run.startedAt || run.createdAt) }}</span>
            </div>
            <h3>{{ run.taskTitle }}</h3>
            <div class="run-project"><i class="ri-stack-line"></i>{{ run.projectName }}</div>
            <div class="live-progress">
              <span class="live-pulse"></span>
              <div><b>{{ run.latestProgress || '正在等待第一条 AI 反馈…' }}</b><small>{{ progressAge(run.progressUpdatedAt) }}</small></div>
            </div>
            <div class="run-foot">
              <span class="engine-tag" :style="{ color: engineColor(run.engine) }">
                <i :class="engineIcon(run.engine)"></i>{{ engineLabel(run.engine) }}
              </span>
              <span class="foot-runner"><i class="ri-computer-line"></i>{{ run.runnerName || '等待自动分配' }}</span>
              <i class="ri-arrow-right-line"></i>
            </div>
          </button>
        </div>
      </section>

      <section v-if="recentRuns.length" class="recent-section">
        <div class="section-head">
          <div><h2>最近完成</h2><p>完成后任务会自动进入“待验证”。</p></div>
        </div>
        <div class="recent-list">
          <button v-for="run in recentRuns" :key="run.id" @click="inspectRun(run)">
            <span class="recent-icon" :style="{ color: metaFor(run).color, background: metaFor(run).bg }">
              <i :class="metaFor(run).icon"></i>
            </span>
            <span class="recent-main">
              <b>{{ run.taskTitle }}</b>
              <em>
                {{ run.projectName }} · {{ engineLabel(run.engine) }} · {{ run.runnerName || '未分配电脑' }}
                <span v-if="usageSummary(run.usage)" class="recent-usage">· {{ usageSummary(run.usage) }}</span>
              </em>
              <small v-if="run.status === 'failed'">{{ resultPreview(run) }}</small>
            </span>
            <span class="recent-status">{{ metaFor(run).label }}</span>
            <span class="run-time">{{ timeText(run.finishedAt) }}</span>
            <i class="ri-arrow-right-s-line"></i>
          </button>
        </div>
      </section>
    </template>

    <template v-else-if="view === 'runners'">
      <section class="run-section">
        <div class="section-head">
          <div>
            <h2>执行电脑</h2>
            <p>每台电脑自己声明装了哪些 AI 工具；登录态和源码都留在本机。</p>
          </div>
          <button class="pair-btn ghost" :disabled="updatingRunners" @click="pushRunnerUpdate">
            <i :class="updatingRunners ? 'ri-loader-4-line spin' : 'ri-download-cloud-2-line'"></i>更新 Runner 脚本
          </button>
          <button class="pair-btn" @click="generatePairCode"><i class="ri-link"></i>生成配对码</button>
        </div>
        <div v-if="pairCode" class="pair-code">
          <div>
            <b>{{ pairCode }}</b>
            <small>十分钟内有效 · 到期 {{ pairExpiresAt.replace('T', ' ') }}</small>
          </div>
          <code>python wao-ai-runner.py --server https://wao.wayuwa.cn --pair-code {{ pairCode }} --name 电脑名 --engines codex,claude,qoder</code>
        </div>
        <div v-if="!runners.length && !loading" class="empty ready">
          <i class="ri-computer-line"></i>
          <b>还没有电脑接入</b>
          <span>点击右上角生成配对码，在目标电脑上启动 wao-ai-runner.py。</span>
        </div>
        <div v-else class="runner-grid">
          <article v-for="runner in runners" :key="runner.id" class="runner-card" :class="{ offline: !runner.online }">
            <header>
              <span class="online-dot"></span>
              <b>{{ runner.name }}</b>
              <span class="runner-state">{{ runner.online ? '在线' : '离线' }}</span>
              <button
                class="icon-btn danger"
                :disabled="removingRunnerId === runner.id"
                :title="runner.activeRuns ? '这台电脑还有任务在跑，暂时不能解除' : '解除这台电脑的接入'"
                aria-label="解除接入"
                @click="removeRunner(runner)"
              >
                <i :class="removingRunnerId === runner.id ? 'ri-loader-4-line spin' : 'ri-delete-bin-line'"></i>
              </button>
            </header>
            <div class="runner-platform"><i class="ri-hard-drive-2-line"></i>{{ runner.platform || '未知平台' }}</div>
            <div class="engine-tags">
              <span
                v-for="engine in runner.engines"
                :key="engine"
                class="engine-tag boxed"
                :style="{ color: engineColor(engine), borderColor: `${engineColor(engine)}33` }"
              >
                <i :class="engineIcon(engine)"></i>{{ engineLabel(engine) }}
              </span>
            </div>
            <div class="slot-line">
              <template v-if="editingRunnerId === runner.id">
                <span class="concurrency-edit">
                  并发
                  <input
                    v-model.number="editingRunnerValue"
                    type="number"
                    min="1"
                    max="8"
                    :disabled="editingRunnerSaving"
                    @keyup.enter="saveRunnerConcurrency(runner)"
                    @keyup.esc="cancelEditingRunner"
                  />
                  <button
                    class="icon-btn"
                    :disabled="editingRunnerSaving"
                    aria-label="保存"
                    @click="saveRunnerConcurrency(runner)"
                  >
                    <i :class="editingRunnerSaving ? 'ri-loader-4-line spin' : 'ri-check-line'"></i>
                  </button>
                  <button
                    class="icon-btn secondary"
                    :disabled="editingRunnerSaving"
                    aria-label="取消"
                    @click="cancelEditingRunner"
                  >
                    <i class="ri-close-line"></i>
                  </button>
                </span>
              </template>
              <template v-else>
                <span>
                  槽位 {{ runner.activeRuns }} / {{ runner.maxConcurrency }}
                  <button class="icon-btn secondary" aria-label="修改并发数" @click="startEditingRunner(runner)">
                    <i class="ri-edit-line"></i>
                  </button>
                </span>
              </template>
              <span class="run-time">{{ runner.lastSeenAt ? `心跳 ${timeText(runner.lastSeenAt)}` : '从未上报' }}</span>
            </div>
            <div class="slot-bar">
              <i :style="{ width: `${Math.min(100, runner.activeRuns / Math.max(1, runner.maxConcurrency) * 100)}%` }"></i>
            </div>
            <div v-if="runner.configuredMaxConcurrency != null" class="runner-config-hint">
              <i class="ri-settings-3-line"></i>后台已配置为 {{ runner.configuredMaxConcurrency }} 线程
            </div>
            <div v-if="runner.updateRequested" class="runner-script-hint pending">
              <i class="ri-download-cloud-2-line"></i>
              {{ runner.scriptUpToDate == null
                ? '已下发更新；旧版 Runner 不支持自动更新，需手动替换一次'
                : '更新已下发，等该电脑空闲后自动重启生效' }}
            </div>
            <div v-else-if="runner.scriptUpToDate === false" class="runner-script-hint stale">
              <i class="ri-refresh-line"></i>Runner 脚本落后于服务端版本，可点上方「更新 Runner 脚本」
            </div>
            <button class="runner-log-btn" @click="showRunnerLog(runner)">
              <i class="ri-terminal-box-line"></i>查看 Runner 日志
            </button>
            <div v-if="!runner.online" class="wake-panel">
                <button class="wake-btn" :disabled="wakingRunnerId === runner.id || !runner.wakeConfigured" @click="wakeRunner(runner)">
                  <i :class="wakingRunnerId === runner.id ? 'ri-loader-4-line spin' : 'ri-shut-down-line'"></i>
                  {{ wakingRunnerId === runner.id ? '正在发送唤醒包' : '远程唤醒电脑' }}
                </button>
                <small v-if="!runner.wakeConfigured">首次在线时会自动准备，无需手动填写网络参数</small>
                <small v-if="runner.lastWakeRequestedAt">上次请求 {{ timeText(runner.lastWakeRequestedAt) }}，是否上线以心跳为准</small>
            </div>
            <ul v-if="runsOnRunner(runner.id).length" class="runner-tasks">
              <li v-for="run in runsOnRunner(runner.id)" :key="run.id">
                <i :class="engineIcon(run.engine)" :style="{ color: engineColor(run.engine) }"></i>
                <button @click="inspectRun(run)">{{ run.taskTitle }}</button>
              </li>
            </ul>
            <div v-else class="runner-idle">当前空闲</div>
          </article>
        </div>
      </section>
    </template>

    <template v-else>
      <section class="run-section">
        <div class="section-head">
          <div>
            <h2>AI 工作流</h2>
            <p>每个项目一条工作流：仓库、基线分支、默认 AI 工具、固定电脑和并发上限。</p>
          </div>
          <button class="pair-btn ghost" :disabled="workflowsLoading" @click="refreshWorkflows">
            <i :class="workflowsLoading ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>刷新
          </button>
        </div>
        <div v-if="workflowsLoading && !workflows.length" class="empty"><i class="ri-loader-4-line spin"></i>正在读取工作流…</div>
        <div v-else class="workflow-list">
          <article v-for="workflow in workflows" :key="workflow.projectId" class="workflow-card" :class="{ off: !workflow.config.enabled }">
            <header>
              <span class="flow-state" :class="{ on: workflow.config.enabled }">
                {{ workflow.config.enabled ? '已开启' : '未接入' }}
              </span>
              <b>{{ workflow.projectName }}</b>
              <span v-if="workflow.projectDeleted" class="flow-orphan">项目已删除</span>
              <span
                class="engine-tag"
                :style="{ color: engineColor(workflow.config.defaultEngine) }"
              >
                <i :class="engineIcon(workflow.config.defaultEngine)"></i>{{ engineLabel(workflow.config.defaultEngine) }}
              </span>
              <div class="spacer"></div>
              <button v-if="!workflow.projectDeleted" class="link-btn" @click="editingProjectId === workflow.projectId ? editingProjectId = null : startEditing(workflow)">
                {{ editingProjectId === workflow.projectId ? '收起' : workflow.config.enabled ? '编辑' : '接入' }}
              </button>
              <button
                v-if="workflow.configured !== false"
                class="link-btn danger"
                :disabled="removingWorkflowId === workflow.projectId"
                @click="removeWorkflow(workflow)"
              >
                {{ removingWorkflowId === workflow.projectId ? '删除中…' : '删除' }}
              </button>
            </header>
            <dl class="flow-meta">
              <div>
                <dt>仓库</dt>
                <dd v-if="workflow.config.repoUrl">
                  <a
                    v-if="githubBrowseUrl(workflow.config.repoUrl)"
                    :href="githubBrowseUrl(workflow.config.repoUrl)"
                    target="_blank"
                    rel="noopener"
                    class="repo-link"
                    :title="workflow.config.repoUrl"
                    @click.stop
                  >
                    <i class="ri-git-repository-line"></i>{{ workflow.config.repoUrl.replace(/^https?:\/\/github\.com\//, '').replace(/\.git$/, '') }}
                    <i class="ri-external-link-line"></i>
                  </a>
                  <span v-else :title="workflow.config.repoUrl">{{ workflow.config.repoUrl }}</span>
                </dd>
                <dd v-else>未填写</dd>
              </div>
              <div><dt>基线分支</dt><dd>{{ workflow.config.defaultBranch }}</dd></div>
              <div><dt>固定电脑</dt><dd>{{ workflow.preferredRunnerName || '自动分配' }}</dd></div>
              <div><dt>项目并发</dt><dd>{{ workflow.config.maxParallelRuns }}</dd></div>
            </dl>
            <div class="flow-stats">
              <span><i class="ri-loader-4-line"></i>进行中 {{ workflow.activeRuns }}</span>
              <span><i class="ri-checkbox-circle-line"></i>成功 {{ workflow.succeededRuns }}</span>
              <span><i class="ri-error-warning-line"></i>失败 {{ workflow.failedRuns }}</span>
              <span class="run-time">{{ workflow.lastRunAt ? `最近运行 ${timeText(workflow.lastRunAt)}` : '还没有跑过' }}</span>
              <template v-if="githubBrowseUrl(workflow.config.repoUrl)">
                <a
                  class="flow-gh-link"
                  :href="githubSubPage(workflow.config.repoUrl, 'pulls')"
                  target="_blank"
                  rel="noopener"
                  title="Pull Requests"
                  @click.stop
                ><i class="ri-git-pull-request-line"></i>PR</a>
                <a
                  class="flow-gh-link"
                  :href="githubSubPage(workflow.config.repoUrl, 'issues')"
                  target="_blank"
                  rel="noopener"
                  title="Issues"
                  @click.stop
                ><i class="ri-error-warning-line"></i>Issues</a>
                <a
                  class="flow-gh-link"
                  :href="githubSubPage(workflow.config.repoUrl, 'actions')"
                  target="_blank"
                  rel="noopener"
                  title="Actions"
                  @click.stop
                ><i class="ri-flashlight-line"></i>Actions</a>
              </template>
            </div>

            <div v-if="editingProjectId === workflow.projectId" class="flow-form">
              <label class="switch-row">
                <input v-model="form.enabled" type="checkbox" />
                <span>允许这个项目触发 AI Run</span>
              </label>
              <label class="form-field wide">
                <span>Git 仓库</span>
                <input v-model="form.repoUrl" placeholder="https://github.com/组织/仓库.git" />
              </label>
              <label class="form-field">
                <span>基线分支</span>
                <input v-model="form.defaultBranch" placeholder="main" />
              </label>
              <label class="form-field">
                <span>本机工程文件夹</span>
                <input v-model="form.workspaceDir" placeholder="wao" />
              </label>
              <label class="form-field">
                <span>默认 AI 工具</span>
                <select v-model="form.defaultEngine">
                  <option v-for="engine in AI_ENGINES" :key="engine.id" :value="engine.id">{{ engine.label }}</option>
                </select>
              </label>
              <label class="form-field">
                <span>固定执行电脑</span>
                <select v-model="form.preferredRunnerId">
                  <option value="">自动分配在线电脑</option>
                  <option v-for="runner in runnersFor(form.defaultEngine)" :key="runner.id" :value="runner.id">
                    {{ runner.name }}{{ runner.online ? ' · 在线' : ' · 离线' }}
                  </option>
                </select>
              </label>
              <label class="form-field">
                <span>同项目最大并发</span>
                <input v-model.number="form.maxParallelRuns" type="number" min="1" max="10" />
              </label>
              <div class="flow-actions">
                <button class="secondary" @click="editingProjectId = null">取消</button>
                <button class="primary" :disabled="saving" @click="saveWorkflow(workflow.projectId)">
                  {{ saving ? '保存中…' : '保存工作流' }}
                </button>
              </div>
            </div>
          </article>
        </div>
      </section>
    </template>

    <div v-if="selectedRun" class="run-detail-mask" @click.self="selectedRun = null">
      <section class="run-detail" role="dialog" aria-modal="true" aria-labelledby="run-detail-title">
        <header>
          <span class="recent-icon" :style="{ color: metaFor(selectedRun).color, background: metaFor(selectedRun).bg }">
            <i :class="metaFor(selectedRun).icon"></i>
          </span>
          <div>
            <h2 id="run-detail-title">{{ metaFor(selectedRun).label }}</h2>
            <p>{{ selectedRun.taskTitle }}</p>
          </div>
          <button class="detail-close" aria-label="关闭运行详情" @click="selectedRun = null"><i class="ri-close-line"></i></button>
        </header>
        <dl>
          <div><dt>AI 工具</dt><dd>{{ engineLabel(selectedRun.engine) }}</dd></div>
          <div><dt>执行电脑</dt><dd>{{ selectedRun.runnerName || '未分配电脑' }}</dd></div>
          <div><dt>触发人</dt><dd>{{ selectedRun.requestedBy || '系统未记录' }}</dd></div>
          <div><dt>执行时长</dt><dd>{{ durationText(selectedRun.startedAt, selectedRun.finishedAt) }}</dd></div>
          <div><dt>退出码</dt><dd>{{ selectedRun.exitCode ?? '—' }}</dd></div>
          <div><dt>{{ selectedRun.finishedAt ? '结束时间' : '最近反馈' }}</dt><dd>{{ timeText(selectedRun.finishedAt || selectedRun.progressUpdatedAt) || '等待中' }}</dd></div>
        </dl>
        <div class="result-title">本次消耗</div>
        <div v-if="selectedRun.usage?.recorded" class="usage-grid">
          <div><dt>模型</dt><dd :title="selectedRun.usage.models || ''">{{ shortModel(selectedRun.usage.model) || '未回传' }}</dd></div>
          <div><dt>合计 token</dt><dd>{{ formatTokens(selectedRun.usage.totalTokens) }}</dd></div>
          <div><dt>输入 / 输出</dt><dd>{{ formatTokens(selectedRun.usage.inputTokens) }} / {{ formatTokens(selectedRun.usage.outputTokens) }}</dd></div>
          <div><dt>缓存命中</dt><dd>{{ formatTokens(selectedRun.usage.cachedInputTokens) }}</dd></div>
          <div><dt>折算额度</dt><dd>{{ formatCost(selectedRun.usage.costUsd) || '该工具未回传' }}</dd></div>
        </div>
        <p v-else class="usage-empty">
          {{ ['queued', 'claimed', 'running'].includes(selectedRun.status) ? '运行结束后会记下本次消耗。' : '这次运行没有记上账（执行节点未回传用量）。' }}
        </p>
        <div class="result-title result-gap">AI 执行记录</div>
        <AiRunProgressTimeline :run="selectedRun" />
        <template v-if="selectedRun.resultSummary">
          <div class="result-title result-gap">{{ resultTitle(selectedRun) }}</div>
          <pre>{{ selectedRun.resultSummary }}</pre>
        </template>
        <a
          v-if="selectedRun.reportUrl"
          class="run-report-link"
          :href="selectedRun.reportUrl"
          target="_blank"
          rel="noopener"
        >
          <span><i class="ri-file-chart-line"></i></span>
          <div><b>查看持久化报告</b><small>完整报告已关联本次运行，可直接分享公开链接</small></div>
          <i class="ri-arrow-right-up-line"></i>
        </a>
        <footer>
          <span v-if="selectedRun.retryOfRunId" class="retry-note">
            <i class="ri-corner-down-right-line"></i>本次是基于上一次报错的重跑
          </span>
          <a
            v-if="selectedRun.branchName"
            class="secondary gh-branch-link"
            :href="findWorkflowForRun(selectedRun)?.config?.repoUrl
              ? githubSubPage(findWorkflowForRun(selectedRun)!.config.repoUrl, `tree/${selectedRun.branchName}`)
              : '#'"
            :class="{ disabled: !findWorkflowForRun(selectedRun)?.config?.repoUrl }"
            target="_blank"
            rel="noopener"
            :title="selectedRun.branchName"
          >
            <i class="ri-git-branch-line"></i>{{ selectedRun.branchName }}
          </a>
          <button class="secondary" @click="selectedRun = null">关闭</button>
          <button
            v-if="selectedRun.status === 'failed'"
            class="secondary retry"
            :disabled="rerunning"
            @click="rerun(selectedRun)"
          >
            <i :class="rerunning ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>基于报错重跑
          </button>
          <button class="primary" @click="openRun(selectedRun)">打开对应任务</button>
        </footer>
      </section>
    </div>

    <div v-if="logRunner" class="run-detail-mask" @click.self="closeRunnerLog">
      <section class="run-detail runner-log-detail" role="dialog" aria-modal="true" aria-labelledby="runner-log-title">
        <header>
          <span class="recent-icon runner-log-icon"><i class="ri-terminal-box-line"></i></span>
          <div>
            <h2 id="runner-log-title">Runner 日志</h2>
            <p>{{ logRunner.name }} · 最近 200 行安全诊断</p>
          </div>
          <button class="detail-close" aria-label="关闭 Runner 日志" @click="closeRunnerLog"><i class="ri-close-line"></i></button>
        </header>
        <div v-if="runnerLogLoading" class="runner-log-state">
          <i class="ri-loader-4-line spin"></i>正在读取日志…
        </div>
        <div v-else-if="runnerLogError" class="runner-log-state error">
          <i class="ri-error-warning-line"></i>
          <span>{{ runnerLogError }}</span>
          <button class="secondary" @click="loadRunnerLog">重试</button>
        </div>
        <div v-else-if="!runnerLog?.content" class="runner-log-state">
          <i class="ri-file-list-3-line"></i>
          <span>这台电脑还没有上报日志；新版 Runner 下次心跳后会自动出现。</span>
        </div>
        <template v-else>
          <div class="runner-log-meta">
            仅显示脱敏后的日志尾部 · 更新于 {{ timeText(runnerLog.updatedAt) || '时间未知' }}
          </div>
          <pre class="runner-log-content">{{ runnerLog.content }}</pre>
        </template>
        <footer>
          <button v-if="runnerLog?.content" class="secondary" @click="copyRunnerLog"><i class="ri-file-copy-line"></i>复制</button>
          <button class="secondary" :disabled="runnerLogLoading" @click="loadRunnerLog"><i class="ri-refresh-line"></i>刷新</button>
          <button class="primary" @click="closeRunnerLog">关闭</button>
        </footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.home {
  min-height: 100%;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  padding: 16px 24px 36px;
  background: var(--wao-bg-page);
}
.hero {
  min-height: 118px;
  padding: 18px 22px;
  border-radius: 14px;
  background:
    radial-gradient(circle at 85% 20%, rgba(136, 153, 255, .35), transparent 35%),
    linear-gradient(135deg, #202b69, #4358d6);
  color: #fff;
  display: flex;
  align-items: center;
  gap: 24px;
  box-shadow: 0 14px 35px rgba(43, 59, 141, .2);
}
.hero > div:first-child { flex: 1; }
.eyebrow { font-size: 10px; letter-spacing: 2px; opacity: .65; }
.hero h1 { margin: 8px 0 6px; font-size: 28px; }
.hero h1.slogan { font-size: 25px; font-weight: 800; letter-spacing: .7px; line-height: 1.2; }
.hero p { margin: 0; font-size: 13px; opacity: .72; }
.hero-now {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 11px;
  margin-bottom: 8px;
  border: 1px solid #ffffff33;
  border-radius: 999px;
  background: #ffffff1f;
  font-size: 12px;
}
.hero-now b { font-size: 14px; }
.engine-status { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; }
.engine-status span {
  padding: 5px 10px;
  border: 1px solid #ffffff2e;
  border-radius: 8px;
  background: #ffffff14;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.engine-status span.dim { opacity: .55; }
.engine-status b { font-weight: 600; opacity: .8; }
.hero-link {
  margin-top: 12px;
  padding: 6px 12px;
  border: 1px solid #ffffff55;
  border-radius: 999px;
  background: #ffffff1f;
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.hero-link:hover { background: #ffffff33; }
.hero-link i { font-size: 14px; }
.hero-metrics { width: 188px; flex: 0 0 auto; display: grid; gap: 9px; }
.quota-card {
  min-height: 92px;
  padding: 12px 14px;
  border: 1px solid #ffffff2b;
  border-radius: 14px;
  background: #ffffff12;
  display: grid;
  align-content: center;
}
.quota-card > span { color: #ffffffb5; font-size: 10.5px; }.quota-card > span i { margin-right: 5px; }
.quota-card strong { margin-top: 2px; color: #fff; font-size: 35px; line-height: 1; }.quota-card strong em { font-size: 17px; font-style: normal; }
.quota-card small { margin-top: 6px; color: #ffffff9c; font-size: 9.5px; line-height: 1.35; }
.quota-card.good strong { color: #c7ffdf; }.quota-card.warn strong { color: #ffe9a9; }.quota-card.danger strong { color: #ffd0d0; }
.capacity {
  position: relative;
  width: auto;
  padding: 12px;
  border: 1px solid #ffffff2b;
  border-radius: 14px;
  background: #ffffff12;
  font-size: 11px;
  color: #ffffffa8;
  display: flex;
  align-items: center;
  gap: 12px;
  overflow: hidden;
}
.capacity-orbit { position: relative; width: 48px; height: 48px; flex: 0 0 auto; border: 2px solid #ffffff32; border-radius: 50%; }
.capacity-orbit::before, .capacity-orbit::after { content: ''; position: absolute; border: 1px solid #ffffff4a; border-radius: 50%; animation: orbit 4s linear infinite; }
.capacity-orbit::before { inset: 7px -8px; transform: rotate(58deg); }
.capacity-orbit::after { inset: -8px 7px; animation-duration: 6s; animation-direction: reverse; }
.capacity-orbit i { position: absolute; inset: 8px; border-radius: 50%; background: conic-gradient(#fff var(--capacity), #ffffff22 0); mask: radial-gradient(transparent 58%, #000 60%); }
.capacity-orbit b { position: absolute; left: 50%; top: 50%; width: 7px; height: 7px; border-radius: 50%; background: #fff; transform: translate(-50%, -50%); box-shadow: 0 0 12px #fff; animation: corePulse 1.8s ease-in-out infinite; }
.capacity-orbit em { position: absolute; right: -3px; top: 6px; width: 5px; height: 5px; border-radius: 50%; background: #aef5d0; box-shadow: 0 0 9px #aef5d0; }
.capacity-copy { min-width: 0; flex: 1; }
.capacity-num { color: #fff; font-size: 30px; font-weight: 750; }
.capacity-num span { font-size: 14px; opacity: .6; }
.capacity-bar { height: 5px; margin-top: 11px; overflow: hidden; border-radius: 9px; background: #ffffff24; }
.capacity-bar i { display: block; height: 100%; width: var(--capacity); border-radius: inherit; background: #fff; transition: width .3s; }
.daily-guidance {
  margin-top: 16px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, #7f63d8 25%, var(--wao-line));
  border-radius: 15px;
  background:
    radial-gradient(circle at 94% 0, rgba(167, 139, 250, .13), transparent 34%),
    linear-gradient(145deg, color-mix(in srgb, #f6f1ff 72%, var(--wao-bg-card)), var(--wao-bg-card));
  box-shadow: 0 8px 24px rgba(83, 63, 151, .07);
}
.daily-guidance > header { padding: 17px 19px 13px; display: flex; align-items: flex-start; gap: 11px; }
.guidance-mark { width: 40px; height: 40px; flex: 0 0 auto; border-radius: 12px; background: #ece4ff; color: #7253c4; display: grid; place-items: center; font-size: 20px; }
.daily-guidance header > div { flex: 1; min-width: 0; }
.daily-guidance h2 { margin: 4px 0; color: var(--wao-text-1); font-size: 18px; }
.daily-guidance header p { margin: 0; color: var(--wao-text-weak); font-size: 11px; line-height: 1.55; }
.guidance-refresh, .guidance-state button { padding: 7px 11px; border: 1px solid #ac99df; border-radius: 8px; background: var(--wao-bg-card); color: #684bb4; cursor: pointer; font-size: 11px; white-space: nowrap; }
.guidance-refresh:disabled { opacity: .58; cursor: wait; }
.guidance-refresh i, .guidance-state button i { margin-right: 4px; }
.guidance-state { min-height: 112px; padding: 18px 20px; border-top: 1px solid color-mix(in srgb, #7f63d8 12%, var(--wao-line)); color: #6c58a8; display: flex; align-items: center; justify-content: center; gap: 12px; }
.guidance-state > i { font-size: 23px; }
.guidance-state div { display: grid; gap: 4px; }
.guidance-state b { color: var(--wao-text-2); font-size: 13px; }
.guidance-state span { color: var(--wao-text-weak); font-size: 11px; line-height: 1.55; }
.guidance-state.error { justify-content: flex-start; color: var(--wao-danger-strong); }
.guidance-state.error div { flex: 1; }
.guidance-content { padding: 5px 20px 16px 70px; }
.guidance-content :deep(.md-body) { color: var(--wao-text-2); font-size: 12px; line-height: 1.72; }
.guidance-content :deep(.md-body > :first-child) { margin-top: 0; }
.guidance-content :deep(.md-body > :last-child) { margin-bottom: 0; }
.guidance-content :deep(.md-body h2), .guidance-content :deep(.md-body h3) { margin: 13px 0 5px; color: #4f397f; font-size: 13px; }
.guidance-content :deep(.md-body p), .guidance-content :deep(.md-body ul) { margin: 6px 0; }
.guidance-content :deep(.md-body ul) { padding-left: 19px; }
.guidance-content > small { margin-top: 14px; padding-top: 9px; border-top: 1px solid color-mix(in srgb, #7f63d8 11%, var(--wao-line)); color: var(--wao-text-weak); font-size: 9.5px; display: block; }
.guidance-content > small i { margin-right: 5px; color: #7253c4; }
.guidance-inline-error { margin-top: 10px; padding: 8px 10px; border: 1px solid var(--wao-danger-line); border-radius: 8px; background: var(--wao-danger-bg); color: var(--wao-danger-strong); display: flex; align-items: center; gap: 7px; font-size: 10.5px; }
.guidance-inline-error span { flex: 1; }
.guidance-inline-error button { border: 0; background: transparent; color: inherit; cursor: pointer; font-weight: 650; }
.evolution-panel {
  margin-top: 16px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, #3b8c69 24%, var(--wao-line));
  border-radius: 15px;
  background: linear-gradient(145deg, color-mix(in srgb, #e8f7ef 76%, var(--wao-bg-card)), var(--wao-bg-card));
  box-shadow: 0 8px 24px rgba(38, 113, 81, .06);
}
.evolution-panel > header { padding: 16px 18px 12px; display: flex; align-items: flex-start; gap: 11px; }
.evolution-mark { width: 38px; height: 38px; flex: 0 0 auto; border-radius: 11px; background: #d9f1e4; color: #267c59; display: grid; place-items: center; font-size: 19px; }
.evolution-panel header > div { flex: 1; min-width: 0; }.evolution-panel h2 { margin: 4px 0 4px; color: var(--wao-text-1); font-size: 17px; }
.evolution-policy { width: fit-content; margin: 0 0 5px; padding: 3px 7px; border-radius: 999px; background: #e3f3ea; color: #2a7658; font-size: 9.5px; line-height: 1.4; }.evolution-policy i { margin-right: 4px; }
.evolution-panel header p { margin: 0; color: var(--wao-text-weak); font-size: 11px; line-height: 1.55; }.evolution-panel header p.evolution-error { color: var(--wao-danger-strong); }
.auto-state { padding: 5px 9px; border-radius: 999px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 10.5px; white-space: nowrap; }
.auto-state.ready { background: #d9f1e4; color: #267c59; }.auto-state i { margin-right: 4px; }
.evolution-panel header > button { padding: 6px 9px; border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.evolution-panel header > button:disabled { opacity: .55; cursor: wait; }
.evolution-schedule { margin: 0 18px 14px; padding: 12px; border: 1px solid color-mix(in srgb, #3b8c69 20%, var(--wao-line)); border-radius: 10px; background: color-mix(in srgb, #e8f5ed 42%, var(--wao-bg-card)); display: flex; align-items: end; flex-wrap: wrap; gap: 10px; }
.evolution-schedule > div:first-child { flex: 1 1 240px; align-self: center; }.evolution-schedule > div:first-child strong, .evolution-schedule > div:first-child span { display: block; }.evolution-schedule > div:first-child strong { color: var(--wao-text-1); font-size: 12px; }.evolution-schedule > div:first-child span { margin-top: 3px; color: var(--wao-text-weak); font-size: 10px; line-height: 1.5; }
.evolution-schedule label { display: flex; align-items: center; gap: 6px; color: var(--wao-text-2); font-size: 11px; white-space: nowrap; }.evolution-schedule input, .evolution-schedule select { height: 32px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-1); padding: 0 8px; }.evolution-schedule input { width: 64px; }.evolution-schedule select { min-width: 94px; }
.evolution-schedule > button { height: 32px; border: 0; border-radius: 7px; background: #317b5d; color: #fff; padding: 0 11px; cursor: pointer; }.evolution-schedule > button:disabled { opacity: .55; cursor: default; }.schedule-feedback { flex-basis: 100%; color: var(--wao-text-weak); font-size: 10px; }.schedule-feedback.error { color: var(--wao-danger-strong); }.schedule-feedback.saved { color: #267c59; }.schedule-feedback i { margin-right: 4px; }
.suggestion-loading { min-height: 92px; padding: 22px; display: grid; place-content: center; gap: 7px; color: var(--wao-text-weak); font-size: 11px; }
.suggestion-grid { padding: 0 12px 13px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.suggestion-card { min-width: 0; padding: 12px; border: 1px solid color-mix(in srgb, #3b8c69 14%, var(--wao-line)); border-radius: 11px; background: color-mix(in srgb, var(--wao-bg-card) 92%, transparent); display: grid; grid-template-columns: 32px minmax(0, 1fr) auto; align-items: center; gap: 9px; }
.suggestion-card > span { width: 32px; height: 32px; border-radius: 9px; background: #e3f3ea; color: #2a7658; display: grid; place-items: center; }
.suggestion-card h3 { margin: 0; color: var(--wao-text-1); font-size: 12px; }.suggestion-card p { margin: 3px 0 0; color: var(--wao-text-weak); font-size: 10px; line-height: 1.45; }
.suggestion-source { display: inline-flex; align-items: center; gap: 3px; max-width: 100%; margin-top: 6px; color: var(--wao-primary); font-size: 9.5px; text-decoration: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.suggestion-card button { height: 30px; padding: 0 9px; border: 1px solid #4a9274; border-radius: 8px; background: #fff; color: #2a7658; cursor: pointer; font-size: 10.5px; white-space: nowrap; }.suggestion-card button i { margin-right: 4px; }.suggestion-card button:disabled { opacity: .5; cursor: not-allowed; }
.suggestion-empty { min-height: 112px; padding: 20px; display: grid; place-items: center; align-content: center; gap: 5px; color: var(--wao-text-weak); text-align: center; font-size: 10.5px; }.suggestion-empty > i { font-size: 21px; color: #4a9274; }.suggestion-empty strong { color: var(--wao-text-2); font-size: 12px; }
.suggestion-feedback { margin: 0 12px 12px; padding: 9px 10px; border: 1px solid; border-radius: 8px; display: flex; align-items: center; gap: 7px; font-size: 10.5px; }
.suggestion-feedback span { flex: 1; }
.suggestion-feedback.loading { border-color: color-mix(in srgb, #3b8c69 32%, var(--wao-line)); background: color-mix(in srgb, #e8f7ef 72%, var(--wao-bg-card)); color: #267c59; }
.suggestion-feedback.error { border-color: var(--wao-danger-line); background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
.suggestion-feedback button { border: 0; background: transparent; color: inherit; cursor: pointer; font-weight: 650; }
.evolution-panel > footer { padding: 9px 18px; border-top: 1px solid color-mix(in srgb, #3b8c69 13%, var(--wao-line)); display: flex; align-items: center; flex-wrap: wrap; gap: 8px; color: var(--wao-text-weak); font-size: 9.5px; }
.evolution-panel > footer span { padding: 3px 7px; border-radius: 999px; background: #dff2e7; color: #326c56; }.evolution-panel > footer small { margin-left: auto; }
.goal-launcher {
  margin-top: 16px;
  padding: 19px 20px;
  border: 1px solid #dfe3f4;
  border-radius: 15px;
  background: linear-gradient(135deg, #fff, #f7f8ff);
  box-shadow: 0 8px 24px rgba(43, 59, 141, .07);
  display: grid;
  grid-template-columns: minmax(230px, .8fr) minmax(360px, 1.2fr);
  gap: 22px;
  align-items: center;
}
.goal-heading { min-width: 0; display: flex; align-items: flex-start; gap: 12px; }
.goal-icon { width: 38px; height: 38px; flex: 0 0 auto; border-radius: 11px; background: #eef1ff; color: #4358d6; display: grid; place-items: center; font-size: 19px; }
.eyebrow.dark { color: #7d85a6; opacity: 1; }
.goal-heading h2 { margin: 5px 0 5px; color: var(--wao-text-1); font-size: 17px; }
.goal-heading p { margin: 0; color: var(--wao-text-weak); font-size: 11.5px; line-height: 1.6; }
.goal-compose { overflow: hidden; border: 1px solid #d9deef; border-radius: 12px; background: #fff; transition: border-color .16s, box-shadow .16s; }
.goal-compose:focus-within { border-color: #8f9ee6; box-shadow: 0 0 0 3px #e9ecff; }
.goal-compose :deep(.md-body) { border: 0 !important; border-radius: 0; }
.goal-compose :deep(.md-body .tiptap) { min-height: 78px; padding: 12px 46px 7px 13px; color: var(--wao-text-1); font-size: 12.5px; line-height: 1.6; }
.goal-compose :deep(.md-body p.is-editor-empty:first-child::before) { color: #a0a5b6; }
.goal-compose :deep(.image-upload) { right: 10px; bottom: 8px; }
.goal-actions { min-height: 44px; padding: 7px 8px 8px 12px; border-top: 1px solid #f0f1f5; display: flex; align-items: center; gap: 10px; }
.goal-actions label { min-width: 0; display: flex; align-items: center; gap: 7px; color: var(--wao-text-weak); font-size: 10.5px; }
.goal-actions select { max-width: 230px; height: 29px; padding: 0 26px 0 8px; border: 1px solid var(--wao-line); border-radius: 7px; background: #fff; color: var(--wao-text-2); font-size: 11px; }
.goal-actions .goal-runner-select { max-width: 180px; }
.goal-shortcut { margin-left: auto; color: #a0a5b6; font-size: 9.5px; white-space: nowrap; }
.goal-submit { min-width: 112px; height: 32px; padding: 0 12px; border: 1px solid #4358d6; border-radius: 8px; background: #4358d6; color: #fff; cursor: pointer; font-size: 11.5px; font-weight: 650; display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
.goal-submit.setup { min-width: 140px; }
.goal-submit:disabled { opacity: .55; cursor: not-allowed; }
@keyframes orbit { to { transform: rotate(418deg); } }
@keyframes corePulse { 50% { transform: translate(-50%, -50%) scale(.68); opacity: .55; } }
.console-tabs {
  margin-top: 10px;
  padding: 5px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  display: flex;
  align-items: center;
  gap: 4px;
}
.console-tabs button {
  padding: 8px 14px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--wao-text-2);
  cursor: pointer;
  font-size: 12.5px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.console-tabs button:hover { background: var(--wao-bg-hover); }
.console-tabs button.on { background: #eef1ff; color: var(--wao-primary-text); font-weight: 650; }
.console-tabs em { padding: 1px 6px; border-radius: 20px; background: #e3e7f6; color: #4358d6; font-size: 10px; font-style: normal; }
.console-tabs .live { margin-left: auto; padding-right: 8px; }
.section-title { margin-right: 4px; font-size: 12px; font-weight: 650; color: var(--wao-text-2); }
.online-dot { width: 7px; height: 7px; border-radius: 50%; background: #31a56a; box-shadow: 0 0 0 3px #dff5e9; }
.offline .online-dot { background: #9ca1ad; box-shadow: none; }
.run-section, .recent-section {
  margin-top: 10px;
  padding: 14px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.section-head { display: flex; align-items: flex-end; gap: 8px; margin-bottom: 12px; }
.section-head > div { flex: 1; }
.section-head h2 { margin: 0; font-size: 17px; }
.section-head p { margin: 4px 0 0; color: var(--wao-text-weak); font-size: 11.5px; }
.live { color: var(--wao-text-weak); font-size: 10.5px; }
.live i { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: #35a86e; }
.run-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(270px, 1fr)); gap: 12px; }
.run-card {
  padding: 12px;
  border: 1px solid var(--wao-line);
  border-radius: 13px;
  background: var(--wao-bg-card);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: .16s;
}
.run-card:hover { transform: translateY(-2px); border-color: var(--wao-primary-line); box-shadow: 0 8px 22px #27366c14; }
.run-top, .run-foot { display: flex; align-items: center; gap: 8px; }
.status { padding: 4px 7px; border-radius: 6px; font-size: 10.5px; font-weight: 650; }
.run-time { margin-left: auto; color: var(--wao-text-weak); font-size: 10.5px; }
.run-card h3 { min-height: 20px; margin: 9px 0 4px; font-size: 14px; line-height: 1.4; }
.run-project { color: var(--wao-text-weak); font-size: 11.5px; }
.run-project i { margin-right: 5px; }
.live-progress { margin-top: 8px; padding: 8px; border-radius: 8px; background: var(--wao-bg-sub); display: flex; gap: 9px; align-items: flex-start; }
.live-progress > div { min-width: 0; }
.live-progress b, .live-progress small { display: block; }
.live-progress b { overflow: hidden; color: #41485c; font-size: 11px; font-weight: 550; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.live-progress small { margin-top: 3px; color: #969bac; font-size: 9.5px; }
.live-pulse { width: 8px; height: 8px; margin-top: 4px; flex: 0 0 auto; border-radius: 50%; background: #36a86f; box-shadow: 0 0 0 4px #dff5e9; animation: livePulse 1.5s infinite; }
.run-foot { margin-top: 9px; padding-top: 8px; border-top: 1px solid #f0f1f5; color: var(--wao-text-3); font-size: 11px; }
.run-foot .foot-runner { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.run-foot span i { margin-right: 5px; }
.engine-tag { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; font-weight: 600; }
.engine-tag.boxed { padding: 3px 8px; border: 1px solid #e2e5f0; border-radius: 7px; }
.empty {
  min-height: 150px;
  border: 1px dashed #d6d9e5;
  border-radius: 13px;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  font-size: 12px;
}
.empty.ready { flex-direction: column; gap: 5px; }
.empty.ready > i { margin-bottom: 4px; color: #7d8ddd; font-size: 26px; }
.empty.ready b { color: var(--wao-text-2); font-size: 13px; }
.runner-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.runner-card { padding: 16px; border: 1px solid var(--wao-line); border-radius: 13px; background: var(--wao-bg-card); }
.runner-card.offline { background: var(--wao-bg-sub); opacity: .8; }
.runner-card header { display: flex; align-items: center; gap: 8px; }
.runner-card header b { font-size: 13.5px; }
.runner-state { margin-left: auto; color: var(--wao-text-weak); font-size: 10.5px; }
.runner-platform { margin-top: 8px; color: var(--wao-text-weak); font-size: 11px; }
.runner-platform i { margin-right: 5px; }
.engine-tags { margin-top: 11px; display: flex; flex-wrap: wrap; gap: 6px; }
.slot-line { margin-top: 13px; display: flex; align-items: center; color: var(--wao-text-3); font-size: 11px; }
.slot-bar { height: 5px; margin-top: 6px; overflow: hidden; border-radius: 9px; background: #eef0f6; }
.slot-bar i { display: block; height: 100%; border-radius: inherit; background: var(--wao-primary); transition: width .3s; }
.runner-tasks { margin: 12px 0 0; padding: 11px 0 0; border-top: 1px solid #f0f1f5; list-style: none; }
.runner-tasks li { display: flex; align-items: center; gap: 7px; margin-top: 6px; font-size: 11.5px; }
.runner-tasks li:first-child { margin-top: 0; }
.runner-tasks button { flex: 1; padding: 0; border: 0; overflow: hidden; background: none; color: var(--wao-text-2); cursor: pointer; font-size: 11.5px; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.runner-tasks button:hover { color: #4358d6; text-decoration: underline; }
.runner-idle { margin-top: 12px; padding-top: 11px; border-top: 1px solid #f0f1f5; color: var(--wao-text-weak); font-size: 11px; }
.concurrency-edit { display: inline-flex; align-items: center; gap: 5px; }
.concurrency-edit input {
  width: 42px;
  padding: 3px 6px;
  border: 1px solid var(--wao-line);
  border-radius: 6px;
  text-align: center;
  font-size: 11px;
}
.icon-btn {
  padding: 3px 5px;
  border: 1px solid var(--wao-primary);
  border-radius: 6px;
  background: var(--wao-primary);
  color: #fff;
  cursor: pointer;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.icon-btn:disabled { opacity: .6; cursor: not-allowed; }
.icon-btn.secondary { border-color: var(--wao-line); background: var(--wao-bg-card); color: var(--wao-text-2); }
.icon-btn.secondary:hover { border-color: var(--wao-primary-line); color: #4358d6; }
.icon-btn.danger { border-color: var(--wao-line); background: var(--wao-bg-card); color: var(--wao-text-weak); }
.icon-btn.danger:hover { border-color: var(--wao-danger-line); background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
.runner-config-hint {
  margin-top: 7px;
  color: #237a4b;
  font-size: 10.5px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.runner-script-hint {
  margin-top: 7px;
  font-size: 10.5px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.runner-script-hint.pending { color: #8a5b0d; }
.runner-script-hint.stale { color: var(--wao-danger-strong); }
.runner-log-btn {
  width: 100%;
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-sub-2);
  color: var(--wao-text-2);
  cursor: pointer;
  font-size: 11.5px;
}
.runner-log-btn:hover { border-color: var(--wao-primary-line); color: var(--wao-primary); }
.runner-log-btn i { margin-right: 5px; }
.wake-panel { margin-top: 11px; padding-top: 11px; border-top: 1px solid #eceef4; display: flex; flex-wrap: wrap; align-items: center; gap: 7px; }
.wake-panel button { padding: 6px 9px; border: 1px solid var(--wao-primary-line); border-radius: 7px; background: var(--wao-primary-tint); color: var(--wao-primary-text); cursor: pointer; font-size: 11px; }
.wake-panel button:disabled { opacity: .6; cursor: wait; }
.wake-panel .wake-btn { border-color: var(--wao-primary); background: var(--wao-primary); color: #fff; font-weight: 650; }
.wake-panel small { flex-basis: 100%; color: var(--wao-text-weak); font-size: 10px; line-height: 1.5; }
.pair-btn {
  padding: 8px 13px;
  border: 1px solid var(--wao-primary);
  border-radius: 9px;
  background: var(--wao-primary);
  color: #fff;
  cursor: pointer;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.pair-btn.ghost { border-color: var(--wao-line); background: var(--wao-bg-card); color: var(--wao-text-2); }
.pair-code { margin-bottom: 12px; padding: 13px 15px; border: 1px solid var(--wao-line-control); border-radius: 12px; background: var(--wao-bg-sub); }
.pair-code > div { display: flex; align-items: baseline; gap: 10px; }
.pair-code b { font-size: 19px; font-weight: 700; letter-spacing: 3px; color: #2f3f9e; }
.pair-code small { color: var(--wao-text-weak); font-size: 11px; }
.pair-code code { display: block; margin-top: 9px; overflow-x: auto; padding: 9px 11px; border-radius: 8px; background: #eceffb; color: #47506e; font: 11px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre; }
.workflow-list { display: grid; gap: 12px; }
.workflow-card { padding: 16px 18px; border: 1px solid var(--wao-line); border-radius: 13px; background: var(--wao-bg-card); }
.workflow-card.off { background: var(--wao-bg-sub); }
.workflow-card header { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.workflow-card header b { font-size: 14px; }
.workflow-card .spacer { flex: 1; }
.flow-state { padding: 3px 8px; border-radius: 6px; background: var(--wao-bg-hover-2); color: var(--wao-text-weak); font-size: 10.5px; font-weight: 650; }
.flow-state.on { background: #eaf8f0; color: #237a4b; }
.flow-orphan { padding: 3px 8px; border-radius: 6px; background: #fff0f0; color: var(--wao-danger-strong); font-size: 10.5px; font-weight: 650; }
.link-btn { padding: 5px 10px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; font-size: 11.5px; }
.link-btn:hover { border-color: var(--wao-primary-line); color: #4358d6; }
.link-btn.danger { color: var(--wao-danger-strong); }
.link-btn.danger:hover { border-color: var(--wao-danger-strong); background: #fff5f5; color: var(--wao-danger-strong); }
.link-btn:disabled { opacity: .6; cursor: wait; }
.flow-meta { margin: 14px 0 0; display: grid; grid-template-columns: 2fr 1fr 1fr 1fr; gap: 10px; }
.flow-meta > div { min-width: 0; padding: 9px 11px; border-radius: 9px; background: var(--wao-bg-hover); }
.flow-meta dt { color: var(--wao-text-weak); font-size: 10px; }
.flow-meta dd { margin: 4px 0 0; overflow: hidden; font-size: 11.5px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.flow-stats { margin-top: 11px; display: flex; align-items: center; gap: 14px; color: var(--wao-text-3); font-size: 11px; }
.flow-stats i { margin-right: 4px; }
.repo-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #4358d6;
  text-decoration: none;
  font-weight: 600;
}
.repo-link:hover { text-decoration: underline; }
.repo-link i { font-size: 12px; }
.repo-link .ri-external-link-line { font-size: 10px; opacity: .6; }
.flow-gh-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 7px;
  border: 1px solid var(--wao-line);
  border-radius: 6px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  text-decoration: none;
  font-size: 10px;
}
.flow-gh-link:hover {
  border-color: var(--wao-primary-line);
  color: #4358d6;
  background: var(--wao-primary-tint);
}
.flow-gh-link i { font-size: 11px; }
.flow-form { margin-top: 14px; padding-top: 14px; border-top: 1px solid #f0f1f5; display: grid; grid-template-columns: repeat(2, 1fr); gap: 11px; }
.switch-row { grid-column: 1 / -1; display: flex; align-items: center; gap: 8px; font-size: 12px; }
.form-field { display: flex; flex-direction: column; gap: 5px; font-size: 11px; color: var(--wao-text-weak); }
.form-field.wide { grid-column: 1 / -1; }
.form-field input, .form-field select {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-1);
  font-size: 12px;
}
.form-field input:focus, .form-field select:focus { border-color: #8f9ee6; outline: none; }
.flow-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 9px; }
.flow-actions button { padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 12px; }
.flow-actions .secondary { border: 1px solid var(--wao-line); background: var(--wao-bg-card); }
.flow-actions .primary { border: 1px solid var(--wao-primary); background: var(--wao-primary); color: #fff; }
.flow-actions .primary:disabled { opacity: .6; cursor: not-allowed; }
.recent-list { overflow: hidden; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); }
.recent-list button {
  width: 100%;
  min-height: 58px;
  padding: 9px 14px;
  border: 0;
  border-bottom: 1px solid var(--wao-bg-tab);
  background: var(--wao-bg-card);
  color: inherit;
  text-align: left;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 11px;
}
.recent-list button:last-child { border-bottom: 0; }
.recent-list button:hover { background: var(--wao-bg-sub-2); }
.recent-icon { width: 30px; height: 30px; border-radius: 8px; display: grid; place-items: center; }
.recent-main { flex: 1; min-width: 0; }
.recent-main b, .recent-main em { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recent-main b { font-size: 12.5px; }
.recent-main em { margin-top: 2px; color: var(--wao-text-weak); font-size: 10.5px; font-style: normal; }
.recent-main small { display: block; margin-top: 4px; overflow: hidden; color: var(--wao-danger-strong); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.recent-status { color: var(--wao-text-3); font-size: 11px; }
.run-detail-mask {
  position: fixed;
  inset: 0;
  z-index: 1200;
  padding: 24px;
  background: rgba(20, 25, 45, .34);
  display: grid;
  place-items: center;
}
.run-detail {
  width: min(680px, 100%);
  max-height: min(720px, calc(100vh - 48px));
  overflow: auto;
  padding: 22px;
  border-radius: 16px;
  background: var(--wao-bg-card);
  box-shadow: 0 24px 70px rgba(26, 31, 61, .22);
}
.run-detail header { display: flex; align-items: center; gap: 12px; }
.run-detail header > div { flex: 1; min-width: 0; }
.run-detail h2 { margin: 0; font-size: 17px; }
.run-detail header p { margin: 3px 0 0; overflow: hidden; color: var(--wao-text-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.detail-close { width: 32px; height: 32px; border: 0; border-radius: 8px; background: var(--wao-bg-hover); cursor: pointer; }
.run-detail dl { margin: 18px 0; display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.run-detail dl > div { padding: 10px 12px; border-radius: 9px; background: var(--wao-bg-hover); }
.run-detail dt { color: var(--wao-text-weak); font-size: 10px; }
.run-detail dd { margin: 4px 0 0; font-size: 11.5px; font-weight: 600; }
.result-title { margin-bottom: 7px; font-size: 12px; font-weight: 650; }
.result-title.result-gap { margin-top: 16px; }
.usage-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(96px, 1fr)); gap: 8px; }
.usage-grid > div { min-width: 0; padding: 9px 11px; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-sub-2); }
.usage-grid dt { color: var(--wao-text-weak); font-size: 10px; }
.usage-grid dd { margin: 4px 0 0; overflow: hidden; font-size: 11.5px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.usage-empty { margin: 0; padding: 11px; border: 1px dashed #dfe2ec; border-radius: 9px; color: var(--wao-text-weak); font-size: 11.5px; }
.recent-usage { color: var(--wao-text-3); }
.run-detail pre { max-height: 360px; margin: 0; overflow: auto; padding: 14px; border: 1px solid var(--wao-danger-line); border-radius: 10px; background: var(--wao-danger-bg); color: var(--wao-danger-strong); font: 11.5px/1.65 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; word-break: break-word; }
.run-report-link { margin-top: 12px; padding: 11px 13px; border: 1px solid #cfe8dc; border-radius: 10px; background: linear-gradient(135deg, #f0faf5, #fff); color: inherit; text-decoration: none; display: flex; align-items: center; gap: 10px; }
.run-report-link:hover { border-color: #89c9aa; box-shadow: 0 5px 16px rgba(27, 126, 83, .08); }
.run-report-link > span { width: 31px; height: 31px; flex: none; border-radius: 8px; background: #daf1e5; color: #16815c; display: grid; place-items: center; }
.run-report-link > div { flex: 1; min-width: 0; }
.run-report-link b, .run-report-link small { display: block; }
.run-report-link b { font-size: 12px; }
.run-report-link small { margin-top: 2px; color: var(--wao-text-weak); font-size: 10.5px; }
.run-report-link > i { color: #16815c; }
.run-detail footer { margin-top: 18px; display: flex; align-items: center; justify-content: flex-end; gap: 9px; }
.run-detail footer .retry-note { margin-right: auto; color: var(--wao-text-weak); font-size: 11px; }
.run-detail footer .retry-note i { margin-right: 4px; }
.run-detail footer button { padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 12px; }
.run-detail footer .retry { display: inline-flex; align-items: center; gap: 6px; color: var(--wao-danger-strong); border-color: var(--wao-danger-line); }
.run-detail footer .retry:disabled { opacity: .6; cursor: not-allowed; }
.gh-branch-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #4358d6;
  border-color: var(--wao-line-control);
}
.gh-branch-link:hover { border-color: var(--wao-primary-line); background: var(--wao-primary-tint); }
.gh-branch-link.disabled { opacity: .5; pointer-events: none; }
.gh-branch-link i { font-size: 13px; }
.run-detail footer .secondary { border: 1px solid var(--wao-line); background: var(--wao-bg-card); }
.run-detail footer .primary { border: 1px solid var(--wao-primary); background: var(--wao-primary); color: #fff; }
.runner-log-detail { width: min(860px, 100%); }
.runner-log-icon { background: #edf0f8; color: #43506e; }
.runner-log-state {
  min-height: 220px;
  margin-top: 18px;
  border: 1px dashed var(--wao-line);
  border-radius: 10px;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex-wrap: wrap;
}
.runner-log-state.error { color: var(--wao-danger-strong); }
.runner-log-state .secondary { margin-left: 6px; padding: 6px 10px; border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); cursor: pointer; }
.runner-log-meta { margin: 16px 0 7px; color: var(--wao-text-weak); font-size: 10.5px; }
.run-detail pre.runner-log-content {
  height: min(440px, 55vh);
  max-height: none;
  overflow: auto;
  border-color: #283044;
  background: #151a25;
  color: #d7deee;
  white-space: pre;
}
.runner-log-detail footer button { display: inline-flex; align-items: center; gap: 5px; }
@keyframes livePulse { 50% { opacity: .45; box-shadow: 0 0 0 7px #dff5e955; } }
.spin { display: inline-block; animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) {
  .home { padding: 16px; }
  .hero { padding: 22px; flex-direction: column; align-items: stretch; }
  .hero h1.slogan { font-size: 24px; letter-spacing: .5px; }
  .hero-metrics { width: auto; grid-template-columns: 1fr 1fr; }
  .hero-metrics.single { grid-template-columns: 1fr; }
  .hero-metrics.with-two-quotas .capacity { grid-column: 1 / -1; }
  .capacity { width: auto; }
  .daily-guidance > header { flex-wrap: wrap; }
  .guidance-refresh { margin-left: 51px; }
  .guidance-content { padding-left: 19px; }
  .guidance-state { align-items: flex-start; flex-wrap: wrap; }
  .guidance-state button { margin-left: 35px; }
  .evolution-panel > header { flex-wrap: wrap; }.auto-state { margin-left: 49px; }.suggestion-grid { grid-template-columns: 1fr; }.suggestion-card { grid-template-columns: 32px minmax(0, 1fr); }.suggestion-card button { grid-column: 2; justify-self: start; }.evolution-panel > footer small { width: 100%; margin-left: 0; }
  .goal-launcher { grid-template-columns: 1fr; padding: 17px; gap: 14px; }
  .goal-actions { flex-wrap: wrap; padding-left: 9px; }
  .goal-actions label { width: 100%; }
  .goal-actions select { flex: 1; max-width: none; }
  .goal-shortcut { margin-left: 0; }
  .goal-submit { margin-left: auto; }
  .console-tabs { overflow-x: auto; }
  .flow-meta { grid-template-columns: 1fr; }
  .flow-form { grid-template-columns: 1fr; }
  .run-detail dl { grid-template-columns: 1fr; }
}
</style>
