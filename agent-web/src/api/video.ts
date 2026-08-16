import { http, unwrap } from './http'
import type { VideoCenter, VideoGeneration } from '@/types'

export const getVideoCenter = () => unwrap<VideoCenter>(http.get('/api/agent/videos'))

export const createVideo = (payload: { prompt: string; model: string; seconds: number; size: string }) =>
  unwrap<VideoGeneration>(http.post('/api/agent/videos', payload, { timeout: 150_000 }))

export const retryVideo = (taskId: string) =>
  unwrap<VideoGeneration>(http.post(`/api/agent/videos/${taskId}/retry`, null, { timeout: 150_000 }))
