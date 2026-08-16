import { describe, expect, it } from 'vitest'
import { audioStreamUrl, audioTitle, formatPlaybackTime, isAudioFile } from '@/utils/audio'

const base = { size: '1 MB', icon: 'ri-file-line', time: '刚刚', by: 'wg', link: '/download' }

describe('音乐文件识别', () => {
  it('优先识别服务端 MIME，并兼容旧文件的音频扩展名', () => {
    expect(isAudioFile({ ...base, id: 'a', name: '录音.bin', contentType: 'audio/mpeg' })).toBe(true)
    expect(isAudioFile({ ...base, id: 'b', name: '夜雨.FLAC', contentType: 'application/octet-stream' })).toBe(true)
    expect(isAudioFile({ ...base, id: 'c', name: '说明.pdf', contentType: 'application/pdf' })).toBe(false)
    expect(isAudioFile({ ...base, name: '演示.mp3', contentType: 'audio/mpeg' })).toBe(false)
  })

  it('生成安全流地址和稳定播放文案', () => {
    expect(audioStreamUrl('song/a b')).toBe('/api/files/song%2Fa%20b/stream')
    expect(audioTitle('雨夜.m4a')).toBe('雨夜')
    expect(formatPlaybackTime(65.9)).toBe('01:05')
    expect(formatPlaybackTime(Number.NaN)).toBe('00:00')
  })
})
