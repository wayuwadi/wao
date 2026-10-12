import { http, type ApiResponse } from './http'
import type { SharedDoc } from '@/types'
export interface NoteShareEntry { id: string; createdAt: string; expiresAt: string }
export interface CreatedNoteShare extends NoteShareEntry { token: string }
export async function listNoteShares(id: string): Promise<NoteShareEntry[]> {
  return (await http.get<ApiResponse<NoteShareEntry[]>>(`/api/docs/${encodeURIComponent(id)}/public-shares`)).data.data
}
export async function createNoteShare(id: string, durationHours: number): Promise<CreatedNoteShare> {
  return (await http.post<ApiResponse<CreatedNoteShare>>(`/api/docs/${encodeURIComponent(id)}/public-shares`, { durationHours })).data.data
}
export async function revokeNoteShare(id: string, shareId: string): Promise<void> {
  await http.delete(`/api/docs/${encodeURIComponent(id)}/public-shares/${encodeURIComponent(shareId)}`)
}
export async function readPublicNote(token: string): Promise<{ doc: SharedDoc; expiresAt: string }> {
  return (await http.get<ApiResponse<{ doc: SharedDoc; expiresAt: string }>>(`/api/share/notes/${encodeURIComponent(token)}`)).data.data
}
export function publicNoteUrl(token: string, markdown = false): string {
  return new URL(markdown ? `/api/share/notes/${encodeURIComponent(token)}.md` : `/s/n/${encodeURIComponent(token)}`, window.location.origin).href
}
