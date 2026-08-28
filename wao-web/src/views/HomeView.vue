<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createAiRuns,
  createAiPairCode,
  fetchAiEvolution,
  fetchAiActivities,
  fetchAiRun,
  fetchAiTaskAutoRunPolicy,
  fetchAiRunOptions,
  fetchAiRunnerLog,
  fetchAiRunners,
  fetchAiRuns,
  fetchAiWorkflows,
  launchAutonomousGoal,
  pauseAiRun,
  removeAiRunner,
  removeAiWorkflow,
  requestRunnerScriptUpdate,
  rerunAiRun,
  saveAiTaskAutoRunPolicy,
  saveAiProjectConfig,
  updateRunnerMaxConcurrency,
  wakeAiRunner,
  type AiEvolutionOverview,
  type AiTaskAutoRunPolicy,
  type CodexQuotaAccount,
  type AiActivity,
  type AiActivityType,
} from '@/api/ai'
import AiRunProgressTimeline from '@/components/ai/AiRunProgressTimeline.vue'
import AiRunDeliverySteps from '@/components/ai/AiRunDeliverySteps.vue'
import AiRunMemoryReferences from '@/components/ai/AiRunMemoryReferences.vue'
import AiRunSkillCapabilities from '@/components/ai/AiRunSkillCapabilities.vue'
import { AI_ENGINES, engineColor, engineIcon, engineLabel } from '@/composables/aiEngines'
import { formatCost, formatTokens, reasoningEffortLabel, shortModel } from '@/composables/aiUsage'
import { toast } from '@/composables/toast'
import { githubBrowseUrl, githubSubPage } from '@/config/quickLinks'
import { fetchWebhookEndpoints, type FeishuWebhookEndpoint } from '@/api/system'
import {
  fetchDailyGuidance,
  generateDailyGuidance,
  type DailyGuidance,
} from '@/api/dailyGuidance'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import type { AiEngine, AiRun, AiRunOptions, AiRunStatus, AiRunner, AiRunnerLog, AiWorkflow, Task, TaskStatus } from '@/types'
import { renderMarkdown } from '@/utils/markdown'

type ConsoleView = 'runs' | 'runners' | 'workflows'

// 与任务描述等输入场景复用同一编辑器；保持异步加载，避免把 ProseMirror 合进首页主包。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))

const route = useRoute()
const router = useRouter()
const data = useDataStore()
const preferences = usePreferenceStore()
const session = useSessionStore()
const ui = useUiStore()
const runs = ref<AiRun[]>([])
const recentActivities = ref<AiActivity[]>([])
const activityFilter = ref<AiActivityType>('ALL')
const recentActivitiesLoading = ref(true)
const recentActivitiesError = ref('')
const recentActivitiesPage = ref(1)
const recentActivitiesTotal = ref(0)
const RECENT_ACTIVITY_PAGE_SIZE = 8
const runners = ref<AiRunner[]>([])
const workflows = ref<AiWorkflow[]>([])
const loading = ref(true)
const workflowsLoading = ref(false)
const quotaOverview = ref<AiEvolutionOverview | null>(null)
const taskAutoRunPolicy = ref<AiTaskAutoRunPolicy | null>(null)
const taskAutoRunEnabled = ref(true)
const taskAutoRunHours = ref(2)
const taskAutoRunBatchSize = ref(2)
const taskAutoRunProjectId = ref('')
const taskAutoRunStatuses = ref<TaskStatus[]>(['待办'])
const taskAutoRunWebhookId = ref('')
const taskAutoRunWebhooks = ref<FeishuWebhookEndpoint[]>([])
const taskAutoRunWebhooksLoading = ref(true)
const taskAutoRunProjectError = ref('')
const taskAutoRunWebhookError = ref('')
const taskAutoRunDirty = ref(false)
const taskAutoRunSaving = ref(false)
const taskAutoRunError = ref('')
const goal = ref('')
const goalDraftState = ref<'idle' | 'saved' | 'error'>('idle')
const goalProjectId = ref('')
const goalRunnerId = ref('')
const goalRunnerManuallySelected = ref(false)
const goalPlanMode = ref(true)
const goalRunOptions = ref<AiRunOptions | null>(null)
const goalRunOptionsLoading = ref(true)
const goalRunOptionsError = ref('')
const goalReasoningEffort = ref('')
const launchingGoal = ref(false)
const goalImageUploads = ref(0)
const growthCoach = ref<DailyGuidance | null>(readCachedGrowthCoach())
const growthCoachLoading = ref(!growthCoach.value)
const growthCoachRefreshing = ref(false)
const growthCoachError = ref('')
const selectedRun = ref<AiRun | null>(null)
const runHistoryOpen = ref(false)
const runHistory = ref<AiActivity[]>([])
const runHistoryLoading = ref(false)
const runHistoryError = ref('')
const runHistoryPage = ref(1)
const runHistoryTotal = ref(0)
const RUN_HISTORY_PAGE_SIZE = 20
const quickTaskListOpen = ref(false)
const taskAutoRunSettingsOpen = ref(false)
const startingQuickTaskId = ref<string | null>(null)
const ACTIVITY_FILTERS: Array<{ id: AiActivityType; label: string }> = [
  { id: 'ALL', label: '全部' },
  { id: 'FEATURE_DEVELOPMENT', label: '功能开发' },
  { id: 'CHAT_REVIEW', label: '聊天复盘' },
]
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
const pausingRunId = ref<string | null>(null)
const editingRunnerId = ref<string | null>(null)
const editingRunnerValue = ref(2)
const editingRunnerSaving = ref(false)
const updatingRunners = ref(false)
const wakingRunnerId = ref<string | null>(null)
const removingRunnerId = ref<string | null>(null)
const removingWorkflowId = ref<string | null>(null)
const form = reactive({
  enabled: false,
  repoUrl: '',
  defaultBranch: 'main',
  workspaceDir: '',
  defaultEngine: 'codex' as AiEngine,
  preferredRunnerId: '',
  maxParallelRuns: 2,
  failureRetryCount: 2,
})
let timer: ReturnType<typeof setInterval> | null = null
let quotaTimer: ReturnType<typeof setInterval> | null = null

const activeRuns = computed(() => runs.value.filter((run) => ['queued', 'claimed', 'running'].includes(run.status)))
const onlineRunners = computed(() => runners.value.filter((runner) => runner.online))
const totalSlots = computed(() => onlineRunners.value.reduce((sum, runner) => sum + runner.maxConcurrency, 0))
const usedSlots = computed(() => onlineRunners.value.reduce((sum, runner) => sum + runner.activeRuns, 0))
const activeWorkflows = computed(() => workflows.value.filter((workflow) => workflow.config.enabled))
const runnableAutoRunWorkflows = computed(() => activeWorkflows.value.filter(
  (workflow) => !workflow.projectDeleted && workflow.config.repoUrl.trim(),
))
const runnableWorkflowByProject = computed(() => new Map(
  runnableAutoRunWorkflows.value.map((workflow) => [workflow.projectId, workflow]),
))
const taskAutoRunProjectMax = computed(() =>
  runnableWorkflowByProject.value.get(taskAutoRunProjectId.value)?.config.maxParallelRuns ?? 10,
)
const enabledTaskAutoRunWebhooks = computed(() => taskAutoRunWebhooks.value.filter((endpoint) => endpoint.enabled))
const AUTO_RUN_TASK_STATUSES: TaskStatus[] = ['待办', '收集中', '进行中']
const QUICK_TASK_PRIORITY = new Map([['P0', 0], ['P1', 1], ['P2', 2], ['P3', 3]])
const activeTaskIds = computed(() => new Set(activeRuns.value.flatMap(
  (run) => run.taskIds?.length ? run.taskIds : [run.taskId],
)))
/** 首页快捷派工只展示真实可运行工作流中的人工待处理任务；最终资格仍由后端入队事务校验。 */
const runnableTasks = computed(() => data.tasks
  .filter((task) => AUTO_RUN_TASK_STATUSES.includes(task.status))
  .filter((task) => runnableWorkflowByProject.value.has(task.project))
  .filter((task) => !activeTaskIds.value.has(task.id))
  .sort((left, right) => {
    const priority = (QUICK_TASK_PRIORITY.get(left.pr) ?? 99) - (QUICK_TASK_PRIORITY.get(right.pr) ?? 99)
    if (priority) return priority
    const leftCreated = left.createdAt ? Date.parse(left.createdAt) : Number.POSITIVE_INFINITY
    const rightCreated = right.createdAt ? Date.parse(right.createdAt) : Number.POSITIVE_INFINITY
    if (leftCreated !== rightCreated) return leftCreated - rightCreated
    return left.id.localeCompare(right.id)
  }))
const recommendedTasks = computed(() => runnableTasks.value.slice(0, 3))
const goalWorkflow = computed(() => activeWorkflows.value.find(
  (workflow) => workflow.projectId === goalProjectId.value,
) ?? null)
const goalSupportsPlanMode = computed(() => goalWorkflow.value?.config.defaultEngine === 'codex')
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
const WEEKLY_QUOTA_FRESH_MS = 45 * 60 * 1_000
const WEEKLY_QUOTA_CLOCK_SKEW_MS = 5 * 60 * 1_000

/** OAuth 快照没有单独 fresh 标记，只展示仍在有效窗口且最近四十五分钟同步成功的数据。 */
function isFreshWeeklyQuota(quota: AiEvolutionOverview['weeklyQuotas'][number]) {
  const reset = quota.resetsAt ? Date.parse(quota.resetsAt) : Number.NaN
  const synced = quota.lastSyncedAt ? Date.parse(quota.lastSyncedAt) : Number.NaN
  const age = Date.now() - synced
  const resetValid = quota.resetsAt === null || (Number.isFinite(reset) && reset > Date.now())
  return quota.status === 'SYNCED'
    && resetValid
    && Number.isFinite(synced)
    && age >= -WEEKLY_QUOTA_CLOCK_SKEW_MS
    && age <= WEEKLY_QUOTA_FRESH_MS
}

const visibleWeeklyQuotas = computed(() => quotaOverview.value?.weeklyQuotas.filter(isFreshWeeklyQuota) ?? [])
const primaryWeeklyQuota = computed(() => visibleWeeklyQuotas.value.find((quota) => quota.provider === 'codex') ?? null)
const codexQuotaAccounts = computed(() => quotaOverview.value?.codexQuotas ?? [])
const primaryCodexAccount = computed(() => codexQuotaAccounts.value.find((account) => account.fresh) ?? null)
const localWeeklyWindow = computed(() => {
  const window = findCodexWindow(primaryCodexAccount.value, 10_080)
  if (!window) return null
  return window.resetsAt === null || window.resetsAt * 1_000 > Date.now() ? window : null
})
const heroWeeklyQuota = computed(() => localWeeklyWindow.value ? {
  remainingPercent: 100 - localWeeklyWindow.value.usedPercent,
  resetsAt: unixTime(localWeeklyWindow.value.resetsAt),
} : primaryWeeklyQuota.value)

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
  queued: { label: '等待电脑领取', icon: 'ri-time-line', color: 'var(--wao-warning-strong)', bg: 'var(--wao-warning-bg)' },
  claimed: { label: '准备工作区', icon: 'ri-git-branch-line', color: 'var(--wao-primary-text)', bg: 'var(--wao-primary-soft)' },
  running: { label: 'AI 运行中', icon: 'ri-loader-4-line', color: 'var(--wao-primary-text)', bg: 'var(--wao-primary-soft)' },
  succeeded: { label: '等待验收', icon: 'ri-checkbox-circle-line', color: 'var(--wao-success-strong)', bg: 'var(--wao-success-bg)' },
  failed: { label: '运行失败', icon: 'ri-error-warning-line', color: 'var(--wao-danger-strong)', bg: 'var(--wao-danger-bg)' },
  cancelled: { label: '已取消', icon: 'ri-stop-circle-line', color: 'var(--wao-text-3)', bg: 'var(--wao-bg-hover-2)' },
}

// 退出码 3 是 Runner 的约定：AI 结束了会话但没报告任务状态，成败需要人判断，
// 不能直接挂一个「运行失败」让人分不清到底成没成。
const UNCONFIRMED_META = { label: '结果待确认', icon: 'ri-question-line', color: 'var(--wao-warning-strong)', bg: 'var(--wao-warning-bg)' }

function statusUnreported(run: AiRun) {
  return run.status === 'failed' && run.exitCode === 3
}

function metaFor(run: AiRun) {
  if (statusUnreported(run)) return UNCONFIRMED_META
  if (run.deliveryStage === 'waiting_deployment') {
    return { label: '代码已完成 · 等待部署', icon: 'ri-upload-cloud-2-line', color: 'var(--wao-warning-strong)', bg: 'var(--wao-warning-bg)' }
  }
  if (run.deliveryStage === 'deployment_succeeded') {
    return { ...statusMeta.succeeded, label: '部署成功 · 等待验收' }
  }
  if (run.deliveryStage === 'deployment_failed') {
    return { ...statusMeta.failed, label: '部署失败' }
  }
  if (run.deliveryStage === 'code_failed') {
    return { ...statusMeta.failed, label: '代码未完成' }
  }
  return statusMeta[run.status]
}

function resultTitle(run: AiRun) {
  if (statusUnreported(run)) return '结果待确认（AI 未报告任务状态）'
  return run.status === 'failed' ? '失败原因' : '最终结果'
}

async function refresh() {
  try {
    const prevSucceededIds = new Set(runs.value.filter((run) => run.status === 'succeeded').map((run) => run.id))
    const [nextRuns, nextRunners, activities] = await Promise.all([
      fetchAiRuns(false),
      fetchAiRunners(),
      fetchAiActivities(activityFilter.value, recentActivitiesPage.value, RECENT_ACTIVITY_PAGE_SIZE).catch((error) => {
        recentActivitiesError.value = error instanceof Error ? error.message : '最近 AI 运行读取失败'
        return null
      }),
    ])
    runs.value = nextRuns
    runners.value = nextRunners
    if (activities) {
      recentActivities.value = activities.items
      recentActivitiesPage.value = activities.page
      recentActivitiesTotal.value = activities.total
      recentActivitiesError.value = ''
    }
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
    recentActivitiesLoading.value = false
  }
}

async function refreshWorkflows() {
  workflowsLoading.value = true
  try {
    workflows.value = await fetchAiWorkflows()
    taskAutoRunProjectError.value = ''
  } catch (error) {
    taskAutoRunProjectError.value = error instanceof Error ? error.message : '自动派发项目读取失败'
    toast(error instanceof Error ? error.message : 'AI 工作流读取失败', 'ri-error-warning-line')
  } finally {
    workflowsLoading.value = false
  }
}

/** 首页思考深度使用后台允许列表和默认值，避免前端与 Runner 能力漂移。 */
async function refreshGoalRunOptions() {
  goalRunOptionsLoading.value = true
  try {
    const options = await fetchAiRunOptions()
    goalRunOptions.value = options
    if (!options.reasoningEfforts.some((item) => item.id === goalReasoningEffort.value)) {
      goalReasoningEffort.value = options.defaultReasoningEffort
    }
    goalRunOptionsError.value = ''
  } catch (error) {
    goalRunOptions.value = null
    goalRunOptionsError.value = error instanceof Error ? error.message : '思考深度读取失败'
  } finally {
    goalRunOptionsLoading.value = false
  }
}

/** 额度仍在首页展示，但不再渲染、生成或派发任何自主进化建议。 */
async function refreshQuotaOverview() {
  try {
    quotaOverview.value = await fetchAiEvolution()
  } catch {
    // 额度读取失败时隐藏入口，运行状态与目标派发继续可用。
  }
}

function growthCoachBusinessDate() {
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date())
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${value.year}-${value.month}-${value.day}`
}

function growthCoachCacheKey() {
  return `wao-growth-coach-v1:${session.currentUser.id || 'wg'}`
}

function readCachedGrowthCoach(): DailyGuidance | null {
  try {
    const raw = sessionStorage.getItem(growthCoachCacheKey())
    if (!raw) return null
    const cached = JSON.parse(raw) as DailyGuidance
    return cached.status === 'ready' && cached.date === growthCoachBusinessDate() && cached.content
      ? cached : null
  } catch {
    return null
  }
}

function rememberGrowthCoach(value: DailyGuidance | null) {
  try {
    if (value?.status === 'ready' && value.content && value.date === growthCoachBusinessDate()) {
      sessionStorage.setItem(growthCoachCacheKey(), JSON.stringify(value))
    } else {
      sessionStorage.removeItem(growthCoachCacheKey())
    }
  } catch {
    // 隐私模式禁用会话存储时仍以服务端预生成结果为准。
  }
}

async function loadGrowthCoach() {
  growthCoachLoading.value = !growthCoach.value?.content
  growthCoachError.value = ''
  try {
    const current = await fetchDailyGuidance()
    growthCoach.value = current
    rememberGrowthCoach(current)
  } catch (error) {
    growthCoachError.value = error instanceof Error ? error.message : '今天的成长启发读取失败'
  } finally {
    growthCoachLoading.value = false
  }
}

async function refreshGrowthCoach(refresh = true) {
  if (growthCoachRefreshing.value) return
  growthCoachRefreshing.value = true
  growthCoachError.value = ''
  try {
    growthCoach.value = await generateDailyGuidance(refresh)
    rememberGrowthCoach(growthCoach.value)
  } catch (error) {
    growthCoachError.value = error instanceof Error ? error.message : '今天的成长启发生成失败，请重试'
  } finally {
    growthCoachRefreshing.value = false
    growthCoachLoading.value = false
  }
}

function discussGrowthCoach() {
  if (!growthCoach.value?.content) return
  ui.openAssistant(`我想继续聊今天的成长启发。请不要急着给结论，先结合 WAO 里的真实项目、Task 和个人记忆，用追问帮我把模糊困惑说清楚。\n\n今天的成长启发：\n${growthCoach.value.content}`)
}

/** 后台轮询只在没有本地草稿时回填，避免覆盖用户正在修改的开关或小时数。 */
async function refreshTaskAutoRunPolicy() {
  try {
    const policy = await fetchAiTaskAutoRunPolicy()
    taskAutoRunPolicy.value = policy
    if (!taskAutoRunDirty.value && !taskAutoRunSaving.value) {
      taskAutoRunEnabled.value = policy.enabled
      taskAutoRunHours.value = policy.idleHours
      taskAutoRunBatchSize.value = policy.batchSize
      taskAutoRunProjectId.value = policy.projectId ?? ''
      taskAutoRunStatuses.value = [...policy.taskStatuses]
      taskAutoRunWebhookId.value = policy.notificationWebhookId ?? ''
    }
    taskAutoRunError.value = ''
  } catch (error) {
    taskAutoRunError.value = error instanceof Error ? error.message : '自动派发策略读取失败'
  }
}

async function refreshTaskAutoRunWebhooks() {
  taskAutoRunWebhooksLoading.value = true
  try {
    taskAutoRunWebhooks.value = await fetchWebhookEndpoints()
    taskAutoRunWebhookError.value = ''
  } catch (error) {
    taskAutoRunWebhookError.value = error instanceof Error ? error.message : '通知群读取失败，请重试'
  } finally {
    taskAutoRunWebhooksLoading.value = false
  }
}

function markTaskAutoRunDirty() {
  taskAutoRunDirty.value = true
  taskAutoRunError.value = ''
}

function changeTaskAutoRunProject() {
  taskAutoRunBatchSize.value = Math.min(taskAutoRunBatchSize.value, taskAutoRunProjectMax.value)
  markTaskAutoRunDirty()
}

async function saveTaskAutoRunPolicy() {
  const hours = Number(taskAutoRunHours.value)
  if (!Number.isInteger(hours) || hours < 1 || hours > 168) {
    taskAutoRunError.value = '空闲时长请输入 1 到 168 之间的整数'
    return
  }
  const batchSize = Number(taskAutoRunBatchSize.value)
  if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > taskAutoRunProjectMax.value) {
    taskAutoRunError.value = `单次推进数请输入 1 到 ${taskAutoRunProjectMax.value} 之间的整数`
    return
  }
  if (!taskAutoRunProjectId.value) {
    taskAutoRunError.value = '请选择自动派发项目'
    return
  }
  const statuses = taskAutoRunStatuses.value.filter(
    (status): status is '待办' | '收集中' | '进行中' => AUTO_RUN_TASK_STATUSES.includes(status),
  )
  if (!statuses.length) {
    taskAutoRunError.value = '请至少选择一种任务状态'
    return
  }
  taskAutoRunSaving.value = true
  taskAutoRunError.value = ''
  try {
    const policy = await saveAiTaskAutoRunPolicy(
      taskAutoRunEnabled.value,
      hours,
      batchSize,
      taskAutoRunProjectId.value,
      statuses,
      taskAutoRunWebhookId.value || null,
    )
    taskAutoRunPolicy.value = policy
    taskAutoRunEnabled.value = policy.enabled
    taskAutoRunHours.value = policy.idleHours
    taskAutoRunBatchSize.value = policy.batchSize
    taskAutoRunProjectId.value = policy.projectId ?? ''
    taskAutoRunStatuses.value = [...policy.taskStatuses]
    taskAutoRunWebhookId.value = policy.notificationWebhookId ?? ''
    taskAutoRunDirty.value = false
    toast(policy.enabled ? '自动派发策略已开启并保存' : '自动派发已关闭', 'ri-check-line')
  } catch (error) {
    taskAutoRunError.value = error instanceof Error ? error.message : '自动派发策略保存失败，请重试'
  } finally {
    taskAutoRunSaving.value = false
  }
}

function quotaClass(remaining: number) {
  return remaining < 20 ? 'danger' : remaining < 50 ? 'warn' : 'good'
}

function findCodexWindow(account: CodexQuotaAccount | null, durationMinutes: number) {
  if (!account) return null
  const general = account.limits.find((limit) => limit.id === 'codex') ?? account.limits[0]
  return general?.windows.find((window) => window.windowDurationMinutes === durationMinutes) ?? null
}

function unixTime(value: number | null) {
  return value ? new Date(value * 1000).toISOString() : null
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

function goalDraftKey() {
  return `wao-home-goal-draft-v1:${session.currentUser.id || 'wg'}`
}

function restoreGoalDraft() {
  try {
    goal.value = localStorage.getItem(goalDraftKey()) ?? ''
    goalDraftState.value = goal.value ? 'saved' : 'idle'
  } catch {
    goalDraftState.value = 'error'
  }
}

function saveGoalDraft(value: string) {
  try {
    if (value) localStorage.setItem(goalDraftKey(), value)
    else localStorage.removeItem(goalDraftKey())
    goalDraftState.value = value ? 'saved' : 'idle'
  } catch {
    goalDraftState.value = 'error'
  }
}

watch(goal, saveGoalDraft)

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
    toast('请先在「AI 工作流」中接入一个项目', 'ri-information-line')
    return
  }
  if (goalImageUploads.value) {
    toast('图片还在上传，请稍候再派发', 'ri-information-line')
    return
  }
  if (goalSupportsPlanMode.value && !goalRunOptions.value) {
    toast(goalRunOptionsError.value || '正在读取思考深度，请稍候再派发', 'ri-information-line')
    return
  }
  launchingGoal.value = true
  try {
    const run = await launchAutonomousGoal(
      goalProjectId.value, normalizedGoal, goalRunnerId.value || null,
      goalSupportsPlanMode.value && goalPlanMode.value,
      goalSupportsPlanMode.value ? goalReasoningEffort.value : null,
    )
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
    const image = await data.uploadImage(file, '自主目标', { requirePersisted: true })
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
    failureRetryCount: workflow.config.failureRetryCount,
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
      failureRetryCount: form.failureRetryCount,
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

function openTask(task: Task) {
  quickTaskListOpen.value = false
  void router.push({ path: `/projects/${task.project}`, query: { task: task.id } })
}

function taskProjectName(task: Task) {
  return runnableWorkflowByProject.value.get(task.project)?.projectName
    ?? data.projectById(task.project)?.name
    ?? '未知项目'
}

function taskExcerpt(task: Task) {
  const text = task.desc
    .replace(/!\[[^\]]*]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/[`#>*_~-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return text || '打开任务可查看完整描述和未完成子任务。'
}

/** 一次点击沿用项目默认工作流直接入队；后端继续负责 Runner 能力、重复运行和权限的最终校验。 */
async function startQuickTask(task: Task) {
  if (startingQuickTaskId.value) return
  const workflow = runnableWorkflowByProject.value.get(task.project)
  if (!workflow) {
    toast('这个项目当前没有可用的 AI 工作流，请先完成接入', 'ri-information-line')
    return
  }
  startingQuickTaskId.value = task.id
  try {
    const created = await createAiRuns([task.id], {
      engine: workflow.config.defaultEngine,
      runnerId: workflow.config.preferredRunnerId,
      planMode: workflow.config.defaultEngine === 'codex',
    })
    if (!created.length) {
      await refresh()
      toast('这个任务已经在运行，无需重复派发', 'ri-information-line')
      return
    }
    runs.value = [...created, ...runs.value.filter((item) => !created.some((run) => run.id === item.id))]
    task.status = 'AI 进行中'
    quickTaskListOpen.value = false
    view.value = 'runs'
    toast(`已把「${task.title}」交给 ${engineLabel(workflow.config.defaultEngine)}，正在等待执行电脑`, 'ri-rocket-2-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '任务派发失败，请重试', 'ri-error-warning-line')
  } finally {
    startingQuickTaskId.value = null
  }
}

/** 根据 run 找到对应的工作流配置，用于拼接 GitHub 链接。 */
function findWorkflowForRun(run: AiRun) {
  return workflows.value.find((w) => w.projectId === run.projectId)
}

function inspectRun(run: AiRun) {
  selectedRun.value = run
}

function activityMeta(activity: AiActivity) {
  if (activity.status === 'FAILED') return { label: '运行失败', icon: 'ri-close-circle-line', color: 'var(--wao-danger-strong)', bg: 'var(--wao-danger-bg)' }
  return { label: '运行成功', icon: 'ri-checkbox-circle-line', color: 'var(--wao-success-strong)', bg: 'var(--wao-success-bg)' }
}

function activityTypeMeta(activity: AiActivity) {
  return activity.type === 'CHAT_REVIEW'
    ? { label: '聊天复盘', icon: 'ri-chat-history-line', color: 'var(--wao-success-strong)', bg: 'var(--wao-success-bg)' }
    : { label: '功能开发', icon: 'ri-code-box-line', color: 'var(--wao-primary-text)', bg: 'var(--wao-primary-soft)' }
}

async function openActivity(activity: AiActivity) {
  if (activity.type === 'CHAT_REVIEW') void router.push({ path: '/chat', query: { review: activity.targetId } })
  else {
    try {
      selectedRun.value = runs.value.find((run) => run.id === activity.id) ?? await fetchAiRun(activity.id)
    } catch (error) {
      toast(error instanceof Error ? error.message : '运行详情读取失败', 'ri-error-warning-line')
    }
  }
}

async function selectActivityFilter(type: AiActivityType) {
  activityFilter.value = type
  await changeRecentActivitiesPage(1)
}

async function changeRecentActivitiesPage(page: number) {
  recentActivitiesLoading.value = true
  recentActivitiesError.value = ''
  try {
    const result = await fetchAiActivities(activityFilter.value, page, RECENT_ACTIVITY_PAGE_SIZE)
    recentActivities.value = result.items
    recentActivitiesPage.value = result.page
    recentActivitiesTotal.value = result.total
  } catch (error) {
    recentActivitiesError.value = error instanceof Error ? error.message : '最近 AI 运行读取失败'
  } finally {
    recentActivitiesLoading.value = false
  }
}

async function openRunHistory(page = 1) {
  runHistoryOpen.value = true
  runHistoryLoading.value = true
  runHistoryError.value = ''
  try {
    const result = await fetchAiActivities(activityFilter.value, page, RUN_HISTORY_PAGE_SIZE)
    runHistory.value = result.items
    runHistoryPage.value = result.page
    runHistoryTotal.value = result.total
  } catch (error) {
    runHistoryError.value = error instanceof Error ? error.message : '运行历史读取失败'
  } finally {
    runHistoryLoading.value = false
  }
}

const runHistoryPages = computed(() => Math.max(1, Math.ceil(runHistoryTotal.value / RUN_HISTORY_PAGE_SIZE)))
const recentActivitiesPages = computed(() => Math.max(1, Math.ceil(recentActivitiesTotal.value / RECENT_ACTIVITY_PAGE_SIZE)))

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

/** 首页运行档案也能停止真实执行；后端落为 cancelled，Runner 随后终止整棵 CLI 进程树。 */
async function stopRun(run: AiRun) {
  if (pausingRunId.value) return
  if (!window.confirm(`停止 AI 任务「${run.taskTitle}」？当前执行会被终止，已产生的未提交结果可能丢失。`)) return
  pausingRunId.value = run.id
  try {
    const stopped = await pauseAiRun(run.id)
    runs.value = runs.value.map((item) => item.id === run.id ? stopped : item)
    if (run.runnerId) {
      runners.value = runners.value.map((item) => item.id === run.runnerId
        ? { ...item, activeRuns: Math.max(0, item.activeRuns - 1) }
        : item)
    }
    selectedRun.value = stopped
    toast('已停止 AI 任务，执行电脑正在结束当前进程', 'ri-stop-circle-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '停止失败，请重试', 'ri-error-warning-line')
  } finally {
    pausingRunId.value = null
  }
}

function timeText(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function capacityText(value?: number | null) {
  if (value == null || value < 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = value
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit += 1 }
  return `${size >= 100 || unit === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[unit]}`
}

function resourcePercent(total?: number | null, available?: number | null) {
  if (!total || available == null) return 0
  return Math.max(0, Math.min(100, Math.round((total - available) * 100 / total)))
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

/**
 * 首页运行弹层不属于 AppLayout 的全局 UI store，需要自己进入 Escape 关闭栈。
 * 详情可能叠在完整历史之上，因此一次只关闭最上层；全局弹层或任务抽屉在前景时交回各自处理。
 */
function onDialogKeydown(event: KeyboardEvent) {
  if (
    event.key !== 'Escape'
    || ui.assistantOpen
    || ui.celebrating
    || ui.helpOpen
    || ui.searchOpen
    || ui.modal
    || ui.openTaskId
  ) return
  if (selectedRun.value) selectedRun.value = null
  else if (logRunner.value) closeRunnerLog()
  else if (runHistoryOpen.value) runHistoryOpen.value = false
  else if (quickTaskListOpen.value) quickTaskListOpen.value = false
  else if (taskAutoRunSettingsOpen.value) taskAutoRunSettingsOpen.value = false
  else return
  event.preventDefault()
  event.stopPropagation()
}

onMounted(() => {
  restoreGoalDraft()
  void refresh()
  void refreshWorkflows()
  void loadGrowthCoach()
  void refreshGoalRunOptions()
  void refreshQuotaOverview()
  void refreshTaskAutoRunPolicy()
  void refreshTaskAutoRunWebhooks()
  timer = setInterval(refresh, 5_000)
  quotaTimer = setInterval(() => {
    void refreshQuotaOverview()
    void refreshTaskAutoRunPolicy()
  }, 60_000)
  window.addEventListener('keydown', onDialogKeydown)
  if (route.query.console) revealAiArea()
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (quotaTimer) clearInterval(quotaTimer)
  window.removeEventListener('keydown', onDialogKeydown)
})
</script>

<template>
  <div class="home">
    <div class="home-primary">
      <section class="hero">
      <div>
        <div class="eyebrow">AI CONTROL CENTER</div>
        <!-- 核心理念摆在首屏第一行：这套系统的用法就是「你敢想，AI 就敢做」 -->
        <h1 class="slogan">娃有多大胆，AI 有多大产</h1>
        <div class="hero-signals" aria-label="AI 控制中心状态">
          <span :title="`正在运行 ${activeRuns.length} 个任务`" :aria-label="`正在运行 ${activeRuns.length} 个任务`">
            <i class="ri-pulse-line"></i><b>{{ activeRuns.length }}</b>
          </span>
          <span
            v-for="engine in engineStatus"
            :key="engine.id"
            :class="{ dim: !engine.online }"
            :title="`${engine.label}：${engine.online ? `${engine.online} 台在线` : engine.total ? '全部离线' : '未接入'}`"
            :aria-label="`${engine.label}：${engine.online ? `${engine.online} 台在线` : engine.total ? '全部离线' : '未接入'}`"
          >
            <i :class="engine.icon"></i><i class="signal-dot"></i>
          </span>
          <span :title="`执行槽位 ${usedSlots}/${totalSlots}`" :aria-label="`执行槽位 ${usedSlots}/${totalSlots}`">
            <i class="ri-server-line"></i><b>{{ usedSlots }}/{{ totalSlots }}</b>
          </span>
        </div>
        <div class="hero-links">
          <button class="hero-link" type="button" @click="router.push('/dashboard')">
            <i class="ri-line-chart-line"></i>查看 AI 详情
          </button>
          <button class="hero-link" type="button" @click="router.push('/system-reports')">
            <i class="ri-bar-chart-box-line"></i>查看系统报告
          </button>
        </div>
      </div>
      <div class="hero-metrics" :class="{ single: !heroWeeklyQuota }">
        <div
          v-if="heroWeeklyQuota"
          class="quota-card weekly-quota"
          :class="quotaClass(heroWeeklyQuota.remainingPercent)"
          :title="`Codex 真实周剩余额度，${resetText(heroWeeklyQuota.resetsAt)}`"
        >
          <span><i class="ri-speed-up-line"></i>Codex 周剩余额度</span>
          <strong>{{ heroWeeklyQuota.remainingPercent }}<em>%</em></strong>
          <small>{{ resetText(heroWeeklyQuota.resetsAt) }}</small>
        </div>
        <div
          class="capacity"
          :style="{ '--capacity': `${totalSlots ? Math.min(100, usedSlots / totalSlots * 100) : 0}%` }"
        >
          <div class="capacity-orbit" aria-hidden="true"><i></i><b></b><em></em></div>
          <div class="capacity-copy">
            <div class="capacity-num">{{ usedSlots }}<span>/ {{ totalSlots }}</span></div>
            <div>执行槽位占用</div>
            <div class="capacity-bar"><i></i></div>
          </div>
        </div>
      </div>
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
            <label class="goal-control-field" title="放进哪条工作流">
              <span v-if="!preferences.compactControls">放进哪条工作流</span>
              <select
                v-model="goalProjectId"
                class="goal-workflow-select"
                aria-label="放进哪条工作流"
                :disabled="workflowsLoading || launchingGoal || !activeWorkflows.length"
              >
                <option v-if="!activeWorkflows.length" value="">还没有已启用的工作流</option>
                <option v-for="workflow in activeWorkflows" :key="workflow.projectId" :value="workflow.projectId">
                  {{ workflow.projectName }} · {{ engineLabel(workflow.config.defaultEngine) }}
                </option>
              </select>
            </label>
            <label class="goal-runner-field goal-control-field" :title="`执行电脑：${goalRunnerHint}`">
              <span v-if="!preferences.compactControls">执行电脑</span>
              <select
                v-model="goalRunnerId"
                class="goal-runner-select"
                aria-label="执行电脑"
                :disabled="launchingGoal || !goalWorkflow"
                @change="goalRunnerManuallySelected = true"
              >
                <option value="">自动分配电脑</option>
                <option v-for="runner in goalRunnerCandidates" :key="runner.id" :value="runner.id">
                  {{ runner.name }}{{ runner.online ? ` · 在线 ${runner.activeRuns}/${runner.maxConcurrency}` : ' · 离线' }}
                </option>
              </select>
            </label>
            <label v-if="goalSupportsPlanMode" class="goal-control-field" title="为本次 Codex AI Run 选择模型思考深度">
              <span v-if="!preferences.compactControls">思考深度</span>
              <select
                v-if="goalRunOptions"
                v-model="goalReasoningEffort"
                class="goal-reasoning-select"
                aria-label="模型思考深度"
                :disabled="launchingGoal"
              >
                <option v-for="option in goalRunOptions.reasoningEfforts" :key="option.id" :value="option.id">
                  {{ option.label }} · {{ option.description }}
                </option>
              </select>
              <button
                v-else
                type="button"
                class="goal-reasoning-retry"
                :disabled="goalRunOptionsLoading"
                @click="refreshGoalRunOptions"
              >
                <i :class="goalRunOptionsLoading ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>
                {{ goalRunOptionsLoading ? '读取思考深度' : '重试思考深度' }}
              </button>
            </label>
            <button
              v-if="goalSupportsPlanMode"
              class="goal-plan-mode"
              :class="{ on: goalPlanMode }"
              type="button"
              role="switch"
              :aria-checked="goalPlanMode"
              :disabled="launchingGoal"
              @click="goalPlanMode = !goalPlanMode"
            >
              <span class="goal-switch"><i></i></span><span>Plan 模式</span>
            </button>
            <span class="goal-shortcut">
              <template v-if="goalImageUploads">{{ goalImageUploads }} 张图片上传中 · </template>
              <template v-else-if="goalDraftState === 'saved'">草稿已保存在本机 · </template>
              <template v-else-if="goalDraftState === 'error'">本机草稿保存失败 · </template>
              {{ goal.length }} 字 · Ctrl/Command + Enter
            </span>
            <button
              v-if="activeWorkflows.length"
              class="goal-submit"
              :disabled="launchingGoal || goalImageUploads > 0 || !goal.trim() || !goalProjectId || (goalSupportsPlanMode && !goalRunOptions)"
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

      <section class="growth-coach" aria-labelledby="growth-coach-title">
        <header class="growth-coach-head">
          <span class="growth-coach-icon"><i class="ri-compass-discover-line"></i></span>
          <div>
            <div class="eyebrow dark">GROWTH COACH</div>
            <h2 id="growth-coach-title">今天别只比 Coding</h2>
            <p>从你真实做过和正在做的事里，找到一条值得刻意训练的 L2–L5 能力。</p>
          </div>
          <button
            v-if="growthCoach?.status === 'ready'"
            class="growth-coach-refresh"
            :disabled="growthCoachRefreshing"
            @click="refreshGrowthCoach(true)"
          >
            <i :class="growthCoachRefreshing ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>
            {{ growthCoachRefreshing ? '正在换个角度…' : '换个角度' }}
          </button>
        </header>

        <div v-if="growthCoachLoading && !growthCoach?.content" class="growth-coach-state">
          <i class="ri-loader-4-line spin"></i>
          <div><b>正在读取今天的成长启发</b><span>后台会结合你的真实 Task 提前准备。</span></div>
        </div>
        <div v-else-if="growthCoach?.status === 'ready' && growthCoach.content" class="growth-coach-ready">
          <div class="growth-coach-content md-body" v-html="renderMarkdown(growthCoach.content)"></div>
          <footer>
            <small v-if="growthCoach.generatedAt"><i class="ri-shield-check-line"></i>{{ timeText(growthCoach.generatedAt) }} 生成 · 只使用你的 WAO 上下文</small>
            <button class="growth-coach-discuss" @click="discussGrowthCoach">
              <i class="ri-chat-smile-3-line"></i>带着这个问题继续聊
            </button>
          </footer>
          <div v-if="growthCoachError" class="growth-coach-error" role="alert">
            <i class="ri-error-warning-line"></i><span>{{ growthCoachError }}</span>
            <button :disabled="growthCoachRefreshing" @click="refreshGrowthCoach(true)">重试</button>
          </div>
        </div>
        <div v-else class="growth-coach-state" :class="{ error: growthCoachError || growthCoach?.status === 'unavailable' }">
          <i :class="growthCoach?.status === 'missing' ? 'ri-time-line' : 'ri-error-warning-line'"></i>
          <div>
            <b>{{ growthCoach?.status === 'missing' ? '今天的成长启发正在准备' : '成长教练暂时不可用' }}</b>
            <span>{{ growthCoachError || growthCoach?.message || '稍后可以重新读取真实结果。' }}</span>
          </div>
          <button @click="loadGrowthCoach"><i class="ri-refresh-line"></i>重新读取</button>
          <button
            v-if="growthCoach?.status === 'missing'"
            class="primary"
            :disabled="growthCoachRefreshing"
            @click="refreshGrowthCoach(false)"
          ><i :class="growthCoachRefreshing ? 'ri-loader-4-line spin' : 'ri-sparkling-line'"></i>{{ growthCoachRefreshing ? '生成中…' : '现在生成' }}</button>
        </div>
      </section>
    </div>

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
          <div class="run-section-actions">
            <button
              class="auto-run-settings-trigger"
              :class="{ on: taskAutoRunEnabled }"
              :title="taskAutoRunPolicy?.message || '设置空闲时自动推进最早待办'"
              @click="taskAutoRunSettingsOpen = true"
            >
              <i class="ri-timer-flash-line"></i>
              <span>自动推进</span>
              <em>{{ taskAutoRunPolicy ? taskAutoRunEnabled ? '已开启' : '已关闭' : '读取中' }}</em>
            </button>
            <button class="quick-task-list-trigger" @click="quickTaskListOpen = true">
              <i class="ri-list-check-3"></i>可 AI Run 的任务
              <em v-if="runnableTasks.length">{{ runnableTasks.length }}</em>
            </button>
          </div>
        </div>
        <div v-if="loading" class="empty"><i class="ri-loader-4-line spin"></i>正在读取 AI 运行状态…</div>
        <div v-else-if="!activeRuns.length" class="quick-run-empty">
          <div class="quick-run-empty-title">
            <span><i class="ri-robot-2-line"></i></span>
            <div>
              <b>当前没有任务在运行</b>
              <p v-if="recommendedTasks.length">为你推荐 {{ recommendedTasks.length }} 条可直接执行的任务，按优先级和录入时间排序。</p>
              <p v-else>这里会展示已接入 AI 工作流、且当前可以执行的真实任务。</p>
            </div>
          </div>
          <div v-if="data.taskLoadState === 'loading' || workflowsLoading" class="quick-task-state">
            <i class="ri-loader-4-line spin"></i>正在读取可执行任务…
          </div>
          <div v-else-if="data.taskLoadState === 'error' || taskAutoRunProjectError" class="quick-task-state error" role="alert">
            <i class="ri-error-warning-line"></i>
            <span>{{ data.taskLoadError || taskAutoRunProjectError }}</span>
            <button @click="data.hydrate(); refreshWorkflows()">重试</button>
          </div>
          <div v-else-if="recommendedTasks.length" class="quick-task-grid">
            <article v-for="task in recommendedTasks" :key="task.id" class="quick-task-card">
              <div class="quick-task-meta">
                <span :class="`priority ${task.pr.toLowerCase()}`">{{ task.pr }}</span>
                <span>{{ task.status }}</span>
                <span><i class="ri-stack-line"></i>{{ taskProjectName(task) }}</span>
              </div>
              <button class="quick-task-title" :title="`打开任务：${task.title}`" @click="openTask(task)">{{ task.title }}</button>
              <p>{{ taskExcerpt(task) }}</p>
              <footer>
                <span v-if="task.createdAt"><i class="ri-time-line"></i>{{ timeText(task.createdAt) }} 录入</span>
                <button class="quick-task-start" :disabled="startingQuickTaskId !== null" @click="startQuickTask(task)">
                  <i :class="startingQuickTaskId === task.id ? 'ri-loader-4-line spin' : 'ri-play-circle-line'"></i>
                  {{ startingQuickTaskId === task.id ? '正在派发…' : '开始运行' }}
                </button>
              </footer>
            </article>
          </div>
          <div v-else class="quick-task-state">
            <i class="ri-checkbox-circle-line"></i>
            <span>{{ runnableAutoRunWorkflows.length ? '目前没有待办、收集中或进行中的可执行任务。' : '还没有已启用且配置了 Git 仓库的 AI 工作流。' }}</span>
            <button v-if="!runnableAutoRunWorkflows.length" @click="view = 'workflows'">接入工作流</button>
          </div>
        </div>
        <div v-else class="run-grid">
          <button v-for="run in activeRuns" :key="run.id" class="run-card" @click="inspectRun(run)">
            <div class="run-top">
              <span class="status" :style="{ color: metaFor(run).color, background: metaFor(run).bg }">
                <i :class="[metaFor(run).icon, { spin: run.status === 'running' && run.deliveryStage !== 'waiting_deployment' }]"></i>
                {{ metaFor(run).label }}
              </span>
              <span class="run-time">{{ timeText(run.startedAt || run.createdAt) }}</span>
            </div>
            <div class="run-media" :class="{ 'has-images': run.previewImages?.length }">
              <div v-if="run.previewImages?.length" class="run-image-strip" :class="{ single: run.previewImages.length === 1 }">
                <img v-for="(image, index) in run.previewImages" :key="`${image}-${index}`" :src="image" :alt="`${run.taskTitle} · 图片 ${index + 1}`" loading="lazy" />
              </div>
              <div v-else class="run-image-placeholder" aria-hidden="true">
                <span><i :class="engineIcon(run.engine)"></i></span>
                <i class="ri-code-s-slash-line"></i>
              </div>
            </div>
            <h3 :title="run.taskTitle">{{ run.taskTitle }}</h3>
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

      <section class="recent-section">
        <div class="section-head">
          <div><h2>最近 AI 运行</h2><p>开发任务与聊天复盘按完成时间统一排列，类型可继续扩展。</p></div>
          <div class="recent-actions">
            <nav v-if="recentActivitiesTotal > RECENT_ACTIVITY_PAGE_SIZE" class="recent-pagination" aria-label="最近 AI 运行分页">
              <button :disabled="recentActivitiesLoading || recentActivitiesPage <= 1" @click="changeRecentActivitiesPage(recentActivitiesPage - 1)"><i class="ri-arrow-left-line"></i>上一页</button>
              <span>{{ recentActivitiesPage }} / {{ recentActivitiesPages }}</span>
              <button :disabled="recentActivitiesLoading || recentActivitiesPage >= recentActivitiesPages" @click="changeRecentActivitiesPage(recentActivitiesPage + 1)">下一页<i class="ri-arrow-right-line"></i></button>
            </nav>
            <button class="history-link" @click="openRunHistory()">查看全部<i class="ri-external-link-line"></i></button>
          </div>
        </div>
        <div class="activity-filters" role="tablist" aria-label="AI 运行类型">
          <button v-for="option in ACTIVITY_FILTERS" :key="option.id" :class="{ active: activityFilter === option.id }" :disabled="recentActivitiesLoading" @click="selectActivityFilter(option.id)">{{ option.label }}</button>
        </div>
        <div v-if="recentActivitiesLoading" class="recent-state"><i class="ri-loader-4-line spin"></i>正在读取最近 AI 运行…</div>
        <div v-else-if="recentActivitiesError" class="recent-state error" role="alert"><i class="ri-error-warning-line"></i><span>{{ recentActivitiesError }}</span><button @click="changeRecentActivitiesPage(recentActivitiesPage)">重试</button></div>
        <div v-else-if="!recentActivities.length" class="recent-state"><i class="ri-inbox-2-line"></i>该类型还没有已结束的运行。</div>
        <div v-else class="recent-grid">
          <button v-for="activity in recentActivities" :key="`${activity.type}-${activity.id}`" class="recent-card" @click="openActivity(activity)">
            <span class="recent-card-top">
              <span class="recent-icon" :style="{ color: activityTypeMeta(activity).color, background: activityTypeMeta(activity).bg }"><i :class="activityTypeMeta(activity).icon"></i></span>
              <span class="recent-type" :style="{ color: activityTypeMeta(activity).color, background: activityTypeMeta(activity).bg }">{{ activityTypeMeta(activity).label }}</span>
              <span class="recent-status" :style="{ color: activityMeta(activity).color, background: activityMeta(activity).bg }"><i :class="activityMeta(activity).icon"></i>{{ activityMeta(activity).label }}</span>
              <span class="run-time">{{ timeText(activity.finishedAt || activity.createdAt) }}</span>
            </span>
            <span class="recent-main">
              <b :title="activity.title">{{ activity.title }}</b>
              <em><i :class="activity.type === 'CHAT_REVIEW' ? 'ri-chat-3-line' : 'ri-stack-line'"></i>{{ activity.subtitle }}</em>
              <em><i class="ri-sparkling-2-line"></i>{{ activity.capabilityLabel }}</em>
              <small v-if="activity.status === 'FAILED'">{{ activity.resultPreview }}</small>
            </span>
            <span class="recent-open">{{ activity.type === 'CHAT_REVIEW' ? '打开复盘' : '查看详情' }}<i class="ri-arrow-right-line"></i></span>
          </button>
        </div>
      </section>
    </template>

    <template v-else-if="view === 'runners'">
      <section class="run-section">
        <div class="section-head">
          <div>
            <h2>执行电脑与服务器资源</h2>
            <p>每台已连接节点上报 AI 工具与有界资源快照；登录态、进程和文件明细仍留在本机。</p>
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
            <section v-if="runner.systemMetricsCollectedAt" class="runner-resources">
              <header><b>机器资源</b><small>{{ timeText(runner.systemMetricsCollectedAt) }} 采集</small></header>
              <div class="resource-row">
                <span><i class="ri-cpu-line"></i>CPU</span>
                <b>{{ runner.cpuUsagePercent == null ? '等待第二次采样' : `${runner.cpuUsagePercent.toFixed(1)}%` }}</b>
                <em><i :style="{ width: `${runner.cpuUsagePercent || 0}%` }"></i></em>
                <small>{{ runner.cpuCores || '—' }} 核</small>
              </div>
              <div class="resource-row">
                <span><i class="ri-ram-line"></i>内存</span>
                <b>{{ resourcePercent(runner.memoryTotalBytes, runner.memoryAvailableBytes) }}%</b>
                <em><i :style="{ width: `${resourcePercent(runner.memoryTotalBytes, runner.memoryAvailableBytes)}%` }"></i></em>
                <small>可用 {{ capacityText(runner.memoryAvailableBytes) }} / {{ capacityText(runner.memoryTotalBytes) }}</small>
              </div>
              <div class="resource-row">
                <span><i class="ri-hard-drive-3-line"></i>磁盘 {{ runner.diskRoot || '' }}</span>
                <b>{{ resourcePercent(runner.diskTotalBytes, runner.diskFreeBytes) }}%</b>
                <em><i :style="{ width: `${resourcePercent(runner.diskTotalBytes, runner.diskFreeBytes)}%` }"></i></em>
                <small>可用 {{ capacityText(runner.diskFreeBytes) }} / {{ capacityText(runner.diskTotalBytes) }}</small>
              </div>
            </section>
            <div v-else class="runner-resource-empty"><i class="ri-pulse-line"></i>等待新版 Runner 上报真实机器资源</div>
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
              <div><dt>失败重试</dt><dd>{{ workflow.config.failureRetryCount }} 次</dd></div>
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
              <label class="form-field">
                <span>失败自动重试次数</span>
                <input v-model.number="form.failureRetryCount" type="number" min="0" max="5" />
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

    <div v-if="taskAutoRunSettingsOpen" class="run-detail-mask" @click.self="taskAutoRunSettingsOpen = false">
      <section class="run-detail auto-run-dialog" role="dialog" aria-modal="true" aria-labelledby="auto-run-policy-title">
        <header>
          <span class="recent-icon auto-run-dialog-icon"><i class="ri-timer-flash-line"></i></span>
          <div>
            <h2 id="auto-run-policy-title">空闲时自动推进最早待办</h2>
            <p>没有 AI Run 活动时，按真实工作流和所选任务状态批量派发录入最早的待办。</p>
          </div>
          <button class="detail-close" aria-label="关闭自动推进设置" @click="taskAutoRunSettingsOpen = false"><i class="ri-close-line"></i></button>
        </header>
        <div class="auto-run-summary" :class="{ enabled: taskAutoRunEnabled }">
          <span><i :class="taskAutoRunEnabled ? 'ri-checkbox-circle-line' : 'ri-pause-circle-line'"></i></span>
          <div>
            <b>{{ taskAutoRunEnabled ? '自动推进已开启' : '自动推进已关闭' }}</b>
            <p v-if="taskAutoRunPolicy">{{ taskAutoRunPolicy.message }}</p>
            <p v-else-if="taskAutoRunError">{{ taskAutoRunError }}</p>
            <p v-else>正在读取自动派发策略…</p>
            <small v-if="taskAutoRunPolicy?.nextEligibleAt && taskAutoRunPolicy.status === 'cooling_down'">最早可检查时间：{{ timeText(taskAutoRunPolicy.nextEligibleAt) }}</small>
          </div>
        </div>
        <div class="auto-run-policy">
          <div class="auto-run-controls">
            <button class="auto-run-switch" :class="{ on: taskAutoRunEnabled }" type="button" role="switch" :aria-checked="taskAutoRunEnabled" :disabled="taskAutoRunSaving" @click="taskAutoRunEnabled = !taskAutoRunEnabled; markTaskAutoRunDirty()">
              <i></i><span>{{ taskAutoRunEnabled ? '已开启' : '已关闭' }}</span>
            </button>
            <label>
              <span>连续空闲</span>
              <input v-model.number="taskAutoRunHours" type="number" min="1" max="168" step="1" :disabled="taskAutoRunSaving" aria-label="自动派发空闲小时数" @input="markTaskAutoRunDirty" />
              <span>小时</span>
            </label>
            <label :title="`所选项目同一时间最多运行 ${taskAutoRunProjectMax} 条任务`">
              <span>单次推进</span>
              <input v-model.number="taskAutoRunBatchSize" type="number" min="1" :max="taskAutoRunProjectMax" step="1" :disabled="taskAutoRunSaving" aria-label="自动派发单次推进数" @input="markTaskAutoRunDirty" />
              <span>条</span>
            </label>
          </div>
          <small class="auto-run-limit-hint">每条任务会创建独立 AI Run；当前项目最多并发 {{ taskAutoRunProjectMax }} 条，Runner 暂无空闲槽位时会安全排队。</small>
          <div class="auto-run-options">
            <label class="auto-run-select">
              <span>任务项目</span>
              <select v-model="taskAutoRunProjectId" :disabled="taskAutoRunSaving || workflowsLoading" aria-label="自动派发任务项目" @change="changeTaskAutoRunProject">
                <option value="" disabled>{{ workflowsLoading ? '正在读取项目…' : runnableAutoRunWorkflows.length ? '请选择项目' : '没有可用的 AI 工作流' }}</option>
                <option v-for="workflow in runnableAutoRunWorkflows" :key="workflow.projectId" :value="workflow.projectId">{{ workflow.projectName }}</option>
              </select>
            </label>
            <fieldset class="auto-run-statuses" :disabled="taskAutoRunSaving">
              <legend>任务状态</legend>
              <label v-for="status in AUTO_RUN_TASK_STATUSES" :key="status" :class="{ on: taskAutoRunStatuses.includes(status) }">
                <input v-model="taskAutoRunStatuses" type="checkbox" :value="status" @change="markTaskAutoRunDirty" />
                <i class="ri-check-line"></i>{{ status }}
              </label>
            </fieldset>
            <label class="auto-run-select">
              <span>触发通知群</span>
              <select v-model="taskAutoRunWebhookId" :disabled="taskAutoRunSaving || taskAutoRunWebhooksLoading" aria-label="自动派发触发通知群" @change="markTaskAutoRunDirty">
                <option value="">系统默认通知群</option>
                <option v-for="endpoint in enabledTaskAutoRunWebhooks" :key="endpoint.id" :value="endpoint.id">{{ endpoint.name }}{{ endpoint.systemDefault ? '（系统默认）' : '' }}</option>
              </select>
            </label>
          </div>
          <div v-if="taskAutoRunProjectError || taskAutoRunWebhookError" class="auto-run-options-error" role="alert">
            <span>{{ [taskAutoRunProjectError, taskAutoRunWebhookError].filter(Boolean).join('；') }}</span>
            <button type="button" @click="refreshWorkflows(); refreshTaskAutoRunWebhooks()"><i class="ri-refresh-line"></i>重试读取</button>
          </div>
          <div v-if="taskAutoRunDirty || taskAutoRunError" class="auto-run-feedback" :class="{ error: taskAutoRunError }" role="status">{{ taskAutoRunError || '设置尚未保存，后台轮询不会覆盖这次修改。' }}</div>
        </div>
        <footer>
          <span class="retry-note"><i class="ri-shield-check-line"></i>保存后才会改变无人值守派发策略</span>
          <button class="secondary" @click="taskAutoRunSettingsOpen = false">关闭</button>
          <button class="primary auto-run-save" :disabled="taskAutoRunSaving || (!taskAutoRunDirty && !taskAutoRunError)" @click="saveTaskAutoRunPolicy">
            <i :class="taskAutoRunSaving ? 'ri-loader-4-line spin' : taskAutoRunError ? 'ri-refresh-line' : 'ri-save-3-line'"></i>
            {{ taskAutoRunSaving ? '保存中' : taskAutoRunError ? '重试保存' : taskAutoRunDirty ? '保存设置' : '已保存' }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="quickTaskListOpen" class="run-detail-mask" @click.self="quickTaskListOpen = false">
      <section class="run-detail quick-task-dialog" role="dialog" aria-modal="true" aria-labelledby="quick-task-dialog-title">
        <header>
          <span class="recent-icon quick-task-dialog-icon"><i class="ri-list-check-3"></i></span>
          <div>
            <h2 id="quick-task-dialog-title">可 AI Run 的任务</h2>
            <p>来自已启用的真实工作流；按优先级和录入时间排序，一次点击即可按项目默认配置运行。</p>
          </div>
          <button class="detail-close" aria-label="关闭可 AI Run 的任务清单" @click="quickTaskListOpen = false"><i class="ri-close-line"></i></button>
        </header>
        <div v-if="data.taskLoadState === 'loading' || workflowsLoading" class="quick-task-dialog-state">
          <i class="ri-loader-4-line spin"></i>正在读取可执行任务…
        </div>
        <div v-else-if="data.taskLoadState === 'error' || taskAutoRunProjectError" class="quick-task-dialog-state error" role="alert">
          <i class="ri-error-warning-line"></i>
          <span>{{ data.taskLoadError || taskAutoRunProjectError }}</span>
          <button class="secondary" @click="data.hydrate(); refreshWorkflows()">重试</button>
        </div>
        <div v-else-if="runnableTasks.length" class="quick-task-list">
          <article v-for="task in runnableTasks" :key="task.id" class="quick-task-list-item">
            <div class="quick-task-list-copy">
              <div class="quick-task-meta">
                <span :class="`priority ${task.pr.toLowerCase()}`">{{ task.pr }}</span>
                <span>{{ task.status }}</span>
                <span><i class="ri-stack-line"></i>{{ taskProjectName(task) }}</span>
              </div>
              <button class="quick-task-title" :title="`打开任务：${task.title}`" @click="openTask(task)">{{ task.title }}</button>
              <p>{{ taskExcerpt(task) }}</p>
            </div>
            <button class="quick-task-start" :disabled="startingQuickTaskId !== null" @click="startQuickTask(task)">
              <i :class="startingQuickTaskId === task.id ? 'ri-loader-4-line spin' : 'ri-play-circle-line'"></i>
              {{ startingQuickTaskId === task.id ? '正在派发…' : '开始运行' }}
            </button>
          </article>
        </div>
        <div v-else class="quick-task-dialog-state">
          <i class="ri-checkbox-circle-line"></i>
          <span>{{ runnableAutoRunWorkflows.length ? '当前没有可执行任务。' : '还没有可用的 AI 工作流，请先完成接入。' }}</span>
        </div>
        <footer>
          <span class="retry-note"><i class="ri-shield-check-line"></i>重复运行、执行电脑能力和项目配置仍由服务端校验</span>
          <button class="secondary" @click="quickTaskListOpen = false">关闭</button>
        </footer>
      </section>
    </div>

    <div v-if="runHistoryOpen" class="run-detail-mask" @click.self="runHistoryOpen = false">
      <section class="run-detail run-history-detail" role="dialog" aria-modal="true" aria-labelledby="run-history-title">
        <header>
          <span class="recent-icon history-icon"><i class="ri-history-line"></i></span>
          <div>
            <h2 id="run-history-title">全部运行历史</h2>
            <p>共 {{ runHistoryTotal }} 条已结束运行，按最新时间排列。</p>
          </div>
          <button class="detail-close" aria-label="关闭运行历史" @click="runHistoryOpen = false"><i class="ri-close-line"></i></button>
        </header>
        <div class="activity-filters history-filters" role="tablist" aria-label="历史运行类型">
          <button v-for="option in ACTIVITY_FILTERS" :key="option.id" :class="{ active: activityFilter === option.id }" @click="selectActivityFilter(option.id); openRunHistory(1)">{{ option.label }}</button>
        </div>
        <div v-if="runHistoryLoading" class="history-state"><i class="ri-loader-4-line spin"></i>正在读取运行历史…</div>
        <div v-else-if="runHistoryError" class="history-state error">
          <i class="ri-error-warning-line"></i><span>{{ runHistoryError }}</span>
          <button class="secondary" @click="openRunHistory(runHistoryPage)">重试</button>
        </div>
        <div v-else-if="!runHistory.length" class="history-state"><i class="ri-inbox-2-line"></i>还没有已结束的运行。</div>
        <div v-else class="recent-grid history-grid">
          <button v-for="activity in runHistory" :key="`${activity.type}-${activity.id}`" class="recent-card" @click="openActivity(activity)">
            <span class="recent-card-top">
              <span class="recent-icon" :style="{ color: activityTypeMeta(activity).color, background: activityTypeMeta(activity).bg }"><i :class="activityTypeMeta(activity).icon"></i></span>
              <span class="recent-type" :style="{ color: activityTypeMeta(activity).color, background: activityTypeMeta(activity).bg }">{{ activityTypeMeta(activity).label }}</span>
              <span class="recent-status" :style="{ color: activityMeta(activity).color, background: activityMeta(activity).bg }"><i :class="activityMeta(activity).icon"></i>{{ activityMeta(activity).label }}</span>
              <span class="run-time">{{ timeText(activity.finishedAt || activity.createdAt) }}</span>
            </span>
            <span class="recent-main">
              <b :title="activity.title">{{ activity.title }}</b>
              <em><i :class="activity.type === 'CHAT_REVIEW' ? 'ri-chat-3-line' : 'ri-stack-line'"></i>{{ activity.subtitle }}</em>
              <em><i class="ri-sparkling-2-line"></i>{{ activity.capabilityLabel }}</em>
              <small v-if="activity.status === 'FAILED'">{{ activity.resultPreview }}</small>
            </span>
            <span class="recent-open">{{ activity.type === 'CHAT_REVIEW' ? '打开复盘' : '查看详情' }}<i class="ri-arrow-right-line"></i></span>
          </button>
        </div>
        <footer v-if="!runHistoryLoading && !runHistoryError && runHistoryTotal > RUN_HISTORY_PAGE_SIZE" class="history-pagination">
          <button class="secondary" :disabled="runHistoryPage <= 1" @click="openRunHistory(runHistoryPage - 1)"><i class="ri-arrow-left-line"></i>上一页</button>
          <span>第 {{ runHistoryPage }} / {{ runHistoryPages }} 页</span>
          <button class="secondary" :disabled="runHistoryPage >= runHistoryPages" @click="openRunHistory(runHistoryPage + 1)">下一页<i class="ri-arrow-right-line"></i></button>
        </footer>
      </section>
    </div>

    <div v-if="selectedRun" class="run-detail-mask" @click.self="selectedRun = null">
      <section class="run-detail run-activity-detail" role="dialog" aria-modal="true" aria-labelledby="run-detail-title">
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
        <div v-if="selectedRun.previewImages?.length" class="run-detail-images">
          <img
            v-for="(image, index) in selectedRun.previewImages"
            :key="`${image}-${index}`"
            :src="image"
            :alt="`${selectedRun.taskTitle} · 图片 ${index + 1}`"
          />
        </div>
        <AiRunDeliverySteps v-if="selectedRun.deliveryStage" :stage="selectedRun.deliveryStage" />
        <dl class="run-detail-meta">
          <div><dt>AI 工具</dt><dd>{{ engineLabel(selectedRun.engine) }}</dd></div>
          <div v-if="selectedRun.requestedModel"><dt>本次模型</dt><dd>{{ shortModel(selectedRun.requestedModel) }}</dd></div>
          <div v-if="selectedRun.reasoningEffort"><dt>推理强度</dt><dd>{{ reasoningEffortLabel(selectedRun.reasoningEffort) }}</dd></div>
          <div v-if="selectedRun.serviceTier"><dt>运行速度</dt><dd>{{ selectedRun.serviceTier === 'default' ? '标准（Fast 未开启）' : selectedRun.serviceTier }}</dd></div>
          <div><dt>执行模式</dt><dd>{{ selectedRun.planMode ? 'Plan（推荐项自动选择）' : '直接执行' }}</dd></div>
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
        <AiRunMemoryReferences :reads="selectedRun.memoryReads" :writes="selectedRun.memoryWrites" />
        <AiRunSkillCapabilities :skills="selectedRun.skills" :audits="selectedRun.skillAudits" />
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
        <footer class="run-detail-footer">
          <div v-if="selectedRun.retryOfRunId || selectedRun.branchName" class="run-detail-context">
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
              <i class="ri-git-branch-line"></i><span>{{ selectedRun.branchName }}</span>
            </a>
          </div>
          <div class="run-detail-actions">
            <button class="secondary" @click="selectedRun = null">关闭</button>
            <button
              v-if="['queued', 'claimed', 'running'].includes(selectedRun.status)"
              class="secondary stop-run"
              :disabled="pausingRunId === selectedRun.id"
              @click="stopRun(selectedRun)"
            >
              <i :class="pausingRunId === selectedRun.id ? 'ri-loader-4-line spin' : 'ri-stop-circle-line'"></i>
              {{ pausingRunId === selectedRun.id ? '停止中…' : '停止任务' }}
            </button>
            <button
              v-if="selectedRun.status === 'failed'"
              class="secondary retry"
              :disabled="rerunning"
              @click="rerun(selectedRun)"
            >
              <i :class="rerunning ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>基于报错重跑
            </button>
            <button class="primary" @click="openRun(selectedRun)">打开对应任务</button>
          </div>
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
  display: flex;
  flex-direction: column;
}
.home > * { order: 2; }
.home-primary { order: 1; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
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
.hero-signals { margin-top: 13px; display: flex; align-items: center; gap: 7px; }
.hero-signals > span {
  position: relative;
  min-width: 34px;
  height: 32px;
  padding: 0 9px;
  border: 1px solid #ffffff38;
  border-radius: 9px;
  background: #ffffff17;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
}
.hero-signals > span.dim { opacity: .48; }
.hero-signals > span > i:first-child { font-size: 15px; }
.hero-signals b { font-size: 11px; }
.signal-dot { width: 5px; height: 5px; border-radius: 50%; background: #9bf0c2; box-shadow: 0 0 7px #9bf0c2; }
.hero-signals .dim .signal-dot { background: #ffffff72; box-shadow: none; }
.hero-links { margin-top: 13px; display: flex; flex-wrap: wrap; gap: 7px; }
.hero-link {
  padding: 7px 13px;
  border: 1px solid #ffffff55;
  border-radius: 999px;
  background: #ffffff1f;
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: background .16s, transform .16s;
}
.hero-link:hover { background: #ffffff33; transform: translateY(-1px); }
.hero-link i { font-size: 14px; }
.hero-metrics { width: 194px; flex: 0 0 auto; display: grid; gap: 9px; }
.quota-card {
  min-height: 92px;
  padding: 12px 14px;
  border: 1px solid #ffffff2b;
  border-radius: 14px;
  background: #ffffff12;
  display: grid;
  align-content: center;
}
.quota-card > span { color: #ffffffb5; font-size: 10.5px; }
.quota-card > span i { margin-right: 5px; }
.quota-card strong { margin-top: 3px; color: #fff; font-size: 35px; line-height: 1; }
.quota-card strong em { font-size: 17px; font-style: normal; }
.quota-card small { margin-top: 6px; color: #ffffff9c; font-size: 9.5px; line-height: 1.35; }
.quota-card.good strong { color: #c7ffdf; }
.quota-card.warn strong { color: #ffe9a9; }
.quota-card.danger strong { color: #ffd0d0; }
.capacity {
  position: relative;
  padding: 12px;
  overflow: hidden;
  border: 1px solid #ffffff2b;
  border-radius: 14px;
  background: #ffffff12;
  color: #ffffffa8;
  font-size: 11px;
  display: flex;
  align-items: center;
  gap: 12px;
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
.goal-launcher {
  margin-top: 0;
  padding: 19px 20px;
  border: 1px solid var(--wao-primary-line);
  border-radius: 15px;
  background: linear-gradient(135deg, var(--wao-bg-card), var(--wao-primary-tint));
  box-shadow: var(--wao-shadow-card-hover-2);
  display: grid;
  grid-template-columns: minmax(230px, .8fr) minmax(360px, 1.2fr);
  gap: 22px;
  align-items: center;
}
.home-primary .goal-launcher { grid-template-columns: minmax(0, 1fr); }
.home-primary .goal-actions { flex-wrap: wrap; }
.goal-heading { min-width: 0; display: flex; align-items: flex-start; gap: 12px; }
.goal-icon { width: 38px; height: 38px; flex: 0 0 auto; border-radius: 11px; background: var(--wao-primary-soft); color: var(--wao-primary-text); display: grid; place-items: center; font-size: 19px; }
.eyebrow.dark { color: var(--wao-text-3); opacity: 1; }
.goal-heading h2 { margin: 5px 0 5px; color: var(--wao-text); font-size: 17px; }
.goal-heading p { margin: 0; color: var(--wao-text-weak); font-size: 11.5px; line-height: 1.6; }
.goal-compose { overflow: hidden; border: 1px solid var(--wao-line-control); border-radius: 12px; background: var(--wao-bg-card); transition: border-color .16s, box-shadow .16s; }
.goal-compose:focus-within { border-color: var(--wao-primary); box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.goal-compose :deep(.md-body) { border: 0 !important; border-radius: 0; }
.goal-compose :deep(.md-body .tiptap) { min-height: 78px; padding: 12px 46px 7px 13px; color: var(--wao-text); font-size: 12.5px; line-height: 1.6; }
.goal-compose :deep(.md-body p.is-editor-empty:first-child::before) { color: var(--wao-text-faint); }
.goal-compose :deep(.image-upload) { right: 10px; bottom: 8px; }
.goal-actions { min-height: 44px; padding: 7px 8px 8px 12px; border-top: 1px solid var(--wao-divider); display: flex; align-items: center; gap: 10px; }
.goal-actions label { min-width: 0; display: flex; align-items: center; gap: 7px; color: var(--wao-text-weak); font-size: 10.5px; }
.goal-actions select { max-width: 230px; height: 29px; padding: 0 26px 0 8px; border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); font-size: 11px; }
.goal-actions .goal-runner-select { max-width: 180px; }
.goal-actions .goal-reasoning-select { max-width: 190px; }
.goal-reasoning-retry { height: 29px; padding: 0 9px; border: 1px solid var(--wao-danger); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-danger-strong); cursor: pointer; font-size: 10.5px; white-space: nowrap; }
.goal-reasoning-retry:disabled { border-color: var(--wao-line); color: var(--wao-text-weak); cursor: default; }
.goal-plan-mode { height: 29px; padding: 0 8px; display: inline-flex; align-items: center; gap: 6px; border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; font-size: 10.5px; white-space: nowrap; }
.goal-plan-mode.on { border-color: var(--wao-primary); background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.goal-plan-mode:disabled { opacity: .55; cursor: default; }
.goal-switch { width: 24px; height: 14px; flex: 0 0 auto; padding: 2px; border-radius: 8px; background: var(--wao-line); }
.goal-switch i { display: block; width: 10px; height: 10px; border-radius: 50%; background: #fff; transition: transform .15s ease; }
.goal-plan-mode.on .goal-switch { background: var(--wao-primary); }
.goal-plan-mode.on .goal-switch i { transform: translateX(10px); }
.goal-shortcut { margin-left: auto; color: var(--wao-text-faint); font-size: 9.5px; white-space: nowrap; }
.goal-submit { min-width: 112px; height: 32px; padding: 0 12px; border: 1px solid var(--wao-primary); border-radius: 8px; background: var(--wao-primary); color: #fff; cursor: pointer; font-size: 11.5px; font-weight: 650; display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
.goal-submit.setup { min-width: 140px; }
.goal-submit:disabled { opacity: .55; cursor: not-allowed; }
.growth-coach {
  grid-column: 1 / -1;
  overflow: hidden;
  padding: 18px 20px;
  border: 1px solid color-mix(in srgb, #7558d8 35%, var(--wao-line));
  border-radius: 15px;
  background:
    radial-gradient(circle at 92% 0, color-mix(in srgb, #7558d8 12%, transparent), transparent 34%),
    var(--wao-bg-card);
  box-shadow: var(--wao-shadow-card);
}
.growth-coach-head { display: flex; align-items: flex-start; gap: 12px; }
.growth-coach-head > div { min-width: 0; flex: 1; }
.growth-coach-icon { width: 38px; height: 38px; flex: 0 0 auto; border-radius: 11px; background: color-mix(in srgb, #7558d8 13%, var(--wao-bg-card)); color: #7558d8; display: grid; place-items: center; font-size: 19px; }
.growth-coach-head h2 { margin: 5px 0 4px; color: var(--wao-text); font-size: 17px; }
.growth-coach-head p { margin: 0; color: var(--wao-text-weak); font-size: 11.5px; line-height: 1.6; }
.growth-coach-refresh, .growth-coach-discuss, .growth-coach-state > button, .growth-coach-error button {
  min-height: 32px;
  padding: 0 11px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
}
.growth-coach-refresh:disabled, .growth-coach-state > button:disabled { opacity: .55; cursor: default; }
.growth-coach-ready { margin: 15px 0 0 50px; }
.growth-coach-content { color: var(--wao-text-2); font-size: 12px; line-height: 1.72; }
.growth-coach-content :deep(h2), .growth-coach-content :deep(h3) { margin: 13px 0 4px; color: var(--wao-text); font-size: 13px; }
.growth-coach-content :deep(h2:first-child), .growth-coach-content :deep(h3:first-child) { margin-top: 0; }
.growth-coach-content :deep(p) { margin: 4px 0; }
.growth-coach-content :deep(ul) { margin: 5px 0; padding-left: 19px; }
.growth-coach-content :deep(blockquote) { margin: 7px 0; padding: 8px 11px; border-left: 3px solid #7558d8; border-radius: 0 8px 8px 0; background: color-mix(in srgb, #7558d8 7%, var(--wao-bg-card)); }
.growth-coach-ready footer { margin-top: 13px; padding-top: 11px; border-top: 1px solid var(--wao-divider); display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.growth-coach-ready footer small { color: var(--wao-text-faint); font-size: 10px; }
.growth-coach-ready footer small i { margin-right: 4px; color: var(--wao-success-strong); }
.growth-coach-discuss { border-color: color-mix(in srgb, #7558d8 45%, var(--wao-line)); color: #6548c4; }
.growth-coach-state { min-height: 74px; margin: 14px 0 0 50px; padding: 13px 14px; border: 1px dashed var(--wao-line); border-radius: 10px; color: var(--wao-text-weak); display: flex; align-items: center; gap: 10px; }
.growth-coach-state > i { color: #7558d8; font-size: 19px; }
.growth-coach-state > div { min-width: 0; flex: 1; }
.growth-coach-state b, .growth-coach-state span { display: block; }
.growth-coach-state b { color: var(--wao-text-2); font-size: 12px; }
.growth-coach-state span { margin-top: 3px; font-size: 10.5px; line-height: 1.5; }
.growth-coach-state.error > i { color: var(--wao-danger-strong); }
.growth-coach-state > button.primary { border-color: var(--wao-primary); background: var(--wao-primary); color: #fff; }
.growth-coach-error { margin-top: 10px; padding: 9px 10px; border: 1px solid var(--wao-danger-line); border-radius: 8px; background: var(--wao-danger-bg); color: var(--wao-danger-strong); font-size: 10.5px; display: flex; align-items: center; gap: 7px; }
.growth-coach-error span { min-width: 0; flex: 1; }
.growth-coach-error button { min-height: 27px; color: inherit; }
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
.console-tabs button.on { background: var(--wao-primary-soft); color: var(--wao-primary-text); font-weight: 650; }
.console-tabs em { padding: 1px 6px; border-radius: 20px; background: var(--wao-primary-soft-2); color: var(--wao-primary-text); font-size: 10px; font-style: normal; }
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
.auto-run-policy { margin-top: 16px; display: grid; gap: 14px; }
.auto-run-controls { display: flex; align-items: center; justify-content: flex-start; gap: 8px; }
.auto-run-switch, .auto-run-save { height: 32px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; font-size: 11px; display: inline-flex; align-items: center; gap: 6px; }
.auto-run-switch { padding: 0 9px; }
.auto-run-switch > i { position: relative; width: 25px; height: 14px; border-radius: 999px; background: #c7cad5; transition: .16s; }
.auto-run-switch > i::after { content: ''; position: absolute; top: 2px; left: 2px; width: 10px; height: 10px; border-radius: 50%; background: #fff; transition: .16s; }
.auto-run-switch.on { border-color: var(--wao-primary-line); color: var(--wao-primary-text); background: var(--wao-primary-tint); }
.auto-run-switch.on > i { background: var(--wao-primary); }
.auto-run-switch.on > i::after { transform: translateX(11px); }
.auto-run-controls label { height: 32px; padding: 0 7px 0 9px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-weak); font-size: 10.5px; display: flex; align-items: center; gap: 5px; }
.auto-run-controls input { width: 46px; height: 24px; padding: 0 4px; border: 1px solid var(--wao-line); border-radius: 6px; background: var(--wao-bg-sub); color: var(--wao-text); text-align: center; }
.auto-run-limit-hint { color: var(--wao-text-weak); font-size: 10.5px; line-height: 1.55; }
.auto-run-options { padding-top: 14px; border-top: 1px solid var(--wao-divider); display: grid; grid-template-columns: minmax(180px, .9fr) minmax(280px, 1.2fr) minmax(200px, 1fr); align-items: end; gap: 10px; }
.auto-run-select { min-width: 0; display: grid; gap: 5px; color: var(--wao-text-weak); font-size: 10.5px; }
.auto-run-select select { width: 100%; height: 32px; padding: 0 28px 0 9px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); font: inherit; }
.auto-run-select select:disabled { opacity: .58; }
.auto-run-statuses { min-width: 0; margin: 0; padding: 0; border: 0; display: flex; align-items: center; gap: 6px; }
.auto-run-statuses legend { margin-bottom: 5px; color: var(--wao-text-weak); font-size: 10.5px; }
.auto-run-statuses label { height: 32px; padding: 0 9px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-weak); cursor: pointer; display: inline-flex; align-items: center; gap: 4px; font-size: 10.5px; }
.auto-run-statuses label.on { border-color: var(--wao-primary-line); background: var(--wao-primary-tint); color: var(--wao-primary-text); }
.auto-run-statuses input { position: absolute; width: 1px; height: 1px; overflow: hidden; opacity: 0; pointer-events: none; }
.auto-run-statuses i { display: none; }
.auto-run-statuses label.on i { display: inline; }
.auto-run-options-error { grid-column: 1 / -1; color: var(--wao-danger-strong); font-size: 10.5px; display: flex; align-items: center; gap: 8px; }
.auto-run-options-error button { padding: 3px 7px; border: 1px solid var(--wao-danger-line); border-radius: 6px; background: var(--wao-danger-bg); color: inherit; cursor: pointer; font: inherit; }
.auto-run-save { padding: 0 10px; border-color: var(--wao-primary); background: var(--wao-primary); color: #fff; }
.auto-run-save:disabled, .auto-run-switch:disabled { opacity: .55; cursor: not-allowed; }
.auto-run-feedback { color: var(--wao-warning-strong); font-size: 10.5px; }
.auto-run-feedback.error { color: var(--wao-danger-strong); }
.section-head { display: flex; align-items: flex-end; gap: 8px; margin-bottom: 12px; }
.section-head > div { flex: 1; }
.section-head h2 { margin: 0; font-size: 17px; }
.section-head p { margin: 4px 0 0; color: var(--wao-text-weak); font-size: 11.5px; }
.run-section-actions { flex: 0 0 auto !important; display: flex; align-items: center; gap: 7px; }
.auto-run-settings-trigger { min-height: 32px; padding: 0 9px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; font-size: 11.5px; display: inline-flex; align-items: center; gap: 5px; }
.auto-run-settings-trigger:hover { border-color: var(--wao-primary-line); background: var(--wao-primary-tint); }
.auto-run-settings-trigger.on { border-color: var(--wao-success-line); color: var(--wao-success-strong); background: var(--wao-success-bg); }
.auto-run-settings-trigger em { padding-left: 6px; border-left: 1px solid currentColor; opacity: .72; font-size: 9.5px; font-style: normal; }
.quick-task-list-trigger {
  min-height: 32px; padding: 0 10px; border: 1px solid var(--wao-primary-line); border-radius: 8px;
  background: var(--wao-primary-tint); color: var(--wao-primary-text); cursor: pointer; font-size: 11.5px;
  display: inline-flex; align-items: center; gap: 6px;
}
.quick-task-list-trigger:hover { background: var(--wao-primary-soft); }
.quick-task-list-trigger:disabled { opacity: .58; cursor: wait; }
.quick-task-list-trigger em { min-width: 18px; padding: 1px 5px; border-radius: 999px; background: var(--wao-primary); color: #fff; font-size: 9.5px; font-style: normal; }
.history-link { padding: 6px 9px; border: 0; border-radius: 8px; background: transparent; color: var(--wao-primary); cursor: pointer; font-size: 11.5px; display: inline-flex; align-items: center; gap: 3px; }
.history-link:hover { background: var(--wao-primary-tint); }
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
  display: flex;
  flex-direction: column;
  transition: .16s;
}
.run-card:hover { transform: translateY(-2px); border-color: var(--wao-primary-line); box-shadow: 0 8px 22px #27366c14; }
.run-top, .run-foot { display: flex; align-items: center; gap: 8px; }
.status { padding: 4px 7px; border-radius: 6px; font-size: 10.5px; font-weight: 650; }
.run-time { margin-left: auto; color: var(--wao-text-weak); font-size: 10.5px; }
.run-card h3 { min-height: 39px; max-height: 39px; margin: 9px 0 4px; overflow: hidden; font-size: 14px; line-height: 1.4; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.run-media { height: 112px; margin-top: 9px; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-sub); }
.run-image-strip { height: 100%; display: grid; grid-template-columns: repeat(auto-fit, minmax(56px, 1fr)); gap: 5px; }
.run-image-strip.single { grid-template-columns: minmax(0, 1fr); }
.run-image-strip img { width: 100%; height: 100%; min-width: 0; object-fit: cover; }
.run-image-placeholder { position: relative; height: 100%; overflow: hidden; color: var(--wao-primary-text); display: grid; place-items: center; }
.run-image-placeholder::before, .run-image-placeholder::after { content: ''; position: absolute; border: 1px solid var(--wao-primary-line); border-radius: 50%; opacity: .55; }
.run-image-placeholder::before { width: 132px; height: 132px; left: -54px; top: -72px; }
.run-image-placeholder::after { width: 92px; height: 92px; right: -27px; bottom: -57px; }
.run-image-placeholder > span { width: 40px; height: 40px; border: 1px solid var(--wao-primary-line); border-radius: 12px; background: var(--wao-bg-card); box-shadow: 0 8px 24px var(--wao-primary-soft); display: grid; place-items: center; font-size: 18px; }
.run-image-placeholder > i { position: absolute; right: 13px; top: 12px; color: var(--wao-text-faint); font-size: 15px; }
.run-project { color: var(--wao-text-weak); font-size: 11.5px; }
.run-project i { margin-right: 5px; }
.live-progress { margin-top: 8px; padding: 8px; border-radius: 8px; background: var(--wao-bg-sub); display: flex; gap: 9px; align-items: flex-start; }
.live-progress > div { min-width: 0; }
.live-progress b, .live-progress small { display: block; }
.live-progress b { overflow: hidden; color: var(--wao-text-2); font-size: 11px; font-weight: 550; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.live-progress small { margin-top: 3px; color: var(--wao-text-weak); font-size: 9.5px; }
.live-pulse { width: 8px; height: 8px; margin-top: 4px; flex: 0 0 auto; border-radius: 50%; background: #36a86f; box-shadow: 0 0 0 4px #dff5e9; animation: livePulse 1.5s infinite; }
.run-foot { margin-top: auto; padding-top: 8px; border-top: 1px solid #f0f1f5; color: var(--wao-text-3); font-size: 11px; }
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
.quick-run-empty { min-height: 150px; padding: 14px; border: 1px dashed var(--wao-line); border-radius: 13px; background: var(--wao-bg-sub); }
.quick-run-empty-title { display: flex; align-items: center; gap: 10px; }
.quick-run-empty-title > span { width: 34px; height: 34px; flex: 0 0 auto; border-radius: 10px; background: var(--wao-primary-soft); color: var(--wao-primary-text); display: grid; place-items: center; font-size: 18px; }
.quick-run-empty-title b { color: var(--wao-text-2); font-size: 13.5px; }
.quick-run-empty-title p { margin: 3px 0 0; color: var(--wao-text-weak); font-size: 11px; }
.quick-task-grid { margin-top: 12px; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; }
.quick-task-card, .quick-task-list-item { border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); }
.quick-task-card { min-width: 0; padding: 10px; }
.quick-task-meta { min-width: 0; display: flex; align-items: center; gap: 6px; color: var(--wao-text-weak); font-size: 9.5px; }
.quick-task-meta > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.quick-task-meta > span:last-child { margin-left: auto; }
.quick-task-meta i { margin-right: 3px; }
.quick-task-meta .priority { flex: 0 0 auto; padding: 2px 5px; border-radius: 5px; background: var(--wao-bg-hover); color: var(--wao-text-2); font-weight: 700; }
.quick-task-meta .priority.p0 { background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
.quick-task-meta .priority.p1 { background: var(--wao-warning-bg); color: var(--wao-warning-strong); }
.quick-task-title { width: 100%; margin-top: 7px; padding: 0; overflow: hidden; border: 0; background: transparent; color: var(--wao-text-1); cursor: pointer; font: inherit; font-size: 12.5px; font-weight: 650; line-height: 1.45; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.quick-task-title:hover { color: var(--wao-primary-text); }
.quick-task-card > p, .quick-task-list-copy > p { margin: 4px 0 0; overflow: hidden; color: var(--wao-text-weak); font-size: 10.5px; line-height: 1.45; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.quick-task-card footer { margin-top: 9px; padding-top: 8px; border-top: 1px solid var(--wao-divider); display: flex; align-items: center; gap: 8px; }
.quick-task-card footer > span { min-width: 0; overflow: hidden; color: var(--wao-text-weak); font-size: 9.5px; text-overflow: ellipsis; white-space: nowrap; }
.quick-task-card footer > span i { margin-right: 3px; }
.quick-task-start { min-height: 29px; margin-left: auto; padding: 0 9px; flex: 0 0 auto; border: 1px solid var(--wao-primary); border-radius: 7px; background: var(--wao-primary); color: #fff; cursor: pointer; font-size: 10.5px; display: inline-flex; align-items: center; gap: 4px; }
.quick-task-start:hover { filter: brightness(.96); }
.quick-task-start:disabled { opacity: .58; cursor: not-allowed; }
.quick-task-state { min-height: 90px; color: var(--wao-text-weak); font-size: 11.5px; display: flex; align-items: center; justify-content: center; gap: 7px; }
.quick-task-state.error { color: var(--wao-danger-strong); }
.quick-task-state button { padding: 5px 8px; border: 1px solid currentColor; border-radius: 7px; background: transparent; color: inherit; cursor: pointer; }
.runner-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.runner-card { padding: 16px; border: 1px solid var(--wao-line); border-radius: 13px; background: var(--wao-bg-card); }
.runner-card.offline { background: var(--wao-bg-sub); opacity: .8; }
.runner-card header { display: flex; align-items: center; gap: 8px; }
.runner-card header b { font-size: 13.5px; }
.runner-state { margin-left: auto; color: var(--wao-text-weak); font-size: 10.5px; }
.runner-platform { margin-top: 8px; color: var(--wao-text-weak); font-size: 11px; }
.runner-platform i { margin-right: 5px; }
.engine-tags { margin-top: 11px; display: flex; flex-wrap: wrap; gap: 6px; }
.runner-resources { margin-top: 13px; padding: 10px; border: 1px solid var(--wao-divider); border-radius: 10px; background: var(--wao-bg-sub); }
.runner-resources > header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.runner-resources > header b { font-size: 11px; }
.runner-resources > header small { color: var(--wao-text-faint); font-size: 8px; }
.resource-row { display: grid; grid-template-columns: minmax(78px, auto) auto; gap: 3px 8px; align-items: center; margin-top: 7px; }
.resource-row > span { color: var(--wao-text-3); font-size: 10px; }
.resource-row > span i { margin-right: 4px; color: var(--wao-primary-text); }
.resource-row > b { justify-self: end; font-size: 10px; }
.resource-row > em { grid-column: 1 / -1; height: 4px; overflow: hidden; border-radius: 9px; background: var(--wao-bg-tab); }
.resource-row > em i { display: block; height: 100%; border-radius: inherit; background: var(--wao-primary); }
.resource-row > small { grid-column: 1 / -1; color: var(--wao-text-faint); font-size: 8.5px; }
.runner-resource-empty { margin-top: 12px; padding: 9px; border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-faint); font-size: 9.5px; }
.runner-resource-empty i { margin-right: 5px; }
.slot-line { margin-top: 13px; display: flex; align-items: center; color: var(--wao-text-3); font-size: 11px; }
.slot-bar { height: 5px; margin-top: 6px; overflow: hidden; border-radius: 9px; background: var(--wao-bg-tab); }
.slot-bar i { display: block; height: 100%; border-radius: inherit; background: var(--wao-primary); transition: width .3s; }
.runner-tasks { margin: 12px 0 0; padding: 11px 0 0; border-top: 1px solid var(--wao-divider); list-style: none; }
.runner-tasks li { display: flex; align-items: center; gap: 7px; margin-top: 6px; font-size: 11.5px; }
.runner-tasks li:first-child { margin-top: 0; }
.runner-tasks button { flex: 1; padding: 0; border: 0; overflow: hidden; background: none; color: var(--wao-text-2); cursor: pointer; font-size: 11.5px; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.runner-tasks button:hover { color: #4358d6; text-decoration: underline; }
.runner-idle { margin-top: 12px; padding-top: 11px; border-top: 1px solid var(--wao-divider); color: var(--wao-text-weak); font-size: 11px; }
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
  color: var(--wao-success-strong);
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
.runner-script-hint.pending { color: var(--wao-warning-strong); }
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
.wake-panel { margin-top: 11px; padding-top: 11px; border-top: 1px solid var(--wao-divider); display: flex; flex-wrap: wrap; align-items: center; gap: 7px; }
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
.pair-code b { font-size: 19px; font-weight: 700; letter-spacing: 3px; color: var(--wao-primary-text); }
.pair-code small { color: var(--wao-text-weak); font-size: 11px; }
.pair-code code { display: block; margin-top: 9px; overflow-x: auto; padding: 9px 11px; border-radius: 8px; background: var(--wao-primary-soft-2); color: var(--wao-text-2); font: 11px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre; }
.workflow-list { display: grid; gap: 12px; }
.workflow-card { padding: 16px 18px; border: 1px solid var(--wao-line); border-radius: 13px; background: var(--wao-bg-card); }
.workflow-card.off { background: var(--wao-bg-sub); }
.workflow-card header { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.workflow-card header b { font-size: 14px; }
.workflow-card .spacer { flex: 1; }
.flow-state { padding: 3px 8px; border-radius: 6px; background: var(--wao-bg-hover-2); color: var(--wao-text-weak); font-size: 10.5px; font-weight: 650; }
.flow-state.on { background: var(--wao-success-bg); color: var(--wao-success-strong); }
.flow-orphan { padding: 3px 8px; border-radius: 6px; background: var(--wao-danger-bg); color: var(--wao-danger-strong); font-size: 10.5px; font-weight: 650; }
.link-btn { padding: 5px 10px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; font-size: 11.5px; }
.link-btn:hover { border-color: var(--wao-primary-line); color: #4358d6; }
.link-btn.danger { color: var(--wao-danger-strong); }
.link-btn.danger:hover { border-color: var(--wao-danger-strong); background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
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
.flow-form { margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--wao-divider); display: grid; grid-template-columns: repeat(2, 1fr); gap: 11px; }
.switch-row { grid-column: 1 / -1; display: flex; align-items: center; gap: 8px; font-size: 12px; }
.form-field { display: flex; flex-direction: column; gap: 5px; font-size: 11px; color: var(--wao-text-weak); }
.form-field.wide { grid-column: 1 / -1; }
.form-field input, .form-field select {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-size: 12px;
}
.form-field input:focus, .form-field select:focus { border-color: #8f9ee6; outline: none; }
.flow-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 9px; }
.flow-actions button { padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 12px; }
.flow-actions .secondary { border: 1px solid var(--wao-line); background: var(--wao-bg-card); }
.flow-actions .primary { border: 1px solid var(--wao-primary); background: var(--wao-primary); color: #fff; }
.flow-actions .primary:disabled { opacity: .6; cursor: not-allowed; }
.recent-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.activity-filters { margin: 0 0 12px; display: flex; flex-wrap: wrap; gap: 6px; }
.activity-filters button { min-height: 30px; padding: 0 11px; border: 1px solid var(--wao-line); border-radius: 999px; background: var(--wao-bg-card); color: var(--wao-text-weak); cursor: pointer; font-size: 11px; }
.activity-filters button.active { border-color: var(--wao-primary-line); background: var(--wao-primary-soft); color: var(--wao-primary-text); font-weight: 650; }
.activity-filters button:disabled { opacity: .55; cursor: wait; }
.recent-actions { flex: 0 0 auto !important; display: flex; align-items: center; gap: 7px; }
.recent-pagination { height: 30px; padding: 0 3px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 10.5px; display: flex; align-items: center; gap: 2px; }
.recent-pagination button { height: 24px; padding: 0 6px; border: 0; border-radius: 6px; background: transparent; color: var(--wao-text-2); cursor: pointer; font-size: 10.5px; display: inline-flex; align-items: center; gap: 3px; }
.recent-pagination button:hover:not(:disabled) { background: var(--wao-bg-card); color: var(--wao-primary-text); }
.recent-pagination button:disabled { opacity: .38; cursor: not-allowed; }
.recent-pagination span { min-width: 34px; text-align: center; }
.history-filters { margin: 14px 0 4px; }
.recent-card {
  width: 100%;
  min-width: 0;
  min-height: 150px;
  padding: 12px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  color: inherit;
  text-align: left;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 9px;
  transition: transform .16s, border-color .16s, box-shadow .16s;
}
.recent-card:hover { transform: translateY(-2px); border-color: var(--wao-primary-line); box-shadow: 0 8px 22px #27366c14; }
.recent-card-top { min-height: 28px; display: flex; align-items: center; gap: 6px; }
.recent-icon { width: 28px; height: 28px; flex: 0 0 auto; border-radius: 8px; display: grid; place-items: center; }
.recent-icon.recent-image { overflow: hidden; border: 1px solid var(--wao-line); background: var(--wao-bg-sub); }
.recent-icon.recent-image img { width: 100%; height: 100%; object-fit: cover; }
.recent-main { min-width: 0; }
.recent-main b { min-height: 36px; overflow: hidden; font-size: 12.5px; line-height: 1.45; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.recent-main em { display: block; margin-top: 4px; overflow: hidden; color: var(--wao-text-weak); font-size: 10.5px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.recent-main em i { margin-right: 5px; }
.recent-main small { display: block; margin-top: 4px; overflow: hidden; color: var(--wao-danger-strong); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.recent-type, .recent-status { padding: 3px 6px; border-radius: 999px; font-size: 9.5px; font-weight: 650; white-space: nowrap; display: inline-flex; align-items: center; gap: 3px; }
.recent-card-top .run-time { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recent-open { margin-top: auto; padding-top: 7px; border-top: 1px solid var(--wao-bg-tab); color: var(--wao-primary); font-size: 10.5px; display: flex; align-items: center; justify-content: flex-end; gap: 4px; }
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
.run-activity-detail {
  width: min(960px, 100%);
  max-height: min(820px, calc(100dvh - 48px));
}
.run-history-detail { width: min(1080px, 100%); }
.quick-task-dialog { width: min(860px, 100%); }
.auto-run-dialog { width: min(820px, 100%); }
.auto-run-dialog-icon { background: var(--wao-primary-tint); color: var(--wao-primary); }
.auto-run-summary { margin-top: 16px; padding: 12px 13px; border: 1px solid var(--wao-line); border-radius: 11px; background: var(--wao-bg-sub); color: var(--wao-text-weak); display: flex; align-items: flex-start; gap: 10px; }
.auto-run-summary.enabled { border-color: var(--wao-success-line); background: var(--wao-success-bg); }
.auto-run-summary > span { color: var(--wao-text-3); font-size: 20px; }
.auto-run-summary.enabled > span { color: var(--wao-success-strong); }
.auto-run-summary > div { min-width: 0; }
.auto-run-summary b { color: var(--wao-text-2); font-size: 12px; }
.auto-run-summary p { margin: 3px 0 0; font-size: 10.5px; line-height: 1.55; }
.auto-run-summary small { display: block; margin-top: 3px; color: var(--wao-text-3); font-size: 9.5px; }
.quick-task-dialog-icon { background: var(--wao-primary-tint); color: var(--wao-primary); }
.quick-task-list { max-height: min(520px, calc(100vh - 220px)); margin-top: 16px; overflow-y: auto; display: grid; gap: 8px; }
.quick-task-list-item { padding: 11px 12px; display: flex; align-items: center; gap: 14px; }
.quick-task-list-copy { min-width: 0; flex: 1; }
.quick-task-list-copy .quick-task-title { font-size: 13px; }
.quick-task-list-copy > p { -webkit-line-clamp: 1; }
.quick-task-dialog-state { min-height: 280px; margin-top: 16px; border: 1px dashed var(--wao-line); border-radius: 12px; color: var(--wao-text-weak); display: flex; align-items: center; justify-content: center; gap: 8px; }
.quick-task-dialog-state.error { color: var(--wao-danger-strong); }
.quick-task-dialog-state button { margin-left: 4px; padding: 6px 10px; border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); cursor: pointer; }
.history-icon { background: var(--wao-primary-tint); color: var(--wao-primary); }
.history-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); margin-top: 18px; }
.history-state { min-height: 260px; margin-top: 18px; border: 1px dashed var(--wao-line); border-radius: 12px; color: var(--wao-text-weak); display: flex; align-items: center; justify-content: center; gap: 8px; }
.history-state.error { color: var(--wao-danger-strong); }
.history-state .secondary { margin-left: 4px; padding: 6px 10px; border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); cursor: pointer; }
.recent-state { min-height: 150px; border: 1px dashed var(--wao-line); border-radius: 12px; color: var(--wao-text-weak); display: flex; align-items: center; justify-content: center; gap: 8px; }
.recent-state.error { color: var(--wao-danger-strong); }
.recent-state button { margin-left: 4px; padding: 5px 9px; border: 1px solid currentColor; border-radius: 7px; background: transparent; color: inherit; cursor: pointer; }
.run-detail footer.history-pagination { justify-content: center; color: var(--wao-text-weak); font-size: 11px; }
.history-pagination button { display: inline-flex; align-items: center; gap: 4px; }
.history-pagination button:disabled { opacity: .5; cursor: not-allowed; }
button:focus-visible, select:focus-visible, input:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.run-detail header { display: flex; align-items: center; gap: 12px; }
.run-detail header > div { flex: 1; min-width: 0; }
.run-detail h2 { margin: 0; font-size: 17px; }
.run-detail header p { margin: 3px 0 0; overflow: hidden; color: var(--wao-text-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.run-detail-images { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; margin-top: 16px; }
.run-detail-images img { width: 100%; max-height: 260px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-sub); object-fit: contain; }
.detail-close { width: 32px; height: 32px; border: 0; border-radius: 8px; background: var(--wao-bg-hover); cursor: pointer; }
.run-detail dl { margin: 18px 0; display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.run-activity-detail .run-detail-meta { grid-template-columns: repeat(5, minmax(0, 1fr)); }
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
.run-report-link { margin-top: 12px; padding: 11px 13px; border: 1px solid var(--wao-success-line); border-radius: 10px; background: linear-gradient(135deg, var(--wao-success-bg), var(--wao-bg-card)); color: inherit; text-decoration: none; display: flex; align-items: center; gap: 10px; }
.run-report-link:hover { border-color: #89c9aa; box-shadow: 0 5px 16px rgba(27, 126, 83, .08); }
.run-report-link > span { width: 31px; height: 31px; flex: none; border-radius: 8px; background: var(--wao-success-bg); color: var(--wao-success-strong); display: grid; place-items: center; }
.run-report-link > div { flex: 1; min-width: 0; }
.run-report-link b, .run-report-link small { display: block; }
.run-report-link b { font-size: 12px; }
.run-report-link small { margin-top: 2px; color: var(--wao-text-weak); font-size: 10.5px; }
.run-report-link > i { color: var(--wao-success-strong); }
.run-detail footer { margin-top: 18px; display: flex; align-items: center; justify-content: flex-end; gap: 9px; }
.run-activity-detail > footer { flex-wrap: wrap; gap: 10px 14px; }
.run-detail-context { min-width: 0; flex: 1 1 300px; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.run-detail-actions { flex: 0 1 auto; display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 9px; }
.run-detail footer .retry-note { margin-right: auto; color: var(--wao-text-weak); font-size: 11px; }
.run-activity-detail footer .retry-note { margin-right: 0; }
.run-detail footer .retry-note i { margin-right: 4px; }
.run-detail footer button { padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 12px; }
.run-detail footer .retry { display: inline-flex; align-items: center; gap: 6px; color: var(--wao-danger-strong); border-color: var(--wao-danger-line); }
.run-detail footer .retry:disabled { opacity: .6; cursor: not-allowed; }
.run-detail footer .stop-run { display: inline-flex; align-items: center; gap: 6px; border-color: var(--wao-danger-line); color: var(--wao-danger-strong); }
.run-detail footer .stop-run:disabled { opacity: .6; cursor: not-allowed; }
.gh-branch-link {
  min-width: 0;
  max-width: min(280px, 100%);
  display: inline-flex;
  align-items: center;
  flex: 0 1 auto;
  gap: 5px;
  color: #4358d6;
  border-color: var(--wao-line-control);
}
.gh-branch-link:hover { border-color: var(--wao-primary-line); background: var(--wao-primary-tint); }
.gh-branch-link.disabled { opacity: .5; pointer-events: none; }
.gh-branch-link i { font-size: 13px; }
.gh-branch-link span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.run-detail footer .secondary { border: 1px solid var(--wao-line); background: var(--wao-bg-card); }
.run-detail footer .primary { border: 1px solid var(--wao-primary); background: var(--wao-primary); color: #fff; }
.runner-log-detail { width: min(860px, 100%); }
.runner-log-icon { background: var(--wao-bg-hover-2); color: var(--wao-text-2); }
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
@keyframes orbit { to { transform: rotate(418deg); } }
@keyframes corePulse { 50% { transform: translate(-50%, -50%) scale(.68); opacity: .55; } }
@keyframes livePulse { 50% { opacity: .45; box-shadow: 0 0 0 7px #dff5e955; } }
.spin { display: inline-block; animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) {
  .home { padding: 16px; }
  .run-detail-mask { padding: 12px; }
  .run-detail { max-height: calc(100dvh - 24px); padding: 16px; }
  .run-activity-detail > footer { align-items: stretch; }
  .run-detail-context { flex-basis: 100%; align-items: stretch; flex-direction: column; }
  .gh-branch-link { max-width: 100%; }
  .run-detail-actions { width: 100%; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .run-detail-actions button { width: 100%; min-width: 0; }
  .run-detail-actions .primary { grid-column: 1 / -1; }
  .home-primary { grid-template-columns: minmax(0, 1fr); }
  .hero { padding: 22px; flex-direction: column; align-items: stretch; }
  .hero h1.slogan { font-size: 24px; letter-spacing: .5px; }
  .hero-metrics { width: auto; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .hero-metrics.single { grid-template-columns: minmax(0, 1fr); }
  .goal-launcher { grid-template-columns: 1fr; padding: 17px; gap: 14px; }
  .goal-actions { flex-wrap: wrap; padding-left: 9px; }
  .goal-actions label { width: 100%; }
  .goal-actions select { flex: 1; max-width: none; }
  .goal-shortcut { margin-left: 0; }
  .goal-submit { margin-left: auto; }
  .growth-coach { padding: 16px; }
  .growth-coach-head { flex-wrap: wrap; }
  .growth-coach-head .growth-coach-refresh { margin-left: 50px; }
  .growth-coach-ready, .growth-coach-state { margin-left: 0; }
  .growth-coach-ready footer { align-items: stretch; flex-direction: column; }
  .growth-coach-discuss { width: 100%; }
  .growth-coach-state { align-items: stretch; flex-wrap: wrap; }
  .growth-coach-state > div { flex-basis: calc(100% - 32px); }
  .growth-coach-state > button { flex: 1; }
  .console-tabs { overflow-x: auto; }
  .recent-grid { grid-template-columns: 1fr; }
  .auto-run-controls { justify-content: flex-start; flex-wrap: wrap; }
  .auto-run-options { grid-template-columns: 1fr; }
  .auto-run-statuses { flex-wrap: wrap; }
  .section-head { align-items: flex-start; flex-wrap: wrap; }
  .run-section-actions, .recent-actions { width: 100%; }
  .run-section-actions > button { flex: 1; justify-content: center; }
  .recent-actions { justify-content: space-between; }
  .recent-pagination { flex: 1; justify-content: space-between; }
  .recent-pagination button { min-height: 28px; }
  .auto-run-dialog header p, .quick-task-dialog header p { white-space: normal; }
  .auto-run-dialog footer { align-items: stretch; flex-wrap: wrap; }
  .auto-run-dialog footer .retry-note { width: 100%; margin: 0 0 2px; }
  .quick-task-grid { grid-template-columns: 1fr; }
  .quick-task-list-item { align-items: stretch; flex-direction: column; gap: 9px; }
  .quick-task-list-item .quick-task-start { align-self: flex-end; }
  .history-grid { grid-template-columns: 1fr; }
  .flow-meta { grid-template-columns: 1fr; }
  .flow-form { grid-template-columns: 1fr; }
  .run-detail dl, .run-activity-detail .run-detail-meta { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) {
  .capacity-orbit::before, .capacity-orbit::after, .capacity-orbit b { animation: none; }
}
@media (min-width: 761px) and (max-width: 1180px) {
  .home-primary { grid-template-columns: minmax(0, 1fr); }
  .recent-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .history-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
