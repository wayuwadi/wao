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
  /** oauth = 一键登录，manual = 手工 JSON，api_key = 自定义 Responses API */
  authMethod: 'oauth' | 'manual' | 'api_key'
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
  baseUrl?: string | null
  model?: string | null
  apiProtocol?: 'responses' | null
}

export interface AiAccountLoginStart {
  state: string
  authorizeUrl: string
  expiresAt: string
  hint: string
  flow: 'authorization_code' | 'device_code'
  userCode: string | null
  pollIntervalSeconds: number
}

export interface AiAccountLoginStatus {
  status: 'pending' | 'completed'
  message: string
  account: AiAccount | null
}

export interface AiAccountForm {
  provider: 'codex' | 'claude' | 'qoder'
  label: string
  email?: string
  credentials?: string
  enabled?: boolean
  baseUrl?: string
  model?: string
  apiProtocol?: 'responses'
  apiKey?: string
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
export const startAiAccountLogin = (provider: 'claude' | 'codex', label?: string) =>
  post<AiAccountLoginStart>('/api/settings/ai-accounts/login/start', { provider, label })

/** 回填授权码完成登录，直接拿到建好的账号。 */
export const completeAiAccountLogin = (state: string, code: string) =>
  post<AiAccount>('/api/settings/ai-accounts/login/complete', { state, code })

/** 查询 Codex 设备码是否已授权；完成时服务端会直接创建账号。 */
export const fetchAiAccountLoginStatus = (state: string) =>
  get<AiAccountLoginStatus>(`/api/settings/ai-accounts/login/${encodeURIComponent(state)}/status`)
