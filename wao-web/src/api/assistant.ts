import { http, streamTextEvents, type ApiResponse } from './http'
import { waitForPolish, type PolishJobOptions } from './polishJobs'

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface AssistantStatus {
  configured: boolean
  message: string
  capabilityId?: string
  capabilityLabel?: string
}

export interface AiCapability {
  id: string
  label: string
  provider: 'codex' | 'deepseek' | 'model_gateway'
  model?: string | null
  executionMode: 'LOCAL_CODEX' | 'RUNNER' | 'SERVER_API'
  available: boolean
  message: string
}

export interface PolishTextResult {
  polished: string
}

export type AssistantAssetType = 'memory' | 'chat' | 'knowledge' | 'project' | 'task'

export interface AssistantSourceFilter {
  assetTypes: AssistantAssetType[]
  chatIds?: string[]
  senders?: string[]
  dateFrom?: string
  dateTo?: string
  projectIds?: string[]
}

export interface AssistantSourceOption {
  id: string
  name: string
}

export interface AssistantSourceOptions {
  chats: AssistantSourceOption[]
  senders: string[]
  projects: AssistantSourceOption[]
  earliestChatDate?: string
  latestChatDate?: string
}

export interface AssistantConversation {
  id: string
  title: string
  preview?: string
  updatedAt: string
}

export interface AssistantHistoryMessage extends ChatMessage {
  id: string
  createdAt: string
}

export interface AssistantConversationDetail {
  conversation: AssistantConversation
  messages: AssistantHistoryMessage[]
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchAssistantStatus = (capabilityId?: string) =>
  data<AssistantStatus>(
    http.get('/api/assistant/status', {
      params: { _: Date.now(), capabilityId },
      headers: { 'Cache-Control': 'no-cache' },
    }),
  )

export const fetchAiCapabilities = () =>
  data<AiCapability[]>(http.get('/api/assistant/capabilities', {
    params: { _: Date.now() },
    headers: { 'Cache-Control': 'no-cache' },
  }))

/** 润色文档中明确选中的文字；结果不会写进 AI 助手聊天历史。 */
export const polishDocumentText = async (text: string, options?: PolishJobOptions): Promise<PolishTextResult> => {
  const job = await waitForPolish(text, 'DOCUMENT', options)
  return { polished: job.polished ?? '' }
}

export const fetchAssistantSourceOptions = () =>
  data<AssistantSourceOptions>(http.get('/api/assistant/source-options'))

export const fetchAssistantConversations = () =>
  data<AssistantConversation[]>(http.get('/api/assistant/conversations'))

export const createAssistantConversation = () =>
  data<AssistantConversation>(http.post('/api/assistant/conversations'))

export const fetchAssistantConversation = (conversationId: string) =>
  data<AssistantConversationDetail>(http.get(`/api/assistant/conversations/${conversationId}`))

export const deleteAssistantConversation = (conversationId: string) =>
  http.delete(`/api/assistant/conversations/${conversationId}`)

export const sendChat = async (
  message: string,
  history: ChatMessage[],
  pageContext?: string,
  sourceFilter?: AssistantSourceFilter,
): Promise<string> => {
  const res = await http.post('/api/assistant/chat', {
    message,
    history,
    pageContext,
    sourceFilter,
  })
  return res.data.data.content
}

/** 流式对话：返回一个 EventSource 风格的迭代器。 */
export async function* streamChat(
  message: string,
  history: ChatMessage[],
  pageContext?: string,
  conversationId?: string,
  sourceFilter?: AssistantSourceFilter,
  aiCapabilityId?: string,
  referenceImageIds?: string[],
): AsyncGenerator<string> {
  yield* streamTextEvents('/api/assistant/chat/stream', {
    message,
    history,
    pageContext,
    conversationId,
    sourceFilter,
    aiCapabilityId,
    referenceImageIds,
  })
}
