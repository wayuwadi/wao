import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PublishCenter from './PublishCenter.vue'
import { createPublishTask, getPublishCenter, listMedia } from '@/api/publish'
import type { PublishCenter as PublishCenterData, PublishTask } from '@/types'

vi.mock('@/api/publish', () => ({
  createPublishTask: vi.fn(),
  getAuthorizeUrl: vi.fn(),
  getPublishCenter: vi.fn(),
  listMedia: vi.fn(),
  submitPublishTask: vi.fn(),
  unbindAccount: vi.fn(),
  updatePublishTask: vi.fn(),
  uploadMedia: vi.fn(),
}))

const center: PublishCenterData = {
  session: {
    accountId: 'acc-1', displayName: '娃哥', avatarText: '娃', workspaceId: 'aw-1',
    workspaceName: '娃哥的工作区', role: 'OWNER', demoContent: false,
  },
  ai: { available: false, provider: 'deepseek', model: 'deepseek-chat' },
  platforms: [{
    platform: 'DOUYIN', label: '抖音', oauthSupported: true, publishSupported: true,
    mediaKinds: ['VIDEO'], note: '配置官方凭据后可发布', accounts: [],
  }, {
    platform: 'WECHAT_VIDEO', label: '视频号', oauthSupported: false, publishSupported: false,
    mediaKinds: [], note: '没有第三方内容发布接口，只能导出后人工发布', accounts: [],
  }],
  tasks: [],
}

function task(): PublishTask {
  return {
    id: 'pt-1', platform: 'DOUYIN', platformLabel: '抖音', topic: '真实话题',
    tags: [], blocks: [], status: 'DRAFT', progress: 0, version: 1,
    createdAt: '2026-08-11T12:00:00', updatedAt: '2026-08-11T12:00:00',
  }
}

describe('真实发布中心', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getPublishCenter).mockResolvedValue(structuredClone(center))
    vi.mocked(listMedia).mockResolvedValue([])
    vi.mocked(createPublishTask).mockResolvedValue(task())
  })

  it('模型未配置时如实降级为手写稿且不请求模拟生成', async () => {
    const wrapper = mount(PublishCenter, { props: { initialTopic: '从真实热点生成内容' } })
    await flushPromises()

    expect(wrapper.text()).toContain('尚未配置模型，只能手工写稿')
    expect(wrapper.text()).toContain('没有第三方内容发布接口，只能导出后人工发布')
    await wrapper.get('.create-card > button').trigger('click')
    await flushPromises()

    expect(createPublishTask).toHaveBeenCalledWith('从真实热点生成内容', 'DOUYIN', false)
    wrapper.unmount()
  })

  it('内容管理只编辑草稿，内容发布才展示平台提交入口', async () => {
    vi.mocked(getPublishCenter).mockResolvedValue({ ...structuredClone(center), tasks: [task()] })
    vi.mocked(listMedia).mockResolvedValue([{
      id: 'asset-1', kind: 'VIDEO', originalName: '已上传成片.webm', contentType: 'video/webm',
      byteSize: 4096, createdAt: '2026-08-27T10:00:00',
    }])

    const content = mount(PublishCenter, { props: { mode: 'content' } })
    await flushPromises()
    expect(content.text()).toContain('新建内容')
    expect(content.text()).toContain('抖音内容稿')
    expect(content.text()).toContain('已上传成片.webm · 4.0 KB')
    expect(content.text()).not.toContain('连接账号')
    expect(content.text()).not.toContain('确认发布到抖音')
    content.unmount()

    const publishing = mount(PublishCenter, { props: { mode: 'publishing' } })
    await flushPromises()
    expect(publishing.text()).not.toContain('新建内容')
    expect(publishing.text()).toContain('抖音发布稿')
    expect(publishing.text()).toContain('确认发布到抖音')
    publishing.unmount()
  })
})
