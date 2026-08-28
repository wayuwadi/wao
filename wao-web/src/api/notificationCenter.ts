import { http, type ApiResponse } from './http'

export type NotificationEventType = 'TASK_CREATED' | 'TASK_COMPLETED' | 'AI_RUN' | 'LOGIN'

export interface NotificationEvent {
  id: string
  type: NotificationEventType
  title: string
  detail: string
  actorName: string | null
  occurredAt: string
  targetPath: string | null
  severity: 'INFO' | 'SUCCESS' | 'WARNING'
}

export interface MemberLoginUsage {
  memberId: string
  memberName: string
  color: string
  count7Days: number
  count30Days: number
  lastLoginAt: string | null
}

export interface NotificationCenterSnapshot {
  events: NotificationEvent[]
  loginUsage: MemberLoginUsage[]
  unreadCount: number
  readAt: string | null
}

export async function fetchNotificationCenter() {
  const response = await http.get<ApiResponse<NotificationCenterSnapshot>>(
    '/api/notification-center',
    {
      params: { _: Date.now() },
    },
  )
  return response.data.data
}

export async function markNotificationCenterRead() {
  const response = await http.put<ApiResponse<string>>('/api/notification-center/read')
  return response.data.data
}
