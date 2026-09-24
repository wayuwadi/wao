import { http, type ApiResponse } from './http'
import type { TextPolishRecord } from './textPolish'

export interface PolishJob {
  id: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  message: string
  preview?: string
  polished?: string
  record?: TextPolishRecord
}
export interface PolishJobOptions {
  requestId?: string
  onProgress?: (job: PolishJob) => void
  isActive?: () => boolean
}
export const newPolishRequestId = () => crypto.randomUUID().replace(/-/g, '')

/** POST 重试沿用请求标识，GET 只恢复已有任务；每个 HTTP 请求都有短超时。 */
export async function waitForPolish(originalText: string, kind: 'DOCUMENT' | 'WORKSPACE' | 'NOTE_TITLE', options: PolishJobOptions = {}) {
  const requestId = options.requestId ?? newPolishRequestId()
  const read = async (request: Promise<{ data: ApiResponse<PolishJob> }>) => (await request).data.data
  let job = await read(http.post('/api/text-polish/jobs', { requestId, kind, originalText }))
  const startedAt = Date.now()
  const deadline = startedAt + 95 * 60_000
  while (true) {
    if (options.isActive && !options.isActive()) throw new Error('已离开当前润色页面，任务仍在后台保留')
    options.onProgress?.(job)
    if (job.status === 'FAILED') throw new Error(job.message || '润色失败，请重试')
    if (job.status === 'SUCCEEDED') return job
    if (Date.now() >= deadline) throw new Error('等待时间较长，请点击重试查询原任务；原文已保留')
    await new Promise((resolve) => setTimeout(resolve, Date.now() - startedAt < 60_000 ? 500 : 2000))
    if (options.isActive && !options.isActive()) throw new Error('已离开当前润色页面，任务仍在后台保留')
    job = await read(http.get(`/api/text-polish/jobs/${job.id}`))
  }
}
