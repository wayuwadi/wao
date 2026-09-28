import { http, type ApiResponse } from './http'

export interface NoteAiWorkflowStatus {
  docId: string
  title: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'
  image: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'
  polish: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'
  message: string | null
}

export async function fetchNoteAiWorkflows(): Promise<NoteAiWorkflowStatus[]> {
  const response = await http.get<ApiResponse<NoteAiWorkflowStatus[]>>('/api/docs/notes/ai-workflows')
  return response.data.data
}

export async function retryNoteAiWorkflow(docId: string): Promise<NoteAiWorkflowStatus> {
  const response = await http.post<ApiResponse<NoteAiWorkflowStatus>>(`/api/docs/notes/ai-workflows/${encodeURIComponent(docId)}/retry`)
  return response.data.data
}
