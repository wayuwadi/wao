import { http, type ApiResponse } from './http'
export interface NoteVisualRecap {
  status: 'EMPTY' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  id?: string
  sourceRev?: number
  sourceText?: string
  stale?: boolean
  html?: string
  message?: string
}
export async function fetchVisualRecap(id: string): Promise<NoteVisualRecap> {
  return (await http.get<ApiResponse<NoteVisualRecap>>(`/api/docs/notes/${id}/visual-recap`)).data
    .data
}
export async function startVisualRecap(
  id: string,
  expectedRev: number,
  requestId: string,
): Promise<NoteVisualRecap> {
  return (
    await http.post<ApiResponse<NoteVisualRecap>>(`/api/docs/notes/${id}/visual-recap`, {
      expectedRev,
      requestId,
    })
  ).data.data
}
