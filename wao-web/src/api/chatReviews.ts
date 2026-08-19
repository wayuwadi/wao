import { http, type ApiResponse } from './http'

export type ChatReviewStage =
  'WAITING' | 'SYNCING' | 'VERIFYING' | 'ANALYZING' | 'SUCCEEDED' | 'FAILED'

export interface ChatReviewSpeaker {
  id: string
  name: string
}

export interface ChatReviewConfig {
  chatConfigured: boolean
  configured: boolean
  chatId: string
  chatName: string
  availableSpeakers: ChatReviewSpeaker[]
  selectedSpeakers: ChatReviewSpeaker[]
  message: string
}

export interface ChatReviewRunSummary {
  id: string
  startDate: string
  endDate: string
  focus: string | null
  stage: ChatReviewStage
  progressCurrent: number
  progressTotal: number
  currentDate: string | null
  failedStage: ChatReviewStage | null
  failedDate: string | null
  error: string | null
  messageCount: number
  createdById: string
  createdByName: string
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface ChatReviewSource {
  id: string
  date: string
  kbId: string
  documentId: string
  url: string
  messageCount: number
}

export interface ChatReviewReport {
  discussionProcess: string
  discussionDirections: string
  conclusions: string
  recommendations: string
}

export interface ChatReviewRunDetail extends ChatReviewRunSummary {
  chatId: string
  chatName: string
  speakers: ChatReviewSpeaker[]
  aiRunId: string | null
  sources: ChatReviewSource[]
  report: ChatReviewReport | null
}

export interface ChatReviewList {
  activeRun: ChatReviewRunSummary | null
  history: ChatReviewRunSummary[]
}

export interface CreateChatReviewRequest {
  startDate: string
  endDate: string
  focus?: string
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchChatReviewConfig = () =>
  data<ChatReviewConfig>(http.get('/api/chat-reviews/config'))

export const saveChatReviewConfig = (speakerIds: [string, string]) =>
  data<ChatReviewConfig>(http.put('/api/chat-reviews/config', { speakerIds }))

export const createChatReview = (payload: CreateChatReviewRequest) =>
  data<{ runId: string }>(http.post('/api/chat-reviews', payload))

export const fetchChatReviews = () => data<ChatReviewList>(http.get('/api/chat-reviews'))

export const fetchChatReview = (id: string) =>
  data<ChatReviewRunDetail>(http.get(`/api/chat-reviews/${id}`))

export const retryChatReview = (id: string) =>
  data<{ runId: string }>(http.post(`/api/chat-reviews/${id}/retry`))

export const rerunChatReview = (id: string) =>
  data<{ runId: string }>(http.post(`/api/chat-reviews/${id}/rerun`))
