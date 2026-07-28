import { http, type ApiResponse } from './http'

async function get<T>(url: string): Promise<T> {
  const res = await http.get<ApiResponse<T>>(url)
  return res.data.data
}
async function post<T>(url: string, body?: unknown): Promise<T> {
  const res = await http.post<ApiResponse<T>>(url, body)
  return res.data.data
}
async function put<T>(url: string, body?: unknown): Promise<T> {
  const res = await http.put<ApiResponse<T>>(url, body)
  return res.data.data
}
async function del<T>(url: string): Promise<T> {
  const res = await http.delete<ApiResponse<T>>(url)
  return res.data.data
}

export interface AiAccount {
  id: string
  provider: 'codex' | 'claude' | 'qoder'
  label: string
  email: string | null
  enabled: boolean
  quotaStatus: 'UNKNOWN' | 'NO_API' | 'SYNCED' | 'FAILED' | null
  quotaMessage: string | null
  quotaRawJson: string | null
  lastSyncedAt: string | null
  createdAt: string | null
}

export interface AiAccountForm {
  provider: 'codex' | 'claude' | 'qoder'
  label: string
  email?: string
  credentials?: string
  enabled?: boolean
}

export interface AiAccountSyncResult {
  id: string
  provider: string
  status: string
  message: string
  rawJson: string | null
  syncedAt: string | null
}

export const fetchAiAccounts = () => get<AiAccount[]>('/api/settings/ai-accounts')
export const createAiAccount = (payload: AiAccountForm) =>
  post<AiAccount>('/api/settings/ai-accounts', payload)
export const updateAiAccount = (id: string, payload: Partial<AiAccountForm>) =>
  put<AiAccount>(`/api/settings/ai-accounts/${id}`, payload)
export const deleteAiAccount = (id: string) => del<void>(`/api/settings/ai-accounts/${id}`)
export const syncAiAccount = (id: string) =>
  post<AiAccountSyncResult>(`/api/settings/ai-accounts/${id}/sync`)
