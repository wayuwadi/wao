import { http, type ApiResponse } from './http'

export type HolidayCategory = 'CHINA' | 'INTERNATIONAL'
export type HolidayDayType = 'FESTIVAL' | 'DAY_OFF' | 'ADJUSTED_WORKDAY'

export interface HolidayEvent {
  id: string
  code: string
  date: string
  year: number
  name: string
  category: HolidayCategory
  dayType: HolidayDayType
  internationalCategory: string | null
  sourceName: string
  sourceUrl: string | null
  remindable: boolean
  reminderEnabled: boolean
  reminderTime: string | null
}

export interface HolidayYearSource {
  year: number
  officialScheduleAvailable: boolean
  sourceName: string
  sourceUrl: string
  message: string
}

export interface HolidayCalendar {
  events: HolidayEvent[]
  years: HolidayYearSource[]
}

export interface InternationalHolidayCategory {
  code: string
  name: string
  description: string
}

export interface HolidaySettings {
  selectedInternationalCategories: string[]
  availableInternationalCategories: InternationalHolidayCategory[]
  reminderChannelAvailable: boolean
  reminderChannelHint: string
}

export interface HolidayReminder {
  holidayCode: string
  enabled: boolean
  reminderTime: string | null
}

export async function fetchHolidayEvents(from: string, to: string): Promise<HolidayCalendar> {
  const response = await http.get<ApiResponse<HolidayCalendar>>(
    `/api/calendar/holidays?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  )
  return response.data.data
}

export async function fetchHolidaySettings(): Promise<HolidaySettings> {
  const response = await http.get<ApiResponse<HolidaySettings>>('/api/calendar/holidays/settings')
  return response.data.data
}

export async function saveHolidaySettings(selectedInternationalCategories: string[]): Promise<HolidaySettings> {
  const response = await http.put<ApiResponse<HolidaySettings>>('/api/calendar/holidays/settings', {
    selectedInternationalCategories,
  })
  return response.data.data
}

export async function saveHolidayReminder(
  holidayCode: string,
  value: { enabled: boolean; reminderTime: string | null },
): Promise<HolidayReminder> {
  const response = await http.put<ApiResponse<HolidayReminder>>(
    `/api/calendar/holidays/${encodeURIComponent(holidayCode)}/reminder`,
    value,
  )
  return response.data.data
}
