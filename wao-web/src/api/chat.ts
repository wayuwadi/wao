import { apiUploadImage } from './board'
import { http, type ApiResponse } from './http'
import type { Member } from '@/types'

export type ChatFeishuStatus = 'PENDING' | 'SENT' | 'FAILED' | 'UNAVAILABLE'

export interface SharedChatMessage {
  id: string
  author: Member
  content: string
  imageId?: string
  imageName?: string
  imageUrl?: string
  feishuStatus: ChatFeishuStatus
  feishuError?: string
  createdAt: string
}

export interface SendSharedChatMessageInput {
  clientToken: string
  content: string
  imageId?: string
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchSharedChatMessages = (after?: string) =>
  data<SharedChatMessage[]>(
    http.get('/api/chat/messages', { params: after ? { after } : undefined }),
  )

export const sendSharedChatMessage = (input: SendSharedChatMessageInput) =>
  data<SharedChatMessage>(http.post('/api/chat/messages', input, { timeout: 70_000 }))

export const retrySharedChatFeishu = (id: string) =>
  data<SharedChatMessage>(http.post(`/api/chat/messages/${id}/retry-feishu`))

export const uploadSharedChatImage = (file: File) => apiUploadImage(file, 'WAO 聊天')
