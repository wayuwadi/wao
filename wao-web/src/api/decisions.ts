import { http, type ApiResponse } from './http'

export interface MemoryRevision {
  id: string
  versionNo: number
  state: 'draft' | 'published' | 'superseded' | 'rejected'
  title: string
  content: string
  createdAt: string
  createdBy: string
  authorType?: 'human' | 'ai'
  sources?: unknown[]
}

export interface MemoryDocument {
  id: string
  scopeType: string
  scopeId: string
  category: string
  state: string
  currentPublishedRevisionId?: string
  revision: MemoryRevision
  createdBy: string
  createdAt: string
  updatedAt: string
  archivedAt?: string
}

export interface SaveMemoryPayload {
  scopeType: 'personal'
  scopeId: string
  category: 'decision'
  title: string
  content: string
  changeSummary?: string
  evidenceLinks: string[]
  baseRevisionId?: string
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchMemories = (params: { query?: string }) =>
  data<MemoryDocument[]>(http.get('/api/decisions', { params }))

export const fetchMemory = (id: string) =>
  data<MemoryDocument>(http.get(`/api/decisions/${id}`))

export const createMemory = (payload: SaveMemoryPayload) =>
  data<MemoryDocument>(http.post('/api/decisions', payload))

export const reviseMemory = (id: string, payload: SaveMemoryPayload) =>
  data<MemoryDocument>(http.post(`/api/decisions/${id}/revisions`, payload))

export const archiveMemory = (id: string) =>
  data<void>(http.post(`/api/decisions/${id}/archive`))
