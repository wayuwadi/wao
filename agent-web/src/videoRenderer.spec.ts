import { describe, expect, it, vi } from 'vitest'
import { enabledDuration, sceneAt, supportedVideoMime } from './videoRenderer'
import type { VideoScene } from './types'

const scenes: VideoScene[] = [
  { id: 'a', kicker: '', title: '甲', body: '甲', seconds: 15, accent: '#6556D8', enabled: true },
  { id: 'b', kicker: '', title: '乙', body: '乙', seconds: 20, accent: '#347FC5', enabled: false },
  { id: 'c', kicker: '', title: '丙', body: '丙', seconds: 25, accent: '#1F9D7A', enabled: true },
]

describe('视频合成时间线', () => {
  it('总时长只计算启用分镜', () => expect(enabledDuration(scenes)).toBe(40))

  it('禁用分镜不会进入播放时间线', () => {
    expect(sceneAt(scenes, 14)?.scene.id).toBe('a')
    expect(sceneAt(scenes, 15)?.scene.id).toBe('c')
    expect(sceneAt(scenes, 39.9)?.localSeconds).toBeCloseTo(24.9)
  })

  it('优先选择带音频的 VP9 WebM', () => {
    vi.stubGlobal('MediaRecorder', { isTypeSupported: vi.fn(() => true) })
    expect(supportedVideoMime()).toBe('video/webm;codecs=vp9,opus')
    vi.unstubAllGlobals()
  })
})
