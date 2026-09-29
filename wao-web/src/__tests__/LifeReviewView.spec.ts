import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LifeReviewView from '@/views/LifeReviewView.vue'
import { fetchAssistantStatus } from '@/api/assistant'
import {
  fetchLifeReview,
  fetchLifeReviewHistory,
  generateLifeReview,
  type LifeReview,
} from '@/api/lifeReview'

vi.mock('@/api/assistant', () => ({
  fetchAssistantStatus: vi.fn<(typeof import('@/api/assistant'))['fetchAssistantStatus']>(),
}))
vi.mock('@/api/lifeReview', () => ({
  fetchLifeReview: vi.fn<(typeof import('@/api/lifeReview'))['fetchLifeReview']>(),
  fetchLifeReviewHistory: vi.fn<(typeof import('@/api/lifeReview'))['fetchLifeReviewHistory']>(),
  generateLifeReview: vi.fn<(typeof import('@/api/lifeReview'))['generateLifeReview']>(),
}))
vi.mock('@/utils/clipboard', () => ({
  copyText: vi.fn<(typeof import('@/utils/clipboard'))['copyText']>().mockResolvedValue(undefined),
}))

const overview: LifeReview = {
  period: 'WEEK', periodKey: '2026-W32', periodLabel: '2026-08-03 至 2026-08-09',
  startDate: '2026-08-03', endDate: '2026-08-09', taskCount: 2,
  assistantConversationCount: 1, chatDayCount: 3,
  sources: [
    { id: 'S1', type: '已完成任务', title: '修复登录白屏', date: '2026-08-05', url: '/projects/p1?task=t1' },
  ], report: null, generatedAt: null,
  coverageMessage: '已核对 2 项已完成任务、1 段 WAO AI 对话和 3 天飞书聊天归档；报告引用其中 1 条证据',
}

const generated: LifeReview = {
  ...overview,
  report: {
    pastActions: '完成登录修复 [S1]', meaningfulTopics: '讨论了可靠性 [S1]',
    trajectory: '如果继续重视可靠性，可能形成更稳定的工作方式；投入变化会改变趋势 [S1]',
    nextFocus: '- 哪项改进最值得继续？',
  },
  generatedAt: '2026-08-16T10:00:00',
}

describe('LifeReviewView', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(fetchAssistantStatus).mockResolvedValue({ configured: true, message: '已连接本地 Codex' })
    vi.mocked(fetchLifeReview).mockResolvedValue(overview)
    vi.mocked(fetchLifeReviewHistory).mockResolvedValue([])
    vi.mocked(generateLifeReview).mockResolvedValue(generated)
  })

  it('先展示真实资料覆盖，再由用户生成可追溯回顾', async () => {
    const wrapper = mount(LifeReviewView)
    await flushPromises()

    expect(wrapper.text()).toContain('2已完成任务')
    expect(wrapper.text()).toContain('修复登录白屏')
    await wrapper.get('.generate-button').trigger('click')
    await flushPromises()

    expect(generateLifeReview).toHaveBeenCalledWith('WEEK', '2026-08-03')
    expect(wrapper.text()).toContain('完成登录修复')
    expect(wrapper.text()).toContain('轨迹趋势假设')
    expect(wrapper.text()).toContain('S1')
  })

  it('没有真实来源时禁用生成并明确不填演示内容', async () => {
    vi.mocked(fetchLifeReview).mockResolvedValue({
      ...overview, taskCount: 0, assistantConversationCount: 0, chatDayCount: 0,
      sources: [], coverageMessage: '当前周期没有可用于回顾的真实记录',
    })
    const wrapper = mount(LifeReviewView)
    await flushPromises()

    expect(wrapper.text()).toContain('不会用演示内容填空')
    expect(wrapper.get<HTMLButtonElement>('.generate-button').element.disabled).toBe(true)
  })

  it('生成失败时保留来源并提供原地重试', async () => {
    vi.mocked(generateLifeReview).mockRejectedValue(new Error('Runner 暂时离线'))
    const wrapper = mount(LifeReviewView)
    await flushPromises()
    await wrapper.get('.generate-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Runner 暂时离线')
    expect(wrapper.text()).toContain('保留记录并重试')
    expect(wrapper.text()).toContain('修复登录白屏')
  })
  it('切换周期读取失败后不展示上一周期资料，重试只读取不生成', async () => {
    vi.mocked(fetchLifeReview).mockResolvedValueOnce(generated).mockRejectedValueOnce(new Error('月份读取失败')).mockResolvedValueOnce({ ...overview, period: 'MONTH' })
    const wrapper = mount(LifeReviewView); await flushPromises()
    await wrapper.findAll('.period-tabs button')[1]!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('月份读取失败')
    expect(wrapper.find('.report-card').exists()).toBe(false)
    await wrapper.get('.state-card.error button').trigger('click'); await flushPromises()
    expect(fetchLifeReview).toHaveBeenLastCalledWith('MONTH', undefined)
    expect(generateLifeReview).not.toHaveBeenCalled()
  })

  it('慢的旧周期响应不能覆盖新周期', async () => {
    let resolveOld!: (value: LifeReview) => void
    vi.mocked(fetchLifeReview).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValueOnce({ ...overview, period: 'MONTH', periodLabel: '月度记录' })
    const wrapper = mount(LifeReviewView); await flushPromises()
    await wrapper.findAll('.period-tabs button')[1]!.trigger('click'); await flushPromises()
    resolveOld(generated); await flushPromises()
    expect(wrapper.get('.scope-bar').text()).toContain('月度记录')
    expect(wrapper.find('.report-card').exists()).toBe(false)
  })

  it('生成期间可浏览其他周期，完成后不把周报放到月份下', async () => {
    let resolveGeneration!: (value: LifeReview) => void
    vi.mocked(generateLifeReview).mockImplementationOnce(() => new Promise(resolve => { resolveGeneration = resolve }))
    const wrapper = mount(LifeReviewView); await flushPromises()
    await wrapper.get('.generate-button').trigger('click')
    vi.mocked(fetchLifeReview).mockResolvedValueOnce({ ...overview, period: 'MONTH', periodLabel: '月份资料', startDate: '2026-07-01' })
    await wrapper.findAll('.period-tabs button')[1]!.trigger('click'); await flushPromises()
    expect(wrapper.get('.generation-progress').text()).toContain(overview.periodLabel)
    resolveGeneration(generated); await flushPromises()
    expect(wrapper.get('.scope-bar').text()).toContain('月份资料')
    expect(wrapper.find('.report-card').exists()).toBe(false)
    expect(wrapper.find('.generation-progress').exists()).toBe(false)
  })

  it('生成成功后的档案列表失败可独立重试，不再次生成报告', async () => {
    vi.mocked(fetchLifeReviewHistory).mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('档案连接失败')).mockResolvedValueOnce([])
    const wrapper = mount(LifeReviewView); await flushPromises()
    await wrapper.get('.generate-button').trigger('click'); await flushPromises()
    expect(wrapper.get('.report-card').text()).toContain('完成登录修复')
    expect(wrapper.get('.history-card').text()).toContain('档案连接失败')
    await wrapper.get('.history-card .inline-error button').trigger('click'); await flushPromises()
    expect(generateLifeReview).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.history-card').text()).toContain('还没有已生成的回顾档案')
  })

  it('生成失败后切换周期，错误入口仍重试原周期', async () => {
    vi.mocked(generateLifeReview).mockRejectedValueOnce(new Error('生成中断')).mockResolvedValueOnce(generated)
    const wrapper = mount(LifeReviewView); await flushPromises()
    await wrapper.get('.generate-button').trigger('click'); await flushPromises()
    vi.mocked(fetchLifeReview).mockResolvedValueOnce({ ...overview, period: 'MONTH', periodLabel: '月份资料', startDate: '2026-07-01' })
    await wrapper.findAll('.period-tabs button')[1]!.trigger('click'); await flushPromises()
    expect(wrapper.get('.inline-error').text()).toContain(overview.periodLabel)
    await wrapper.get('.inline-error button').trigger('click'); await flushPromises()
    expect(generateLifeReview).toHaveBeenLastCalledWith('WEEK', '2026-08-03')
    expect(wrapper.get('.scope-bar').text()).toContain('月份资料')
  })

})
