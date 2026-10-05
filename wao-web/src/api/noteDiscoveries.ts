import { http, type ApiResponse } from './http'
import type { Doc } from '@/types'
import { fetchNoteMining } from './noteMining'
import { fetchDoc } from './board'

export interface Discovery {
  id: string
  origin: 'AUTO' | 'LEGACY'
  validity?: string
  kind?: string
  headline: string
  observation: string
  valueReason: string
  optionalNextStep?: string | null
  imageUrl?: string | null
  restricted: boolean
  readOnly?: boolean
  evidence: { docId: string; docRev: number; title: string; quote: string; sourceBasis?: string; basisRev?: number }[]
  feedback: { saved: boolean; dismissed: boolean; correction: string | null; version: number }
}
export interface DiscoveryPreference { enabled: boolean; version: number; available: boolean; message: string }
export interface DiscoveryCurrent { discovery: Discovery | null; automationState: string; lastOutcome: string | null; revision: string; coverage?: { days: number; eligible: number; indexed: number; analyzed: number } }
export interface DiscoveryPage { items: Discovery[]; nextCursor: string | null }
const unwrap = async <T>(request: Promise<{ data: ApiResponse<T> }>) => (await request).data.data
export const fetchDiscoveryCurrent = () => unwrap<DiscoveryCurrent>(http.get('/api/note-discoveries/current'))
export const fetchDiscoveryPreference = () => unwrap<DiscoveryPreference>(http.get('/api/note-discoveries/preference'))
export const saveDiscoveryPreference = (enabled: boolean, expectedVersion: number) =>
  unwrap<DiscoveryPreference>(http.put('/api/note-discoveries/preference', { enabled, expectedVersion }))
export async function fetchDiscoveryPage(mode: 'saved' | 'legacy', cursor?: string): Promise<DiscoveryPage> {
  const cacheGeneration = legacyCacheGeneration
  try { return await unwrap<DiscoveryPage>(http.get(`/api/note-discoveries/${mode}`, { params: { cursor, limit: 20 } })) }
  catch (error) {
    if (![404, 410].includes(discoveryHttpStatus(error) ?? 0)) throw error
    // 滚动部署兼容：旧服务只有最近一次历史结果，始终只读，不回退启动。
    const old = await fetchNoteMining(), items: Discovery[] = []
    for (const a of old.artifacts) {
      if (mode === 'saved' && a.feedbackStatus !== 'SAVED') continue
      const permitted = await Promise.all(a.sources.map(source => fetchDoc(source.docId).then(() => true).catch(() => false)))
      const restricted = !permitted.length || permitted.some(value => !value)
      items.push({ id: a.id, origin: 'LEGACY', headline: restricted ? '来源已不可访问' : a.content, observation: restricted ? '' : a.content,
        valueReason: restricted ? '' : a.rationale, evidence: restricted ? [] : a.sources.map(source => ({ ...source, quote: source.excerpt })), restricted, readOnly: true,
        feedback: { saved: a.feedbackStatus === 'SAVED', dismissed: a.feedbackStatus === 'NOT_ACCURATE', correction: null, version: 0 } })
    }
    for (const v of old.visuals ?? []) {
      if (mode === 'saved' && v.feedbackStatus !== 'SAVED') continue
      const permitted = await Promise.all(v.sources.map(source => fetchDoc(source.docId).then(() => true).catch(() => false)))
      const restricted = !permitted.length || permitted.some(value => !value)
      items.push({ id: `visual:${old.job?.id}:${v.lens}`, origin: 'LEGACY', headline: restricted ? '来源已不可访问' : v.caption, observation: restricted ? '' : v.caption,
        valueReason: restricted ? '' : v.rationale, evidence: restricted ? [] : v.sources.map(source => ({ ...source, quote: source.excerpt })), imageUrl: restricted ? null : v.imageUrl, restricted, readOnly: true,
        feedback: { saved: v.feedbackStatus === 'SAVED', dismissed: v.feedbackStatus === 'NOT_ACCURATE', correction: null, version: 0 } })
    }
    if (cacheGeneration === legacyCacheGeneration) { legacyItems.clear(); for (const item of items) legacyItems.set(item.id, item) }
    return { items, nextCursor: null }
  }
}
const legacyItems = new Map<string, Discovery>()
let legacyCacheGeneration = 0
export function clearDiscoveryLegacyCache() { legacyCacheGeneration++; legacyItems.clear() }
export const fetchDiscovery = (id: string) => unwrap<Discovery>(http.get(`/api/note-discoveries/items/${encodeURIComponent(id)}`))
export const feedbackDiscovery = (item: Discovery, patch: Partial<Pick<Discovery['feedback'], 'saved' | 'dismissed' | 'correction'>>) =>
  unwrap<Discovery>(http.patch(`/api/note-discoveries/items/${encodeURIComponent(item.id)}/feedback`, { expectedVersion: item.feedback.version, ...patch }))
export const fetchDiscoverySource = (id: string, docId: string) => {
  const old = legacyItems.get(id)
  if (old?.evidence.some(source => source.docId === docId)) return fetchDoc(docId)
  return unwrap<Doc>(http.get(`/api/note-discoveries/items/${encodeURIComponent(id)}/sources/${encodeURIComponent(docId)}`))
}
export function discoveryHttpStatus(error: unknown): number | undefined {
  const value = error as { status?: number; response?: { status?: number } }
  return value?.status ?? value?.response?.status
}
