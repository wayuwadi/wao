import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import VideoWorkflow from './VideoWorkflow.vue'
import { getVideoProject, saveVideoProject } from '@/api/video'
import type { VideoProject } from '@/types'

vi.mock('@/api/video', () => ({
  getVideoProject: vi.fn(),
  saveVideoProject: vi.fn(),
  uploadRenderedVideo: vi.fn(),
  completeVideoExport: vi.fn(),
}))

const project: VideoProject = {
  id: 'video-1',
  title: '三分钟认识 WAO',
  scenes: [
    { id: 'a', kicker: 'WAO', title: '第一段', body: '真实内容', seconds: 15, accent: '#6556D8', enabled: true },
    { id: 'b', kicker: 'WAO', title: '第二段', body: '真实内容', seconds: 20, accent: '#347FC5', enabled: true },
    { id: 'c', kicker: 'WAO', title: '第三段', body: '真实内容', seconds: 25, accent: '#1F9D7A', enabled: true },
  ],
  totalSeconds: 60,
  status: 'DRAFT',
  version: 1,
  updatedAt: '2026-08-26T11:00:00',
}

describe('视频工作流', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(getVideoProject).mockResolvedValue(structuredClone(project))
    vi.mocked(saveVideoProject).mockImplementation(async (_version, title, scenes) => ({
      ...structuredClone(project), title, scenes, version: 2, updatedAt: '2026-08-26T11:01:00',
    }))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('首次加载展示服务端分镜，不把初始化误判成用户编辑', async () => {
    const wrapper = mount(VideoWorkflow)
    await flushPromises()

    expect(wrapper.text()).toContain('1 分 0 秒')
    expect(wrapper.text()).toContain('分镜已与服务端同步')
    expect(saveVideoProject).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('编辑标题后标记未保存，并在停顿后自动保存新版本', async () => {
    const wrapper = mount(VideoWorkflow)
    await flushPromises()
    const title = wrapper.get('input[aria-label="视频标题"]')

    await title.setValue('WAO 功能导览')
    expect(wrapper.text()).toContain('有未保存修改')
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(saveVideoProject).toHaveBeenCalledWith(1, 'WAO 功能导览', expect.any(Array))
    expect(wrapper.text()).toContain('已保存 · v2')
    wrapper.unmount()
  })
})
