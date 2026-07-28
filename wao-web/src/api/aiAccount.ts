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
  /** oauth = 一键登录拿到的凭据，manual = 手工粘贴的 JSON */
  authMethod: 'oauth' | 'manual'
  plan: string | null
  quotaStatus: 'UNKNOWN' | 'NO_API' | 'SYNCED' | 'FAILED' | null
  quotaMessage: string | null
  /** 最紧张的那条限额窗口，如 session / weekly_all */
  quotaWindow: string | null
  /** 该窗口已用百分比；null 表示没查到，不能当成 0 来显示 */
  quotaPercent: number | null
  quotaResetsAt: string | null
  quotaRawJson: string | null
  tokenExpiresAt: string | null
  lastSyncedAt: string | null
  createdAt: string | null
}

export interface AiAccountLoginStart {
  state: string
  authorizeUrl: string
  expiresAt: string
  hint: string
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

/** 发起 OAuth 登录，拿到要在浏览器里打开的授权地址。 */
export const startAiAccountLogin = (provider: 'claude', label?: string) =>
  post<AiAccountLoginStart>('/api/settings/ai-accounts/login/start', { provider, label })

/** 回填授权码完成登录，直接拿到建好的账号。 */
export const completeAiAccountLogin = (state: string, code: string) =>
  post<AiAccount>('/api/settings/ai-accounts/login/complete', { state, code })
