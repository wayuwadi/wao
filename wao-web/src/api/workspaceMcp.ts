import { http, type ApiResponse } from './http'

export interface WorkspaceMcpToken {
  id: string
  name: string
  tokenPrefix: string
  canWrite: boolean
  canPublishHostedPage: boolean
  createdAt: string
  lastUsedAt?: string
}

export interface CreatedWorkspaceMcpToken {
  token: WorkspaceMcpToken
  secret: string
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchWorkspaceMcpTokens = () =>
  data<WorkspaceMcpToken[]>(http.get('/api/workspace-mcp/tokens'))

export const createWorkspaceMcpToken = (name: string, canWrite: boolean, canPublishHostedPage = false) =>
  data<CreatedWorkspaceMcpToken>(http.post('/api/workspace-mcp/tokens', { name, canWrite, canPublishHostedPage }))

export const revokeWorkspaceMcpToken = (id: string) =>
  http.delete(`/api/workspace-mcp/tokens/${encodeURIComponent(id)}`)
