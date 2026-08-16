import type { FileItem } from '@/types'

const AUDIO_EXTENSION = /\.(?:mp3|m4a|aac|wav|ogg|oga|opus|flac|webm)$/i

/** 只把真实文件库中有稳定 id、且 MIME 或扩展名明确为音频的条目交给播放器。 */
export function isAudioFile(file: FileItem): boolean {
  if (!file.id) return false
  return file.contentType?.toLowerCase().startsWith('audio/') === true || AUDIO_EXTENSION.test(file.name)
}

/** 播放器标题隐藏已知音频扩展名，保留用户上传时的真实文件名主体。 */
export function audioTitle(name: string): string {
  return name.replace(AUDIO_EXTENSION, '') || name
}

export function audioStreamUrl(id: string): string {
  return `/api/files/${encodeURIComponent(id)}/stream`
}

export function formatPlaybackTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00'
  const whole = Math.floor(seconds)
  const minutes = Math.floor(whole / 60)
  const remain = whole % 60
  return `${String(minutes).padStart(2, '0')}:${String(remain).padStart(2, '0')}`
}
