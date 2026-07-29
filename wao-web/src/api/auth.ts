import { http, type ApiResponse } from './http'

export interface AccountIdentity {
  provider: 'email' | 'feishu' | 'wechat'
  email: string | null
  displayName: string
}

export interface SessionMember {
  id: string
  name: string
  initial: string
  color: string
  role: string
}

export interface AuthSession {
  accountId: string
  member: SessionMember
  identities: AccountIdentity[]
  createdAt: string
}

export interface AuthResolution {
  session: AuthSession | null
  claimRequired: boolean
  provider: string | null
  displayName: string | null
  candidates: SessionMember[]
}

export async function getSession() {
  const response = await http.get<ApiResponse<AuthSession>>('/api/auth/session')
  return response.data.data
}

export async function loginWithEmail(email: string, password: string) {
  const response = await http.post<ApiResponse<AuthSession>>('/api/auth/email/login', { email, password })
  return response.data.data
}

export async function registerWithEmail(name: string, email: string, password: string) {
  const response = await http.post<ApiResponse<AuthSession>>('/api/auth/email/register', { name, email, password })
  return response.data.data
}

export async function logoutSession() {
  await http.post('/api/auth/logout')
}

export async function claimMember(memberId: string) {
  const response = await http.post<ApiResponse<AuthSession>>('/api/auth/claim', { memberId })
  return response.data.data
}

export async function updateEmailCredential(email: string, currentPassword: string, newPassword: string) {
  const response = await http.put<ApiResponse<AuthSession>>('/api/account/email', { email, currentPassword, newPassword })
  return response.data.data
}

export async function updateProfile(name: string, initial: string) {
  const response = await http.put<ApiResponse<AuthSession>>('/api/account/profile', { name, initial })
  return response.data.data
}

export async function unlinkIdentity(provider: string) {
  const response = await http.delete<ApiResponse<AuthSession>>(`/api/account/identities/${provider}`)
  return response.data.data
}

/** 管理员为新成员开账号：创建成员 + 绑定邮箱密码登录。 */
export async function apiCreateAccount(name: string, email: string, password: string) {
  const response = await http.post<ApiResponse<SessionMember>>('/api/members/accounts', { name, email, password })
  return response.data.data
}
