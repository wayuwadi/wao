import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchAiRun } from '@/api/ai'
import { fetchAutomationJob } from '@/api/board'
import HttpInspectionHistoryModal from '@/components/overlays/HttpInspectionHistoryModal.vue'
import type { AutomationJob, JobRun } from '@/types'

vi.mock('@/api/board', () => ({
  fetchAutomationJob: vi.fn<(typeof import('@/api/board'))['fetchAutomationJob']>(),
}))
vi.mock('@/api/ai', () => ({
  fetchAiRun: vi.fn<(typeof import('@/api/ai'))['fetchAiRun']>(),
}))

function job(runs: JobRun[]): AutomationJob {
  return {
    id: 'job-http-performance-ai-run',
    name: 'HTTP 请求性能夜间 AI 巡检',
    icon: 'ri-speed-up-line',
    schedule: '每日 06:30',
    status: '正常',
    last: runs[0]?.result ?? '尚未运行',
    desc: '',
    source: '',
    target: '',
    steps: [],
    handler: 'http-performance-ai-run',
    runs,
  }
}

describe('HttpInspectionHistoryModal', () => {
  beforeEach(() => {
    vi.mocked(fetchAutomationJob).mockReset()
    vi.mocked(fetchAiRun).mockReset()
  })

  it('无真实运行时展示空历史而不填示例结果', async () => {
    vi.mocked(fetchAutomationJob).mockResolvedValue(job([]))
    const wrapper = mount(HttpInspectionHistoryModal)
    await flushPromises()

    expect(wrapper.text()).toContain('还没有巡检记录')
    expect(wrapper.text()).toContain('真实结果会出现在这里')
  })

  it('读取失败保留重试入口，重试后展示真实结果', async () => {
    vi.mocked(fetchAutomationJob)
      .mockRejectedValueOnce(new Error('公司网络暂时不可用'))
      .mockResolvedValueOnce(
        job([{ time: '今天 06:30', executor: '定时任务', result: '成功 · 已创建 AI Run ar1' }]),
      )
    const wrapper = mount(HttpInspectionHistoryModal)
    await flushPromises()

    expect(wrapper.text()).toContain('公司网络暂时不可用')
    await wrapper.get('.state.error button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('成功 · 已创建 AI Run ar1')
    expect(fetchAutomationJob).toHaveBeenCalledTimes(2)
  })

  it('超过十条时可通过页码和指定页控件浏览历史', async () => {
    vi.mocked(fetchAutomationJob).mockResolvedValue(
      job(
        Array.from({ length: 11 }, (_, index) => ({
          time: `第 ${index + 1} 次`,
          executor: '定时任务',
          result: `成功 · 运行 ${index + 1}`,
          runAt: `2026-08-${String(30 - index).padStart(2, '0')}T06:30:00`,
          durationMs: 1000,
        })),
      ),
    )
    const wrapper = mount(HttpInspectionHistoryModal)
    await flushPromises()

    expect(wrapper.findAll('.run-list > button')).toHaveLength(10)
    expect(wrapper.find('.history-pagination').text()).toContain('跳至')
    await wrapper.get('[aria-label="第 2 页"]').trigger('click')
    expect(wrapper.findAll('.run-list > button')).toHaveLength(1)
  })

  it('保留真实汇总和触发记录，不再读取下线后的执行详情', async () => {
    vi.mocked(fetchAutomationJob).mockResolvedValue(job([
      { id: 'r1', time: '今天 06:30', executor: '定时任务',
        result: '成功 · 已创建 AI Run ar1', aiRunId: 'ar1', durationMs: 1200 },
      { id: 'r2', time: '昨天 06:30', executor: '定时任务',
        result: '失败 · 工作流不可用', durationMs: 800 },
      { id: 'r3', time: '8月28日 06:30', executor: '定时任务',
        result: '跳过 · 没有真实 HTTP 指标', durationMs: 1000 },
    ]))
    vi.mocked(fetchAiRun).mockResolvedValue({
      id: 'ar1', taskId: 't1', taskTitle: 'HTTP 请求性能夜间巡检', taskIds: ['t1'],
      previewImages: [], subId: null, subTitle: null, projectId: 'p1', projectName: 'Wa All in One',
      engine: 'codex', status: 'succeeded', runnerId: 'rn1', runnerName: 'Linux 主节点',
      requestedRunnerId: null, branchName: 'ai/wao-ar1', retryOfRunId: null,
      resultSummary: '完成 2 个接口治理', reportUrl: '/api/pages/ai-run-ar1',
      latestProgress: null, progressUpdatedAt: null, progress: [],
      usage: { model: 'gpt-5.6-sol', models: 'gpt-5.6-sol', inputTokens: 100,
        outputTokens: 50, cachedInputTokens: 0, totalTokens: 150, costUsd: null, recorded: true },
      continuationPending: false, sessionId: 's1', exitCode: 0,
      createdAt: '2026-08-30T06:30:00', startedAt: '2026-08-30T06:31:00',
      finishedAt: '2026-08-30T06:36:00', memoryReads: [], memoryWrites: [], skills: [], skillAudits: [],
    })
    const wrapper = mount(HttpInspectionHistoryModal)
    await flushPromises()

    expect(wrapper.find('.statistics').text()).toContain('总次数3')
    expect(wrapper.find('.statistics').text()).toContain('已创建1')
    expect(wrapper.find('.statistics').text()).toContain('启动失败1')
    expect(wrapper.find('.statistics').text()).toContain('已跳过1')

    await wrapper.find('.run-list > button').trigger('click')
    await flushPromises()

    expect(fetchAiRun).not.toHaveBeenCalled()
    expect(wrapper.find('.run-detail').text()).toContain('ar1')
    expect(wrapper.find('.ai-run-detail').exists()).toBe(false)
    expect(wrapper.text()).toContain('执行功能已下线')
  })
})
