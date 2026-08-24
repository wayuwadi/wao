import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SystemReportsView from '@/views/SystemReportsView.vue'
import {
  fetchSystemReport,
  generateSystemReport,
  type SystemPeriodicReport,
} from '@/api/systemReports'

vi.mock('@/api/systemReports', () => ({
  fetchSystemReport: vi.fn<(typeof import('@/api/systemReports'))['fetchSystemReport']>(),
  generateSystemReport: vi.fn<(typeof import('@/api/systemReports'))['generateSystemReport']>(),
}))

const report: SystemPeriodicReport = {
  period: 'DAY',
  periodKey: '2026-08-22',
  periodLabel: '2026-08-22 日报',
  startDate: '2026-08-22',
  endDate: '2026-08-22',
  projectTotal: 3,
  taskTotal: 8,
  openTaskTotal: 2,
  aiRunTotal: 11,
  taskCreatedCount: 1,
  taskCompletedCount: 1,
  aiRunCount: 2,
  aiRunSuccessCount: 1,
  aiRunFailureCount: 1,
  aiRunMeteredCount: 1,
  totalTokens: 1234,
  tasks: [
    {
      id: 't1',
      title: '真实任务',
      projectId: 'p1',
      projectName: 'WAO',
      status: '已完成',
      events: ['CREATED', 'COMPLETED'],
      createdAt: '2026-08-22T09:00:00',
      completedAt: '2026-08-22T18:00:00',
    },
  ],
  runs: [
    {
      id: 'ar1',
      taskId: 't1',
      taskTitle: '真实任务',
      projectId: 'p1',
      projectName: 'WAO',
      engine: 'codex',
      status: 'succeeded',
      resultSummary: '已部署验收',
      totalTokens: 1234,
      createdAt: '2026-08-22T10:00:00',
      finishedAt: '2026-08-22T11:00:00',
    },
  ],
  generatedAt: '2026-08-23T00:00:00',
  coverageMessage: '真实数据',
}

describe('SystemReportsView', () => {
  beforeEach(() => {
    vi.mocked(fetchSystemReport).mockResolvedValue(report)
    vi.mocked(generateSystemReport).mockResolvedValue(report)
  })

  it('展示真实存量、周期活动和用量覆盖，不隐藏任务跳转', async () => {
    const wrapper = mount(SystemReportsView)
    await flushPromises()

    expect(wrapper.text()).toContain('系统报告')
    expect(wrapper.text()).toContain('1,234')
    expect(wrapper.text()).toContain('1/2 有用量')
    expect(wrapper.text()).toContain('真实任务')
    expect(wrapper.find('a[href="/projects/p1?task=t1"]').exists()).toBe(true)
  })

  it('可切换月报并在未产出时用真实数据补齐', async () => {
    vi.mocked(fetchSystemReport)
      .mockResolvedValueOnce(report)
      .mockResolvedValueOnce({
        ...report,
        period: 'MONTH',
        periodKey: '2026-07',
        periodLabel: '2026-07 月报',
        startDate: '2026-07-01',
        endDate: '2026-07-31',
        generatedAt: null,
        tasks: [],
        runs: [],
      })
    const wrapper = mount(SystemReportsView)
    await flushPromises()

    await wrapper.get('.period-tabs button:nth-child(3)').trigger('click')
    await flushPromises()
    expect(fetchSystemReport).toHaveBeenLastCalledWith('MONTH', undefined)
    expect(wrapper.text()).toContain('这个周期尚未产出')

    await wrapper.get('.state button').trigger('click')
    await flushPromises()
    expect(generateSystemReport).toHaveBeenCalledWith('MONTH', '2026-07-01')
  })
})
