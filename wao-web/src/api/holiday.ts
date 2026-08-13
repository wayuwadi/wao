import { http, type ApiResponse } from './http'

export type HolidayCategory = 'CHINA' | 'INTERNATIONAL'
export type HolidayDayType = 'FESTIVAL' | 'DAY_OFF' | 'ADJUSTED_WORKDAY'

/** 服务端计算并维护的内置节假日事件。 */
export interface HolidayEvent {
  id: string
  date: string
  name: string
  category: HolidayCategory
  dayType: HolidayDayType
}

export async function fetchHolidayEvents(from: string, to: string): Promise<HolidayEvent[]> {
  const response = await http.get<ApiResponse<HolidayEvent[]>>(
    `/api/calendar/holidays?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  )
  return response.data.data
}
