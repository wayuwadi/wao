import { http, type ApiResponse } from './http'
import type { NoteThinkingImage } from './noteAiWorkflows'
import type { Doc, RichDocContent } from '@/types'
export type CompanionStage = 'title' | 'image' | 'polish' | 'tags' | 'thinking'
export interface CompanionOverview {
  available: boolean
  message: string
  queued: number
  running: number
  unread: number
  mode: 'AUTO'
  version: number
  capabilityVersion: number
}
export interface AiArtifact {
  id: string
  stage: CompanionStage
  sourceRev: number
  sourceTitle: string
  sourceText: string
  currentText?: string
  currentRev?: number
  thinkingImage?: NoteThinkingImage
  resultText: string
  tagRecommendations?: { name: string; reason: string }[]
  application: string
  validity: string
  seen: boolean
  createdAt: string
  removedChars: number
  addedChars: number
  model?: string
  effort?: string
  execution?: string
  reasonCode?: string
  reason?: string
  queuedAt?: string
  startedAt?: string
  finishedAt?: string
}
export interface AiAttempt {
  id: string
  stage: CompanionStage
  state: string
  reasonCode: string
  message: string
  createdAt: string
  updatedAt: string
  cancelling: boolean
  runStatus?: string
  queuedAt?: string
  startedAt?: string
  finishedAt?: string
}
export interface AiResults {
  items: AiArtifact[]
  nextCursor: string
  attempts: AiAttempt[]
}
export interface ChatSource {
  id: string
  rev: number
  title: string
}
export interface NoteChat {
  messages: { id: string; role: string; content: string; createdAt: string }[]
  sources: ChatSource[]
  runs: {
    id: string
    userMessageId?: string
    sourcesJson?: string
    status: string
    error: string | null
  }[]
}
const root = '/api/docs/notes'
const unwrap = async <T>(p: Promise<{ data: ApiResponse<T> }>): Promise<T> => (await p).data.data
export const fetchCompanion = () => unwrap<CompanionOverview>(http.get(`${root}/ai-companion`))
export const fetchAiResults = (docId: string, cursor?: string) =>
  unwrap<AiResults>(http.get(`${root}/${docId}/ai-results`, { params: { cursor } }))
export const fetchAiReceipts = () =>
  unwrap<{ docId: string; unread: number }[]>(http.get(`${root}/ai-receipts`))
export const actOnArtifact = (
  id: string,
  action: 'seen' | 'apply' | 'restore' | 'decline',
  expectedRev?: number,
) => unwrap<Doc>(http.post(`${root}/ai-artifacts/${id}/${action}`, { expectedRev }))
export const cancelNoteAi = (id: string, expectedRev: number) =>
  http.post(`${root}/${id}/ai-cancel`, { expectedRev })
export const fetchNoteChat = (id: string) =>
  unwrap<NoteChat>(http.get(`${root}/${id}/ai-conversation`))
export const askNoteChat = (
  id: string,
  message: string,
  expectedRev: number,
  requestId: string,
  selectedSourceIds: string[],
) =>
  unwrap<NoteChat>(
    http.post(`${root}/${id}/ai-conversation`, {
      message,
      expectedRev,
      requestId,
      selectedSourceIds,
    }),
  )
export const saveCompanionDraft = (input: {
  messageId: string
  docId: string
  existing: boolean
  expectedRev: number
  title: string
  content: RichDocContent
  blocks: Doc['blocks']
  tags: string[]
}) => unwrap<Doc>(http.post(`${root}/ai-transfer`, input))
export function companionError(error: unknown): string {
  const e = error as {
    response?: { status?: number; data?: { message?: string } }
    message?: string
  }
  return !e.response?.data?.message && [404, 503].includes(e.response?.status ?? 0)
    ? '伴读服务尚未就绪，请稍后重试；小记仍可正常使用。'
    : e.response?.data?.message || e.message || '操作未完成，请重试；输入仍保留。'
}
