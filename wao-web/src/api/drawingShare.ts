import type { ExcalidrawInitialDataState } from '@excalidraw/excalidraw/types'
import { http, type ApiResponse } from './http'

export type DrawingShareValidity = '1h' | '1d' | '7d' | 'permanent'

export interface DrawingShareLink {
  id: string
  createdAt: string
  expiresAt: string | null
  token: string | null
}

export interface SharedDrawing {
  title: string
  revision: number
  updatedAt: string
  expiresAt: string | null
  scene: ExcalidrawInitialDataState | null
}

const managementPath = (id: string) => `/api/drawings/${encodeURIComponent(id)}/public-shares`

export async function listDrawingShares(id: string, signal?: AbortSignal) {
  return (await http.get<ApiResponse<DrawingShareLink[]>>(managementPath(id), { signal })).data.data
}

export async function createDrawingShare(id: string, validity: DrawingShareValidity) {
  return (await http.post<ApiResponse<DrawingShareLink>>(managementPath(id), { validity })).data.data
}

export async function revokeDrawingShare(id: string, shareId: string) {
  await http.delete(`${managementPath(id)}/${encodeURIComponent(shareId)}`)
}

export function drawingShareUrl(token: string) {
  const url = new URL('/s/drawing', window.location.origin)
  url.hash = token
  return url.href
}

export async function readSharedDrawing(token: string, knownRevision: number, signal: AbortSignal) {
  const response = await http.get<ApiResponse<SharedDrawing>>('/api/share/drawings/current', {
    headers: { 'X-Drawing-Share-Token': token },
    params: { knownRevision },
    timeout: 120_000,
    signal,
  })
  return response.data.data
}
