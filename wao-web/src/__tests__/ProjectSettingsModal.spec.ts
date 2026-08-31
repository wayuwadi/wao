import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProjectSettingsModal from '@/components/overlays/ProjectSettingsModal.vue'
import { saveAiProjectConfig } from '@/api/ai'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'

vi.mock('@/api/ai', () => ({
  createAiPairCode: vi.fn(),
  fetchAvailableAiSkills: vi.fn(async () => [{
    skillId: 'skill-1', versionId: 'sv-1', displayName: '代码审查', summary: '检查改动风险',
    version: '1.0.0', sha256: 'abc', compatiblePlatforms: ['codex'],
  }]),
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
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore()
  })

  it('可以维护规划中项目的状态并保存到数据层', async () => {
    const data = useDataStore()
    const project = data.projects.find((item) => item.status === '规划中')!
    const wrapper = mount(ProjectSettingsModal, { props: { project } })
    await flushPromises()

    const status = wrapper.find('select[aria-label="项目状态"]')
    expect((status.element as HTMLSelectElement).value).toBe('规划中')
    await status.setValue('进行中')
    await wrapper.find('.btn-primary').trigger('click')
    await flushPromises()

    expect(data.projectById(project.id)?.status).toBe('进行中')
    expect(vi.mocked(saveAiProjectConfig)).toHaveBeenCalledWith(project.id, expect.objectContaining({
      failureRetryCount: 2,
      executionPrompt: '先完整阅读任务原文，再按可验证结果实现。',
    }))
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('展示审核 Skill 并允许固定版本到项目', async () => {
    const project = useDataStore().projects[0]!
    const wrapper = mount(ProjectSettingsModal, { props: { project } })
    await flushPromises()

    expect(wrapper.text()).toContain('代码审查')
    expect(wrapper.text()).toContain('失败自动重试次数')
    expect(wrapper.get('[aria-label="AI 执行提示词"]').element).toHaveProperty(
      'value', '先完整阅读任务原文，再按可验证结果实现。',
    )
    await wrapper.get('.skill-option input').setValue(true)
    expect((wrapper.get('.skill-option input').element as HTMLInputElement).checked).toBe(true)
    wrapper.unmount()
  })
})
