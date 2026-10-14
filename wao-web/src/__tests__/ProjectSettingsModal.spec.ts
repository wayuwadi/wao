import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProjectSettingsModal from '@/components/overlays/ProjectSettingsModal.vue'
import { saveAiProjectConfig } from '@/api/ai'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { useSessionStore } from '@/stores/session'
import { fetchMembers, apiGetProject } from '@/api/board'
import { readProjectSettingsDraft } from '@/utils/projectSettingsDraft'
vi.mock('@/api/board', async original => ({ ...await original<typeof import('@/api/board')>(), apiGetProject: vi.fn(), fetchMembers: vi.fn(async () => [{ id: 'wg', name: '甲' }, { id: 'wd', name: '乙' }]) }))

// AI Run 下线期间设置里不展示 AI 配置；保留的配置代码在开关打开时仍需可用
const flags = vi.hoisted(() => ({ ai: false }))
vi.mock('@/config/features', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/config/features')>()),
  get AI_RUN_ENABLED() {
    return flags.ai
  },
}))

vi.mock('@/api/ai', () => ({
  createAiPairCode: vi.fn(),
  fetchAvailableAiSkills: vi.fn(async () => [
    {
      skillId: 'skill-1',
      versionId: 'sv-1',
      displayName: '代码审查',
      summary: '检查改动风险',
      version: '1.0.0',
      sha256: 'abc',
      compatiblePlatforms: ['codex'],
    },
  ]),
  fetchWaoApplicationSkills: vi.fn(async () => []),
  fetchAiProjectSkills: vi.fn(async () => []),
  fetchAiRunners: vi.fn(async () => []),
  fetchAiProjectConfig: vi.fn(async () => ({
    projectId: 'p1',
    enabled: false,
    repoUrl: '',
    defaultBranch: 'main',
    workspaceDir: '',
    defaultEngine: 'codex',
    preferredRunnerId: null,
    maxParallelRuns: 2,
    failureRetryCount: 2,
    executionPrompt: '先完整阅读任务原文，再按可验证结果实现。',
  })),
  saveAiProjectConfig: vi.fn(async () => ({})),
  saveAiProjectSkills: vi.fn(async () => []),
}))

describe('项目设置', () => {
  const open = (project = useDataStore().projects[0]!) => mount(ProjectSettingsModal, { props: { project }, global: { stubs: { Teleport: true, RouterLink: true } } })
  const tab = async (wrapper: ReturnType<typeof open>, title: string) => wrapper.findAll('[role="tab"]').find(item => item.text().startsWith(title))!.trigger('click')
  it('分项切换保留输入，未改动完成不提交，方向键导航排除隐藏面板', async () => {
    const update = vi.spyOn(useDataStore(), 'updateProject')
    const wrapper = open(); await flushPromises()
    expect(wrapper.get('.btn-primary').text()).toBe('完成')
    await wrapper.get('.btn-primary').trigger('click')
    expect(update).not.toHaveBeenCalled()
    await wrapper.get('.title-input').setValue('新的名称')
    await wrapper.find('[role="tab"]').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.findAll('[role="tabpanel"]')[0]!.attributes('hidden')).toBeDefined()
    await tab(wrapper, '更多'); await tab(wrapper, '基本信息')
    expect(wrapper.get('.title-input').element).toHaveProperty('value', '新的名称')
    expect(wrapper.get('.btn-primary').text()).toBe('保存并关闭')
    wrapper.unmount(); update.mockRestore()
  })
  it('保存失败后重开恢复全部草稿和成员原快照，成功清理并隔离其他成员', async () => {
    const project = useDataStore().projects[0]!
    project.owner = 'wg'; project.members = ['wg']
    useSessionStore().account = { member: { id: 'wg' } } as never
    const save = vi.spyOn(useDataStore(), 'updateProject').mockResolvedValueOnce(false).mockResolvedValue(true)
    const wrapper = open(); await flushPromises()
    await wrapper.get('.title-input').setValue('恢复名称')
    await wrapper.get('.goal').setValue('恢复目标')
    await wrapper.get('[aria-label="项目截止日期"]').setValue('2027-02-03')
    await tab(wrapper, '项目成员'); await wrapper.get('#project-member-add').setValue('wd')
    await wrapper.findAll('button').find(button => button.text() === '添加')!.trigger('click')
    await wrapper.get('.btn-primary').trigger('click'); await flushPromises()
    expect(wrapper.get('.btn-primary').text()).toBe('重试保存'); wrapper.unmount()
    const reopened = open(); await flushPromises()
    expect(reopened.get('.title-input').element).toHaveProperty('value', '恢复名称')
    expect(reopened.get('.goal').element).toHaveProperty('value', '恢复目标')
    expect(reopened.text()).toContain('已恢复本机')
    await reopened.get('.btn-primary').trigger('click'); await flushPromises()
    expect(save).toHaveBeenLastCalledWith(project.id, expect.objectContaining({ name: '恢复名称', goal: '恢复目标', due: '2027-02-03', members: ['wg', 'wd'], expectedMembers: ['wg'] }))
    expect(readProjectSettingsDraft('wg', project.id)).toBeNull()
    reopened.unmount(); save.mockRestore()
    const mine = open(); await mine.get('.title-input').setValue('我的私有草稿'); mine.unmount()
    useSessionStore().account = { member: { id: 'wd' } } as never
    const other = open(); expect(other.get('.title-input').element).toHaveProperty('value', project.name); other.unmount()
  })
  it('恢复时账号同字段已变更须明确选择，不覆盖远端未编辑字段', async () => {
    const project = useDataStore().projects[0]!
    useSessionStore().account = { member: { id: 'wg' } } as never
    const first = open(); await first.get('.title-input').setValue('本机名称'); first.unmount()
    project.name = '账号新名称'; project.goal = '后台新目标'
    const save = vi.spyOn(useDataStore(), 'updateProject').mockResolvedValue(true)
    const reopened = open(); await flushPromises()
    expect(reopened.get('.btn-primary').attributes('disabled')).toBeDefined()
    expect(reopened.get('.goal').element).toHaveProperty('value', '后台新目标')
    await reopened.findAll('button').find(button => button.text() === '保留我的修改')!.trigger('click')
    await reopened.get('.btn-primary').trigger('click'); await flushPromises()
    expect(save).toHaveBeenCalledWith(project.id, { name: '本机名称' })
    reopened.unmount(); save.mockRestore()
  })
  it('存储失败明确提示但保留输入，仍可保存到账号', async () => {
    useSessionStore().account = { member: { id: 'wg' } } as never
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('容量不足') })
    const save = vi.spyOn(useDataStore(), 'updateProject').mockResolvedValue(true)
    const wrapper = open(); await wrapper.get('.title-input').setValue('仍可保存')
    expect(wrapper.text()).toContain('本机草稿无法保存')
    await wrapper.get('.btn-primary').trigger('click'); await flushPromises()
    expect(save).toHaveBeenCalledWith(useDataStore().projects[0]!.id, { name: '仍可保存' })
    wrapper.unmount(); storage.mockRestore(); save.mockRestore()
  })
  it('草稿写入后来失败时，成功保存仍清理本窗口之前持久化的版本', async () => {
    useSessionStore().account = { member: { id: 'wg' } } as never
    const save = vi.spyOn(useDataStore(), 'updateProject').mockResolvedValue(true)
    const wrapper = open()
    await wrapper.get('.title-input').setValue('第一次草稿')
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('容量不足') })
    await wrapper.get('.title-input').setValue('最新输入')
    storage.mockRestore()
    await wrapper.get('.btn-primary').trigger('click'); await flushPromises()
    expect(save).toHaveBeenCalledWith(useDataStore().projects[0]!.id, { name: '最新输入' })
    expect(readProjectSettingsDraft('wg', useDataStore().projects[0]!.id)).toBeNull()
    wrapper.unmount(); save.mockRestore()
  })
  it('冲突提示期间后台刷新未编辑字段，保留修改仅提交本人编辑字段', async () => {
    useSessionStore().account = { member: { id: 'wg' } } as never
    const project = useDataStore().projects[0]!
    const first = open(); await first.get('.title-input').setValue('我的名称'); first.unmount()
    project.name = '后台名称'
    const wrapper = open(); await flushPromises()
    project.goal = '冲突确认期间后台更新的目标'
    await flushPromises()
    const save = vi.spyOn(useDataStore(), 'updateProject').mockResolvedValue(true)
    await wrapper.findAll('button').find(button => button.text() === '保留我的修改')!.trigger('click')
    await wrapper.get('.btn-primary').trigger('click'); await flushPromises()
    expect(save).toHaveBeenCalledWith(project.id, { name: '我的名称' })
    wrapper.unmount(); save.mockRestore()
  })
  it('更多中取消删除与失败重试保持项目和草稿', async () => {
    const remove = vi.spyOn(useDataStore(), 'deleteProject').mockRejectedValueOnce(new Error('断网')).mockResolvedValue()
    const wrapper = open(); await tab(wrapper, '更多')
    await wrapper.findAll('button').find(button => button.text() === '删除项目')!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '取消删除')!.trigger('click')
    expect(remove).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(button => button.text() === '删除项目')!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '确认删除')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('删除失败')
    expect(wrapper.emitted('deleted')).toBeUndefined()
    await wrapper.findAll('button').find(button => button.text() === '确认删除')!.trigger('click'); await flushPromises()
    expect(wrapper.emitted('deleted')).toHaveLength(1)
    wrapper.unmount(); remove.mockRestore()
  })
  it('成员冲突可重读并保留本次修改，不丢弃其他窗口新增的成员', async () => {
    const data = useDataStore(), project = data.projects[0]!
    project.owner = 'wg'; project.members = ['wg']
    useSessionStore().account = { member: { id: 'wg' } } as never
    const save = vi.spyOn(data, 'updateProject').mockRejectedValueOnce(Object.assign(new Error('项目成员已被修改'), { status: 409 })).mockResolvedValue(true)
    vi.mocked(apiGetProject).mockResolvedValue({ ...project, members: ['wg', 'third'] })
    const wrapper = mount(ProjectSettingsModal, { global: { stubs: { Teleport: true, RouterLink: true } }, props: { project } })
    await flushPromises(); await wrapper.get('#project-member-add').setValue('wd')
    await wrapper.findAll('button').find(b => b.text() === '添加')!.trigger('click')
    await wrapper.get('.btn-primary').trigger('click'); await flushPromises()
    expect(wrapper.get('.btn-primary').attributes('disabled')).toBeDefined()
    await wrapper.findAll('button').find(b => b.text() === '读取最新成员并保留我的修改')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已保留本次添加和移除')
    await wrapper.get('.btn-primary').trigger('click'); await flushPromises()
    expect(save).toHaveBeenLastCalledWith(project.id, expect.objectContaining({ members: ['wg', 'third', 'wd'], expectedMembers: ['wg', 'third'] }))
    wrapper.unmount(); save.mockRestore()
  })
  it('负责人可以添加和移除成员，失败保留选择，重试保存携带原始快照', async () => {
    const data = useDataStore(), project = data.projects[0]!
    project.owner = 'wg'; project.members = ['wg']
    useSessionStore().account = { member: { id: 'wg' } } as never
    const save = vi.spyOn(data, 'updateProject').mockRejectedValueOnce(new Error('项目成员已被修改')).mockResolvedValue(true)
    const wrapper = mount(ProjectSettingsModal, { global: { stubs: { Teleport: true, RouterLink: true } }, props: { project } })
    await flushPromises()
    await wrapper.get('#project-member-add').setValue('wd')
    await wrapper.findAll('button').find(b => b.text() === '添加')!.trigger('click')
    expect(wrapper.find('[aria-label="移除成员：甲"]').exists()).toBe(false)
    await wrapper.get('.btn-primary').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('项目成员已被修改')
    expect(wrapper.find('[aria-label="移除成员：乙"]').exists()).toBe(true)
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(project.members).toEqual(['wg'])
    await wrapper.get('.btn-primary').trigger('click'); await flushPromises()
    expect(save).toHaveBeenLastCalledWith(project.id, expect.objectContaining({ members: ['wg', 'wd'], expectedMembers: ['wg'] }))
    wrapper.unmount(); save.mockRestore()
    const remove = vi.spyOn(data, 'updateProject').mockResolvedValue(true)
    const reopened = mount(ProjectSettingsModal, { global: { stubs: { Teleport: true, RouterLink: true } }, props: { project: { ...project, members: ['wg', 'wd'] } } })
    await flushPromises(); await reopened.get('[aria-label="移除成员：乙"]').trigger('click')
    await reopened.get('.btn-primary').trigger('click'); await flushPromises()
    expect(remove).toHaveBeenLastCalledWith(project.id, expect.objectContaining({ members: ['wg'], expectedMembers: ['wg', 'wd'] }))
    reopened.unmount(); remove.mockRestore()
  })
  it('成员目录失败可重读，普通成员不可管理，不修改成员时不回写旧快照', async () => {
    const project = useDataStore().projects[0]!
    useSessionStore().account = { member: { id: 'wd' } } as never
    vi.mocked(fetchMembers).mockRejectedValueOnce(new Error('读取失败'))
    const save = vi.spyOn(useDataStore(), 'updateProject').mockResolvedValue(true)
    const wrapper = mount(ProjectSettingsModal, { global: { stubs: { Teleport: true, RouterLink: true } }, props: { project } })
    await flushPromises()
    expect(wrapper.text()).toContain('成员列表读取失败')
    expect(wrapper.find('#project-member-add').exists()).toBe(false)
    await wrapper.findAll('button').find(b => b.text() === '重新读取')!.trigger('click'); await flushPromises()
    await wrapper.get('.title-input').setValue('只改名称'); await wrapper.get('.btn-primary').trigger('click'); await flushPromises()
    expect(save.mock.calls[0]![1]).not.toHaveProperty('members')
    wrapper.unmount(); save.mockRestore()
  })
  it('截止日期失败保留输入，重试可保存并再次清空', async () => {
    const data = useDataStore(),
      project = data.projects[0]!
    project.due = '2026-12-03'
    const save = vi
      .spyOn(data, 'updateProject')
      .mockResolvedValueOnce(false)
      .mockResolvedValue(true)
    const wrapper = mount(ProjectSettingsModal, {
      global: { stubs: { Teleport: true, RouterLink: true } },
      props: { project },
    })
    await wrapper.get('[aria-label="项目截止日期"]').setValue('2027-01-15')
    await wrapper.get('.btn-primary').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('保存失败')
    expect(wrapper.get('[aria-label="项目截止日期"]').element).toHaveProperty('value', '2027-01-15')
    expect(project.due).toBe('2026-12-03')
    await wrapper.get('.btn-primary').trigger('click')
    await flushPromises()
    expect(save).toHaveBeenLastCalledWith(
      project.id,
      expect.objectContaining({ due: '2027-01-15' }),
    )
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
    const reopened = mount(ProjectSettingsModal, {
      global: { stubs: { Teleport: true, RouterLink: true } },
      props: { project: { ...project, due: '2027-01-15' } },
    })
    await reopened
      .findAll('button')
      .find((button) => button.text() === '清空日期')!
      .trigger('click')
    expect(reopened.get('[aria-label="项目截止日期"]').element).toHaveProperty('value', '')
    await reopened.get('.btn-primary').trigger('click')
    await flushPromises()
    expect(save).toHaveBeenLastCalledWith(project.id, expect.objectContaining({ due: '' }))
    reopened.unmount()
    save.mockRestore()
  })

  it('没有编辑日期时不覆盖后台刷新带来的新日期', async () => {
    const data = useDataStore(),
      project = data.projects[0]!
    project.due = '2026-12-03'
    const save = vi.spyOn(data, 'updateProject').mockResolvedValue(true)
    const wrapper = mount(ProjectSettingsModal, {
      global: { stubs: { Teleport: true, RouterLink: true } },
      props: { project },
    })
    project.due = '2027-01-15'
    await wrapper.get('.title-input').setValue('只改项目名称')
    await wrapper.get('.btn-primary').trigger('click')
    await flushPromises()
    expect(save.mock.calls[0]![1]).not.toHaveProperty('due')
    wrapper.unmount()
    save.mockRestore()
  })
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore()
    flags.ai = false
    vi.mocked(saveAiProjectConfig).mockClear()
  })

  it('项目状态与 AI 配置都不再出现，保存不会覆盖已有 AI 配置', async () => {
    const data = useDataStore()
    const project = data.projects.find((item) => item.status === '规划中')!
    const wrapper = mount(ProjectSettingsModal, {
      global: { stubs: { Teleport: true, RouterLink: true } },
      props: { project },
    })
    await flushPromises()

    expect(wrapper.find('select[aria-label="项目状态"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('项目状态')
    expect(wrapper.text()).not.toContain('AI 执行配置')
    await wrapper.get('.title-input').setValue('改名后的项目')
    await wrapper.find('.btn-primary').trigger('click')
    await flushPromises()

    expect(data.projectById(project.id)).toMatchObject({ name: '改名后的项目', status: '规划中' })
    expect(vi.mocked(saveAiProjectConfig)).not.toHaveBeenCalled()
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('AI Run 开关打开时仍展示审核 Skill 并允许固定版本到项目', async () => {
    flags.ai = true
    const project = useDataStore().projects[0]!
    const wrapper = mount(ProjectSettingsModal, {
      global: { stubs: { Teleport: true, RouterLink: true } },
      props: { project },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('代码审查')
    expect(wrapper.text()).toContain('失败自动重试次数')
    expect(wrapper.get('[aria-label="AI 执行提示词"]').element).toHaveProperty(
      'value',
      '先完整阅读任务原文，再按可验证结果实现。',
    )
    await wrapper.get('.skill-option input').setValue(true)
    expect((wrapper.get('.skill-option input').element as HTMLInputElement).checked).toBe(true)
    wrapper.unmount()
  })
})
