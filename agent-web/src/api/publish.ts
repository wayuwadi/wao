import { http, unwrap } from './http'
import type { MediaAsset, PlatformSummary, PublishCenter, PublishTask } from '@/types'

export const getPublishCenter = () => unwrap<PublishCenter>(http.get('/api/agent/publish/center'))

export const getPlatforms = () => unwrap<PlatformSummary[]>(http.get('/api/agent/platforms'))

export const getAuthorizeUrl = (platform: string) =>
  unwrap<{ authorizeUrl: string }>(http.get(`/api/agent/platforms/${platform}/authorize-url`))

export const unbindAccount = (platformAccountId: string) =>
  unwrap<void>(http.delete(`/api/agent/platforms/accounts/${platformAccountId}`))

export const createPublishTask = (
  topic: string,
  platform: string,
  generateCopy: boolean,
  sourceHotspotId?: string,
) => unwrap<PublishTask>(http.post('/api/agent/publish/tasks',
  { topic, platform, generateCopy, sourceHotspotId }))

export const updatePublishTask = (taskId: string, payload: {
  version: number
  title?: string
  content?: string
  tags?: string[]
  platformAccountId?: string
  mediaAssetId?: string
}) => unwrap<PublishTask>(http.post(`/api/agent/publish/tasks/${taskId}`, payload))

export const submitPublishTask = (taskId: string) =>
  unwrap<PublishTask>(http.post(`/api/agent/publish/tasks/${taskId}/submit`))

/** 视频可能上百 MB，这里必须绕开 http 实例默认的 10s 超时。 */
export const uploadMedia = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return unwrap<MediaAsset>(http.post('/api/agent/publish/media', form, { timeout: 0 }))
}

export const listMedia = () => unwrap<MediaAsset[]>(http.get('/api/agent/publish/media'))
