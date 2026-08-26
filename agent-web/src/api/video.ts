import { http, unwrap } from './http'
import type { MediaAsset, VideoProject, VideoScene } from '@/types'

export const getVideoProject = () =>
  unwrap<VideoProject>(http.get('/api/agent/video-workflow'))

export const saveVideoProject = (version: number, title: string, scenes: VideoScene[]) =>
  unwrap<VideoProject>(http.post('/api/agent/video-workflow', { version, title, scenes }))

export const uploadRenderedVideo = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return unwrap<MediaAsset>(http.post('/api/agent/video-workflow/media', form, { timeout: 0 }))
}

export const completeVideoExport = (version: number, mediaAssetId: string) =>
  unwrap<VideoProject>(http.post('/api/agent/video-workflow/export', { version, mediaAssetId }))
