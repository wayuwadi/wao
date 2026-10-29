import { http, type ApiResponse } from './http'
export interface TaskLabels { taskId: string; subId: string; revision: number; tags: string[] }
export async function fetchTaskLabels() { return (await http.get<ApiResponse<TaskLabels[]>>('/api/task-labels')).data.data }
export async function saveTaskLabels(value: TaskLabels) { return (await http.put<ApiResponse<TaskLabels>>(`/api/task-labels/${encodeURIComponent(value.taskId)}`, value)).data.data }
