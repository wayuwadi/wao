import { http, type ApiResponse } from './http'

export type NoteMiningStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type NoteMiningArtifactType = 'INSIGHT' | 'REMINDER' | 'ARTICLE_SEED'
export type NoteMiningFeedback = 'CANDIDATE' | 'SAVED' | 'NOT_ACCURATE' | 'REMINDER_DRAFT'

export interface NoteMiningSource {
  docId: string
  docRev: number
  title: string
  excerpt: string
}

export interface NoteMiningArtifact {
  id: string
  type: NoteMiningArtifactType
  content: string
  rationale: string
  feedbackStatus: NoteMiningFeedback
  sources: NoteMiningSource[]
  createdAt: string
}

export interface NoteMiningResult {
  job: null | {
    id: string
    status: NoteMiningStatus
    message: string
    sourceCount: number
    createdAt: string
  }
  artifacts: NoteMiningArtifact[]
}

const unwrap = async <T>(request: Promise<{ data: ApiResponse<T> }>) => (await request).data.data

export const newNoteMiningRequestId = () => crypto.randomUUID().replace(/-/g, '')
export const fetchNoteMining = () => unwrap<NoteMiningResult>(http.get('/api/note-mining'))
export const fetchNoteMiningRun = (id: string) => unwrap<NoteMiningResult>(http.get(`/api/note-mining/runs/${id}`))
export const startNoteMining = (requestId = newNoteMiningRequestId()) =>
  unwrap<NoteMiningResult>(http.post('/api/note-mining/runs', { requestId }))
export const feedbackNoteMiningArtifact = (id: string, status: Exclude<NoteMiningFeedback, 'CANDIDATE'>) =>
  unwrap<NoteMiningArtifact>(http.post(`/api/note-mining/artifacts/${id}/feedback`, { status }))

export async function waitForNoteMining(
  initial: NoteMiningResult,
  onProgress: (result: NoteMiningResult) => void,
  isActive: () => boolean,
) {
  let result = initial
  const deadline = Date.now() + 95 * 60_000
  while (result.job && (result.job.status === 'QUEUED' || result.job.status === 'RUNNING')) {
    if (!isActive()) return result
    onProgress(result)
    if (Date.now() >= deadline) throw new Error('挖掘仍在后台继续，稍后回到小记页即可查看结果')
    await new Promise(resolve => window.setTimeout(resolve, 1200))
    if (!isActive()) return result
    result = await fetchNoteMiningRun(result.job.id)
  }
  onProgress(result)
  return result
}
