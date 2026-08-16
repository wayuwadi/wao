import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import VideoStudio from './VideoStudio.vue'
import { createVideo, getVideoCenter } from '@/api/video'
import type { VideoCenter } from '@/types'

vi.mock('@/api/video', () => ({
  createVideo: vi.fn(),
  getVideoCenter: vi.fn(),
  retryVideo: vi.fn(),
}))

const unavailable: VideoCenter = {
  capability: {
    available: false,
    provider: 'OpenAI',
    models: ['sora-2'],
    durations: [4, 8, 12],
    sizes: ['720x1280', '1280x720'],
    shutdownDate: '2026-09-24',
    note: '尚未配置 OpenAI Videos API Key，不能生成真实视频',
  },
  tasks: [],
}

describe('真实视频工作台', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
    vi.mocked(getVideoCenter).mockResolvedValue(structuredClone(unavailable))
  })

  it('没有真实凭据时禁用生成并保持空数据，不展示样例视频', async () => {
    const wrapper = mount(VideoStudio)
    await flushPromises()

    expect(wrapper.text()).toContain('尚未配置 OpenAI Videos API Key')
    expect(wrapper.text()).toContain('还没有视频')
    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.get('.options button').attributes('disabled')).toBeDefined()
    expect(createVideo).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('恢复未提交提示词草稿且后台读取不会覆盖输入', async () => {
    window.localStorage.setItem('wao-agent-video-draft-v1', JSON.stringify({
      prompt: '本地未提交镜头', model: 'sora-2', seconds: 8, size: '1280x720',
    }))
    const wrapper = mount(VideoStudio)
    await flushPromises()

    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('本地未提交镜头')
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('sora-2')
    wrapper.unmount()
  })
})
