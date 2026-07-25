import { http, type ApiResponse } from './http'

export interface FeishuProfile { userId: string; name: string; avatarUrl: string }

export async function startFeishuLogin(scene: 'qr' | 'authorization') {
  const response = await http.get<ApiResponse<{ authorizationUrl: string }>>('/api/auth/feishu/authorize', { params: { scene } })
  window.location.assign(response.data.data.authorizationUrl)
}

export async function exchangeFeishuCode(code: string, state: string) {
  const response = await http.post<ApiResponse<FeishuProfile>>('/api/auth/feishu/exchange', { code, state })
  return response.data.data
}
