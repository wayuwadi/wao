import { apiUploadImage, apiUploadFile } from './board'
import { http, type ApiResponse } from './http'
import type { Member } from '@/types'

export type ChatFeishuStatus = 'PENDING' | 'SENT' | 'FAILED' | 'UNAVAILABLE' | 'RECEIVED' | 'LOCAL'

export interface SharedChatReaction {
  emojiType: string
  emoji: string
  count: number
  reacted: boolean
  names: string[]
}

export interface SharedChatReply {
  id: string
  authorName: string
  preview: string
}

export interface SharedChatMessage {
  id: string
  author: Member
  content: string
  imageId?: string
  imageName?: string
  imageUrl?: string
  source: 'WAO' | 'FEISHU'
  replyTo?: SharedChatReply
  deleted: boolean
  read: boolean
  readCount: number
  feishuReadCount: number
  reactions: SharedChatReaction[]
  feishuStatus: ChatFeishuStatus
  feishuError?: string
  createdAt: string
}

export interface SendSharedChatMessageInput {
  clientToken: string
  content: string
  imageId?: string
  replyToId?: string
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

export const markSharedChatRead = (through: string) =>
  data<void>(http.post('/api/chat/messages/read', undefined, { params: { through } }))

export const toggleSharedChatReaction = (id: string, emojiType: string) =>
  data<SharedChatMessage>(http.post(`/api/chat/messages/${id}/reactions/${emojiType}`))

export const uploadSharedChatImage = (file: File) => apiUploadImage(file, 'WAO 聊天')

/** 聊天文件存入真实组织文件库，沿用 200MB 限额和登录鉴权。 */
export const uploadSharedChatFile = (file: File) => apiUploadFile(file)

export const editSharedChatMessage = (id: string, content: string, expectedContent: string) =>
  data<SharedChatMessage>(http.patch(`/api/chat/messages/${encodeURIComponent(id)}`, { content, expectedContent }))
