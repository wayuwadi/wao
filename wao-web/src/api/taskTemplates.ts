import { http, type ApiResponse } from './http'
import type { Task } from '@/types'

export interface TaskTemplateStep { key:string; parentKey:string | null; title:string; description:string }
export interface TaskTemplateContent { name:string; title:string; description:string; steps:TaskTemplateStep[] }
export interface TaskTemplate { id:string; content:TaskTemplateContent }

export async function fetchTaskTemplates():Promise<TaskTemplate[]> {
  return (await http.get<ApiResponse<TaskTemplate[]>>('/api/task-templates')).data.data
}
export async function saveTaskTemplate(id:string, content:TaskTemplateContent, accountId:string):Promise<TaskTemplate> {
  return (await http.put<ApiResponse<TaskTemplate>>(`/api/task-templates/${encodeURIComponent(id)}`,{accountId,content})).data.data
}
export async function deleteTaskTemplate(id:string, accountId:string):Promise<void> {
  await http.delete<ApiResponse<void>>(`/api/task-templates/${encodeURIComponent(id)}`,{headers:{'X-WAO-Account-Id':accountId}})
}
export async function createTaskFromTemplate(taskId:string, accountId:string, templateId:string, projectId:string):Promise<Task> {
  return (await http.put<ApiResponse<Task>>(`/api/task-templates/creations/${encodeURIComponent(taskId)}`,{accountId,templateId,projectId})).data.data
}
