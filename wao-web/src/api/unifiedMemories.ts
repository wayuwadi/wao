import { http, type ApiResponse } from './http'

export type MemoryScope = 'personal' | 'organization' | 'project' | 'machine' | 'application'
export type MemoryState = 'active' | 'draft' | 'rejected' | 'archived'

export interface MemorySource {
  type: string
  sourceId?: string
  url?: string
  excerptHash?: string
}

export interface MemoryRevision {
  id: string
  versionNo: number
  state: 'draft' | 'published' | 'superseded' | 'rejected'
  title: string
  content: string
  changeSummary?: string
  baseRevisionId?: string
  authorType: 'human' | 'ai'
  createdBy: string
  createdAt: string
  publishedBy?: string
  publishedAt?: string
  expiresAt?: string
  sources: MemorySource[]
}

export interface MemoryDocument {
  id: string
  scopeType: MemoryScope
  scopeId: string
  category: string
  state: MemoryState
  currentPublishedRevisionId?: string
  revision: MemoryRevision
  createdBy: string
  createdAt: string
  updatedAt: string
  archivedAt?: string
}

export interface SaveMemoryPayload {
  scopeType: MemoryScope
  scopeId?: string
  category: string
  title: string
  content: string
  changeSummary?: string
  evidenceLinks: string[]
  expiresAt?: string
  baseRevisionId?: string
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchMemories = (params: {
  scopeType: MemoryScope
  scopeId?: string
  query?: string
  category?: string
  state?: string
}) => data<MemoryDocument[]>(http.get('/api/memories', { params }))

export const fetchMemory = (id: string, revision?: string) =>
  data<MemoryDocument>(http.get(`/api/memories/${id}`, { params: { revision } }))

export const fetchMemoryRevisions = (id: string) =>
  data<MemoryRevision[]>(http.get(`/api/memories/${id}/revisions`))

export const createMemory = (payload: SaveMemoryPayload) =>
  data<MemoryDocument>(http.post('/api/memories', payload))

export const reviseMemory = (id: string, payload: SaveMemoryPayload) =>
  data<MemoryDocument>(http.post(`/api/memories/${id}/revisions`, payload))

export const publishMemoryRevision = (id: string) =>
  data<MemoryDocument>(http.post(`/api/memory-revisions/${id}/publish`))

export const rejectMemoryRevision = (id: string) =>
  data<MemoryDocument>(http.post(`/api/memory-revisions/${id}/reject`))

export const archiveMemory = (id: string) =>
  data<void>(http.post(`/api/memories/${id}/archive`))
