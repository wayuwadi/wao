import { http, type ApiResponse } from './http'
import { DEFAULT_NOTE_MODEL } from '@/utils/noteAiModel'

export interface ArticleImageJob {
  id: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  message: string
  imageUrl: string | null
}

export async function startArticleImage(articleText: string, visualPrompt: string, model = DEFAULT_NOTE_MODEL, requestId?: string): Promise<ArticleImageJob> {
  const response = await http.post<ApiResponse<ArticleImageJob>>('/api/assistant/article-images', {
    articleText, visualPrompt, model, requestId,
  })
  return response.data.data
}

export async function fetchArticleImage(id: string): Promise<ArticleImageJob> {
  const response = await http.get<ApiResponse<ArticleImageJob>>(`/api/assistant/article-images/${encodeURIComponent(id)}`)
  return response.data.data
}
