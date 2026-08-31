import { http, type ApiResponse } from './http'

export type DailyGuidanceStatus = 'ready' | 'missing' | 'unavailable'

export interface DailyGuidance {
  date: string
  status: DailyGuidanceStatus
  content?: string | null
  message: string
  generatedAt?: string | null
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

/** 读取当前成员今天已保存的成长启发与本地 Codex 可用状态。 */
export const fetchDailyGuidance = () =>
  data<DailyGuidance>(http.get('/api/daily-guidance', {
    params: { _: Date.now() },
    headers: { 'Cache-Control': 'no-cache' },
  }))
/** 结合当前成员已保存的今日重点、进行中与最近完成 Task 生成成长启发；主动刷新时替换当天缓存。 */
export const generateDailyGuidance = (refresh = false) =>
  data<DailyGuidance>(http.post('/api/daily-guidance', undefined, {
    params: { refresh },
    timeout: 310_000,
  }))
