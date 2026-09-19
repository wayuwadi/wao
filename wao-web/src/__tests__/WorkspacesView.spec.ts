import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
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
    setActivePinia(createPinia())
    useSessionStore().account = { member: { id: 'u1' } } as never
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
    expect(localStorage.getItem('wao-workspaces:v2:u1:wao-workspace-edit-draft:ws1')).toContain('Nacos 预发环境')
    await wrapper.get('.edit-actions .primary-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('网络不可用')
    expect(wrapper.get('.edit-actions .primary-button').text()).toContain('重试保存')

    await wrapper.get('.edit-actions .primary-button').trigger('click')
    await flushPromises()
    expect(saveWorkspace).toHaveBeenCalledTimes(2)
    expect(localStorage.getItem('wao-workspaces:v2:u1:wao-workspace-edit-draft:ws1')).toBeNull()
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

  it('新建草稿按成员隔离，不读取来源不明的旧草稿', async () => {
    localStorage.setItem('wao-workspace-create-draft', JSON.stringify({ name: '旧账号内容' }))
    const wrapper = mount(WorkspacesView)
    await flushPromises()
    await wrapper.get('.primary-button').trigger('click')
    const name = wrapper.get('.create-modal input')
    expect((name.element as HTMLInputElement).value).toBe('')
    await name.setValue('成员一草稿')
    useSessionStore().account = { member: { id: 'u2' } } as never
    await flushPromises()
    await wrapper.get('.primary-button').trigger('click')
    expect((wrapper.get('.create-modal input').element as HTMLInputElement).value).toBe('')
    useSessionStore().account = { member: { id: 'u1' } } as never
    await flushPromises()
    await wrapper.get('.primary-button').trigger('click')
    expect((wrapper.get('.create-modal input').element as HTMLInputElement).value).toBe('成员一草稿')
    expect(localStorage.getItem('wao-workspace-create-draft')).toContain('旧账号内容')
    wrapper.unmount()
  })
  it('保存中继续输入仍写草稿，旧响应不得覆盖新输入', async () => {
    vi.mocked(fetchWorkspaces).mockResolvedValue([{ ...detail(), groupCount: 1, linkCount: 1 }])
    vi.mocked(fetchWorkspace).mockResolvedValue(detail())
    let resolve!: (value: WorkspaceDetail) => void
    vi.mocked(saveWorkspace).mockImplementation(() => new Promise(r => { resolve = r }))
    const wrapper = mount(WorkspacesView)
    await flushPromises()
    await wrapper.get('.toolbar-actions .ghost-button').trigger('click')
    await wrapper.get('.link-editor input').setValue('先保存的值')
    await wrapper.get('.edit-actions .primary-button').trigger('click')
    await wrapper.get('.link-editor input').setValue('保存中的新输入')
    expect(localStorage.getItem('wao-workspaces:v2:u1:wao-workspace-edit-draft:ws1')).toContain('保存中的新输入')
    resolve(detail())
    await flushPromises()
    expect((wrapper.get('.link-editor input').element as HTMLInputElement).value).toBe('保存中的新输入')
    expect(wrapper.text()).toContain('有未保存修改')
    wrapper.unmount()
  })

  it('本机存储失败明确提示，重试不会丢掉输入', async () => {
    const wrapper = mount(WorkspacesView)
    await flushPromises()
    await wrapper.get('.primary-button').trigger('click')
    const fail = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    await wrapper.get('.create-modal input').setValue('不能丢的内容')
    expect(wrapper.get('[role="alert"]').text()).toContain('本机草稿未能保存')
    fail.mockRestore()
    await wrapper.get('[role="alert"] button').trigger('click')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(localStorage.getItem('wao-workspaces:v2:u1:wao-workspace-create-draft')).toContain('不能丢的内容')
    wrapper.unmount()
  })
})
