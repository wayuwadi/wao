import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkdayRhythmDailyPanel from '@/components/workday/WorkdayRhythmDailyPanel.vue'
import {
  fetchRhythmDashboard,
  setRhythmOccurrenceCompleted,
  setRhythmWorkdayStatus,
} from '@/api/workdayRhythm'
import { createTodayTianrenReading, fetchTodayTianrenReading } from '@/api/tianren'
import { fetchAssistantConversation } from '@/api/assistant'

vi.mock('@/api/workdayRhythm', () => ({
  fetchRhythmDashboard: vi.fn<typeof fetchRhythmDashboard>(),
  setRhythmOccurrenceCompleted: vi.fn<typeof setRhythmOccurrenceCompleted>(),
  setRhythmWorkdayStatus: vi.fn<typeof setRhythmWorkdayStatus>(),
}))
vi.mock('@/api/tianren', () => ({
  fetchTodayTianrenReading: vi.fn<typeof fetchTodayTianrenReading>(),
  createTodayTianrenReading: vi.fn<typeof createTodayTianrenReading>(),
}))
vi.mock('@/api/assistant', () => ({
  fetchAssistantConversation: vi.fn<typeof fetchAssistantConversation>(),
}))

const dashboard = {
  date: '2026-08-23',
  workday: true,
  workdayOverride: null,
  workWeekLabel: '大周 · 周日上班',
  completedCount: 0,
  totalCount: 2,
  checklist: [
    {
      id: 'plan',
      itemId: 'i1',
      occurrenceDate: '2026-08-23',
      kind: 'PLAN',
      category: 'WORK',
      title: '制定今日计划',
      startTime: '07:00',
      dueTime: '09:00',
      dueNextDay: false,
      completedAt: null,
      completedOnTime: null,
      status: 'DUE',
    },
    {
      id: 'out',
      itemId: 'i2',
      occurrenceDate: '2026-08-22',
      kind: 'CLOCK_OUT',
      category: 'WORK',
      title: '下班打卡',
      startTime: '18:00',
      dueTime: '07:00',
      dueNextDay: true,
      completedAt: null,
      completedOnTime: null,
      status: 'LATE',
    },
  ],
  document: {
    id: null,
    state: 'READY',
    kbId: 'kb1',
    folderId: 'f1',
    title: '2026-08-23 每日工作记录',
  },
  settings: {
    followAlternateWeek: true,
    documentKbId: 'kb1',
    documentFolderId: 'f1',
    documentTemplateId: null,
    documentTitlePattern: '{date} 每日工作记录',
    tianrenEnabled: true,
    tianrenRequired: false,
  },
} as const

async function mountPanel() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/tianren', component: { template: '<div />' } },
      { path: '/kb/:id', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return mount(WorkdayRhythmDailyPanel, {
    props: { date: '2026-08-23' },
    global: {
      plugins: [router],
      stubs: {
        MarkdownEditor: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<textarea class="daily-editor" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
      },
    },
  })
}

describe('WorkdayRhythmDailyPanel', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchRhythmDashboard).mockResolvedValue(structuredClone(dashboard) as never)
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(null)
    vi.mocked(fetchAssistantConversation).mockResolvedValue({
      conversation: { id: 'c1', title: '今日一卦', updatedAt: '' },
      messages: [],
    })
    vi.mocked(setRhythmOccurrenceCompleted).mockResolvedValue({
      ...dashboard.checklist[0],
      completedAt: '2026-08-23T08:30:00',
      completedOnTime: true,
      status: 'ON_TIME',
    } as never)
  })

  it('展示大小周、跨日逾期项并保存清单完成状态', async () => {
    const wrapper = await mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('大周 · 周日上班')
    expect(wrapper.text()).toContain('2026-08-22 的跨日事项')
    expect(wrapper.text()).toContain('已逾期')
    await wrapper.findAll('.check-row')[0]!.trigger('click')
    await flushPromises()
    expect(setRhythmOccurrenceCompleted).toHaveBeenCalledWith('plan', true)
    expect(wrapper.get('.completion').text()).toContain('1 / 2')
  })

  it('不再加载或展示每日工作记录', async () => {
    const wrapper = await mountPanel()
    await flushPromises()
    expect(wrapper.text()).not.toContain('每日工作记录')
    expect(wrapper.find('.daily-editor').exists()).toBe(false)
  })

  it('未起卦时保持主动入口并在成功后显示真实卦象', async () => {
    vi.mocked(createTodayTianrenReading).mockResolvedValue({
      id: 'r1',
      date: '2026-08-23',
      lines: [7, 7, 7, 7, 7, 7],
      changingLines: [],
      primary: {
        number: 1,
        name: '乾',
        symbol: '䷀',
        upper: { name: '乾', nature: '天', symbol: '☰' },
        lower: { name: '乾', nature: '天', symbol: '☰' },
      },
      relating: null,
      conversationId: null,
      createdAt: '',
    })
    const wrapper = await mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('不会因为打开页面自动生成')
    await wrapper.get('.hexagram-card button').trigger('click')
    await flushPromises()
    expect(createTodayTianrenReading).toHaveBeenCalled()
  })

  it('展示已保存的最近一次 AI 自照摘要而不编造运势', async () => {
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue({
      id: 'r1',
      date: '2026-08-23',
      lines: [7, 7, 7, 7, 7, 7],
      changingLines: [],
      primary: {
        number: 1,
        name: '乾',
        symbol: '䷀',
        upper: { name: '乾', nature: '天', symbol: '☰' },
        lower: { name: '乾', nature: '天', symbol: '☰' },
      },
      relating: null,
      conversationId: 'c1',
      createdAt: '',
    })
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
    const wrapper = await mountPanel()
    await flushPromises()
    expect(fetchAssistantConversation).toHaveBeenCalledWith('c1')
    expect(wrapper.get('.hex-guidance').text()).toContain('先守住边界，再集中完成最重要的一件事')
    expect(wrapper.text()).not.toContain('大吉')
  })
})
