import { http, type ApiResponse } from './http'
export interface TaskTimeRule {
  revision: number
  repeat: 'none' | 'daily' | 'weekly' | 'monthly' | 'yearly' | 'weekdays'
  interval: number
  mode: 'scheduled' | 'completion'
  anchor: 'start' | 'due'
  until: string
  reminderTime: string
  remindBefore: number[]
  anchorDay?: number
}
export interface TaskTimeState {
  rule: TaskTimeRule
  editable: boolean
  reason: string
  nextTaskId: string | null
  nextDate: string | null
  nextReminder: string | null
  lastDeliveredAt: string | null
  error: string
  schedulerEnabled: boolean
}
export const emptyTaskTimeRule = (): TaskTimeRule => ({ revision: 0, repeat: 'none', interval: 1, mode: 'scheduled', anchor: 'start', until: '', reminderTime: '09:00', remindBefore: [] })
const path = (id: string) => `/api/task-time/${encodeURIComponent(id)}`
export async function fetchTaskTime(id: string) { return (await http.get<ApiResponse<TaskTimeState>>(path(id))).data.data }
export async function saveTaskTime(id: string, rule: TaskTimeRule) { return (await http.put<ApiResponse<TaskTimeState>>(path(id), rule)).data.data }
export async function skipTaskOccurrence(id: string, revision: number, editVersion: string) { return (await http.post<ApiResponse<TaskTimeState>>(`${path(id)}/skip`, { revision, editVersion })).data.data }
export async function retryTaskTime(id: string) { return (await http.post<ApiResponse<TaskTimeState>>(`${path(id)}/retry`)).data.data }
export async function testTaskReminder(id: string, operation: string) { return (await http.post<ApiResponse<TaskTimeState>>(`${path(id)}/test/${encodeURIComponent(operation)}`)).data.data }
