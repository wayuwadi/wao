import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PublishCenter from './PublishCenter.vue'
import {
  createContentGroup, createPublishTask, getPublishCenter, listMedia, retryPublishGeneration,
  updatePublicationData, updatePublishTask,
} from '@/api/publish'
import { createKnowledgeArticle, listKnowledgeBases } from '@/api/knowledge'
import type { PublishCenter as PublishCenterData, PublishTask } from '@/types'

vi.mock('@/api/publish', () => ({
  createContentGroup: vi.fn(),
  createPublishTask: vi.fn(),
  getAuthorizeUrl: vi.fn(),
  getPublishCenter: vi.fn(),
  listMedia: vi.fn(),
  markPublishTaskPublished: vi.fn(),
  submitPublishTask: vi.fn(),
  unbindAccount: vi.fn(),
  updatePublicationData: vi.fn(),
  updatePublishTask: vi.fn(),
  retryPublishGeneration: vi.fn(),
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
  }, {
    platform: 'XIAOHONGSHU', label: '小红书', oauthSupported: false, publishSupported: false,
    mediaKinds: ['IMAGE'], note: '导出后人工发布', accounts: [],
  }, {
    platform: 'WECHAT_MP', label: '公众号', oauthSupported: false, publishSupported: false,
    mediaKinds: ['IMAGE'], note: '导出图文后人工发布', accounts: [],
  }],
  tasks: [],
}

function task(): PublishTask {
  return {
    id: 'pt-1', platform: 'DOUYIN', platformLabel: '抖音', topic: '真实话题',
    tags: [], blocks: [], sourceNotes: '', useWaoContext: true, contextSources: [],
    metricHistory: [], status: 'DRAFT', progress: 0, generationRetryable: false, version: 1,
    createdAt: '2026-08-11T12:00:00', updatedAt: '2026-08-11T12:00:00',
  }
}

describe('真实发布中心', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getPublishCenter).mockResolvedValue(structuredClone(center))
    vi.mocked(listMedia).mockResolvedValue([])
    vi.mocked(createPublishTask).mockResolvedValue(task())
    vi.mocked(createContentGroup).mockResolvedValue([task()])
    vi.mocked(listKnowledgeBases).mockResolvedValue([])
  })

  afterEach(() => vi.useRealTimers())

  it('模型未配置时仍提交真实三端生成意图，不静默创建空白手稿', async () => {
    const wrapper = mount(PublishCenter, { props: { initialTopic: '从真实热点生成内容' } })
    await flushPromises()

    expect(wrapper.text()).toContain('当前没有可用模型')
    expect(wrapper.text()).toContain('没有第三方内容发布接口，只能导出后人工发布')
    await wrapper.get('.create-card > button').trigger('click')
    await flushPromises()

    expect(createContentGroup).toHaveBeenCalledWith('从真实热点生成内容', '', true)
    wrapper.unmount()
  })

  it('三端任务完成后自动轮询，并可在手机标签间切换对应成稿', async () => {
    vi.useFakeTimers()
    const generating = {
      ...task(), contentGroupId: 'group-1', status: 'GENERATING', progress: 10,
      topic: '什么是 AI、Skill 和 MCP',
    }
    const xhsGenerating = { ...generating, id: 'pt-2', platform: 'XIAOHONGSHU', platformLabel: '小红书' }
    const wechatGenerating = { ...generating, id: 'pt-3', platform: 'WECHAT_MP', platformLabel: '公众号' }
    const completed = {
      ...generating, status: 'WAITING_REVIEW', progress: 45, coverText: '三层能力讲清楚',
      title: 'AI、Skill 与 MCP：程序员朋友讲明白', content: '先说结论：它们分别解决不同层次的问题。',
      blocks: [{ label: '钩子', text: '三个词到底差在哪？', seconds: 3 }],
    }
    const xhsCompleted = {
      ...xhsGenerating, status: 'WAITING_REVIEW', progress: 45,
      title: '终于讲懂 AI、Skill 和 MCP', content: '用三个生活例子一次分清。', tags: ['AI入门'],
    }
    const wechatCompleted = {
      ...wechatGenerating, status: 'WAITING_REVIEW', progress: 45,
      title: 'AI、Skill 与 MCP 的能力分层', content: '这是一篇完整的公众号文章。',
    }
    const available = { ...structuredClone(center), ai: { available: true, provider: 'WAO 推荐模型', model: 'deepseek-v4-pro' } }
    vi.mocked(getPublishCenter)
      .mockResolvedValueOnce(available)
      .mockResolvedValueOnce({ ...available, tasks: [generating, xhsGenerating, wechatGenerating] })
      .mockResolvedValueOnce({ ...available, tasks: [completed, xhsCompleted, wechatCompleted] })
    vi.mocked(createContentGroup).mockResolvedValue([generating, xhsGenerating, wechatGenerating])

    const wrapper = mount(PublishCenter, { props: { initialTopic: generating.topic, mode: 'content' } })
    await flushPromises()
    await wrapper.get('.create-card > button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('AI 正在生成标题和正文')

    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(wrapper.get('.editor input[maxlength="200"]').element).toHaveProperty('value', completed.title)
    expect(wrapper.get('.platform-preview').text()).toContain('抖音')
    expect(wrapper.get('.platform-preview').text()).toContain('小红书')
    expect(wrapper.get('.platform-preview').text()).toContain('公众号')
    expect(wrapper.get('.platform-preview').text()).toContain('三个词到底差在哪？')

    await wrapper.findAll('.platform-tabs button')[1]!.trigger('click')
    expect(wrapper.get('.editor input[maxlength="200"]').element).toHaveProperty('value', xhsCompleted.title)
    expect(wrapper.get('.platform-preview').text()).toContain('用三个生活例子一次分清。')
    wrapper.unmount()
  })

  it.each(['DOUYIN', 'XIAOHONGSHU', 'WECHAT_MP'])('清空 %s 审核稿时手机预览不恢复旧内容', async (platform) => {
    const saved = { ...task(), platform, title: '已保存旧标题', content: '已保存旧正文', coverText: '旧封面文案' }
    vi.mocked(getPublishCenter).mockResolvedValue({ ...structuredClone(center), tasks: [saved] })
    const wrapper = mount(PublishCenter, { props: { mode: 'content' } })
    await flushPromises()
    await wrapper.get('.editor input[maxlength="200"]').setValue('实时新标题')
    expect(wrapper.get('.phone-screen').text()).toContain('实时新标题')
    await wrapper.get('.editor input[maxlength="200"]').setValue('')
    await wrapper.get('.body-field textarea').setValue('')
    const phone = wrapper.get('.phone-screen').text()
    expect(phone).not.toContain('已保存旧标题')
    expect(phone).not.toContain('已保存旧正文')
    expect(phone).toContain('标题待填写')
    expect(phone).toContain('正文待填写')
    wrapper.unmount()
  })

  it('生成新内容前保护当前未保存的审核稿和创作参考', async () => {
    vi.mocked(getPublishCenter).mockResolvedValue({ ...structuredClone(center), tasks: [task()] })
    const wrapper = mount(PublishCenter, { props: { mode: 'content' } })
    await flushPromises()
    await wrapper.get('.editor input[maxlength="200"]').setValue('尚未保存的修改')
    await wrapper.get('.create-card > input').setValue('下一个主题')
    await wrapper.get('.source-notes textarea').setValue('下一篇的参考')
    await wrapper.get('.create-card > button').trigger('click')
    expect(createContentGroup).not.toHaveBeenCalled()
    expect(wrapper.get('.error').text()).toContain('请先保存')
    expect(wrapper.get('.editor input[maxlength="200"]').element).toHaveProperty('value', '尚未保存的修改')
    expect(wrapper.get('.source-notes textarea').element).toHaveProperty('value', '下一篇的参考')
    wrapper.unmount()
  })

  it('生成中的回车不重复提交，失败后保留标题与参考供重试', async () => {
    let reject!: (error: Error) => void
    vi.mocked(createContentGroup).mockReturnValueOnce(new Promise((_, fail) => { reject = fail }))
    const wrapper = mount(PublishCenter, { props: { mode: 'content' } })
    await flushPromises()
    await wrapper.get('.create-card > input').setValue('保留创作标题')
    await wrapper.get('.source-notes textarea').setValue('保留参考资料')
    expect(wrapper.emitted('dirty-change')?.at(-1)).toEqual([true])
    await wrapper.get('.create-card > button').trigger('click')
    await wrapper.get('.create-card > input').trigger('keyup.enter')
    expect(createContentGroup).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.source-notes textarea').attributes('disabled')).toBeDefined()
    reject(new Error('模型暂时不可用'))
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('模型暂时不可用')
    expect(wrapper.get('.create-card > input').element).toHaveProperty('value', '保留创作标题')
    expect(wrapper.get('.source-notes textarea').element).toHaveProperty('value', '保留参考资料')
    expect(wrapper.get('.create-card > button').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('生成失败时提供原任务的一键重试', async () => {
    const failed = {
      ...task(), status: 'FAILED', progress: 25, generationRetryable: true,
      errorMessage: '生成文案失败：模型暂时繁忙',
    }
    vi.mocked(getPublishCenter).mockResolvedValue({ ...structuredClone(center), tasks: [failed] })
    vi.mocked(retryPublishGeneration).mockResolvedValue({
      ...failed, status: 'GENERATING', errorMessage: undefined, generationRetryable: true,
    })
    const wrapper = mount(PublishCenter, { props: { mode: 'content' } })
    await flushPromises()
    await wrapper.get('.task-error button').trigger('click')
    await flushPromises()
    expect(retryPublishGeneration).toHaveBeenCalledWith('pt-1')
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
    expect(content.get('.platform-preview').text()).toContain('标题待填写')
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

    expect(wrapper.get('.platform-preview').text()).toContain('我的 WAO')
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

  it('公众号文章插入带语言代码与画图后实时预览，并沿既有保存接口持久化', async () => {
    const article = {
      ...task(), platform: 'WECHAT_MP', platformLabel: '公众号', title: '嵌入能力', content: '开场',
    }
    vi.mocked(getPublishCenter).mockResolvedValue({ ...structuredClone(center), tasks: [article] })
    vi.mocked(updatePublishTask).mockResolvedValue({
      ...article,
      version: 2,
      content: '开场\n\n```python\nprint("WAO")\n```\n\n```mermaid\nflowchart LR\n A[开始] --> B[完成]\n```',
    })
    const wrapper = mount(PublishCenter, { props: { mode: 'content' } })
    await flushPromises()

    const toolbar = wrapper.get('.embed-toolbar')
    expect(wrapper.get('.body-field').element.tagName).toBe('DIV')
    expect(wrapper.find('.body-field > label').exists()).toBe(false)
    await toolbar.findAll('button')[1]!.trigger('click')
    await wrapper.get('.embed-dialog select').setValue('python')
    await wrapper.get('.embed-dialog textarea').setValue('print("WAO")')
    await wrapper.get('.embed-dialog').trigger('submit')
    expect(wrapper.get('.platform-preview').text()).toContain('python')
    expect(wrapper.get('.platform-preview').text()).toContain('print("WAO")')

    await toolbar.findAll('button')[0]!.trigger('click')
    await wrapper.get('.embed-dialog').trigger('submit')
    expect(wrapper.get('.platform-preview').text()).toContain('flowchart LR')
    expect(wrapper.text()).toContain('有未保存修改')

    await wrapper.findAll('.actions button').find((button) => button.text().includes('保存审核稿'))!.trigger('click')
    await flushPromises()
    expect(updatePublishTask).toHaveBeenCalledWith('pt-1', expect.objectContaining({
      content: expect.stringContaining('```python\nprint("WAO")\n```'),
    }))
    expect(updatePublishTask).toHaveBeenCalledWith('pt-1', expect.objectContaining({
      content: expect.stringContaining('```mermaid\nflowchart LR'),
    }))
  })

  it('已发布内容保存链接并追加一组效果数据', async () => {
    const published = {
      ...task(), status: 'PUBLISHED', progress: 100, version: 3,
      publishedAt: '2026-09-04T10:00:00', publicationUrl: '',
    }
    vi.mocked(getPublishCenter).mockResolvedValue({ ...structuredClone(center), tasks: [published] })
    vi.mocked(updatePublicationData).mockResolvedValue({
      ...published, version: 4, publicationUrl: 'https://example.com/article',
      metricHistory: [{
        id: 'metric-1', views: 128, likes: 9, comments: 2, favorites: 3, shares: 1,
        recordedAt: '2026-09-04T12:00:00',
      }],
    })
    const wrapper = mount(PublishCenter, { props: { mode: 'workbench' } })
    await flushPromises()

    await wrapper.get('.publication-data input[type="url"]').setValue('https://example.com/article')
    await wrapper.get('.publication-data input[type="number"]').setValue('128')
    await wrapper.get('.publication-data > button').trigger('click')
    await flushPromises()

    expect(updatePublicationData).toHaveBeenCalledWith('pt-1', expect.objectContaining({
      version: 3, publicationUrl: 'https://example.com/article', views: 128,
    }))
  })
})
