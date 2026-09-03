import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkdayRhythmDailyPanel from '@/components/workday/WorkdayRhythmDailyPanel.vue'
import {
  fetchRhythmDashboard,
  setRhythmOccurrenceCompleted,
  setRhythmWorkdayStatus,
  updateRhythmTimer,
} from '@/api/workdayRhythm'

vi.mock('@/api/workdayRhythm', () => ({
  fetchRhythmDashboard: vi.fn<typeof fetchRhythmDashboard>(),
  setRhythmOccurrenceCompleted: vi.fn<typeof setRhythmOccurrenceCompleted>(),
  setRhythmWorkdayStatus: vi.fn<typeof setRhythmWorkdayStatus>(),
  updateRhythmTimer: vi.fn<typeof updateRhythmTimer>(),
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
      itemType: 'CHECK',
      durationSeconds: null,
      timerRemainingSeconds: null,
      timerRunning: false,
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
      itemType: 'CHECK',
      durationSeconds: null,
      timerRemainingSeconds: null,
      timerRunning: false,
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
    await wrapper.findAll('.check-toggle')[0]!.trigger('click')
    await flushPromises()
    expect(setRhythmOccurrenceCompleted).toHaveBeenCalledWith('plan', true)
    expect(wrapper.find('.completion').exists()).toBe(false)
    expect(wrapper.findAll('.check-row')[0]!.text()).toContain('准时完成')
  })

  it('展示服务端倒计时状态并支持开始与重置', async () => {
    const countdown = {
      ...dashboard.checklist[0],
      id: 'review',
      itemId: 'review-template',
      kind: 'CUSTOM',
      category: 'LIFE',
      title: '晚间复盘',
      itemType: 'COUNTDOWN',
      durationSeconds: 1200,
      timerRemainingSeconds: 1200,
      timerRunning: false,
    } as const
    vi.mocked(fetchRhythmDashboard).mockResolvedValue({
      ...structuredClone(dashboard),
      checklist: [countdown],
      totalCount: 1,
    } as never)
    vi.mocked(updateRhythmTimer).mockResolvedValue({
      ...countdown,
      timerRunning: true,
    } as never)

    const wrapper = await mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('20:00')
    expect(wrapper.get('.timer-controls button').text()).toContain('开始')

    await wrapper.get('.timer-controls button').trigger('click')
    await flushPromises()
    expect(updateRhythmTimer).toHaveBeenCalledWith('review', 'START')
    expect(wrapper.text()).toContain('暂停')
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
    expect(wrapper.find('.checklist-list').exists()).toBe(true)
    expect(wrapper.findAll('.check-row')).toHaveLength(2)
    expect(wrapper.find('.checklist-list h3').exists()).toBe(false)
    expect(wrapper.find('.configure-button').exists()).toBe(false)
    expect(wrapper.emitted('configure')).toBeUndefined()
  })
})
