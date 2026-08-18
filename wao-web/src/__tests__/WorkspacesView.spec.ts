import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkspacesView from '@/views/WorkspacesView.vue'
import { fetchProjects } from '@/api/board'
import {
  createWorkspace,
  fetchWorkspace,
  fetchWorkspaces,
  saveWorkspace,
  type WorkspaceDetail,
} from '@/api/workspaces'

vi.mock('@/api/board', () => ({ fetchProjects: vi.fn<typeof fetchProjects>() }))
vi.mock('@/api/workspaces', () => ({
  createWorkspace: vi.fn<typeof createWorkspace>(),
  deleteWorkspace: vi.fn<() => Promise<null>>(),
  fetchWorkspace: vi.fn<typeof fetchWorkspace>(),
  fetchWorkspaces: vi.fn<typeof fetchWorkspaces>(),
  saveWorkspace: vi.fn<typeof saveWorkspace>(),
}))

function detail(): WorkspaceDetail {
  return {
    id: 'ws1',
    name: '能良工作',
    description: '常用环境与后台',
    projectId: 'p1',
    projectName: '娃哥的能良工作',
    version: 1,
    createdAt: '2026-08-18T10:00:00',
    updatedAt: '2026-08-18T10:00:00',
    groups: [
      {
        id: 'wg1',
        name: '环境',
        icon: 'ri-server-line',
        order: 0,
        links: [
          {
            id: 'wl1',
            title: 'Nacos 测试环境',
            url: 'http://nacos.test.local',
            description: '服务发现',
            environment: '测试',
            icon: 'ri-links-line',
            order: 0,
            favorite: true,
          },
        ],
      },
    ],
  }
}

describe('个人工作台', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(fetchProjects).mockResolvedValue([])
    vi.mocked(fetchWorkspaces).mockResolvedValue([])
  })

  it('空数据时创建真实空间并直接进入整理状态', async () => {
    vi.mocked(createWorkspace).mockResolvedValue(detail())
    const wrapper = mount(WorkspacesView)
    await flushPromises()

    expect(wrapper.text()).toContain('从第一个工作空间开始')
    await wrapper.get('.empty-card button').trigger('click')
    await wrapper.get('.create-modal input').setValue('能良工作')
    await wrapper.get('.create-modal').trigger('submit')
    await flushPromises()

    expect(createWorkspace).toHaveBeenCalledWith({
      name: '能良工作',
      description: '',
      projectId: null,
    })
    expect(wrapper.text()).toContain('修改会立即保存在本机草稿中')
    expect(wrapper.find('.workspace-editor').exists()).toBe(true)
    wrapper.unmount()
  })

  it('保存失败保留完整草稿并允许原地重试', async () => {
    vi.mocked(fetchWorkspaces).mockResolvedValue([
      {
        id: 'ws1',
        name: '能良工作',
        description: '',
        projectId: null,
        projectName: '',
        groupCount: 1,
        linkCount: 1,
        updatedAt: '2026-08-18T10:00:00',
      },
    ])
    vi.mocked(fetchWorkspace).mockResolvedValue(detail())
    vi.mocked(saveWorkspace)
      .mockRejectedValueOnce(new Error('网络不可用'))
      .mockResolvedValueOnce(detail())
    const wrapper = mount(WorkspacesView)
    await flushPromises()

    await wrapper.get('.toolbar-actions .ghost-button').trigger('click')
    const title = wrapper.get('.link-editor input')
    await title.setValue('Nacos 预发环境')
    expect(localStorage.getItem('wao-workspace-edit-draft:ws1')).toContain('Nacos 预发环境')
    await wrapper.get('.edit-actions .primary-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('网络不可用')
    expect(wrapper.get('.edit-actions .primary-button').text()).toContain('重试保存')

    await wrapper.get('.edit-actions .primary-button').trigger('click')
    await flushPromises()
    expect(saveWorkspace).toHaveBeenCalledTimes(2)
    expect(localStorage.getItem('wao-workspace-edit-draft:ws1')).toBeNull()
    expect(wrapper.text()).toContain('已保存')
    wrapper.unmount()
  })

  it('浏览态可以搜索环境和备注，链接安全地在新页签打开', async () => {
    vi.mocked(fetchWorkspaces).mockResolvedValue([
      {
        id: 'ws1',
        name: '能良工作',
        description: '',
        projectId: null,
        projectName: '',
        groupCount: 1,
        linkCount: 1,
        updatedAt: '2026-08-18T10:00:00',
      },
    ])
    vi.mocked(fetchWorkspace).mockResolvedValue(detail())
    const wrapper = mount(WorkspacesView)
    await flushPromises()

    const link = wrapper.get('.group-section .link-card')
    expect(link.attributes()).toMatchObject({
      target: '_blank',
      rel: 'noopener noreferrer',
      href: 'http://nacos.test.local',
    })
    await wrapper.get('.search-box input').setValue('不存在')
    expect(wrapper.text()).toContain('没有匹配“不存在”的链接')
    await wrapper.get('.search-box input').setValue('服务发现')
    expect(wrapper.text()).toContain('Nacos 测试环境')
    wrapper.unmount()
  })
})
