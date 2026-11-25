import { http, type ApiResponse } from './http'

export type MemoryKind = 'preference' | 'experience'
export type MemoryState = 'draft' | 'active' | 'archived' | 'rejected'
export type MemorySource = 'manual' | 'import' | 'codex' | 'agy'

export interface MemoryInput {
  kind: MemoryKind
  title: string
  content: string
  project: string
  source: MemorySource
  sourceSummary: string
}

export interface AgentMemory extends MemoryInput {
  id: string
  revisionId: string
  version: number
  state: MemoryState
  startup: boolean
  createdAt: string
  updatedAt: string
}

export interface MemoryFilters {
  state: MemoryState
  query?: string
  project?: string
  source?: string
  offset?: number
}

export interface MemoryReview {
  action: 'publish' | 'revise' | 'merge' | 'reject' | 'archive'
  expectedRevision: string
  title?: string
  content?: string
  project?: string
  startup?: boolean
  targetId?: string
  expectedTargetRevision?: string
}

const BASE = '/api/agent-memories'

export async function listAgentMemories(params: MemoryFilters) {
  return (await http.get<ApiResponse<{ items: AgentMemory[]; hasMore: boolean }>>(BASE, { params })).data.data
}

export async function agentMemoryHistory(id: string) {
  return (await http.get<ApiResponse<AgentMemory[]>>(`${BASE}/${encodeURIComponent(id)}/history`)).data.data
}

export async function proposeAgentMemory(input: MemoryInput) {
  return (await http.post<ApiResponse<AgentMemory>>(BASE, input)).data.data
}

export async function reviewAgentMemory(id: string, input: MemoryReview) {
  return (await http.post<ApiResponse<AgentMemory>>(`${BASE}/${encodeURIComponent(id)}/review`, input)).data.data
}
