import { http, type ApiResponse } from './http'

export interface CodexModelCatalog {
  models: { id: string; label: string }[]
  updatedAt: string | null
  stale: boolean
  message: string
}
export async function fetchCodexModels(): Promise<CodexModelCatalog> {
  return (await http.get<ApiResponse<CodexModelCatalog>>('/api/assistant/codex-models', {
    headers: { 'Cache-Control': 'no-cache' },
  })).data.data
}
