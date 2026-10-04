import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProjectSettingsModal from '@/components/overlays/ProjectSettingsModal.vue'
import { saveAiProjectConfig } from '@/api/ai'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'

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
  it('截止日期失败保留输入，重试可保存并再次清空', async () => {
    const data = useDataStore(),
      project = data.projects[0]!
    project.due = '2026-12-03'
    const save = vi
      .spyOn(data, 'updateProject')
      .mockResolvedValueOnce(false)
      .mockResolvedValue(true)
    const wrapper = mount(ProjectSettingsModal, {
      global: { stubs: { Teleport: true } },
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
      global: { stubs: { Teleport: true } },
      props: { project: { ...project, due: '2027-01-15' } },
    })
    await reopened
      .findAll('button')
      .find((button) => button.text() === '清空日期')!
      .trigger('click')
    expect(reopened.text()).toContain('未排期')
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
      global: { stubs: { Teleport: true } },
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
      global: { stubs: { Teleport: true } },
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
      global: { stubs: { Teleport: true } },
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
