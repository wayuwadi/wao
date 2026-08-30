import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SystemReportsView from '@/views/SystemReportsView.vue'
import {
  fetchSystemReport,
  fetchSystemReportSettings,
  generateSystemReport,
  saveSystemReportSettings,
  type SystemPeriodicReport,
} from '@/api/systemReports'

vi.mock('@/api/systemReports', () => ({
  fetchSystemReport: vi.fn<(typeof import('@/api/systemReports'))['fetchSystemReport']>(),
  fetchSystemReportSettings:
    vi.fn<(typeof import('@/api/systemReports'))['fetchSystemReportSettings']>(),
  generateSystemReport: vi.fn<(typeof import('@/api/systemReports'))['generateSystemReport']>(),
  saveSystemReportSettings:
    vi.fn<(typeof import('@/api/systemReports'))['saveSystemReportSettings']>(),
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
  aggregationOptions: ['TASK_CREATED', 'TASK_COMPLETED', 'AI_RUN'],
  generatedAt: '2026-08-23T00:00:00',
  coverageMessage: '真实数据',
}

describe('SystemReportsView', () => {
  beforeEach(() => {
    vi.mocked(fetchSystemReport).mockResolvedValue(report)
    vi.mocked(generateSystemReport).mockResolvedValue(report)
    vi.mocked(fetchSystemReportSettings).mockResolvedValue({
      automaticEnabled: true,
      refreshTime: '00:00',
      refreshFrequencyHours: 24,
      periods: ['DAY', 'WEEK', 'MONTH'],
      aggregationOptions: ['TASK_CREATED', 'TASK_COMPLETED', 'AI_RUN'],
    })
    vi.mocked(saveSystemReportSettings).mockImplementation(async (value) => value)
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

    await wrapper.get('.period-tabs > div:nth-child(3) .period-select').trigger('click')
    await flushPromises()
    expect(fetchSystemReport).toHaveBeenLastCalledWith('MONTH', undefined)
    expect(wrapper.text()).toContain('这个周期尚未产出')

    await wrapper.get('.state button').trigger('click')
    await flushPromises()
    expect(generateSystemReport).toHaveBeenCalledWith('MONTH', '2026-07-01')
  })

  it('每类任务和运行记录默认只展示最近 20 条并可分页', async () => {
    const tasks = Array.from({ length: 25 }, (_, index) => ({
      ...report.tasks[0]!,
      id: `t${index + 1}`,
      title: `任务 ${index + 1}`,
    }))
    const runs = Array.from({ length: 25 }, (_, index) => ({
      ...report.runs[0]!,
      id: `ar${index + 1}`,
      taskId: `t${index + 1}`,
      taskTitle: `运行任务 ${index + 1}`,
    }))
    vi.mocked(fetchSystemReport).mockResolvedValueOnce({ ...report, tasks, runs })
    const wrapper = mount(SystemReportsView)
    await flushPromises()

    expect(wrapper.findAll('.task-columns > div:first-child > a')).toHaveLength(20)
    expect(wrapper.findAll('.task-columns > div:nth-child(2) > a')).toHaveLength(20)
    expect(wrapper.findAll('.run-list > a')).toHaveLength(20)

    await wrapper.get('.run-pagination [aria-label="第 2 页"]').trigger('click')
    expect(wrapper.findAll('.run-list > a')).toHaveLength(5)
    expect(wrapper.text()).toContain('运行任务 25')
  })

  it('日报周报月报各自提供重新统计按钮', async () => {
    const wrapper = mount(SystemReportsView)
    await flushPromises()

    await wrapper.get('button[aria-label="重新统计周报"]').trigger('click')
    await flushPromises()

    expect(generateSystemReport).toHaveBeenCalledWith('WEEK', undefined)
  })

  it('报告设置覆盖加载、持久化保存和汇总项选择', async () => {
    const wrapper = mount(SystemReportsView)
    await flushPromises()
    await wrapper.get('.hero button').trigger('click')

    await wrapper.get('input[type="time"]').setValue('09:30')
    const aiRunOption = wrapper.findAll('.settings-card fieldset')[1]!.findAll('input')[2]!
    await aiRunOption.setValue(false)
    await wrapper.get('.settings-card form').trigger('submit')
    await flushPromises()

    expect(saveSystemReportSettings).toHaveBeenCalledWith(
      expect.objectContaining({
        refreshTime: '09:30',
        aggregationOptions: ['TASK_CREATED', 'TASK_COMPLETED'],
      }),
    )
    expect(wrapper.text()).toContain('设置已保存')
  })

  it('报告设置读取和保存失败后都可原地重试', async () => {
    vi.mocked(fetchSystemReportSettings).mockRejectedValueOnce(new Error('设置读取中断'))
    const wrapper = mount(SystemReportsView)
    await flushPromises()
    await wrapper.get('.hero button').trigger('click')

    expect(wrapper.text()).toContain('设置读取中断')
    await wrapper.get('.settings-state button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.settings-card form').exists()).toBe(true)

    vi.mocked(saveSystemReportSettings).mockRejectedValueOnce(new Error('设置保存中断'))
    await wrapper.get('.settings-card form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('设置保存中断')
    expect(wrapper.get('.settings-card footer button').text()).toContain('重试保存')
  })
})
