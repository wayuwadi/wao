import { http, type ApiResponse } from './http'

export type VocabularyState = 'LEARNING' | 'MASTERED'

export interface VocabularyEntry {
  id: string
  term: string
  meaning: string
  context: string
  state: VocabularyState
  encounterCount: number
  reviewCount: number
  lastEncounteredAt: string
  lastReviewedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CaptureVocabularyResult {
  entry: VocabularyEntry
  created: boolean
}

const data = <T>(request: Promise<{ data: ApiResponse<T> }>) =>
  request.then((response) => response.data.data)

export const fetchVocabulary = () =>
  data(http.get<ApiResponse<VocabularyEntry[]>>('/api/vocabulary'))

export const captureVocabulary = (term: string) =>
  data(http.post<ApiResponse<CaptureVocabularyResult>>('/api/vocabulary', { term }))

export const updateVocabulary = (
  id: string,
  payload: { meaning: string; context: string; state: VocabularyState },
) => data(http.patch<ApiResponse<VocabularyEntry>>(`/api/vocabulary/${id}`, payload))

export const reviewVocabulary = (id: string, state: VocabularyState) =>
  data(http.post<ApiResponse<VocabularyEntry>>(`/api/vocabulary/${id}/reviews`, { state }))

export const deleteVocabulary = (id: string) =>
  data(http.delete<ApiResponse<null>>(`/api/vocabulary/${id}`))
