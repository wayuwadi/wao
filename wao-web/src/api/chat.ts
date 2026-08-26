import { apiUploadImage } from './board'
import { http, type ApiResponse } from './http'
import type { Member } from '@/types'

export type ChatFeishuStatus = 'PENDING' | 'SENT' | 'FAILED' | 'UNAVAILABLE' | 'RECEIVED'

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

export interface SharedChatConfig {
  chatId: string
  chatName: string
  configured: boolean
}

export interface SharedChatFeishuGroup {
  chatId: string
  name: string
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

export const markSharedChatRead = (through: string) =>
  data<void>(http.post('/api/chat/messages/read', undefined, { params: { through } }))

export const toggleSharedChatReaction = (id: string, emojiType: string) =>
  data<SharedChatMessage>(http.post(`/api/chat/messages/${id}/reactions/${emojiType}`))

export const fetchSharedChatConfig = () => data<SharedChatConfig>(http.get('/api/chat/config'))

export const saveSharedChatConfig = (chatId: string) =>
  data<SharedChatConfig>(http.put('/api/chat/config', { chatId }))

export const fetchSharedChatFeishuGroups = () =>
  data<SharedChatFeishuGroup[]>(http.get('/api/chat/feishu-chats'))

export const uploadSharedChatImage = (file: File) => apiUploadImage(file, 'WAO 聊天')
