import { http, type ApiResponse } from './http'

/** 探测注册表中声明的外部应用；无 status 字段的成功响应也视为可用。 */
export async function apiExternalAppHealth(url: string): Promise<void> {
  const response = await http.get<ApiResponse<{ status?: string }> | { status?: string }>(url)
  const raw = response.data
  const health = 'code' in raw ? raw.data : raw
  if (health?.status && health.status !== 'UP') throw new Error('应用服务尚未就绪')
}
