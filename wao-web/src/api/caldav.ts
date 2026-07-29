import { http, type ApiResponse } from './http'

/** CalDAV 订阅日历（属于某个账户）。 */
export interface CaldavCalendar {
  id: string
  name: string
  color: string
  enabled: boolean
}

/** CalDAV 账户。凭据只在添加时上送一次，接口从不回传。 */
export interface CaldavAccount {
  id: string
  name: string
  serverUrl: string
  username: string
  status: 'ok' | 'error'
  lastSyncAt: string | null
  syncError: string | null
  calendars: CaldavCalendar[]
}

/** 订阅日历里的一条事件（服务端已展开重复规则）。 */
export interface CaldavEvent {
  id: string
  calendarId: string
  calendarName: string
  color: string
  summary: string
  location: string | null
  start: string
  end: string | null
  allDay: boolean
}

async function get<T>(url: string): Promise<T> {
  const res = await http.get<ApiResponse<T>>(url)
  return res.data.data
}

export const fetchCaldavAccounts = () => get<CaldavAccount[]>('/api/caldav/accounts')

export interface AddCaldavAccountPayload {
  name?: string
  serverUrl: string
  username: string
  password: string
}
/** 添加账户会真实连服务器做日历发现并做首次同步，弱网下可能要十几秒。 */
export async function apiAddCaldavAccount(payload: AddCaldavAccountPayload): Promise<CaldavAccount> {
  const res = await http.post<ApiResponse<CaldavAccount>>('/api/caldav/accounts', payload, { timeout: 60_000 })
  return res.data.data
}

export async function apiDeleteCaldavAccount(id: string): Promise<void> {
  await http.delete<ApiResponse<void>>(`/api/caldav/accounts/${id}`)
}

export async function apiSyncCaldavAccount(id: string): Promise<CaldavAccount> {
  const res = await http.post<ApiResponse<CaldavAccount>>(`/api/caldav/accounts/${id}/sync`, undefined, { timeout: 60_000 })
  return res.data.data
}

export async function apiToggleCaldavCalendar(id: string, enabled: boolean): Promise<CaldavCalendar> {
  const res = await http.patch<ApiResponse<CaldavCalendar>>(`/api/caldav/calendars/${id}`, { enabled }, { timeout: 60_000 })
  return res.data.data
}

export const fetchCaldavEvents = (from: string, to: string) =>
  get<CaldavEvent[]>(`/api/caldav/events?from=${from}&to=${to}`)
