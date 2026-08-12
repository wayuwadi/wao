import { http, type ApiResponse } from './http'

export interface ActionPlanItem {
  id: string
  title: string
  weekdays: number[]
  active: boolean
  scheduled: boolean
  completed: boolean
}

export interface ActionPlan {
  id: string
  title: string
  vision: string
  startDate: string
  targetDate: string
  reminderTime: string | null
  reminderEnabled: boolean
  status: 'ACTIVE' | 'COMPLETED'
  selectedDate: string
  items: ActionPlanItem[]
  note: string
  scheduledCount: number
  completedCount: number
  currentStreak: number
  totalCompletedDays: number
  reminderState: 'OFF' | 'WAITING' | 'SENT' | 'FAILED'
}

export interface SaveActionPlan {
  title: string
  vision: string
  startDate: string
  targetDate: string
  reminderTime: string | null
  reminderEnabled: boolean
  items: Array<{ id?: string; title: string; weekdays: number[] }>
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchActionPlans = (date: string) =>
  data<ActionPlan[]>(http.get('/api/action-plans', { params: { date } }))

export const createActionPlan = (body: SaveActionPlan) =>
  data<ActionPlan>(http.post('/api/action-plans', body))

export const updateActionPlan = (id: string, body: SaveActionPlan) =>
  data<ActionPlan>(http.put(`/api/action-plans/${id}`, body))

export const setActionPlanCompleted = (id: string, completed: boolean) =>
  data<ActionPlan>(http.patch(`/api/action-plans/${id}/status`, undefined, { params: { completed } }))

export const toggleActionCheckin = (planId: string, itemId: string, date: string) =>
  data<ActionPlan>(http.post(`/api/action-plans/${planId}/items/${itemId}/checkins/${date}`))

export const saveActionNote = (planId: string, date: string, content: string) =>
  data<ActionPlan>(http.put(`/api/action-plans/${planId}/notes/${date}`, { content }))
