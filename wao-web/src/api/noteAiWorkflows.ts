import { http, type ApiResponse } from './http'

export interface NoteAiWorkflowStatus {
  docId: string
  title: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'
  image: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'
  polish: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'
  message: string | null
  stages?: Record<string, { reasonCode: string; message: string; retryable: boolean; action: string; jobId: string; sourceVersion: number }>
}

export async function fetchNoteAiWorkflows(): Promise<NoteAiWorkflowStatus[]> {
  const response = await http.get<ApiResponse<NoteAiWorkflowStatus[]>>('/api/docs/notes/ai-workflows')
  return response.data.data
}

export async function retryNoteAiWorkflow(docId: string): Promise<NoteAiWorkflowStatus> {
  const response = await http.post<ApiResponse<NoteAiWorkflowStatus>>(`/api/docs/notes/ai-workflows/${encodeURIComponent(docId)}/retry`)
  return response.data.data
}

export async function startSavedNoteStage(docId: string, stage: string, expectedRev: number, requestId: string): Promise<NoteAiWorkflowStatus> {
  return (await http.post<ApiResponse<NoteAiWorkflowStatus>>(`/api/docs/notes/${encodeURIComponent(docId)}/ai-stages/${stage}`, { expectedRev, requestId })).data.data
}
