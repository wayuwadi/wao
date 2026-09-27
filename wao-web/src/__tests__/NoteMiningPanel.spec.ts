import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NoteMiningPanel from '@/components/notes/NoteMiningPanel.vue'
import {
  feedbackNoteMiningArtifact,
  fetchNoteMining,
  startNoteMining,
} from '@/api/noteMining'

vi.mock('@/api/noteMining', async importOriginal => ({
  ...await importOriginal<object>(),
  fetchNoteMining: vi.fn(),
  startNoteMining: vi.fn(),
  feedbackNoteMiningArtifact: vi.fn(),
  waitForNoteMining: vi.fn(async (result, onProgress) => { onProgress(result); return result }),
  newNoteMiningRequestId: vi.fn(() => '0123456789abcdef0123456789abcdef'),
}))

const empty = { job: null, artifacts: [] }
const succeeded = {
  job: { id: 'nm1', status: 'SUCCEEDED' as const, message: '已生成', sourceCount: 2, createdAt: '2026-09-27T08:00:00' },
  artifacts: [
    { id: 'a1', type: 'INSIGHT' as const, content: '你在疲惫时更容易忘记照顾身体。', rationale: '两篇小记都出现了身体信号。', feedbackStatus: 'CANDIDATE' as const,
      sources: [{ docId: 'd1', docRev: 3, title: '晨间', excerpt: '忘记喝水' }], createdAt: '2026-09-27T08:00:00' },
    { id: 'a2', type: 'REMINDER' as const, content: '现在喝口水，看看远处吧。', rationale: '把重复信号变成温和提示。', feedbackStatus: 'CANDIDATE' as const,
      sources: [{ docId: 'd2', docRev: 2, title: '夜间', excerpt: '眼睛酸涩' }], createdAt: '2026-09-27T08:00:00' },
    { id: 'a3', type: 'ARTICLE_SEED' as const, content: '我们为什么总在忙碌时忘记身体？', rationale: '主题可以继续展开。', feedbackStatus: 'CANDIDATE' as const,
      sources: [{ docId: 'd1', docRev: 3, title: '晨间', excerpt: '忘记喝水' }], createdAt: '2026-09-27T08:00:00' },
  ],
}

describe('AI 挖掘小记', () => {
  beforeEach(() => {
    vi.mocked(fetchNoteMining).mockReset().mockResolvedValue(empty)
    vi.mocked(startNoteMining).mockReset().mockResolvedValue(succeeded)
    vi.mocked(feedbackNoteMiningArtifact).mockReset()
  })

  it('空状态明确说明原文不变，并能启动三类内容创造', async () => {
    const wrapper = mount(NoteMiningPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('AI 不会改写你的原文')
    await wrapper.get('.mine-button').trigger('click')
    await flushPromises()
    expect(startNoteMining).toHaveBeenCalledWith('0123456789abcdef0123456789abcdef')
    expect(wrapper.text()).toContain('你在疲惫时更容易忘记照顾身体')
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(3)
  })

  it('保留生成理由与原始来源，提醒只能先成为未启用草稿', async () => {
    vi.mocked(fetchNoteMining).mockResolvedValue(succeeded)
    const reminder = succeeded.artifacts[1]!
    vi.mocked(feedbackNoteMiningArtifact).mockResolvedValue({
      ...reminder, feedbackStatus: 'REMINDER_DRAFT',
    })
    const wrapper = mount(NoteMiningPanel)
    await flushPromises()
    await wrapper.findAll('[role="tab"]')[1]!.trigger('click')
    expect(wrapper.text()).toContain('把重复信号变成温和提示')
    expect(wrapper.text()).toContain('夜间')
    const draft = wrapper.findAll('.artifact-actions button').find(button => button.text().includes('作为提醒草稿'))
    await draft!.trigger('click')
    await flushPromises()
    expect(feedbackNoteMiningArtifact).toHaveBeenCalledWith('a2', 'REMINDER_DRAFT')
    expect(wrapper.text()).toContain('尚未启用或推送')
    await wrapper.get('.sources button').trigger('click')
    expect(wrapper.emitted('openNote')).toEqual([['d2']])
  })
})
