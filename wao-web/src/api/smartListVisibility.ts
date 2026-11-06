import { http, type ApiResponse } from './http'

const path = '/api/preferences/smart-list/items'
export async function fetchSmartListVisibility(): Promise<Record<string, string>> {
  const result = await http.get<ApiResponse<Record<string, string>>>(path)
  return result.data.data
}
export async function saveSmartListVisibility(id: string, visible: boolean, expected: string): Promise<void> {
  await http.put<ApiResponse<void>>(`${path}/${encodeURIComponent(id)}`, { visible, expected })
}
