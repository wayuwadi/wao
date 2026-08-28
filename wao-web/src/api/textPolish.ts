import { http, type ApiResponse } from './http'

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

export const runTextPolish = (originalText: string) =>
  data<TextPolishRecord>(http.post('/api/text-polish/run', { originalText }, { timeout: 310_000 }))
