import { http, type ApiResponse } from './http'

export interface NotificationReminder {
  id: string
  title: string
  content: string
  time: string
  enabled: boolean
  workdayOnly: boolean
  lastResult: string
  nextFireAt: string | null
}

export interface NotificationReminderSettings {
  canManageSystem: boolean
  reminders: NotificationReminder[]
}

export type SaveNotificationReminder = Pick<
  NotificationReminder,
  'title' | 'content' | 'time' | 'enabled'
>

export async function fetchNotificationReminders() {
  const response = await http.get<ApiResponse<NotificationReminderSettings>>(
    '/api/notification-reminders',
  )
  return response.data.data
}

export async function createNotificationReminder(payload: SaveNotificationReminder) {
  const response = await http.post<ApiResponse<NotificationReminder>>(
    '/api/notification-reminders',
    payload,
  )
  return response.data.data
}

export async function updateNotificationReminder(id: string, payload: SaveNotificationReminder) {
  const response = await http.put<ApiResponse<NotificationReminder>>(
    `/api/notification-reminders/${encodeURIComponent(id)}`,
    payload,
  )
  return response.data.data
}

export async function deleteNotificationReminder(id: string) {
  await http.delete(`/api/notification-reminders/${encodeURIComponent(id)}`)
}

export async function testNotificationReminder(id: string) {
  const response = await http.post<ApiResponse<NotificationReminder>>(
    `/api/notification-reminders/${encodeURIComponent(id)}/test`,
  )
  return response.data.data
}
