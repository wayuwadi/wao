import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { getSession } from '@/api/agent'
import { getStudio } from '@/api/studio'

vi.mock('@/api/agent', () => ({ getSession: vi.fn() }))
vi.mock('@/api/studio', () => ({
  addManualTopic: vi.fn(),
  getStudio: vi.fn(),
  initializeProfile: vi.fn(),
  markSuggestion: vi.fn(),
  recommendTopics: vi.fn(),
  refreshHotspots: vi.fn(),
  saveProfile: vi.fn(),
}))

describe('创作者工作台导航', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getSession).mockResolvedValue({
      accountId: 'acc-1', displayName: '创作者', avatarText: '创', workspaceId: 'aw-1',
      workspaceName: '内容工作室', role: 'OWNER', demoContent: false,
    })
    vi.mocked(getStudio).mockResolvedValue({
      profile: { configured: true, niche: '技术' }, sources: [], hotspots: [], suggestions: [],
    })
  })

  it('把重复入口合并为四个真实能力入口并按入口切换', async () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          PublishCenter: { props: ['mode'], template: '<div class="publish-stub">{{ mode }}</div>' },
          MaterialLibrary: { template: '<div class="material-stub">素材库</div>' },
          VideoWorkflow: { template: '<div class="video-stub">视频创作</div>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.get('.menu-group-label').text()).toBe('创作者工作台')
    expect(wrapper.findAll('.menu-item').map((item) => item.text())).toEqual([
      '内容工作台', '话题与热点', '账号管理', '素材库',
    ])
    expect(wrapper.get('.publish-stub').text()).toBe('workbench')

    await wrapper.findAll('.menu-item')[2].trigger('click')
    expect(wrapper.text()).toContain('保存账号定位')
    expect(wrapper.get('.publish-stub').text()).toBe('accounts')

    await wrapper.findAll('.menu-item')[1].trigger('click')
    expect(wrapper.text()).toContain('热点选题')

    await wrapper.findAll('.menu-item')[3].trigger('click')
    expect(wrapper.get('.material-stub').text()).toBe('素材库')
  })

  it('热点数据首次加载失败时仍显示四个菜单并允许重试', async () => {
    vi.mocked(getStudio).mockRejectedValueOnce(new Error('热点服务暂时不可用'))
    const wrapper = mount(App, {
      global: {
        stubs: {
          PublishCenter: { props: ['mode'], template: '<div class="publish-stub">{{ mode }}</div>' },
          MaterialLibrary: { template: '<div class="material-stub">素材库</div>' },
          VideoWorkflow: { template: '<div class="video-stub">视频创作</div>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.get('.menu-group-label').text()).toBe('创作者工作台')
    expect(wrapper.findAll('.menu-item')).toHaveLength(4)
    await wrapper.findAll('.menu-item')[1].trigger('click')
    expect(wrapper.get('.studio-load-state').text()).toContain('热点服务暂时不可用')

    await wrapper.findAll('.menu-item')[3].trigger('click')
    expect(wrapper.get('.material-stub').text()).toBe('素材库')

    vi.mocked(getStudio).mockResolvedValue({
      profile: { configured: true, niche: '技术' }, sources: [], hotspots: [], suggestions: [],
    })
    await wrapper.findAll('.menu-item')[1].trigger('click')
    await wrapper.get('.studio-load-state button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.studio-load-state').exists()).toBe(false)
    expect(wrapper.text()).toContain('热点选题')
  })
})
