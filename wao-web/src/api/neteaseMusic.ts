import { http, type ApiResponse } from './http'

export interface NeteaseMusicStatus {
  configured: boolean
  connected: boolean
  reauthRequired: boolean
  nickname?: string
  avatarUrl?: string
  expiresAt?: string
  message: string
}

export interface NeteaseMusicTrack {
  id: string
  name: string
  duration: number
  artists: string[]
  album?: string
  coverUrl?: string
  playable: boolean
  audition: boolean
  alg?: string
}

export interface NeteaseMusicTrackPage {
  items: NeteaseMusicTrack[]
  total: number
  offset: number
  limit: number
  playlistId: string
  playlistName: string
}

export interface NeteasePlayUrl {
  url: string
  bitrate: number
  auditionStart?: number
  auditionEnd?: number
}

export interface NeteasePlaybackPayload {
  songId: string
  action: 'startplay' | 'play'
  bitrate: number
  startLogTime: number
  playedSeconds?: number
  end?: 'playend' | 'interrupt' | 'exception' | 'ui'
  playlistId?: string
  alg?: string
  auditionStart?: number
  auditionEnd?: number
}

function networkHeaders() {
  const connection = (navigator as Navigator & {
    connection?: { type?: string; effectiveType?: string }
  }).connection
  let status = connection?.type === 'wifi' ? 'wifi' : connection?.effectiveType
  if (status === 'slow-2g') status = '2g'
  if (!['wifi', '2g', '3g', '4g', '5g'].includes(status ?? '')) status = 'wifi'
  return { 'X-WAO-Net-Status': status }
}

export async function getNeteaseMusicStatus() {
  return (await http.get<ApiResponse<NeteaseMusicStatus>>('/api/music/netease/status')).data.data
}

export async function beginNeteaseAuthorization() {
  return (await http.post<ApiResponse<{ authorizeUrl: string }>>('/api/music/netease/authorize', null, {
    headers: networkHeaders(),
  })).data.data
}

export async function disconnectNeteaseMusic() {
  await http.delete('/api/music/netease/binding')
}

export async function getNeteaseLikedSongs(offset = 0, limit = 30) {
  return (await http.get<ApiResponse<NeteaseMusicTrackPage>>('/api/music/netease/liked-songs', {
    params: { offset, limit },
    headers: networkHeaders(),
  })).data.data
}

export async function getNeteasePlayUrl(songId: string) {
  return (await http.get<ApiResponse<NeteasePlayUrl>>(`/api/music/netease/songs/${encodeURIComponent(songId)}/play-url`, {
    headers: networkHeaders(),
  })).data.data
}

export async function reportNeteasePlayback(payload: NeteasePlaybackPayload) {
  await http.post('/api/music/netease/playback', payload, { headers: networkHeaders() })
}
