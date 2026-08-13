import { http, type ApiResponse } from './http'

export interface WeeklyReport {
  completed: string
  progress: string
  risks: string
  nextPlan: string
}

/** 周报生成会等待本地 Codex，超时与服务端五分钟队列窗口保持一致。 */
export async function generateWeeklyReport(records: string): Promise<WeeklyReport> {
  const response = await http.post<ApiResponse<WeeklyReport>>(
    '/api/weekly-report/generate',
    { records },
    { timeout: 330_000 },
  )
  return response.data.data
}
