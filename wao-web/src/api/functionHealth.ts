import { http, type ApiResponse } from './http'

export interface FunctionHealthItem {
  featureId: string
  score: number
  iterationCount: number
  manualAdjustment: number
  totalOpens: number
  recentOpens: number
  recentShare: number
  lastOpenedAt: string | null
  insightLevel: 'UNKNOWN' | 'LOW' | 'HIGH' | 'MATURE' | 'STABLE' | 'NORMAL'
  insight: string
}

export interface FunctionHealthOverview {
  windowDays: number
  generatedAt: string
  recentOpens: number
  functions: FunctionHealthItem[]
}

export async function fetchFunctionHealth() {
  const response = await http.get<ApiResponse<FunctionHealthOverview>>('/api/function-health')
  return response.data.data
}

export async function recordFunctionOpen(featureId: string, source = 'PAGE') {
  await http.post<ApiResponse<void>>(`/api/function-health/${featureId}/open`, undefined, {
    params: { source },
  })
}

export async function adjustFunctionScore(featureId: string, delta: -1 | 1) {
  const response = await http.post<ApiResponse<FunctionHealthItem>>(
    `/api/function-health/${featureId}/score`,
    { delta },
  )
  return response.data.data
}
