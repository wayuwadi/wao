import { http, type ApiResponse } from './http'
import type { ProjectMemory, ProjectMemoryCategory } from '@/types'
import type { MemoryDocument } from './decisions'

export interface SaveProjectMemoryPayload {
  category: ProjectMemoryCategory
  title: string
  content: string
  evidenceLinks: string[]
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchProjectMemories = (projectId: string, query = '', category = '') =>
  data<ProjectMemory[]>(http.get(`/api/projects/${projectId}/memories`, {
    params: { query: query || undefined, category: category || undefined },
  }))

export const fetchProjectMemoryCandidates = (projectId: string) =>
  data<MemoryDocument[]>(http.get(`/api/projects/${projectId}/memories/candidates`))

export const publishProjectMemoryCandidate = (projectId: string, revisionId: string) =>
  data<MemoryDocument>(http.post(`/api/projects/${projectId}/memories/candidates/${revisionId}/publish`))

export const rejectProjectMemoryCandidate = (projectId: string, revisionId: string) =>
  data<MemoryDocument>(http.post(`/api/projects/${projectId}/memories/candidates/${revisionId}/reject`))

export const createProjectMemory = (projectId: string, payload: SaveProjectMemoryPayload) =>
  data<ProjectMemory>(http.post(`/api/projects/${projectId}/memories`, payload))

export const updateProjectMemory = (projectId: string, memoryId: string, payload: SaveProjectMemoryPayload) =>
  data<ProjectMemory>(http.patch(`/api/projects/${projectId}/memories/${memoryId}`, payload))

export const deleteProjectMemory = (projectId: string, memoryId: string) =>
  data<void>(http.delete(`/api/projects/${projectId}/memories/${memoryId}`))
