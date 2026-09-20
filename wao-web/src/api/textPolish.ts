import { http, type ApiResponse } from './http'
import { waitForPolish, type PolishJobOptions } from './polishJobs'

export interface TextPolishRecord {
  id: string
  promptSnapshot: string
  originalText: string
  polishedText: string
  createdAt: string
}

export interface TextPolishWorkspace {
  prompt: string
  records: TextPolishRecord[]
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchTextPolishWorkspace = () =>
  data<TextPolishWorkspace>(http.get('/api/text-polish'))

export const saveTextPolishPrompt = (prompt: string) =>
  data<string>(http.put('/api/text-polish/prompt', { prompt }))

export const runTextPolish = async (originalText: string, options?: PolishJobOptions): Promise<TextPolishRecord> => {
  const job = await waitForPolish(originalText, 'WORKSPACE', options)
  if (!job.record) throw new Error('润色结果暂未保存，请重试查询原任务')
  return job.record
}
