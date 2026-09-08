import { http, type ApiResponse } from './http'

export type UpdateKind = 'FEATURE' | 'FIX' | 'PERFORMANCE'

export interface UpdateDiaryEntry {
  scope: string
  kind: UpdateKind
  title: string
}

export interface UpdateDiaryDay {
  date: string
  entries: UpdateDiaryEntry[]
}

export interface UpdateDiaryPage {
  days: UpdateDiaryDay[]
  totalDays: number
  page: number
  size: number
}

export async function fetchUpdateDiary(page = 1, size = 12) {
  const response = await http.get<ApiResponse<UpdateDiaryPage>>('/api/update-diary', {
    params: { page, size },
  })
  return response.data.data
}
