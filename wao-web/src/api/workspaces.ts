import { http, type ApiResponse } from './http'

export interface WorkspaceLink {
  id: string
  title: string
  url: string
  description: string
  environment: string
  icon: string
  order: number
  favorite: boolean
}

export interface WorkspaceGroup {
  id: string
  name: string
  icon: string
  order: number
  links: WorkspaceLink[]
}

export interface WorkspaceSummary {
  id: string
  name: string
  description: string
  projectId: string | null
  projectName: string
  groupCount: number
  linkCount: number
  updatedAt: string
}

export interface WorkspaceDetail {
  id: string
  name: string
  description: string
  projectId: string | null
  projectName: string
  version: number
  groups: WorkspaceGroup[]
  createdAt: string
  updatedAt: string
}

export interface WorkspaceInput {
  name: string
  description: string
  projectId: string | null
}

const data = <T>(request: Promise<{ data: ApiResponse<T> }>) =>
  request.then((response) => response.data.data)

export const fetchWorkspaces = () =>
  data(http.get<ApiResponse<WorkspaceSummary[]>>('/api/workspaces'))

export const fetchWorkspace = (id: string) =>
  data(http.get<ApiResponse<WorkspaceDetail>>(`/api/workspaces/${id}`))

export const createWorkspace = (input: WorkspaceInput) =>
  data(http.post<ApiResponse<WorkspaceDetail>>('/api/workspaces', input))

export const saveWorkspace = (id: string, input: WorkspaceInput & { groups: WorkspaceGroup[] }) =>
  data(http.put<ApiResponse<WorkspaceDetail>>(`/api/workspaces/${id}`, input))

export const deleteWorkspace = (id: string) =>
  data(http.delete<ApiResponse<null>>(`/api/workspaces/${id}`))
