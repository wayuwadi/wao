import { http, unwrap } from './http'
import type { HotspotSourceStatus, Studio, TopicSuggestion, WorkspaceProfile } from '@/types'

export const getStudio = () => unwrap<Studio>(http.get('/api/agent/studio'))

/** 采集要挨个请求多个站点，比普通请求慢，放宽超时。 */
export const refreshHotspots = () =>
  unwrap<HotspotSourceStatus[]>(http.post('/api/agent/studio/hotspots/refresh', null, { timeout: 90_000 }))

export const saveProfile = (profile: Omit<WorkspaceProfile, 'configured'>) =>
  unwrap<WorkspaceProfile>(http.post('/api/agent/studio/profile', profile))

/** 推荐要过一次模型，可能要几十秒。 */
export const recommendTopics = (platform: string, count: number) =>
  unwrap<TopicSuggestion[]>(http.post('/api/agent/studio/recommend', { platform, count }, { timeout: 120_000 }))

export const markSuggestion = (suggestionId: string, status: 'USED' | 'DISMISSED') =>
  unwrap<void>(http.post(`/api/agent/studio/suggestions/${suggestionId}/mark?status=${status}`))
