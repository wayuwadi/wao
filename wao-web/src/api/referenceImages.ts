import { http, type ApiResponse } from './http'

export interface ReferenceImage {
  id: string
  name: string
  contentType: string
  byteSize: number
  createdAt: string
  url: string
}

export async function listReferenceImages(): Promise<ReferenceImage[]> {
  const response = await http.get<ApiResponse<ReferenceImage[]>>('/api/reference-images')
  return response.data.data
}

export async function uploadReferenceImage(file: File, signal?: AbortSignal): Promise<ReferenceImage> {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResponse<ReferenceImage>>('/api/reference-images', form, { signal })
  return response.data.data
}
