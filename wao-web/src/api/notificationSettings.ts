import { http, type ApiResponse } from './http'

export interface SystemNotificationPolicy {
  enabled: boolean
  taskActivity: boolean
  taskSuccess: boolean
  aiRunSuccess: boolean
  aiRunFailure: boolean
  manualTask: boolean
  automation: boolean
}

export interface PersonalNotificationPolicy {
  enabled: boolean
  taskCreated: boolean
  taskCompleted: boolean
  aiRunSuccess: boolean
  aiRunFailure: boolean
  loginActivity: boolean
}

export interface NotificationSettings {
  canManageSystem: boolean
  system: SystemNotificationPolicy
  personal: PersonalNotificationPolicy
}

export async function fetchNotificationSettings() {
  const response = await http.get<ApiResponse<NotificationSettings>>('/api/notification-settings')
  return response.data.data
}

export async function saveNotificationSettings(payload: {
  system?: SystemNotificationPolicy
  personal: PersonalNotificationPolicy
}) {
  const response = await http.put<ApiResponse<NotificationSettings>>('/api/notification-settings', payload)
  return response.data.data
}
