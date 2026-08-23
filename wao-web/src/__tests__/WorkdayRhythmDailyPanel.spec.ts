import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkdayRhythmDailyPanel from '@/components/workday/WorkdayRhythmDailyPanel.vue'
import {
  createRhythmDocument,
  fetchRhythmDashboard,
  setRhythmOccurrenceCompleted,
  setRhythmWorkdayStatus,
} from '@/api/workdayRhythm'
import { createTodayTianrenReading, fetchTodayTianrenReading } from '@/api/tianren'
import { fetchDocTemplates } from '@/api/board'

vi.mock('@/stores/data', () => ({ useDataStore: () => ({ MAX_IMAGE_BYTES: 10_000_000, uploadImage: vi.fn() }) }))
vi.mock('@/api/workdayRhythm', () => ({
  fetchRhythmDashboard: vi.fn(), setRhythmOccurrenceCompleted: vi.fn(), setRhythmWorkdayStatus: vi.fn(), createRhythmDocument: vi.fn(),
}))
vi.mock('@/api/tianren', () => ({ fetchTodayTianrenReading: vi.fn(), createTodayTianrenReading: vi.fn() }))
vi.mock('@/api/board', () => ({ fetchDocTemplates: vi.fn(), fetchDoc: vi.fn(), apiPatchDoc: vi.fn() }))

const dashboard = {
  date: '2026-08-23', workday: true, workdayOverride: null, workWeekLabel: '大周 · 周日上班',
  completedCount: 0, totalCount: 2,
  checklist: [
    { id: 'plan', itemId: 'i1', occurrenceDate: '2026-08-23', kind: 'PLAN', category: 'WORK', title: '制定今日计划', startTime: '07:00', dueTime: '09:00', dueNextDay: false, completedAt: null, completedOnTime: null, status: 'DUE' },
    { id: 'out', itemId: 'i2', occurrenceDate: '2026-08-22', kind: 'CLOCK_OUT', category: 'WORK', title: '下班打卡', startTime: '18:00', dueTime: '07:00', dueNextDay: true, completedAt: null, completedOnTime: null, status: 'LATE' },
  ],
  document: { id: null, state: 'READY', kbId: 'kb1', folderId: 'f1', title: '2026-08-23 每日工作记录' },
  settings: { followAlternateWeek: true, documentKbId: 'kb1', documentFolderId: 'f1', documentTemplateId: null, documentTitlePattern: '{date} 每日工作记录', tianrenEnabled: true, tianrenRequired: false },
} as const

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: { template: '<div />' } }, { path: '/tianren', component: { template: '<div />' } },
    { path: '/kb/:id', component: { template: '<div />' } },
  ] })
  await router.push('/'); await router.isReady()
  return mount(WorkdayRhythmDailyPanel, {
    props: { date: '2026-08-23' },
    global: { plugins: [router], stubs: { MarkdownEditor: {
      props: ['modelValue'], emits: ['update:modelValue'],
      template: '<textarea class="daily-editor" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    } } },
  })
}

describe('WorkdayRhythmDailyPanel', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchRhythmDashboard).mockResolvedValue(structuredClone(dashboard) as never)
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(null)
    vi.mocked(fetchDocTemplates).mockResolvedValue([])
    vi.mocked(setRhythmOccurrenceCompleted).mockResolvedValue({ ...dashboard.checklist[0], completedAt: '2026-08-23T08:30:00', completedOnTime: true, status: 'ON_TIME' } as never)
    vi.mocked(createRhythmDocument).mockResolvedValue({ id: 'doc1', title: dashboard.document.title, author: 'm1', created: '', updated: '', links: [], blocks: [], backlinks: [], kbId: 'kb1', folderId: 'f1' })
  })

  it('展示大小周、跨日逾期项并保存清单完成状态', async () => {
    const wrapper = await mountPanel(); await flushPromises()
    expect(wrapper.text()).toContain('大周 · 周日上班')
    expect(wrapper.text()).toContain('2026-08-22 的跨日事项')
    expect(wrapper.text()).toContain('已逾期')
    await wrapper.findAll('.check-row')[0]!.trigger('click'); await flushPromises()
    expect(setRhythmOccurrenceCompleted).toHaveBeenCalledWith('plan', true)
    expect(wrapper.text()).toContain('1 / 2 已完成')
  })

  it('首次输入时幂等创建每日文档并清除本机草稿', async () => {
    vi.useFakeTimers()
    const wrapper = await mountPanel(); await flushPromises()
    await wrapper.get('.daily-editor').setValue('## 今日完成\n\n- 上线')
    await vi.advanceTimersByTimeAsync(1000); await flushPromises()
    expect(createRhythmDocument).toHaveBeenCalledWith('2026-08-23', dashboard.document.title,
      expect.arrayContaining([expect.objectContaining({ h: '今日完成' })]))
    expect(localStorage.getItem('wao-workday-rhythm-document-draft-v1:2026-08-23')).toBeNull()
    vi.useRealTimers()
  })

  it('未起卦时保持主动入口并在成功后显示真实卦象', async () => {
    vi.mocked(createTodayTianrenReading).mockResolvedValue({ id: 'r1', date: '2026-08-23', lines: [7,7,7,7,7,7], changingLines: [], primary: { number: 1, name: '乾', symbol: '䷀', upper: { name: '乾', nature: '天', symbol: '☰' }, lower: { name: '乾', nature: '天', symbol: '☰' } }, relating: null, conversationId: null, createdAt: '' })
    const wrapper = await mountPanel(); await flushPromises()
    expect(wrapper.text()).toContain('不会因为打开页面自动生成')
    await wrapper.get('.hexagram button').trigger('click'); await flushPromises()
    expect(createTodayTianrenReading).toHaveBeenCalled()
  })
})
