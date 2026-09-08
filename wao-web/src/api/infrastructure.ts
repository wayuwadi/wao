import { http, type ApiResponse } from './http'

export interface InfraService {
  name: string; ports?: string; kind: string; group: string; image: string; state: string; status: string
  cpuPercent: number | null; memoryBytes: number | null; memoryLimitBytes?: number | null
}
export interface InfraDisk { mount: string; total: number; used: number; available: number }
export interface InfraPoint {
  ts: number; cpu: number | null; memoryAvailable: number; memoryTotal: number
  diskAvailable: number | null; swapUsed: number
}
export interface InfraNode {
  id: string; name: string; role: string; address: string; online: boolean; stale: boolean
  error?: string | null; collectedAt?: number; lastAttemptAt?: number
  hostname?: string; os?: string; cpuCores?: number; cpuPercent?: number | null
  memory?: { total: number; available: number; swapTotal: number; swapUsed: number }
  disks?: InfraDisk[]; loadAverage?: number[]; uptimeSeconds?: number
  services?: InfraService[]; warnings?: string[]; history: InfraPoint[]
}
export interface InfraOverview { generatedAt: number; intervalSeconds: number; historyHours: number; nodes: InfraNode[] }

export async function apiInfrastructureOverview(hours: number) {
  return (await http.get<ApiResponse<InfraOverview>>('/api/infrastructure/overview', { params: { hours } })).data.data
}
