import { http, type ApiResponse } from './http'

export interface MemoryAccessToken {
  id: string
  name: string
  tokenPrefix: string
  createdAt: string
  lastUsedAt?: string
}

export interface CreatedMemoryAccessToken {
  token: MemoryAccessToken
  secret: string
}

export interface MemoryGrant {
  scopeType: 'personal' | 'organization' | 'project'
  scopeId: string
  canRead: boolean
  canPropose: boolean
  canDirectWrite: boolean
}

export type MemoryCategory =
  | 'preference'
  | 'responsibility'
  | 'plan'
  | 'decision'
  | 'context'
  | 'experience'

export type MemoryMediaType = 'text' | 'image' | 'audio' | 'video' | 'file'

export interface PersonalMemory {
  id: string
  title: string
  content: string
  category: MemoryCategory
  captureMode: 'manual' | 'ai'
  mediaType: MemoryMediaType
  fileName?: string
  contentType?: string
  byteSize?: number
  createdAt: string
  updatedAt: string
  downloadUrl?: string
}

export interface SavePersonalMemory {
  title: string
  content: string
  category: MemoryCategory
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchMemoryAccessTokens = () =>
  data<MemoryAccessToken[]>(http.get('/api/memory/tokens'))

export const createMemoryAccessToken = (name: string) =>
  data<CreatedMemoryAccessToken>(http.post('/api/memory/tokens', { name }))

export const revokeMemoryAccessToken = (tokenId: string) =>
  http.delete(`/api/memory/tokens/${tokenId}`)

export const fetchMemoryGrants = (tokenId: string) =>
  data<MemoryGrant[]>(http.get(`/api/memory/tokens/${tokenId}/grants`))

export const saveMemoryGrants = (tokenId: string, grants: MemoryGrant[]) =>
  data<MemoryGrant[]>(http.put(`/api/memory/tokens/${tokenId}/grants`, { grants }))

export const fetchPersonalMemories = (query = '', category = '') =>
  data<PersonalMemory[]>(http.get('/api/memory/entries', { params: { query: query || undefined, category: category || undefined } }))

export const createPersonalMemory = (payload: SavePersonalMemory) =>
  data<PersonalMemory>(http.post('/api/memory/entries', payload))

export const updatePersonalMemory = (id: string, payload: SavePersonalMemory) =>
  data<PersonalMemory>(http.patch(`/api/memory/entries/${id}`, payload))

export async function uploadPersonalMemory(payload: SavePersonalMemory, file: File) {
  const form = new FormData()
  form.append('title', payload.title)
  form.append('content', payload.content)
  form.append('category', payload.category)
  form.append('file', file)
  return data<PersonalMemory>(http.post('/api/memory/entries/upload', form, { timeout: 120_000 }))
}

export const deletePersonalMemory = (id: string) => http.delete(`/api/memory/entries/${id}`)
