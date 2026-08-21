import { http, type ApiResponse } from './http'

export interface CompanionPairingPreview {
  code: string
  deviceName: string
  platform: string
  status: 'PENDING' | 'APPROVED' | 'PAIRED'
  expiresAt: string
}

export interface CompanionDevice {
  id: string
  name: string
  platform: string
  createdAt: string
  lastSeenAt: string | null
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchCompanionPairing = (code: string) =>
  data<CompanionPairingPreview>(http.get(`/api/companion/pairings/${encodeURIComponent(code)}`))

export const approveCompanionPairing = (code: string) =>
  data<CompanionPairingPreview>(http.post(`/api/companion/pairings/${encodeURIComponent(code)}`))

export const fetchCompanionDevices = () =>
  data<CompanionDevice[]>(http.get('/api/companion/devices'))

export const revokeCompanionDevice = async (id: string): Promise<void> => {
  await http.delete<ApiResponse<void>>(`/api/companion/devices/${encodeURIComponent(id)}`)
}
