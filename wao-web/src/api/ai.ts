import { http, type ApiResponse } from './http'
import type { AiProjectConfig, AiRun, AiRunner } from '@/types'

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchAiProjectConfig = (projectId: string) =>
  data<AiProjectConfig>(http.get(`/api/ai/projects/${projectId}/config`))

export const saveAiProjectConfig = (
  projectId: string,
  payload: Omit<AiProjectConfig, 'projectId'>,
) => data<AiProjectConfig>(http.put(`/api/ai/projects/${projectId}/config`, payload))

export const fetchAiRunners = () => data<AiRunner[]>(http.get('/api/ai/runners'))

export const createAiPairCode = () =>
  data<{ code: string; expiresAt: string }>(http.post('/api/ai/runners/pair-code'))

export const createAiRuns = (taskIds: string[]) =>
  data<AiRun[]>(http.post('/api/ai/runs', { taskIds }))

export const fetchAiRuns = (active = false) =>
  data<AiRun[]>(http.get('/api/ai/runs', { params: { active } }))

export const fetchTaskAiRuns = (taskId: string) =>
  data<AiRun[]>(http.get(`/api/ai/tasks/${taskId}/runs`))
