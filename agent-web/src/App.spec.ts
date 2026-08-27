import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { getSession } from '@/api/agent'
import { getStudio } from '@/api/studio'

vi.mock('@/api/agent', () => ({ getSession: vi.fn() }))
vi.mock('@/api/studio', () => ({
  getStudio: vi.fn(),
  markSuggestion: vi.fn(),
  recommendTopics: vi.fn(),
  refreshHotspots: vi.fn(),
  saveProfile: vi.fn(),
}))

describe('内容创作工作台导航', () => {
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

  it('在内容创作组提供五个真实能力入口并按入口切换', async () => {
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

    expect(wrapper.get('.menu-group-label').text()).toBe('内容创作')
    expect(wrapper.findAll('.menu-item').map((item) => item.text())).toEqual([
      '账号管理', '内容管理', '素材管理', '内容创作', '内容发布',
    ])
    expect(wrapper.text()).toContain('热点选题')

    await wrapper.findAll('.menu-item')[0].trigger('click')
    expect(wrapper.text()).toContain('保存账号定位')
    expect(wrapper.get('.publish-stub').text()).toBe('accounts')

    await wrapper.findAll('.menu-item')[1].trigger('click')
    expect(wrapper.get('.publish-stub').text()).toBe('content')

    await wrapper.findAll('.menu-item')[2].trigger('click')
    expect(wrapper.get('.material-stub').text()).toBe('素材库')

    await wrapper.findAll('.menu-item')[4].trigger('click')
    expect(wrapper.get('.publish-stub').text()).toBe('publishing')
  })
})
