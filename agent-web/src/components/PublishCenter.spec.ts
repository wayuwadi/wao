import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PublishCenter from './PublishCenter.vue'
import { createPublishTask, getPublishCenter, listMedia } from '@/api/publish'
import { createKnowledgeArticle, listKnowledgeBases } from '@/api/knowledge'
import type { PublishCenter as PublishCenterData, PublishTask } from '@/types'

vi.mock('@/api/publish', () => ({
  createPublishTask: vi.fn(),
  getAuthorizeUrl: vi.fn(),
  getPublishCenter: vi.fn(),
  listMedia: vi.fn(),
  markPublishTaskPublished: vi.fn(),
  submitPublishTask: vi.fn(),
  unbindAccount: vi.fn(),
  updatePublishTask: vi.fn(),
  uploadMedia: vi.fn(),
}))

vi.mock('@/api/knowledge', () => ({
  createKnowledgeArticle: vi.fn(),
  listKnowledgeBases: vi.fn(),
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
    vi.mocked(listKnowledgeBases).mockResolvedValue([])
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

  it('统一工作台同时提供草稿编辑、实时预览和人工发布入口', async () => {
    vi.mocked(getPublishCenter).mockResolvedValue({ ...structuredClone(center), tasks: [task()] })
    vi.mocked(listMedia).mockResolvedValue([{
      id: 'asset-1', kind: 'VIDEO', originalName: '已上传成片.webm', contentType: 'video/webm',
      byteSize: 4096, createdAt: '2026-08-27T10:00:00',
    }])

    const content = mount(PublishCenter, { props: { mode: 'workbench' } })
    await flushPromises()
    expect(content.text()).toContain('新建内容')
    expect(content.text()).toContain('抖音内容稿')
    expect(content.text()).toContain('已上传成片.webm · 4.0 KB')
    expect(content.text()).not.toContain('连接账号')
    expect(content.text()).toContain('确认发布到抖音')
    expect(content.get('.live-preview iframe').attributes('srcdoc')).toContain('真实话题')
    expect(content.text()).toContain('输入后立即同步')
    content.unmount()
  })

  it('已保存图文可以预览 HTML 并原子存入真实知识库', async () => {
    const article = {
      ...task(), platform: 'WECHAT_MP', platformLabel: '公众号', topic: '我的WAO首篇图文',
      title: '我的 WAO', content: '开场\n\n## 为什么\n\n- 任务闭环\n- 知识沉淀',
    }
    vi.mocked(getPublishCenter).mockResolvedValue({ ...structuredClone(center), tasks: [article] })
    vi.mocked(listKnowledgeBases).mockResolvedValue([{
      id: 'kb-1', name: '内容库', folders: [{ id: 'folder-1', name: '文章', docs: [] }],
    }])
    vi.mocked(createKnowledgeArticle).mockResolvedValue({
      id: 'doc-1', kbId: 'kb-1', folderId: 'folder-1', title: '我的 WAO',
    })
    const wrapper = mount(PublishCenter, { props: { mode: 'content' } })
    await flushPromises()

    expect(wrapper.get('.live-preview iframe').attributes('srcdoc')).toContain('我的 WAO')
    await wrapper.findAll('.actions button').find((button) => button.text().includes('导出 HTML'))!.trigger('click')
    expect(wrapper.get('.export-dialog iframe').attributes('srcdoc')).toContain('/agent/article/my-wao-cover.png')
    await wrapper.get('.export-dialog header button').trigger('click')

    await wrapper.findAll('.actions button').find((button) => button.text().includes('存入知识库'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('.knowledge-dialog footer button').find((button) => button.text().includes('保存完整图文'))!.trigger('click')
    await flushPromises()

    expect(createKnowledgeArticle).toHaveBeenCalledWith(expect.objectContaining({
      kbId: 'kb-1', folderId: 'folder-1', title: '我的 WAO',
      blocks: expect.arrayContaining([{ h: '为什么', hLevel: 2 }]),
    }))
    expect(wrapper.text()).toContain('完整图文已进入知识库')
    wrapper.unmount()
  })
})
