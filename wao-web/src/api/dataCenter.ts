import { http, type ApiResponse } from './http'

export type DataCenterStatus = 'READY' | 'TIME_UNKNOWN' | 'EMPTY' | 'REGISTERED_ONLY'
export type DataCenterCoverage = 'FULL_RECORDS' | 'REGISTRY_ONLY'

export interface DataCenterSource {
  id: string
  label: string
  category: string
  description: string
  recordUnit: string
  recordCount: number
  latestAt: string | null
  status: DataCenterStatus
  coverage: DataCenterCoverage
  boundary: string
}

export interface DataCenterOverview {
  generatedAt: string
  sourceCount: number
  populatedSourceCount: number
  totalRecords: number
  sources: DataCenterSource[]
}

export async function fetchDataCenterOverview() {
  const response = await http.get<ApiResponse<DataCenterOverview>>('/api/data-center/overview')
  return response.data.data
}
