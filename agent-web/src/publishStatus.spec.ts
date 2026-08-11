import { describe, expect, it } from 'vitest'
import {
  canSubmitPublish,
  formatBytes,
  isPublishEditable,
  isPublishInFlight,
  publishStatusLabel,
} from './publishStatus'

describe('publishStatus', () => {
  it('翻译已知状态并原样返回未知状态', () => {
    expect(publishStatusLabel('WAITING_REVIEW')).toBe('等待人工确认')
    expect(publishStatusLabel('PUBLISHED')).toBe('已发布')
    expect(publishStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })

  it('只有生成中和发布中需要轮询', () => {
    expect(isPublishInFlight('GENERATING')).toBe(true)
    expect(isPublishInFlight('PUBLISHING')).toBe(true)
    expect(isPublishInFlight('WAITING_REVIEW')).toBe(false)
    expect(isPublishInFlight('PUBLISHED')).toBe(false)
  })

  it('发布中和已发布的任务不可再编辑', () => {
    expect(isPublishEditable('DRAFT')).toBe(true)
    expect(isPublishEditable('FAILED')).toBe(true)
    expect(isPublishEditable('PUBLISHING')).toBe(false)
    expect(isPublishEditable('PUBLISHED')).toBe(false)
  })

  it('平台没有官方发布接口时不给提交入口', () => {
    expect(canSubmitPublish('WAITING_REVIEW', true)).toBe(true)
    expect(canSubmitPublish('FAILED', true)).toBe(true)
    expect(canSubmitPublish('WAITING_REVIEW', false)).toBe(false)
    expect(canSubmitPublish('PUBLISHED', true)).toBe(false)
  })

  it('按量级格式化素材大小', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(2048)).toBe('2.0 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB')
  })
})
