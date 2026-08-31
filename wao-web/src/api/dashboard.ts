import { http, type ApiResponse } from './http'

export interface DashboardSummary {
  totalRuns: number
  activeRuns: number
  onlineRunners: number
  completedThisWeek: number
  succeededThisWeek: number
  failedThisWeek: number
}

export interface AiRunTrendItem {
  date: string
  total: number
  succeeded: number
  failed: number
  cancelled: number
  /** CLI 真实回传了 Token 用量的运行数；其余运行不能解释为零消耗。 */
  recordedRuns: number
  inputTokens: number
  outputTokens: number
  cachedInputTokens: number
  totalTokens: number
}

export interface TaskTrendItem {
  date: string
  completed: number
  created: number
}

export interface EngineUsageItem {
  engine: string
  label: string
  count: number
}

export interface AiQuotaDailyPoint {
  date: string
  remainingPercent: number
  decreasedPercent: number
}

export interface AiQuotaSeries {
  id: string
  accountLabel: string
  provider: string
  windowLabel: string
  points: AiQuotaDailyPoint[]
}

export interface AiQuotaRefreshEvent {
  accountLabel: string
  provider: string
  windowLabel: string
  beforeRemainingPercent: number
  afterRemainingPercent: number
  refreshedPercent: number
  resetsAt: string | null
  observedAt: string
}

export interface AiQuotaHistory {
  recordedSamples: number
  series: AiQuotaSeries[]
  refreshes: AiQuotaRefreshEvent[]
}

export interface AiUsageAdvice {
  category: 'OUTCOME' | 'QUALITY' | 'ROUTING' | 'RETRY' | 'ACCOUNTING'
  title: string
  recommendation: string
  evidence: string
  sufficientData: boolean
}

export interface AiUsageInsights {
  windowDays: number
  totalRuns: number
  completedRuns: number
  meteredRuns: number
  timedRuns: number
  deliveredRuns: number
  deliveredTasks: number
  verifiedTasks: number
  totalTokens: number
  failedTokens: number
  tokensPerDeliveredTask: number | null
  retryRuns: number
  retryTokens: number
  medianDurationSeconds: number | null
  medianAiExecutionSeconds: number | null
  advice: AiUsageAdvice[]
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchDashboardSummary = () =>
  data<DashboardSummary>(http.get('/api/dashboard/summary', { params: { _: Date.now() } }))

export const fetchAiRunTrend = (days = 30) =>
  data<AiRunTrendItem[]>(http.get('/api/dashboard/ai-runs', { params: { days } }))

export const fetchTaskTrend = (days = 7) =>
  data<TaskTrendItem[]>(http.get('/api/dashboard/tasks', { params: { days } }))

export const fetchEngineUsage = () => data<EngineUsageItem[]>(http.get('/api/dashboard/engines'))

export const fetchAiQuotaHistory = (days = 30) =>
  data<AiQuotaHistory>(http.get('/api/dashboard/quota-history', { params: { days } }))

export const fetchAiUsageInsights = (days = 30) =>
  data<AiUsageInsights>(http.get('/api/dashboard/usage-insights', { params: { days } }))
