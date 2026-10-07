import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import WorkspaceConnectionPanel from '@/components/capabilities/WorkspaceConnectionPanel.vue'
import { useSessionStore } from '@/stores/session'
import type { WorkspaceCapability, WorkspaceMcpToken } from '@/api/workspaceMcp'
const api = vi.hoisted(() => ({
  createGranularWorkspaceToken: vi.fn(),
  fetchWorkspaceResources: vi.fn(),
  revokeWorkspaceMcpToken: vi.fn(),
  fetchWorkspaceMcpTokens: vi.fn(),
}))
vi.mock('@/api/workspaceMcp', () => api)
const capabilities: WorkspaceCapability[] = [
  {
    name: 'get_task',
    title: '读取任务',
    description: '读取详情',
    group: 'tasks',
    readOnly: true,
    cli: 'wao tasks get',
    mcp: 'get_task',
  },
  {
    name: 'update_task',
    title: '更新任务',
    description: '更新状态',
    group: 'tasks',
    readOnly: false,
    cli: 'wao tasks update',
    mcp: 'update_task',
  },
  {
    name: 'get_document',
    title: '读取文档',
    description: '读取正文',
    group: 'documents',
    readOnly: true,
    cli: 'wao documents get',
    mcp: 'get_document',
  },
]
function login(id = 'wd') {
  useSessionStore().accept({
    accountId: id,
    member: { id, name: id, initial: 'W', color: '#123', role: '成员' },
    identities: [],
    createdAt: '',
  })
}
async function render(token?: WorkspaceMcpToken) {
  const wrapper = mount(WorkspaceConnectionPanel, { props: { capabilities, token } })
  await flushPromises()
  return wrapper
}
describe('页内逐项连接授权', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    login()
    vi.clearAllMocks()
    api.fetchWorkspaceResources.mockResolvedValue({
      projects: [{ id: 'p1', name: '真实项目' }],
      knowledgeBases: [{ id: 'kb1', name: '真实知识库' }],
    })
    api.createGranularWorkspaceToken.mockResolvedValue({
      secret: 'one-time-secret',
      token: { id: 'new' },
    })
  })
  it.each([false, true])('新连接不自动选择 Pet 权限，延迟目录=%s', async (lateCatalog) => {
    const pet: WorkspaceCapability = {
      name: 'list_my_pets', title: '读取我的宠物', description: '',
      group: 'pets', readOnly: true, cli: 'wao pets list', mcp: null,
    }
    const all = [...capabilities, pet]
    const wrapper = mount(WorkspaceConnectionPanel, {
      props: { capabilities: lateCatalog ? [] : all },
    })
    if (lateCatalog) await wrapper.setProps({ capabilities: all })
    await flushPromises()
    await wrapper.findAll('.cap-group-picker button')
      .find((button) => button.text().startsWith('Pet 仓库'))!.trigger('click')
    const checkbox = wrapper.get('input[aria-label="允许读取我的宠物"]')
    expect((checkbox.element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.find('.cap-resource-scope').exists()).toBe(false)
    await checkbox.setValue(true)
    expect(JSON.parse(localStorage.getItem('wao:capability-connection-draft:v1:wd')!).permissions)
      .toContain('list_my_pets')
    expect(api.createGranularWorkspaceToken).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('没有弹层或自动签发，按项目和知识库分别保留选择', async () => {
    const wrapper = await render()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(api.createGranularWorkspaceToken).not.toHaveBeenCalled()
    await wrapper.get('input[placeholder="例如：我的 Codex"]').setValue('本机测试')
    await wrapper.get('.cap-resource-scope select').setValue('specific')
    await wrapper.get('.cap-scope-list input').setValue(true)
    await wrapper
      .findAll('.cap-group-picker button')
      .find((b) => b.text().startsWith('文档'))!
      .trigger('click')
    expect((wrapper.get('.cap-resource-scope select').element as HTMLSelectElement).value).toBe(
      'all',
    )
    await wrapper.get('.cap-resource-scope select').setValue('specific')
    await wrapper.get('.cap-scope-list input').setValue(true)
    const saved = JSON.parse(localStorage.getItem('wao:capability-connection-draft:v1:wd')!)
    expect(saved).toMatchObject({ name: '本机测试', projectIds: ['p1'], knowledgeBaseIds: ['kb1'] })
    wrapper.unmount()
    const restored = await render()
    expect(
      (restored.get('input[placeholder="例如：我的 Codex"]').element as HTMLInputElement).value,
    ).toBe('本机测试')
    login('other')
    await flushPromises()
    expect(
      (restored.get('input[placeholder="例如：我的 Codex"]').element as HTMLInputElement).value,
    ).toBe('')
    expect(localStorage.getItem('wao:capability-connection-draft:v1:wd')).toContain('本机测试')
    restored.unmount()
  })
  it('日常完整只填授权草稿，保留数据范围并由用户签发，不含公开发布', async () => {
    const wrapper = await render()
    await wrapper.setProps({
      capabilities: [
        ...capabilities,
        {
          name: 'publish_html',
          title: '发布页面',
          description: '',
          group: 'pages',
          readOnly: false,
          cli: 'wao pages publish',
          mcp: null,
        },
      ],
    })
    await wrapper.get('.cap-resource-scope select').setValue('specific')
    await wrapper.get('.cap-scope-list input').setValue(true)
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '日常完整')!
      .trigger('click')
    const saved = JSON.parse(localStorage.getItem('wao:capability-connection-draft:v1:wd')!)
    expect(saved.permissions).toEqual(['get_task', 'update_task', 'get_document'])
    expect(saved.projectIds).toEqual(['p1'])
    expect(api.createGranularWorkspaceToken).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('小记方案只包含相关权限，汇总范围保留；调整单项后取消方案选中状态', async () => {
    const wrapper = await render()
    await wrapper.setProps({
      capabilities: [
        ...capabilities,
        {
          name: 'create_note',
          title: '创建小记',
          description: '',
          group: 'documents',
          readOnly: false,
          cli: 'wao notes create',
          mcp: 'create_note',
        },
        {
          name: 'publish_html',
          title: '发布页面',
          description: '',
          group: 'pages',
          readOnly: false,
          cli: 'wao pages publish',
          mcp: null,
        },
      ],
    })
    await wrapper.get('.cap-resource-scope select').setValue('specific')
    await wrapper.get('.cap-scope-list input').setValue(true)
    const preset = wrapper
      .findAll('.cap-presets button')
      .find((button) => button.text() === '记录小记')!
    await preset.trigger('click')
    expect(
      JSON.parse(localStorage.getItem('wao:capability-connection-draft:v1:wd')!),
    ).toMatchObject({
      permissions: ['get_document', 'create_note'],
      projectIds: ['p1'],
    })
    expect(wrapper.get('[aria-label="授权汇总"]').text()).toContain('读取 1')
    expect(wrapper.get('[aria-label="授权汇总"]').text()).toContain('写入 1')
    expect(wrapper.get('[aria-label="授权汇总"]').text()).toContain('公开发布 0')
    expect(wrapper.get('[aria-label="授权汇总"]').text()).toContain('真实项目')
    expect(preset.attributes('aria-pressed')).toBe('true')
    await wrapper.get('input[aria-label="允许读取文档"]').setValue(false)
    expect(preset.attributes('aria-pressed')).toBe('false')
    expect(api.createGranularWorkspaceToken).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('失败保留草稿，成功明文只留当前页面，不写本机草稿', async () => {
    api.createGranularWorkspaceToken.mockRejectedValueOnce(new Error('服务器暂不可用'))
    const wrapper = await render()
    await wrapper.get('input[placeholder="例如：我的 Codex"]').setValue('测试连接')
    await wrapper.get('input[aria-label="允许更新任务"]').setValue(true)
    const sign = () =>
      wrapper
        .findAll('button')
        .find((b) => b.text() === '生成连接指令')!
        .trigger('click')
    await sign()
    await flushPromises()
    expect(wrapper.text()).toContain('服务器暂不可用')
    expect(localStorage.getItem('wao:capability-connection-draft:v1:wd')).toContain('update_task')
    await sign()
    await flushPromises()
    expect(api.createGranularWorkspaceToken).toHaveBeenLastCalledWith(
      expect.objectContaining({
        name: '测试连接',
        permissions: ['get_task', 'get_document', 'update_task'],
      }),
    )
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain(
      'one-time-secret',
    )
    expect(JSON.stringify(localStorage)).not.toContain('one-time-secret')
    wrapper.unmount()
    const fresh = await render()
    expect(fresh.find('textarea').exists()).toBe(false)
    fresh.unmount()
  })
  it('旧令牌保持只读展示，迁移只写新草稿，吊销需要页内确认', async () => {
    const token = {
      id: 'old',
      name: '历史连接',
      tokenPrefix: 'prefix',
      canWrite: true,
      canPublishHostedPage: false,
      createdAt: '2026-10-04',
      lastUsedAt: null,
      permissionMode: 'legacy',
    } as WorkspaceMcpToken
    const wrapper = await render(token)
    expect(wrapper.text()).toContain('旧版整体授权')
    expect(wrapper.get('input[aria-label="允许更新任务"]').attributes('disabled')).toBeDefined()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '复制为新授权草稿')!
      .trigger('click')
    expect(wrapper.emitted('newDraft')).toHaveLength(1)
    expect(api.createGranularWorkspaceToken).not.toHaveBeenCalled()
    expect(localStorage.getItem('wao:capability-connection-draft:v1:wd')).toContain(
      '历史连接 · 新授权',
    )
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '吊销连接')!
      .trigger('click')
    expect(api.revokeWorkspaceMcpToken).not.toHaveBeenCalled()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '确认吊销')!
      .trigger('click')
    await flushPromises()
    expect(api.revokeWorkspaceMcpToken).toHaveBeenCalledWith('old')
    wrapper.unmount()
  })
  it('旧整体授权不展示或迁移新增能力，汇总与原服务端兼容白名单一致', async () => {
    const token = {
      id: 'old',
      name: '旧连接',
      tokenPrefix: 'prefix',
      canWrite: true,
      canPublishHostedPage: false,
      createdAt: '',
      permissionMode: 'legacy',
    } as WorkspaceMcpToken
    const wrapper = await render(token)
    await wrapper.setProps({
      capabilities: [
        ...capabilities,
        {
          name: 'get_todo_day',
          title: '读取每日安排',
          description: '',
          group: 'tasks',
          readOnly: true,
          cli: 'wao day get',
          mcp: 'get_todo_day',
        },
        {
          name: 'record_daily_note',
          title: '记录进展',
          description: '',
          group: 'tasks',
          readOnly: false,
          cli: 'wao day record',
          mcp: 'record_daily_note',
        },
      ],
    })
    expect(
      (wrapper.get('input[aria-label="允许读取每日安排"]').element as HTMLInputElement).checked,
    ).toBe(false)
    expect(
      (wrapper.get('input[aria-label="允许记录进展"]').element as HTMLInputElement).checked,
    ).toBe(false)
    expect(wrapper.get('[aria-label="授权汇总"]').text()).toContain('读取 2')
    expect(wrapper.get('[aria-label="授权汇总"]').text()).toContain('写入 1')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '复制为新授权草稿')!
      .trigger('click')
    expect(
      JSON.parse(localStorage.getItem('wao:capability-connection-draft:v1:wd')!).permissions,
    ).toEqual(['get_task', 'update_task', 'get_document'])
    wrapper.unmount()
  })
})
