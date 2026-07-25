import { http, type ApiResponse } from './http'
import type { AuthResolution } from './auth'

export async function startWechatLogin(scene: 'login' | 'link' = 'login') {
  try {
    const response = await http.get<ApiResponse<{ authorizationUrl: string }>>('/api/auth/wechat/authorize', { params: { scene } })
    window.location.assign(response.data.data.authorizationUrl)
  } catch {
    throw new Error('微信登录尚未配置，请管理员提供微信开放平台网站应用的 App ID 与 App Secret。')
  }
}

export async function exchangeWechatCode(code: string, state: string) {
  const response = await http.post<ApiResponse<AuthResolution>>('/api/auth/wechat/exchange', { code, state })
  return response.data.data
}
