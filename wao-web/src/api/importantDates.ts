import { http, type ApiResponse } from './http'

export interface FamilyImportantDate {
  id: string
  name: string
  eventDate: string
  note: string | null
  reminderEnabled: boolean
  reminderDaysBefore: number
  reminderTime: string | null
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
}

export interface FamilyImportantDateCalendar {
  items: FamilyImportantDate[]
  events: FamilyImportantDateEvent[]
  reminderChannelAvailable: boolean
  reminderChannelHint: string
}

export interface SaveFamilyImportantDate {
  name: string
  eventDate: string
  note: string | null
  reminderEnabled: boolean
  reminderDaysBefore: number
  reminderTime: string | null
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
