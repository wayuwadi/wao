import { http, type ApiResponse } from './http'

export interface HelloData {
  message: string
  service: string
  serverTime: string
}

export async function getHello(): Promise<ApiResponse<HelloData>> {
  const response = await http.get<ApiResponse<HelloData>>('/api/hello')
  return response.data
}
