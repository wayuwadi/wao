import { http, type ApiResponse } from './http'

export type SystemReportPeriod = 'DAY' | 'WEEK' | 'MONTH'
export type SystemReportAggregation = 'TASK_CREATED' | 'TASK_COMPLETED' | 'AI_RUN'

export interface SystemReportSettings {
  automaticEnabled: boolean
  refreshTime: string
  refreshFrequencyHours: 1 | 3 | 6 | 12 | 24
  periods: SystemReportPeriod[]
  aggregationOptions: SystemReportAggregation[]
}

export interface SystemReportTask {
  id: string
  title: string
  projectId: string | null
  projectName: string | null
  status: string
  events: Array<'CREATED' | 'COMPLETED'>
  createdAt: string | null
  completedAt: string | null
}

export interface SystemReportRun {
  id: string
  taskId: string
  taskTitle: string | null
  projectId: string
  projectName: string | null
  engine: string
  status: string
  resultSummary: string | null
  totalTokens: number | null
  createdAt: string
  finishedAt: string | null
}

export interface SystemPeriodicReport {
  period: SystemReportPeriod
  periodKey: string
  periodLabel: string
  startDate: string
  endDate: string
  projectTotal: number
  taskTotal: number
  openTaskTotal: number
  aiRunTotal: number
  taskCreatedCount: number
  taskCompletedCount: number
  aiRunCount: number
  aiRunSuccessCount: number
  aiRunFailureCount: number
  aiRunMeteredCount: number
  totalTokens: number
  tasks: SystemReportTask[]
  runs: SystemReportRun[]
  aggregationOptions: SystemReportAggregation[]
  generatedAt: string | null
  coverageMessage: string
}

export async function fetchSystemReportSettings() {
  const response = await http.get<ApiResponse<SystemReportSettings>>('/api/system-reports/settings')
  return response.data.data
}

export async function saveSystemReportSettings(settings: SystemReportSettings) {
  const response = await http.put<ApiResponse<SystemReportSettings>>(
    '/api/system-reports/settings',
    settings,
  )
  return response.data.data
}

export async function fetchSystemReport(period: SystemReportPeriod, anchorDate?: string) {
  const response = await http.get<ApiResponse<SystemPeriodicReport>>('/api/system-reports', {
    params: { period, ...(anchorDate ? { anchorDate } : {}) },
  })
  return response.data.data
}

export async function generateSystemReport(period: SystemReportPeriod, anchorDate?: string) {
  const response = await http.post<ApiResponse<SystemPeriodicReport>>(
    '/api/system-reports/generate',
    null,
    { params: { period, ...(anchorDate ? { anchorDate } : {}) } },
  )
  return response.data.data
}
