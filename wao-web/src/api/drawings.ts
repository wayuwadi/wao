import { http, type ApiResponse } from './http'

export interface DrawingFileSummary {
  id: string
  title: string
  revision: number
  createdAt: string
  updatedAt: string
}

export interface DrawingFile extends DrawingFileSummary {
  sceneJson: string
}

export interface DrawingFileVersion {
  revision: number
  createdAt: string
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchDrawings = () =>
  data(http.get<ApiResponse<DrawingFileSummary[]>>('/api/drawings'))

export const fetchDrawing = (id: string) =>
  data(http.get<ApiResponse<DrawingFile>>(`/api/drawings/${id}`))

export const createDrawing = (title?: string) =>
  data(http.post<ApiResponse<DrawingFile>>('/api/drawings', { title }))

export const saveDrawing = (id: string, sceneJson: string, revision: number) =>
  data(http.put<ApiResponse<DrawingFile>>(`/api/drawings/${id}`, { sceneJson, revision }))

export const fetchDrawingVersions = (id: string) =>
  data(http.get<ApiResponse<DrawingFileVersion[]>>(`/api/drawings/${id}/versions`))

export const restoreDrawingVersion = (id: string, version: number, revision: number) =>
  data(
    http.post<ApiResponse<DrawingFile>>(`/api/drawings/${id}/versions/${version}/restore`, {
      revision,
    }),
  )

export const renameDrawing = (id: string, title: string) =>
  data(http.patch<ApiResponse<DrawingFileSummary>>(`/api/drawings/${id}`, { title }))

export const deleteDrawing = (id: string) =>
  data(http.delete<ApiResponse<null>>(`/api/drawings/${id}`))
