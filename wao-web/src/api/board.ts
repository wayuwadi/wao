import { http, type ApiResponse } from './http'
import type { Area, Member, Org, Project, Task } from '@/types'

/**
 * 后端读写接口封装。组件不直接调用，统一经由 data store。
 * 契约见 docs/api/*.yaml；响应为 ApiResponse<T>，拦截器已解包 code!=0 的错误。
 */

async function get<T>(url: string): Promise<T> {
  const res = await http.get<ApiResponse<T>>(url)
  return res.data.data
}

export const fetchMembers = () => get<Member[]>('/api/members')
export const fetchOrg = () => get<Org>('/api/org')
export const fetchAreas = () => get<Area[]>('/api/areas')
export const fetchProjects = () => get<Project[]>('/api/projects')
export const fetchTasks = () => get<Task[]>('/api/tasks')

export interface CreateTaskPayload {
  title: string
  project: string
  pr: string
  owner: string
  due: string
}
export async function apiCreateTask(payload: CreateTaskPayload): Promise<Task> {
  const res = await http.post<ApiResponse<Task>>('/api/tasks', payload)
  return res.data.data
}

export interface PatchTaskPayload {
  title?: string
  status?: string
  pr?: string
  owner?: string
  desc?: string
}
export async function apiPatchTask(id: string, payload: PatchTaskPayload): Promise<Task> {
  const res = await http.patch<ApiResponse<Task>>(`/api/tasks/${id}`, payload)
  return res.data.data
}

export interface CreateProjectPayload {
  name: string
  area: string
  goal: string
  visAll: boolean
}
export async function apiCreateProject(payload: CreateProjectPayload): Promise<Project> {
  const res = await http.post<ApiResponse<Project>>('/api/projects', payload)
  return res.data.data
}
