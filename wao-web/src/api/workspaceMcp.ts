import { http, type ApiResponse } from './http'

export interface WorkspaceMcpToken {
  id: string
  name: string
  tokenPrefix: string
  canWrite: boolean
  canPublishHostedPage: boolean
  createdAt: string
  lastUsedAt?: string | null
  lastVerifiedAt?: string | null
  lastVerificationFailedAt?: string | null
  verifiedToolCount?: number | null
  permissionMode?: 'legacy' | 'granular'
  permissions?: string[] | null
  projectIds?: string[] | null
  knowledgeBaseIds?: string[] | null
  clientType?: 'CLI' | 'MCP' | null
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

export const createWorkspaceMcpToken = (
  name: string,
  canWrite: boolean,
  canPublishHostedPage = false,
) =>
  data<CreatedWorkspaceMcpToken>(
    http.post('/api/workspace-mcp/tokens', { name, canWrite, canPublishHostedPage }),
  )

export const revokeWorkspaceMcpToken = (id: string) =>
  http.delete(`/api/workspace-mcp/tokens/${encodeURIComponent(id)}`)

export interface WorkspaceCapability {
  name: string
  title: string
  description: string
  group: 'projects' | 'tasks' | 'knowledge' | 'documents' | 'pages'
  cli: string
  mcp: string | null
  readOnly: boolean
  inputSchema?: Record<string, unknown>
}

export interface WorkspaceGrantRequest {
  name: string
  clientType: 'CLI' | 'MCP'
  permissions: string[]
  projectIds: string[] | null
  knowledgeBaseIds: string[] | null
}

export const fetchWorkspaceCapabilities = () =>
  data<{ capabilities: WorkspaceCapability[] }>(http.get('/api/public/capabilities'))

export const fetchWorkspaceResources = () =>
  data<{
    projects: { id: string; name: string }[]
    knowledgeBases: { id: string; name: string }[]
  }>(http.get('/api/workspace-mcp/resources'))

export const createGranularWorkspaceToken = (body: WorkspaceGrantRequest) =>
  data<CreatedWorkspaceMcpToken>(http.post('/api/workspace-mcp/tokens', body))
