import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkdayRhythmDailyPanel from '@/components/workday/WorkdayRhythmDailyPanel.vue'
import {
  fetchRhythmDashboard,
  setRhythmOccurrenceCompleted,
  setRhythmWorkdayStatus,
} from '@/api/workdayRhythm'

vi.mock('@/api/workdayRhythm', () => ({
  fetchRhythmDashboard: vi.fn<typeof fetchRhythmDashboard>(),
  setRhythmOccurrenceCompleted: vi.fn<typeof setRhythmOccurrenceCompleted>(),
  setRhythmWorkdayStatus: vi.fn<typeof setRhythmWorkdayStatus>(),
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

  it('每日必做项目使用通栏列表且不再提供卡内配置入口', async () => {
    const wrapper = await mountPanel()
    await flushPromises()
    expect(wrapper.findAll('.checklist-columns > section')).toHaveLength(1)
    expect(wrapper.findAll('.check-row')).toHaveLength(2)
    expect(wrapper.find('.configure-button').exists()).toBe(false)
    expect(wrapper.emitted('configure')).toBeUndefined()
  })
})
