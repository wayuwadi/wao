import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkdayRhythmHexagramSummary from '@/components/workday/WorkdayRhythmHexagramSummary.vue'
import { fetchSharedTodayTianrenReading } from '@/api/tianren'

vi.mock('@/api/tianren', () => ({ fetchSharedTodayTianrenReading: vi.fn() }))

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
  beforeEach(() => vi.mocked(fetchSharedTodayTianrenReading).mockResolvedValue(null))
  it('等待定时结果，不提供手动起卦入口', async () => {
    const wrapper = await mountSummary()
    await flushPromises()
    expect(wrapper.text()).toContain('每天早上 8 点生成')
    expect(wrapper.find('.cast-button').exists()).toBe(false)
  })
  it('显示共享的本卦及变卦', async () => {
    vi.mocked(fetchSharedTodayTianrenReading).mockResolvedValue(reading)
    const wrapper = await mountSummary()
    await flushPromises()
    expect(wrapper.text()).toContain('乾卦')
    expect(wrapper.text()).toContain('变化后 · 姤卦')
  })
})
