import { http, type ApiResponse } from './http'
import type { DocBlock, DocLink, RichDocNode } from '@/types'
export type NoteSourceContent = { title: string | null; markdown: string; document: RichDocNode | null; blocks: DocBlock[]; links: DocLink[] }
export type NoteSourceSnapshot = NoteSourceContent & { id: string; sourceType: string; sourceRev: number | null; createdAt: string }
export type NoteOriginals = { before: NoteSourceSnapshot | null; initial: NoteSourceSnapshot | null; explanation: string; appliedRev: number | null }
export type NoteAiInput = { requestId: string; stableId: string; docId: string | null; sourceRev: number | null; stage: 'title' | 'image' | 'polish'; content: NoteSourceContent; inputText: string; selection?: { from: number; to: number; original: string } }
export async function fetchNoteOriginals(id: string): Promise<NoteOriginals> { return (await http.get<ApiResponse<NoteOriginals>>(`/api/docs/notes/${encodeURIComponent(id)}/originals`)).data.data }
export async function freezeNoteAiInput(input: NoteAiInput): Promise<{ snapshotId: string; overviewImageUrl: string | null }> { return (await http.post<ApiResponse<{ snapshotId: string; overviewImageUrl: string | null }>>('/api/docs/notes/ai-inputs', input)).data.data }
export async function retainNoteAiResult(request: string, taskId: string, content: NoteSourceContent): Promise<void> { await http.post(`/api/docs/notes/ai-inputs/${encodeURIComponent(request)}/results`, { taskId, content }) }

export type NoteAiOrigin = { stableId: string; docId?: string; sourceRev?: number; title: string; blocks: DocBlock[]; links: DocLink[] }
