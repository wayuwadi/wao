import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import CapabilityCenterView from '@/views/CapabilityCenterView.vue'
import { useSessionStore } from '@/stores/session'
import { clearSkillPackageDraft } from '@/utils/skillPackageDrafts'
const api = vi.hoisted(() => ({
  fetchWorkspaceCapabilities: vi.fn(),
  fetchWorkspaceMcpTokens: vi.fn(),
  fetchWorkspaceResources: vi.fn(),
  fetchSkills: vi.fn(),
  fetchMySkills: vi.fn(),
  fetchSkillCategories: vi.fn(),
  fetchPets: vi.fn(),
  fetchMyPets: vi.fn(),
  fetchPublisher: vi.fn(),
  inspectSkillPackage: vi.fn(),
  createSkillDraft: vi.fn(),
  savePublisher: vi.fn(),
  submitSkill: vi.fn(),
}))
vi.mock('@/api/workspaceMcp', () => ({
  ...api,
  createGranularWorkspaceToken: vi.fn(),
  revokeWorkspaceMcpToken: vi.fn(),
}))
vi.mock('@/api/skills', () => api)
vi.mock('@/api/pets', () => api)
async function render(path = '/capabilities', authed = true) {
  const pinia = createPinia()
  setActivePinia(pinia)
  if (authed)
    useSessionStore().accept({
      accountId: 'wd',
      member: { id: 'wd', name: 'wage', initial: 'W', color: '#fff', role: '成员' },
      identities: [],
      createdAt: '',
    })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      ...['/capabilities', '/capabilities/publish/:kind', '/capabilities/skills/:handle/:slug'].map(
        (path) => ({ path, component: CapabilityCenterView }),
      ),
      { path: '/login', component: { template: '<p>登录</p>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}
describe('能力中心真实目录与区域', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    localStorage.clear()
    await clearSkillPackageDraft('wao-skill-publish-draft-v2:author:new')
    api.fetchWorkspaceCapabilities.mockResolvedValue({
      capabilities: [
        {
          name: 'get_task',
          title: '读取任务',
          description: '当前成员可见任务',
          group: 'tasks',
          readOnly: true,
          cli: 'wao tasks get ID',
          mcp: 'get_task',
        },
      ],
    })
    api.fetchSkills.mockResolvedValue({ items: [], total: 0 })
    api.fetchPets.mockResolvedValue({ items: [], hasMore: false })
    api.fetchMySkills.mockResolvedValue([])
    api.fetchMyPets.mockResolvedValue([])
    api.fetchWorkspaceMcpTokens.mockResolvedValue([])
    api.fetchWorkspaceResources.mockResolvedValue({ projects: [], knowledgeBases: [] })
    api.fetchSkillCategories.mockResolvedValue([])
    api.fetchPublisher.mockResolvedValue({ handle: 'author', displayName: '作者' })
    api.inspectSkillPackage.mockResolvedValue({
      name: '文件草稿',
      description: '真实选中内容',
      suggestedSlug: 'test',
      findings: [],
      fileCount: 1,
    })
  })
  it('显示后端目录的可见分组，应用接入保持规划状态', async () => {
    const { wrapper } = await render()
    const group = wrapper.get('.cap-capability-group')
    expect(group.attributes('style') ?? '').not.toContain('display: none')
    expect(group.text()).toContain('读取任务')
    expect(wrapper.text()).toContain('1 项业务操作')
    expect(wrapper.get('.cap-type-filters').text()).not.toContain('应用接入')
    expect(wrapper.get('.cap-entry-grid').text()).not.toContain('外部应用')
    expect(wrapper.get('.cap-planning').attributes('open')).toBeUndefined()
    wrapper.unmount()
  })
  it('旧应用接入深链展开规划说明，同时保留真实能力列表', async () => {
    const { wrapper, router } = await render('/capabilities?type=应用接入')
    expect(wrapper.get('.cap-planning').attributes('open')).toBeDefined()
    expect(wrapper.get('.cap-planning').text()).toContain('目前没有可用的授权')
    expect(wrapper.get('.cap-entry-grid').text()).toContain('WAO CLI')
    expect(wrapper.get('.cap-entry-grid').text()).not.toContain('外部应用')
    await wrapper.get('.cap-planning a').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query.type).toBe('Skill')
    wrapper.unmount()
  })
  it('游客不能签发，目录失败可重试且不会显示虚假能力', async () => {
    api.fetchWorkspaceCapabilities.mockRejectedValueOnce(new Error('目录不可用'))
    const { wrapper } = await render('/capabilities?area=connections', false)
    expect(wrapper.text()).toContain('登录后管理连接与发布')
    expect(api.fetchWorkspaceMcpTokens).not.toHaveBeenCalled()
    wrapper.unmount()
    const view = await render()
    expect(view.wrapper.get('.cap-capability-group').text()).toContain('读取任务')
    view.wrapper.unmount()
  })
  it('Skill 文件与输入在切换区域、刷新后恢复，目录路径仍完整', async () => {
    const { wrapper, router } = await render('/capabilities/publish/skill')
    await vi.waitFor(() => expect(wrapper.find('input[webkitdirectory]').exists()).toBe(true))
    const file = new File(['name: example'], 'SKILL.md', { type: 'text/markdown' })
    Object.defineProperty(file, 'webkitRelativePath', { value: 'draft/SKILL.md' })
    const input = wrapper.get('input[webkitdirectory]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
    await wrapper.get('input[maxlength="80"]').setValue('修改后的标题')
    await wrapper
      .get('.cap-area-tabs')
      .findAll('button')
      .find((b) => b.text() === '发现能力')!
      .trigger('click')
    await flushPromises()
    await router.push('/capabilities/publish/skill')
    await flushPromises()
    expect(wrapper.text()).toContain('1 个文件')
    expect((wrapper.get('input[maxlength="80"]').element as HTMLInputElement).value).toBe(
      '修改后的标题',
    )
    wrapper.unmount()
    const restored = await render('/capabilities/publish/skill')
    await vi.waitFor(() => expect(restored.wrapper.text()).toContain('1 个文件'))
    expect(restored.wrapper.text()).toContain('1 个文件')
    expect((restored.wrapper.get('input[maxlength="80"]').element as HTMLInputElement).value).toBe(
      '修改后的标题',
    )
    expect(api.inspectSkillPackage.mock.calls.slice(-1)[0]![1][0].webkitRelativePath).toBe(
      'draft/SKILL.md',
    )
    restored.wrapper.unmount()
  })
})
