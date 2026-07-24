import { http, type ApiResponse } from './http'
import type {
  Area,
  AutomationJob,
  CalendarEvent,
  Doc,
  FileItem,
  ImageItem,
  ImageRef,
  KnowledgeBase,
  Member,
  Org,
  Project,
  ScheduleItem,
  Suggestion,
  Task,
  VaultGroup,
} from '@/types'

/** 目标胶囊（首页右上）。 */
export interface GoalDto {
  id: string
  name: string
  color: string
  pct: number
}

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

// 内容 / 参考域（只读）
export const fetchKbs = () => get<KnowledgeBase[]>('/api/kbs')
export const fetchDocs = () => get<Doc[]>('/api/docs')
export const fetchJobs = () => get<AutomationJob[]>('/api/jobs')
export const fetchFiles = () => get<FileItem[]>('/api/files')
export const fetchImages = () => get<ImageItem[]>('/api/images')
export const fetchVault = () => get<VaultGroup[]>('/api/vault')
export const fetchEvents = () => get<CalendarEvent[]>('/api/calendar/events')
export const fetchSchedule = () => get<ScheduleItem[]>('/api/schedule')
export const fetchSuggestions = () => get<Suggestion[]>('/api/suggestions')
export const fetchGoals = () => get<GoalDto[]>('/api/goals')

export interface CreateTaskPayload {
  title: string
  project: string
  pr: string
  owner: string
  due: string
  desc?: string
  images?: ImageRef[]
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

/** 全量替换任务（本地变更后的写透）。 */
export async function apiPutTask(id: string, task: Task): Promise<Task> {
  const res = await http.put<ApiResponse<Task>>(`/api/tasks/${id}`, task)
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

export interface PatchProjectPayload {
  name?: string
  area?: string
  goal?: string
  icon?: string
}
/** 局部更新项目（名称 / 领域 / 目标 / 图标）。 */
export async function apiPatchProject(id: string, payload: PatchProjectPayload): Promise<Project> {
  const res = await http.patch<ApiResponse<Project>>(`/api/projects/${id}`, payload)
  return res.data.data
}

/** 删除项目。 */
export async function apiDeleteProject(id: string): Promise<void> {
  await http.delete<ApiResponse<void>>(`/api/projects/${id}`)
}
