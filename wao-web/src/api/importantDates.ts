import { http, type ApiResponse } from './http'

export interface FamilyImportantDate {
  id: string
  name: string
  eventDate: string
  note: string | null
  reminderEnabled: boolean
  reminderDaysBefore: number
  reminderTime: string | null
  calendarType: 'SOLAR' | 'LUNAR'
  lunarMonth: number | null
  lunarDay: number | null
  lunarLeapMonth: boolean
  solarDateLabel: string
  lunarDateLabel: string
  notificationType: 'DIRECT' | 'GROUP'
  chatId: string | null
  chatName: string | null
}

export interface FamilyImportantDateEvent {
  id: string
  importantDateId: string
  date: string
  name: string
  note: string | null
  reminderEnabled: boolean
  reminderDaysBefore: number
  reminderTime: string | null
  calendarType: 'SOLAR' | 'LUNAR'
  solarDateLabel: string
  lunarDateLabel: string
  notificationType: 'DIRECT' | 'GROUP'
  chatName: string | null
}

export interface FamilyImportantDateCalendar {
  items: FamilyImportantDate[]
  events: FamilyImportantDateEvent[]
  reminderChannelAvailable: boolean
  reminderChannelHint: string
  groupChannelAvailable: boolean
  groupChannelHint: string
}

export interface SaveFamilyImportantDate {
  name: string
  eventDate: string
  note: string | null
  reminderEnabled: boolean
  reminderDaysBefore: number
  reminderTime: string | null
  calendarType: 'SOLAR' | 'LUNAR'
  lunarMonth: number | null
  lunarDay: number | null
  lunarLeapMonth: boolean
  notificationType: 'DIRECT' | 'GROUP'
  chatId: string | null
  chatName: string | null
}

export interface CalendarDateConversion {
  solarDate: string
  lunarYear: number
  lunarMonth: number
  lunarDay: number
  lunarLeapMonth: boolean
  solarLabel: string
  lunarLabel: string
}

export async function fetchFamilyImportantDates(from: string, to: string): Promise<FamilyImportantDateCalendar> {
  const response = await http.get<ApiResponse<FamilyImportantDateCalendar>>(
    `/api/calendar/important-dates?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  )
  return response.data.data
}

export async function createFamilyImportantDate(value: SaveFamilyImportantDate): Promise<FamilyImportantDate> {
  const response = await http.post<ApiResponse<FamilyImportantDate>>('/api/calendar/important-dates', value)
  return response.data.data
}

export async function updateFamilyImportantDate(
  id: string,
  value: SaveFamilyImportantDate,
): Promise<FamilyImportantDate> {
  const response = await http.put<ApiResponse<FamilyImportantDate>>(
    `/api/calendar/important-dates/${encodeURIComponent(id)}`,
    value,
  )
  return response.data.data
}

export async function deleteFamilyImportantDate(id: string): Promise<void> {
  await http.delete(`/api/calendar/important-dates/${encodeURIComponent(id)}`)
}

export async function convertCalendarDate(value: {
  calendarType: 'SOLAR' | 'LUNAR'
  solarDate?: string
  lunarYear?: number
  lunarMonth?: number
  lunarDay?: number
  lunarLeapMonth?: boolean
}): Promise<CalendarDateConversion> {
  const params = new URLSearchParams()
  params.set('calendarType', value.calendarType)
  if (value.solarDate) params.set('solarDate', value.solarDate)
  if (value.lunarYear != null) params.set('lunarYear', String(value.lunarYear))
  if (value.lunarMonth != null) params.set('lunarMonth', String(value.lunarMonth))
  if (value.lunarDay != null) params.set('lunarDay', String(value.lunarDay))
  if (value.lunarLeapMonth) params.set('lunarLeapMonth', 'true')
  const response = await http.get<ApiResponse<CalendarDateConversion>>(
    `/api/calendar/important-dates/date-conversion?${params.toString()}`,
  )
  return response.data.data
}
