import { http, type ApiResponse } from './http'

export interface AgyQuotaBucket {
  id: string | null
  name: string | null
  window: string | null
  remainingPercent: number
  resetsAt: number | null
}

export interface AgyQuotaGroup {
  name: string
  buckets: AgyQuotaBucket[]
}

export interface AgyQuotaAccount {
  key: string
  accountLabel: string
  groups: AgyQuotaGroup[]
  sources: string[]
  lastSyncedAt: string
  fresh: boolean
}

export async function fetchAgyQuotas() {
  return (await http.get<ApiResponse<AgyQuotaAccount[]>>('/api/agy-quota')).data.data
}
