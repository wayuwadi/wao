import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import WaoAiCapabilitiesPanel from '@/components/settings/WaoAiCapabilitiesPanel.vue'
import { useSessionStore } from '@/stores/session'

const { fetchAvailableAiSkills, fetchWaoApplicationSkills, saveWaoApplicationSkills } = vi.hoisted(() => ({
  fetchAvailableAiSkills: vi.fn(),
  fetchWaoApplicationSkills: vi.fn(),
  saveWaoApplicationSkills: vi.fn(),
}))

vi.mock('@/api/ai', () => ({ fetchAvailableAiSkills, fetchWaoApplicationSkills, saveWaoApplicationSkills }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))

const skill = {
  skillId: 'skill-1', versionId: 'sv-1', displayName: '代码审查', summary: '检查改动风险',
  version: '1.0.0', sha256: '1234567890abcdef', compatiblePlatforms: ['codex'],
}

describe('WAO AI 能力设置', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const session = useSessionStore()
    session.accept({ accountId: 'account-1', identities: [], createdAt: '2026-08-27T00:00:00',
      member: { id: 'admin', name: '管理员', initial: 'A', color: '#123456', role: '管理员' } })
    fetchAvailableAiSkills.mockReset().mockResolvedValue([skill])
    fetchWaoApplicationSkills.mockReset().mockResolvedValue([])
    saveWaoApplicationSkills.mockReset().mockImplementation(async (versionIds: string[]) =>
      versionIds.includes('sv-1') ? [{ ...skill, activationScope: 'WAO_APPLICATION' }] : [])
  })

  it('选择固定版本并保存为 WAO 默认能力', async () => {
    const wrapper = mount(WaoAiCapabilitiesPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('代码审查')
    await wrapper.get('.skill-option input').setValue(true)
    expect(wrapper.text()).toContain('有未保存的修改')
    await wrapper.get('button.save').trigger('click')
    await flushPromises()

    expect(saveWaoApplicationSkills).toHaveBeenCalledWith(['sv-1'])
    expect(wrapper.text()).toContain('当前选择已保存')
  })

  it('加载失败时展示真实原因并允许重试', async () => {
    fetchAvailableAiSkills.mockRejectedValueOnce(new Error('网络不可用'))
    const wrapper = mount(WaoAiCapabilitiesPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('网络不可用')
    await wrapper.get('.state.error button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('代码审查')
  })
})
