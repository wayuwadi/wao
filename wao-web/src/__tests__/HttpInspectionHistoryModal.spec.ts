import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchAutomationJob } from '@/api/board'
import HttpInspectionHistoryModal from '@/components/overlays/HttpInspectionHistoryModal.vue'
import type { AutomationJob, JobRun } from '@/types'

vi.mock('@/api/board', () => ({
  fetchAutomationJob: vi.fn<(typeof import('@/api/board'))['fetchAutomationJob']>(),
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
  beforeEach(() => vi.mocked(fetchAutomationJob).mockReset())

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

    expect(wrapper.findAll('.run-list article')).toHaveLength(10)
    expect(wrapper.find('.history-pagination').text()).toContain('跳至')
    await wrapper.get('[aria-label="第 2 页"]').trigger('click')
    expect(wrapper.findAll('.run-list article')).toHaveLength(1)
  })
})
