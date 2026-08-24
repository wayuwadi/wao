import { http, type ApiResponse } from './http'

export interface RequestMetricEndpoint {
  method: string
  path: string
  requestCount: number
  averageDurationMs: number
  p95DurationMs: number
  slowCount: number
  failedCount: number
}

export interface RequestMetricItem {
  id: number
  method: string
  path: string
  statusCode: number | null
  durationMs: number
  networkType: string | null
  retryAttempt: number
  startedAt: string
}

export interface RequestMetricOverview {
  windowHours: number
  generatedAt: string
  totalRequests: number
  averageDurationMs: number
  p95DurationMs: number
  slowRequests: number
  failedRequests: number
  endpoints: RequestMetricEndpoint[]
  recentRequests: RequestMetricItem[]
}

export async function fetchRequestMetricOverview(hours = 24) {
  const response = await http.get<ApiResponse<RequestMetricOverview>>(
    '/api/request-metrics/overview',
    { params: { hours } },
  )
  return response.data.data
}
