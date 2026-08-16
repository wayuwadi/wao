import { describe, expect, it } from 'vitest'
import { formatVideoSize, isVideoInFlight, videoStatusLabel } from './videoStatus'

describe('视频任务状态', () => {
  it('显示真实异步状态并识别需要轮询的阶段', () => {
    expect(videoStatusLabel('DOWNLOADING')).toBe('正在保存视频')
    expect(isVideoInFlight('QUEUED')).toBe(true)
    expect(isVideoInFlight('COMPLETED')).toBe(false)
  })

  it('把官方尺寸转成可读画幅', () => {
    expect(formatVideoSize('1280x720')).toBe('横屏 1280×720')
    expect(formatVideoSize('720x1280')).toBe('竖屏 720×1280')
  })
})
