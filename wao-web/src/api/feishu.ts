import { http, type ApiResponse } from './http'
import type { AuthResolution } from './auth'

export type FeishuScene = 'qr' | 'authorization' | 'workbench' | 'browser' | 'link'

export async function getFeishuAuthorizationUrl(scene: FeishuScene) {
  const response = await http.get<ApiResponse<{ authorizationUrl: string }>>('/api/auth/feishu/authorize', { params: { scene } })
  return response.data.data.authorizationUrl
}

export async function startFeishuLogin(scene: FeishuScene, beforeNavigate?: (url: string) => void | boolean) {
  const url = await getFeishuAuthorizationUrl(scene)
  if (beforeNavigate?.(url) === false) return
  window.location.assign(url)
}

export async function exchangeFeishuCode(code: string, state: string) {
  const response = await http.post<ApiResponse<AuthResolution>>('/api/auth/feishu/exchange', { code, state })
  return response.data.data
}

/** 容器内免登前置信息：能不能免登、JSSDK 要用的 App ID。 */
export async function getFeishuInAppConfig() {
  const response = await http.get<ApiResponse<{ enabled: boolean; appId: string }>>('/api/auth/feishu/in-app-config')
  return response.data.data
}

/** 容器内免登：JSSDK requestAuthCode 拿到的临时码直接换 WAO 会话。 */
export async function exchangeFeishuInAppCode(code: string) {
  const response = await http.post<ApiResponse<AuthResolution>>('/api/auth/feishu/in-app-exchange', { code })
  return response.data.data
}
