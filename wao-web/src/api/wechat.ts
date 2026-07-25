import { http, type ApiResponse } from './http'
import type { AuthResolution } from './auth'

export async function startWechatLogin(scene: 'login' | 'link' = 'login') {
  const response = await http.get<ApiResponse<{ authorizationUrl: string }>>('/api/auth/wechat/authorize', { params: { scene } })
  window.location.assign(response.data.data.authorizationUrl)
}

export async function exchangeWechatCode(code: string, state: string) {
  const response = await http.post<ApiResponse<AuthResolution>>('/api/auth/wechat/exchange', { code, state })
  return response.data.data
}
