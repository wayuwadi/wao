import { http, streamTextEvents, type ApiResponse } from './http'

export interface TianrenTrigram {
  name: string
  nature: string
  symbol: string
}

export interface TianrenHexagram {
  number: number
  name: string
  symbol: string
  upper: TianrenTrigram
  lower: TianrenTrigram
}

export interface TianrenReading {
  id: string
  date: string
  /** 初爻到上爻的 6/7/8/9。 */
  lines: number[]
  changingLines: number[]
  primary: TianrenHexagram
  relating: TianrenHexagram | null
  conversationId: string | null
  createdAt: string
}

export interface TianrenAiSettings {
  version: number
  autoImage: boolean
  interpretationPrompt: string
  imagePrompt: string
  defaultInterpretationPrompt: string
  defaultImagePrompt: string
}

export interface TianrenVisual {
  interpretationRunId: string | null
  state: 'NOT_GENERATED' | 'DISABLED' | 'BRIEF_FAILED' | 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  title: string | null
  change: string | null
  keep: string | null
  imageAttemptId: string | null
  message: string
  retryable: boolean
}

export const fetchTianrenAiSettings = () => data<TianrenAiSettings>(http.get('/api/tianren/settings'))
export const saveTianrenAiSettings = (settings: Pick<TianrenAiSettings, 'version' | 'autoImage' | 'interpretationPrompt' | 'imagePrompt'>) =>
  data<TianrenAiSettings>(http.put('/api/tianren/settings', settings))
export const fetchTianrenVisual = (readingId: string) =>
  data<TianrenVisual>(http.get(`/api/tianren/readings/${encodeURIComponent(readingId)}/visual`))
export const generateTianrenImage = (readingId: string, interpretationRunId: string, requestId: string) =>
  data<TianrenVisual>(http.post(`/api/tianren/readings/${encodeURIComponent(readingId)}/visual/image`, { interpretationRunId, requestId }))
/** 私有图片通过统一鉴权客户端读取，不使用公开图床地址。 */
export async function fetchTianrenImage(readingId: string, attemptId: string): Promise<Blob> {
  const response = await http.get<Blob>(`/api/tianren/readings/${encodeURIComponent(readingId)}/visual/images/${encodeURIComponent(attemptId)}/raw`, {
    responseType: 'blob', headers: { Accept: 'image/png' },
  })
  return response.data
}

export interface TianrenNotification {
  state: 'DISABLED' | 'CONFIGURATION_REQUIRED' | 'INTERPRETING' | 'INTERPRETATION_FAILED' | 'WAITING' | 'PENDING' | 'SENDING' | 'SENT' | 'FAILED' | 'SKIPPED' | 'UNKNOWN'
  targetName: string
  message: string
  retryable: boolean
}

export const fetchTianrenNotification = (id: string) =>
  data<TianrenNotification>(http.get(`/api/tianren/readings/${encodeURIComponent(id)}/notification`))

export const retryTianrenNotification = (id: string, confirmNotReceived = false) =>
  data<TianrenNotification>(http.post(`/api/tianren/readings/${encodeURIComponent(id)}/notification/retry`, { confirmNotReceived }))

export type SharedTianrenReading = Omit<TianrenReading, 'id' | 'conversationId'> & {
  /** 卦象专属会话最新一条 AI 自照；不包含用户关注点或完整会话。 */
  interpretation: string | null
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchTodayTianrenReading = () =>
  data<TianrenReading | null>(http.get('/api/tianren/readings/today'))

export interface TianrenAiStatus { configured: boolean; message: string; runStatus?: string | null; runId?: string | null }
export const fetchTianrenAiStatus = () => data<TianrenAiStatus>(http.get('/api/tianren/readings/ai-status'))

export const createTodayTianrenReading = () =>
  data<TianrenReading>(http.post('/api/tianren/readings/today'))

export const fetchTianrenHistory = (limit = 14) =>
  data<TianrenReading[]>(http.get('/api/tianren/readings', { params: { limit } }))

export const fetchCalendarTianrenReadings = (from: string, to: string) =>
  data<TianrenReading[]>(http.get('/api/tianren/readings/calendar', { params: { from, to } }))

export async function createTianrenShareLink(readingId: string): Promise<string> {
  const { token } = await data<{ token: string }>(http.post(`/api/tianren/readings/${readingId}/share`))
  return `${window.location.origin}/s/tianren/${token}`
}

export const fetchSharedTianrenReading = (token: string) =>
  data<SharedTianrenReading>(http.get(`/api/share/tianren/${encodeURIComponent(token)}`))

export async function* streamTianrenInterpretation(focus: string): AsyncGenerator<string> {
  yield* streamTextEvents('/api/tianren/readings/today/interpret/stream', { focus })
}

/** 下线节奏仪表盘后，卦象仍沿用既有成员通知偏好。 */
export { fetchPreference as fetchTianrenPreference, savePreference as saveTianrenPreference } from './favorites'
