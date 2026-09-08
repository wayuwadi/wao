import { http, type ApiResponse } from './http'
import type { AiRun, AiRunProgress, AiWorkflow } from '@/types'
export interface SessionPreference {
  title: string | null
  pinned: boolean
  archived: boolean
}
export interface AiSessionItem {
  id: string
  taskId: string
  title: string
  projectName: string
  engine: string
  model: string | null
  status: string
  sessionId: string | null
  runnerName: string | null
  preview: string | null
  updatedAt: string
  pinned: boolean
  archived: boolean
}
export interface SessionPage {
  items: AiSessionItem[]
  total: number
  page: number
  size: number
}
export interface SessionDetail {
  run: AiRun
  preference: SessionPreference
  prompt: string | null
}
const data = async <T>(p: Promise<{ data: ApiResponse<T> }>) => (await p).data.data
const path = (id: string) => `/api/ai/sessions/${encodeURIComponent(id)}`
export const listSessions = (params: {
  q: string
  status: string
  engine: string
  archived: boolean
  page: number
}) => data<SessionPage>(http.get('/api/ai/sessions', { params }))
export const getSession = (id: string) => data<SessionDetail>(http.get(path(id)))
export const saveSessionPreference = (id: string, pref: SessionPreference) =>
  data<SessionPreference>(http.put(`${path(id)}/preference`, pref))
export const sessionConversation = (id: string, before?: string) =>
  data<{ items: AiRunProgress[]; nextCursor: string | null }>(
    http.get(`${path(id)}/conversation`, { params: { before } }),
  )
export const sendSessionInput = (id: string, input: string) =>
  data<AiRun>(http.post(`${path(id)}/continue`, { input }))
export const pauseSession = (id: string) => data<AiRun>(http.post(`${path(id)}/pause`))
export const sessionWorkflows = () => data<AiWorkflow[]>(http.get('/api/ai/sessions/workflows'))
export const createSession = (payload: {
  projectId: string
  goal: string
  runnerId: string | null
  planMode: boolean
  goalMode: boolean
  reasoningEffort: string
}) => data<AiRun>(http.post('/api/ai/sessions', payload))
