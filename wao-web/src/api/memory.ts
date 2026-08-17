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

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchMemoryAccessTokens = () =>
  data<MemoryAccessToken[]>(http.get('/api/memory/tokens'))

export const createMemoryAccessToken = (name: string) =>
  data<CreatedMemoryAccessToken>(http.post('/api/memory/tokens', { name }))

export const revokeMemoryAccessToken = (tokenId: string) =>
  http.delete(`/api/memory/tokens/${tokenId}`)
