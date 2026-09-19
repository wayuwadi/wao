import { http, type ApiResponse } from './http'
export interface SyncSettings {
  kbId: string
  apiKey?: string
  modules: string[]
  batchLimit: number
  syncDeletes: boolean
  enabled: boolean
  liveSync?: boolean
  time: string
  revision: number
  customSchedule?: boolean
  hasKey?: boolean
  nextFireAt?: string
}
export interface SyncModule {
  id: string
  label: string
  scope: string
  count: number
}
export interface SyncRun {
  id: string
  trigger_type: string
  status: string
  created_at: string
  started_at: string | null
  finished_at: string | null
  uploaded: number
  skipped: number
  removed: number
  remaining: number
  failed: number
  message: string
}
export interface SyncItem {
  source_key: string
  module_id: string
  source_title: string
  source_url: string
  remote_id: string | null
  status: string
  parse_status: string
  summary_status: string
  error_message: string
  updated_at: string
}
export interface SyncProgress {
  runs: SyncRun[]
  items: SyncItem[]
}
export interface SyncOverview extends SyncProgress {
  ownerId: string
  configured: boolean
  settings: Partial<SyncSettings>
  catalog: SyncModule[]
  totals: { tracked: number; transferred: number; failed: number }
}
export interface SyncSource {
  key: string
  module: string
  title: string
  url: string
  content: string
  hash: string
  originalUrl: string
}
export interface DocumentSyncState {
  id: string
  state: string
  message: string
  sourceUrl: string
  knowledgeUrl: string
}
export async function getDocumentSyncStates(ids: string[]) {
  return (await http.get<ApiResponse<DocumentSyncState[]>>('/api/weknora-sync/documents', { params: { ids: ids.join(',') } })).data.data
}
export async function runKnowledgeSync() {
  return (await http.post<ApiResponse<{ id: string }>>('/api/weknora-sync/knowledge-run')).data.data
}
export async function getSyncOverview() {
  return (await http.get<ApiResponse<SyncOverview>>('/api/weknora-sync')).data.data
}
export async function getSyncProgress() {
  return (await http.get<ApiResponse<SyncProgress>>('/api/weknora-sync/progress')).data.data
}
export async function saveSyncSettings(settings: SyncSettings) {
  await http.put('/api/weknora-sync', settings, { timeout: 60000 })
}
export async function runSync() {
  return (await http.post<ApiResponse<{ id: string }>>('/api/weknora-sync/run')).data.data
}
export async function getSyncSource(key: string) {
  return (await http.get<ApiResponse<SyncSource>>('/api/weknora-sync/source', { params: { key } }))
    .data.data
}
