// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AgentMemoryPanel from '@/components/settings/AgentMemoryPanel.vue'
import * as api from '@/api/agentMemories'

vi.mock('@/api/agentMemories', () => ({
  listAgentMemories: vi.fn(),
  agentMemoryHistory: vi.fn(),
  proposeAgentMemory: vi.fn(),
  reviewAgentMemory: vi.fn(),
}))

const candidate: api.AgentMemory = {
  id: 'memory-1', revisionId: 'rev-1', version: 1, state: 'draft', kind: 'preference',
  title: '工作偏好', content: '先给答案', project: '', source: 'codex', sourceSummary: '明确纠正',
  startup: false, createdAt: '2026-11-25T10:00:00', updatedAt: '2026-11-25T10:00:00',
}

function panel(memberId = 'm1') {
  return mount(AgentMemoryPanel, { props: { memberId }, global: { stubs: ['RouterLink'] } })
}

describe('AgentMemoryPanel', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    sessionStorage.clear()
    vi.mocked(api.listAgentMemories).mockResolvedValue({ items: [candidate], hasMore: false })
  })
  afterEach(() => vi.restoreAllMocks())

  it('审核时保留正文改动，明确允许通用偏好恢复，并带原版本保存', async () => {
    const wrapper = panel()
    await flushPromises()
    await wrapper.get('.memory-list-item').trigger('click')
    await wrapper.get('textarea').setValue('优先给结论')
    await wrapper.get('input[type=checkbox]').setValue(true)
    await wrapper.get('.memory-form').trigger('submit')
    await flushPromises()
    expect(api.reviewAgentMemory).toHaveBeenCalledWith('memory-1', expect.objectContaining({
      action: 'publish', expectedRevision: 'rev-1', content: '优先给结论', startup: true,
    }))
    expect(wrapper.text()).toContain('操作已保存')
    wrapper.unmount()
  })

  it('会话草稿按成员隔离，跨端冲突保留草稿直到明确采用新版本', async () => {
    const wrapper = panel()
    await flushPromises()
    await wrapper.get('.memory-list-item').trigger('click')
    await wrapper.get('textarea').setValue('我的草稿')
    vi.mocked(api.reviewAgentMemory).mockRejectedValueOnce(Object.assign(new Error('记忆已更新'), { status: 409 }))
    await wrapper.get('.memory-form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('我的草稿')
    expect(wrapper.text()).toContain('查看账号上的最新版本')
    wrapper.unmount()
    const other = panel('other')
    expect(other.find('textarea').exists()).toBe(false)
    other.unmount()
    const reopened = panel()
    expect(reopened.get('textarea').element.value).toBe('我的草稿')
    reopened.unmount()
  })
})
