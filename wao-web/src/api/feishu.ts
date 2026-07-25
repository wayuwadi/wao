import { http, type ApiResponse } from './http'
import type { AuthResolution } from './auth'

export type FeishuScene = 'qr' | 'authorization' | 'workbench' | 'browser' | 'link'

export async function getFeishuAuthorizationUrl(scene: FeishuScene) {
  const response = await http.get<ApiResponse<{ authorizationUrl: string }>>('/api/auth/feishu/authorize', { params: { scene } })
  return response.data.data.authorizationUrl
}

export async function startFeishuLogin(scene: FeishuScene) {
  window.location.assign(await getFeishuAuthorizationUrl(scene))
}

export async function exchangeFeishuCode(code: string, state: string) {
  const response = await http.post<ApiResponse<AuthResolution>>('/api/auth/feishu/exchange', { code, state })
  return response.data.data
}
