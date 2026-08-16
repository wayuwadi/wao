import { http, type ApiResponse } from './http'

export type LifeReviewPeriod = 'WEEK' | 'MONTH' | 'YEAR' | 'LIFE'

export interface LifeReviewReport {
  pastActions: string
  meaningfulTopics: string
  trajectory: string
  nextFocus: string
}

export interface LifeReviewSource {
  id: string
  type: string
  title: string
  date: string | null
  url: string | null
}

export interface LifeReview {
  period: LifeReviewPeriod
  periodKey: string
  periodLabel: string
  startDate: string
  endDate: string
  taskCount: number
  assistantConversationCount: number
  chatDayCount: number
  sources: LifeReviewSource[]
  report: LifeReviewReport | null
  generatedAt: string | null
  coverageMessage: string
}

export interface LifeReviewHistory {
  period: LifeReviewPeriod
  periodKey: string
  periodLabel: string
  startDate: string
  endDate: string
  generatedAt: string
  excerpt: string
}

export async function fetchLifeReview(period: LifeReviewPeriod, anchorDate?: string) {
  const response = await http.get<ApiResponse<LifeReview>>('/api/life-reviews', {
    params: { period, ...(anchorDate ? { anchorDate } : {}) },
  })
  return response.data.data
}

export async function generateLifeReview(period: LifeReviewPeriod, anchorDate?: string) {
  const response = await http.post<ApiResponse<LifeReview>>(
    '/api/life-reviews/generate',
    { period, anchorDate: anchorDate || null },
    { timeout: 320_000 },
  )
  return response.data.data
}

export async function fetchLifeReviewHistory() {
  const response = await http.get<ApiResponse<LifeReviewHistory[]>>('/api/life-reviews/history')
  return response.data.data
}
