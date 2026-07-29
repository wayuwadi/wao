import { http, type ApiResponse } from './http'

export interface AgentHealth {
  service: string
  status: string
}

/** 探测独立 Agent 后端，避免 iframe 在服务未就绪时留下空白页。 */
export async function apiAgentHealth(): Promise<AgentHealth> {
  const response = await http.get<ApiResponse<AgentHealth>>('/api/agent/health')
  return response.data.data
}
