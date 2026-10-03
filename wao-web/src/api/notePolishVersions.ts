import { http, type ApiResponse } from './http'

export interface NotePolishVersion {
  docId: string
  sourceMarkdown?: string
  sourceRev?: number
  polishedMarkdown: string
}

/** 只读取当前成员的小记润色对照；正文更新后由列表按快照判定是否仍有效。 */
export async function fetchNotePolishVersions(): Promise<NotePolishVersion[]> {
  const response = await http.get<ApiResponse<NotePolishVersion[]>>('/api/docs/notes/polish-versions/reading')
  return response.data.data
}

/** 幂等保存一次成功润色；源正文改变时服务端拒绝旧结果。 */
export async function saveNotePolishVersion(docId: string, sourceMarkdown: string, polishedMarkdown: string, aiJobId?: string): Promise<NotePolishVersion> {
  const response = await http.put<ApiResponse<NotePolishVersion>>(
    `/api/docs/notes/polish-versions/${encodeURIComponent(docId)}`,
    { sourceMarkdown, polishedMarkdown, ...(aiJobId ? { aiJobId } : {}) },
  )
  return response.data.data
}
