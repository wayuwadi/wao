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

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchDashboardSummary = () =>
  data<DashboardSummary>(http.get('/api/dashboard/summary', { params: { _: Date.now() } }))

export const fetchAiRunTrend = (days = 30) =>
  data<AiRunTrendItem[]>(http.get('/api/dashboard/ai-runs', { params: { days } }))

export const fetchTaskTrend = (days = 7) =>
  data<TaskTrendItem[]>(http.get('/api/dashboard/tasks', { params: { days } }))

export const fetchEngineUsage = () =>
  data<EngineUsageItem[]>(http.get('/api/dashboard/engines'))
