import { fetchPreference, savePreference } from './favorites'
import { http, type ApiResponse } from './http'
import {
  DEFAULT_WORKDAY_RHYTHM,
  cloneRhythmConfig,
  normalizeRhythmConfig,
  parseRhythmConfig,
  parseRhythmDay,
  rhythmConfigError,
  type WorkdayRhythmConfig,
  type WorkdayRhythmDay,
} from '@/utils/workdayRhythm'
import type { Doc, DocBlock } from '@/types'

const CONFIG_KEY = 'workday-rhythm-config-v1'
const dayKey = (date: string) => `workday-rhythm-day-${date}`

export async function fetchWorkdayRhythm(): Promise<WorkdayRhythmConfig> {
  const raw = await fetchPreference(CONFIG_KEY)
  return parseRhythmConfig(raw) ?? cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
}

export async function saveWorkdayRhythm(value: WorkdayRhythmConfig): Promise<WorkdayRhythmConfig> {
  const error = rhythmConfigError(value)
  if (error) throw new Error(error)
  const normalized = normalizeRhythmConfig(value)
  await savePreference(CONFIG_KEY, JSON.stringify(normalized))
  return normalized
}

export async function fetchWorkdayRhythmDay(
  date: string,
  weekday: number,
  validIds: string[],
  validEnergyIds: string[],
): Promise<WorkdayRhythmDay> {
  const raw = await fetchPreference(dayKey(date))
  return parseRhythmDay(raw, date, weekday, validIds, validEnergyIds)
}

export async function saveWorkdayRhythmDay(value: WorkdayRhythmDay): Promise<void> {
  await savePreference(dayKey(value.date), JSON.stringify(value))
}

export type WorkdayRhythmEnergyAction = string

export interface EnergyNotificationResult {
  sent: boolean
  chatName: string
}

export interface WorkdayRhythmHistoryEnergy {
  id: string
  title: string
  mode: 'COUNT' | 'DAILY_ONCE'
  count: number
}

export interface WorkdayRhythmHistoryDay {
  date: string
  workdayActive: boolean
  focus: string[]
  energyItems: WorkdayRhythmHistoryEnergy[]
  checklist: Array<{
    title: string
    category: 'WORK' | 'LIFE'
    completedAt: string | null
    completedOnTime: boolean | null
  }>
  documentId: string | null
  documentKbId: string | null
  documentTitle: string | null
}

export interface WorkdayRhythmHistory {
  from: string
  to: string
  items: WorkdayRhythmHistoryDay[]
  skippedCount: number
}

/** 读取当前成员已保存的每日目标与精力补给次数，不补造没有记录的日期。 */
export async function fetchWorkdayRhythmHistory(
  from: string,
  to: string,
): Promise<WorkdayRhythmHistory> {
  const res = await http.get<ApiResponse<WorkdayRhythmHistory>>('/api/workday-rhythm/history', {
    params: { from, to },
  })
  return res.data.data
}

/** 当天记录成功落库后，由服务端复核本人记录并用服务端飞书凭据向本人配置的群发通知。 */
export async function sendEnergyNotification(
  action: WorkdayRhythmEnergyAction,
  date: string,
): Promise<EnergyNotificationResult> {
  const res = await http.post<ApiResponse<EnergyNotificationResult>>(
    '/api/workday-rhythm/energy-notifications',
    { action, date },
  )
  return res.data.data
}

export type RhythmChecklistStatus = 'UPCOMING' | 'DUE' | 'LATE' | 'ON_TIME' | 'COMPLETED_LATE'
export type RhythmChecklistKind = 'PLAN' | 'CLOCK_IN' | 'CLOCK_OUT' | 'TIANREN' | 'CUSTOM'
export type RhythmScheduleType = 'DAILY' | 'WORKDAY' | 'WEEKDAYS'

export interface RhythmSettings {
  followAlternateWeek: boolean
  documentKbId: string | null
  documentFolderId: string | null
  documentTemplateId: string | null
  documentTitlePattern: string
  tianrenEnabled: boolean
  tianrenRequired: boolean
}

export interface RhythmChecklistItem {
  id: string
  kind: RhythmChecklistKind
  category: 'WORK' | 'LIFE'
  title: string
  scheduleType: RhythmScheduleType
  weekdays: number[]
  startTime: string
  dueTime: string
  dueNextDay: boolean
  active: boolean
  order: number
  notifyDirect: boolean
  notifyChat: boolean
  chatId: string | null
  chatName: string | null
  notifyWebhook: boolean
  webhookId: string | null
  webhookName: string | null
  reminderTime: string | null
  reminderNextDay: boolean
}

export interface RhythmOccurrence {
  id: string
  itemId: string
  occurrenceDate: string
  kind: RhythmChecklistKind
  category: 'WORK' | 'LIFE'
  title: string
  startTime: string
  dueTime: string
  dueNextDay: boolean
  completedAt: string | null
  completedOnTime: boolean | null
  status: RhythmChecklistStatus
}

export interface RhythmDashboard {
  date: string
  workday: boolean
  workdayOverride: boolean | null
  workWeekLabel: string
  checklist: RhythmOccurrence[]
  completedCount: number
  totalCount: number
  document: {
    id: string | null
    state: 'UNCONFIGURED' | 'READY' | 'CREATED' | 'TRASHED' | 'MISSING'
    kbId: string | null
    folderId: string | null
    title: string | null
  }
  settings: RhythmSettings
}

export type SaveRhythmChecklistItem = Omit<RhythmChecklistItem, 'id' | 'kind'>

async function responseData<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchRhythmDashboard = (date: string) =>
  responseData<RhythmDashboard>(http.get('/api/workday-rhythm/dashboard', { params: { date } }))
export const fetchRhythmSettings = () =>
  responseData<RhythmSettings>(http.get('/api/workday-rhythm/settings'))
export const saveRhythmSettings = (body: RhythmSettings) =>
  responseData<RhythmSettings>(http.put('/api/workday-rhythm/settings', body))
export const fetchRhythmChecklistItems = () =>
  responseData<RhythmChecklistItem[]>(http.get('/api/workday-rhythm/checklist-items'))
export const createRhythmChecklistItem = (body: SaveRhythmChecklistItem) =>
  responseData<RhythmChecklistItem>(http.post('/api/workday-rhythm/checklist-items', body))
export const updateRhythmChecklistItem = (id: string, body: SaveRhythmChecklistItem) =>
  responseData<RhythmChecklistItem>(http.patch(`/api/workday-rhythm/checklist-items/${id}`, body))
export const deleteRhythmChecklistItem = (id: string) =>
  responseData<void>(http.delete(`/api/workday-rhythm/checklist-items/${id}`))
export const setRhythmWorkdayStatus = (date: string, active: boolean | null) =>
  responseData<RhythmDashboard>(http.put(`/api/workday-rhythm/days/${date}/workday-status`, { active }))
export const saveRhythmFocus = (date: string, focus: string[]) =>
  responseData<RhythmDashboard>(http.put(`/api/workday-rhythm/days/${date}/focus`, { focus }))
export const setRhythmOccurrenceCompleted = (id: string, completed: boolean) =>
  responseData<RhythmOccurrence>(http.put(`/api/workday-rhythm/occurrences/${id}/completion`, { completed }))
export const createRhythmDocument = (date: string, title: string, blocks: DocBlock[]) =>
  responseData<Doc>(http.put(`/api/workday-rhythm/days/${date}/document`, { title, blocks }))
