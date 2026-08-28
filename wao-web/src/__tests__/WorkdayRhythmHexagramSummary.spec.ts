import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkdayRhythmHexagramSummary from '@/components/workday/WorkdayRhythmHexagramSummary.vue'
import { fetchAssistantConversation } from '@/api/assistant'
import { createTodayTianrenReading, fetchTodayTianrenReading } from '@/api/tianren'

vi.mock('@/api/tianren', () => ({
  fetchTodayTianrenReading: vi.fn<typeof fetchTodayTianrenReading>(),
  createTodayTianrenReading: vi.fn<typeof createTodayTianrenReading>(),
}))
vi.mock('@/api/assistant', () => ({
  fetchAssistantConversation: vi.fn<typeof fetchAssistantConversation>(),
}))

const reading = {
  id: 'r1',
  date: '2026-08-23',
  lines: [9, 7, 7, 7, 7, 7],
  changingLines: [1],
  primary: {
    number: 1,
    name: '乾',
    symbol: '䷀',
    upper: { name: '乾', nature: '天', symbol: '☰' },
    lower: { name: '乾', nature: '天', symbol: '☰' },
  },
  relating: {
    number: 44,
    name: '姤',
    symbol: '䷫',
    upper: { name: '乾', nature: '天', symbol: '☰' },
    lower: { name: '巽', nature: '风', symbol: '☴' },
  },
  conversationId: null,
  createdAt: '',
}

async function mountSummary() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/tianren', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return mount(WorkdayRhythmHexagramSummary, {
    props: { date: '2026-08-23' },
    global: { plugins: [router] },
  })
}

describe('WorkdayRhythmHexagramSummary', () => {
  beforeEach(() => {
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(null)
    vi.mocked(fetchAssistantConversation).mockResolvedValue({
      conversation: { id: 'c1', title: '今日一卦', updatedAt: '' },
      messages: [],
    })
  })

  it('未起卦时保持主动入口并在成功后显示本卦和变化后卦象', async () => {
    vi.mocked(createTodayTianrenReading).mockResolvedValue(reading)
    const wrapper = await mountSummary()
    await flushPromises()

    expect(wrapper.text()).toContain('不会因打开页面自动生成')
    await wrapper.get('.cast-button').trigger('click')
    await flushPromises()

    expect(createTodayTianrenReading).toHaveBeenCalled()
    expect(wrapper.get('.hexagram-result').text()).toContain('乾卦')
    expect(wrapper.get('.hexagram-result').text()).toContain('变化后 · 姤卦')
    expect(wrapper.text()).toContain('本卦为乾卦（上天下天）')
  })

  it('优先展示已保存的最近一次 AI 自照摘要而不编造运势', async () => {
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue({ ...reading, conversationId: 'c1' })
    vi.mocked(fetchAssistantConversation).mockResolvedValue({
      conversation: { id: 'c1', title: '今日一卦', updatedAt: '' },
      messages: [
        {
          id: 'm1',
          role: 'assistant',
          content: '## 今日方向\n\n先守住边界，再集中完成最重要的一件事。',
          createdAt: '',
        },
      ],
    })
    const wrapper = await mountSummary()
    await flushPromises()

    expect(fetchAssistantConversation).toHaveBeenCalledWith('c1')
    expect(wrapper.text()).toContain('先守住边界，再集中完成最重要的一件事')
    expect(wrapper.text()).not.toContain('大吉')
  })
})
