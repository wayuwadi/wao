import { http, type ApiResponse } from './http'
export type FocusMode = 'pomodoro' | 'stopwatch' | 'break'
export interface FocusSession {
  id: string; mode: FocusMode; status: 'running' | 'paused' | 'finished'; revision: number
  taskId: string; subId: string; taskTitle: string; note: string; noteId: string | null
  startedAt: number; runningSince: number; elapsedMs: number; plannedMs: number; endedAt: number
}
export interface FocusState {
  serverNow: number; active: FocusSession | null; history: FocusSession[]
  stats: { todayCount: number; todayMs: number; totalCount: number; totalMs: number }
}
export interface FocusStart { mode: FocusMode; minutes: number; taskId: string; subId: string; note: string }
export const fetchFocus = async () => (await http.get<ApiResponse<FocusState>>('/api/focus')).data.data
export const startFocus = async (id: string, body: FocusStart) => (await http.put<ApiResponse<FocusSession>>(`/api/focus/${id}`, body)).data.data
export const changeFocus = async (id: string, body: { revision: number; action: string; note: string }) => (await http.post<ApiResponse<FocusSession>>(`/api/focus/${id}`, body)).data.data
export function focusElapsed(session: FocusSession, now: number) {
  const elapsed = session.elapsedMs + (session.status === 'running' ? Math.max(0, now - session.runningSince) : 0)
  return session.plannedMs ? Math.min(elapsed, session.plannedMs) : elapsed
}
export function focusTime(ms: number) {
  const seconds = Math.max(0, Math.ceil(ms / 1000))
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`
}
