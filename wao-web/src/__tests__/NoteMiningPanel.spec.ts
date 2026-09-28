import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NoteMiningPanel from '@/components/notes/NoteMiningPanel.vue'
import {
  feedbackNoteMiningArtifact,
  feedbackNoteMiningVisual,
  fetchNoteMining,
  retryNoteMiningVisual,
  startNoteMining,
} from '@/api/noteMining'

vi.mock('@/api/noteMining', async importOriginal => ({
  ...await importOriginal<object>(),
  fetchNoteMining: vi.fn(),
  startNoteMining: vi.fn(),
  feedbackNoteMiningArtifact: vi.fn(),
  feedbackNoteMiningVisual: vi.fn(),
  retryNoteMiningVisual: vi.fn(),
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
    vi.mocked(feedbackNoteMiningVisual).mockReset()
    vi.mocked(retryNoteMiningVisual).mockReset()
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

  it('三张图分别显示依据、失败重试和本人取舍', async () => {
    const sources = [{ docId: 'd1', docRev: 3, title: '晨间', excerpt: '忘记喝水' }]
    const visuals = [
      { lens: 'PROGRESS' as const, caption: '积累的变化', rationale: '两篇小记相连', imageUrl: 'https://example.test/a.png', status: 'DONE' as const,
        errorMessage: null, feedbackStatus: 'CANDIDATE' as const, sources },
      { lens: 'REFRAME' as const, caption: '另一种看法', rationale: '换一个角度', imageUrl: 'https://example.test/b.png', status: 'DONE' as const,
        errorMessage: null, feedbackStatus: 'CANDIDATE' as const, sources },
      { lens: 'NEXT_STEP' as const, caption: '下次的观察', rationale: '还可继续记录', imageUrl: null, status: 'FAILED' as const,
        errorMessage: '生成失败', feedbackStatus: 'CANDIDATE' as const, sources },
    ]
    vi.mocked(fetchNoteMining).mockResolvedValue({ ...succeeded, visuals })
    vi.mocked(feedbackNoteMiningVisual).mockResolvedValue({ ...visuals[0]!, feedbackStatus: 'SAVED' })
    vi.mocked(retryNoteMiningVisual).mockResolvedValue({ ...visuals[2]!, status: 'PENDING', errorMessage: null })
    const wrapper = mount(NoteMiningPanel)
    await flushPromises()
    expect(wrapper.findAll('.visual-card')).toHaveLength(3)
    expect(wrapper.text()).toContain('积累的变化')
    expect(wrapper.text()).toContain('晨间 · v3')
    await wrapper.findAll('.visual-card')[0]!.find('.visual-actions button').trigger('click')
    await flushPromises()
    expect(feedbackNoteMiningVisual).toHaveBeenCalledWith('nm1', 'PROGRESS', 'SAVED')
    await wrapper.findAll('.visual-card')[2]!.find('.visual-placeholder button').trigger('click')
    await flushPromises()
    expect(retryNoteMiningVisual).toHaveBeenCalledWith('nm1', 'NEXT_STEP')
    wrapper.unmount()
  })
})
