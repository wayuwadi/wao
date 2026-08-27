import { http, type ApiResponse } from './http'
import type { AiEngine, AiProjectConfig, AiRun, AiRunner, AiRunnerLog, AiRunOptions, AiSkillCapability, AiWorkflow } from '@/types'

export interface AiWeeklyQuota {
  accountId: string
  provider: 'codex' | 'claude'
  label: string
  plan: string | null
  usedPercent: number
  remainingPercent: number
  resetsAt: string | null
  lastSyncedAt: string | null
  status: string
  message: string | null
}

export interface AiSessionQuota extends AiWeeklyQuota {
  provider: 'codex'
}

export interface CodexQuotaWindow {
  usedPercent: number
  windowDurationMinutes: number | null
  resetsAt: number | null
}

export interface CodexQuotaLimit {
  id: string
  label: string
  windows: CodexQuotaWindow[]
}

export interface CodexQuotaAccount {
  key: string
  accountEmail: string | null
  plan: string | null
  credits: { hasCredits: boolean; unlimited: boolean; balance: string | null } | null
  limits: CodexQuotaLimit[]
  runnerNames: string[]
  lastSyncedAt: string
  fresh: boolean
}

export interface AiEvolutionSuggestion {
  id: string
  title: string
  description: string
  icon: string
  goal: string
  sourceTitle: string
  sourceUrl: string
}

export interface AiEvolutionOverview {
  codexQuotas: CodexQuotaAccount[]
  sessionQuotas: AiSessionQuota[]
  weeklyQuotas: AiWeeklyQuota[]
  suggestions: AiEvolutionSuggestion[]
  autoStatus: 'busy' | 'cooling_down' | 'quota_unavailable' | 'ready'
  autoMessage: string
  lastActivityAt: string | null
  nextEligibleAt: string | null
  nextTriggerAt: string | null
  lastCheckedAt: string | null
  lastRunId: string | null
  intervalHours: number
  batchSize: number
}

/** 空闲后自动执行最早待办的持久化策略与真实调度状态。 */
export interface AiTaskAutoRunPolicy {
  enabled: boolean
  idleHours: number
  projectId: string | null
  taskStatuses: Array<'待办' | '收集中' | '进行中'>
  notificationWebhookId: string | null
  status: 'disabled' | 'busy' | 'cooling_down' | 'no_task' | 'invalid_config' | 'ready'
  message: string
  lastActivityAt: string | null
  nextEligibleAt: string | null
  lastCheckedAt: string | null
  lastRunId: string | null
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchAiProjectConfig = (projectId: string) =>
  data<AiProjectConfig>(http.get(`/api/ai/projects/${projectId}/config`))

export const saveAiProjectConfig = (
  projectId: string,
  payload: Omit<AiProjectConfig, 'projectId'>,
) => data<AiProjectConfig>(http.put(`/api/ai/projects/${projectId}/config`, payload))

export const fetchAvailableAiSkills = () =>
  data<AiSkillCapability[]>(http.get('/api/ai/skills/available'))

export const fetchWaoApplicationSkills = () =>
  data<AiSkillCapability[]>(http.get('/api/ai/application-skills'))

/** 整体替换 WAO 默认固定版本；变化只影响之后新建的 AI Run。 */
export const saveWaoApplicationSkills = (versionIds: string[]) =>
  data<AiSkillCapability[]>(http.put('/api/ai/application-skills', { versionIds }))

export const fetchAiProjectSkills = (projectId: string) =>
  data<AiSkillCapability[]>(http.get(`/api/ai/projects/${projectId}/skills`))

/** 整体替换固定版本绑定；变化只影响之后新建的 AI Run。 */
export const saveAiProjectSkills = (projectId: string, versionIds: string[]) =>
  data<AiSkillCapability[]>(http.put(`/api/ai/projects/${projectId}/skills`, { versionIds }))

export const fetchAiWorkflows = () => data<AiWorkflow[]>(http.get('/api/ai/workflows'))

/** 后台受控的 Codex 模型、推理强度与服务档位。 */
export const fetchAiRunOptions = () => data<AiRunOptions>(http.get('/api/ai/run-options'))

/** 删除工作流配置；历史运行记录不受影响。 */
export const removeAiWorkflow = (projectId: string) =>
  data<void>(http.delete(`/api/ai/workflows/${projectId}`))

/**
 * 执行电脑在线状态是实时数据，不能复用浏览器或中间代理里的旧 GET 响应。
 */
export const fetchAiRunners = () => data<AiRunner[]>(http.get('/api/ai/runners', {
  params: { _: Date.now() },
  headers: { 'Cache-Control': 'no-cache' },
}))

/** 按需读取一台执行电脑最近上报的安全日志快照。 */
export const fetchAiRunnerLog = (runnerId: string) =>
  data<AiRunnerLog>(http.get(`/api/ai/runners/${runnerId}/log`, {
    params: { _: Date.now() },
    headers: { 'Cache-Control': 'no-cache' },
  }))

export const updateRunnerMaxConcurrency = (runnerId: string, maxConcurrency: number) =>
  data<AiRunner>(http.put(`/api/ai/runners/${runnerId}/max-concurrency`, { maxConcurrency }))

/** 解除一台执行电脑的接入；返回解除后剩下的电脑列表。 */
export const removeAiRunner = (runnerId: string) =>
  data<AiRunner[]>(http.delete(`/api/ai/runners/${runnerId}`))

/** 一键把服务端打包的最新 Runner 脚本下发给所有执行电脑。 */
export const requestRunnerScriptUpdate = () => data<AiRunner[]>(http.post('/api/ai/runners/update-script'))
export const wakeAiRunner = (runnerId: string) =>
  data<{ runnerId: string; requestedAt: string; message: string }>(
    http.post(`/api/ai/runners/${runnerId}/wake`),
  )

export const createAiPairCode = () =>
  data<{ code: string; expiresAt: string }>(http.post('/api/ai/runners/pair-code'))

/**
 * 入队；engine / runnerId 留空表示沿用项目默认工具并由任意在线电脑领取。
 *
 * <p>传 subId 表示只跑这一条子任务，此时 taskIds 只能有一个任务。
 */
export const createAiRuns = (
  taskIds: string[],
  options: {
    subId?: string | null
    engine?: AiEngine | null
    runnerId?: string | null
    accountId?: string | null
    model?: string | null
    reasoningEffort?: string | null
    planMode?: boolean
    mergeTasks?: boolean
  } = {},
) =>
  data<AiRun[]>(http.post('/api/ai/runs', {
    taskIds,
    subId: options.subId ?? null,
    engine: options.engine ?? null,
    runnerId: options.runnerId ?? null,
    accountId: options.accountId ?? null,
    model: options.model ?? null,
    reasoningEffort: options.reasoningEffort ?? null,
    planMode: options.planMode ?? false,
    mergeTasks: options.mergeTasks ?? false,
  }))

/** 从一句目标创建任务并派发；runnerId 和 Codex Plan 模式都只覆盖本次运行。 */
export const launchAutonomousGoal = (
  projectId: string,
  goal: string,
  runnerId: string | null = null,
  planMode = false,
  reasoningEffort: string | null = null,
) => data<AiRun>(http.post('/api/ai/goals', { projectId, goal, runnerId, planMode, reasoningEffort }))

/** 首页真实会话/周额度、自主进化状态和最近一批真实资产建议。 */
export const fetchAiEvolution = () => data<AiEvolutionOverview>(http.get('/api/ai/evolution', {
  params: { _: Date.now() },
  headers: { 'Cache-Control': 'no-cache' },
}))

/** 从真实知识库和飞书同步归档挖掘最多五条新建议。 */
export const refreshEvolutionSuggestions = () =>
  data<AiEvolutionSuggestion[]>(http.post('/api/ai/evolution/suggestions/refresh', null, {
    timeout: 310_000,
  }))

/** 保存自主进化的空闲间隔与单次自动派发数量。 */
export const saveAiEvolutionSchedule = (intervalHours: number, batchSize: number) =>
  data<AiEvolutionOverview>(http.put('/api/ai/evolution/schedule', { intervalHours, batchSize }))

export const fetchAiTaskAutoRunPolicy = () =>
  data<AiTaskAutoRunPolicy>(http.get('/api/ai/task-auto-run', {
    params: { _: Date.now() },
    headers: { 'Cache-Control': 'no-cache' },
  }))

export const saveAiTaskAutoRunPolicy = (
  enabled: boolean,
  idleHours: number,
  projectId: string,
  taskStatuses: Array<'待办' | '收集中' | '进行中'>,
  notificationWebhookId: string | null,
) => data<AiTaskAutoRunPolicy>(http.put('/api/ai/task-auto-run', {
  enabled, idleHours, projectId, taskStatuses, notificationWebhookId,
}))

/** 一键把一条建议交给选中的项目工作流。 */
export const launchEvolutionSuggestion = (suggestionId: string, projectId: string) =>
  data<AiRun>(http.post(`/api/ai/evolution/suggestions/${suggestionId}/launch`, { projectId }))

/** 基于一次失败运行重跑：AI 会拿到上次的报错接着修，不用从零重来。 */
export const rerunAiRun = (runId: string) => data<AiRun>(http.post(`/api/ai/runs/${runId}/rerun`))

export const continueAiRun = (runId: string, input: string) =>
  data<AiRun>(http.post(`/api/ai/runs/${runId}/continue`, { input }))

/** 暂停等待中或正在执行的 AI 运行。 */
export const pauseAiRun = (runId: string) => data<AiRun>(http.post(`/api/ai/runs/${runId}/pause`))

export const fetchAiRuns = (active = false) =>
  data<AiRun[]>(http.get('/api/ai/runs', { params: { active } }))

export interface AiRunPage {
  items: AiRun[]
  total: number
  page: number
  size: number
}

/** 按页读取全部已结束运行；首页默认八条仍走轻量列表。 */
export const fetchAiRunHistory = (page = 1, size = 20) =>
  data<AiRunPage>(http.get('/api/ai/runs/history', { params: { page, size } }))

export const fetchTaskAiRuns = (taskId: string) =>
  data<AiRun[]>(http.get(`/api/ai/tasks/${taskId}/runs`))
