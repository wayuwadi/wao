import { http, type ApiResponse } from './http'
import type { AuthResolution } from './auth'

export async function startWechatLogin(scene: 'login' | 'link' = 'login') {
  try {
    const response = await http.get<ApiResponse<{ authorizationUrl: string }>>(
      '/api/auth/wechat/authorize',
      { params: { scene } },
    )
    window.location.assign(response.data.data.authorizationUrl)
  } catch {
    throw new Error('微信登录尚未配置，请管理员提供微信开放平台网站应用的 App ID 与 App Secret。')
  }
}

export async function exchangeWechatCode(code: string, state: string) {
  const response = await http.post<ApiResponse<AuthResolution>>('/api/auth/wechat/exchange', {
    code,
    state,
  })
  return response.data.data
}

export interface WechatOfficialSession {
  sessionId: string
  qrCodeUrl: string
  verificationCode: string
  expiresAt: string
}

export interface WechatOfficialPoll {
  status: 'WAITING_SCAN' | 'WAITING_CODE' | 'COMPLETED' | 'EXPIRED'
  resolution: AuthResolution | null
}

/** 创建公众号带参二维码和本次扫码专属验证码。 */
export async function createWechatOfficialSession() {
  const response = await http.post<ApiResponse<WechatOfficialSession>>(
    '/api/auth/wechat/official/sessions',
  )
  return response.data.data
}

/** 轮询扫码与验证码状态；完成时服务端同时签发 WAO 会话。 */
export async function pollWechatOfficialSession(sessionId: string) {
  const response = await http.get<ApiResponse<WechatOfficialPoll>>(
    `/api/auth/wechat/official/sessions/${encodeURIComponent(sessionId)}`,
  )
  return response.data.data
}
