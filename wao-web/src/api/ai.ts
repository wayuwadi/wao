import { http, type ApiResponse } from './http'
import type { AiEngine, AiProjectConfig, AiRun, AiRunner, AiWorkflow } from '@/types'

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchAiProjectConfig = (projectId: string) =>
  data<AiProjectConfig>(http.get(`/api/ai/projects/${projectId}/config`))

export const saveAiProjectConfig = (
  projectId: string,
  payload: Omit<AiProjectConfig, 'projectId'>,
) => data<AiProjectConfig>(http.put(`/api/ai/projects/${projectId}/config`, payload))

export const fetchAiWorkflows = () => data<AiWorkflow[]>(http.get('/api/ai/workflows'))

export const fetchAiRunners = () => data<AiRunner[]>(http.get('/api/ai/runners'))

export const updateRunnerMaxConcurrency = (runnerId: string, maxConcurrency: number) =>
  data<AiRunner>(http.put(`/api/ai/runners/${runnerId}/max-concurrency`, { maxConcurrency }))

/** 一键把服务端打包的最新 Runner 脚本下发给所有执行电脑。 */
export const requestRunnerScriptUpdate = () => data<AiRunner[]>(http.post('/api/ai/runners/update-script'))

export const updateRunnerWakeConfig = (
  runnerId: string,
  payload: { mac: string; host: string; port: number },
) => data<AiRunner>(http.put(`/api/ai/runners/${runnerId}/wake-config`, payload))

export const wakeAiRunner = (runnerId: string) =>
  data<{ runnerId: string; requestedAt: string; message: string }>(
    http.post(`/api/ai/runners/${runnerId}/wake`),
  )

export const createAiPairCode = () =>
  data<{ code: string; expiresAt: string }>(http.post('/api/ai/runners/pair-code'))

/**
 * 入队；engine / runnerId 留空表示沿用项目默认工具并由任意在线电脑领取。
 *
 * <p>传 subId 表示只跑这一条子任务，此时 taskIds 只能有一个任务。
 */
export const createAiRuns = (
  taskIds: string[],
  options: { subId?: string | null; engine?: AiEngine | null; runnerId?: string | null } = {},
) =>
  data<AiRun[]>(http.post('/api/ai/runs', {
    taskIds,
    subId: options.subId ?? null,
    engine: options.engine ?? null,
    runnerId: options.runnerId ?? null,
  }))

/** 基于一次失败运行重跑：AI 会拿到上次的报错接着修，不用从零重来。 */
export const rerunAiRun = (runId: string) => data<AiRun>(http.post(`/api/ai/runs/${runId}/rerun`))

export const continueAiRun = (runId: string, input: string) =>
  data<AiRun>(http.post(`/api/ai/runs/${runId}/continue`, { input }))

export const fetchAiRuns = (active = false) =>
  data<AiRun[]>(http.get('/api/ai/runs', { params: { active } }))

export const fetchTaskAiRuns = (taskId: string) =>
  data<AiRun[]>(http.get(`/api/ai/tasks/${taskId}/runs`))
