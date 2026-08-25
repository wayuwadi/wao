import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clearRequestMetricHistory,
  fetchRequestMetricOverview,
  type RequestMetricOverview,
} from '@/api/requestMetrics'
import RequestMonitoringView from '@/views/RequestMonitoringView.vue'

vi.mock('@/api/requestMetrics', () => ({
  clearRequestMetricHistory:
    vi.fn<(typeof import('@/api/requestMetrics'))['clearRequestMetricHistory']>(),
  fetchRequestMetricOverview:
    vi.fn<(typeof import('@/api/requestMetrics'))['fetchRequestMetricOverview']>(),
}))

const overview: RequestMetricOverview = {
  windowHours: 24,
  generatedAt: '2026-08-24T12:00:00',
  totalRequests: 3,
  averageDurationMs: 800,
  p95DurationMs: 2_100,
  slowRequests: 1,
  failedRequests: 1,
  endpoints: [
    {
      method: 'GET',
      path: '/api/projects',
      requestCount: 3,
      averageDurationMs: 800,
      p95DurationMs: 2_100,
      slowCount: 1,
      failedCount: 1,
    },
  ],
  recentRequests: [
    {
      id: 1,
      method: 'GET',
      path: '/api/projects',
      statusCode: 504,
      durationMs: 2_100,
      networkType: '4g',
      retryAttempt: 0,
      startedAt: '2026-08-24T11:59:00',
    },
  ],
}

describe('RequestMonitoringView', () => {
  beforeEach(() => {
    vi.mocked(fetchRequestMetricOverview).mockReset()
    vi.mocked(fetchRequestMetricOverview).mockResolvedValue(overview)
    vi.mocked(clearRequestMetricHistory).mockReset()
    vi.mocked(clearRequestMetricHistory).mockResolvedValue({ cleared: 3 })
  })

  it('展示真实聚合、慢请求和最近明细', async () => {
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()
    expect(fetchRequestMetricOverview).toHaveBeenCalledWith(24)
    expect(wrapper.text()).toContain('P95 耗时')
    expect(wrapper.text()).toContain('/api/projects')
    expect(wrapper.text()).toContain('2100 ms')
    expect(wrapper.text()).toContain('504')
  })

  it('无数据时明确不填演示数据', async () => {
    vi.mocked(fetchRequestMetricOverview).mockResolvedValue({
      ...overview,
      totalRequests: 0,
      averageDurationMs: 0,
      p95DurationMs: 0,
      slowRequests: 0,
      failedRequests: 0,
      endpoints: [],
      recentRequests: [],
    })
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()
    expect(wrapper.text()).toContain('还没有请求记录')
    expect(wrapper.text()).toContain('不会填充演示数据')
  })

  it('加载失败时给出原地重试', async () => {
    vi.mocked(fetchRequestMetricOverview).mockRejectedValue(new Error('公司网络暂时不可达'))
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()
    expect(wrapper.text()).toContain('请求监控加载失败，请检查网络后重试')
    expect(wrapper.text()).toContain('重新加载')
  })

  it('确认后清空当前账号的全部历史并重新读取真实空状态', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(fetchRequestMetricOverview)
      .mockResolvedValueOnce(overview)
      .mockResolvedValueOnce({
        ...overview,
        totalRequests: 0,
        endpoints: [],
        recentRequests: [],
      })
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()

    await wrapper.find('.clear-button').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith('清空当前账号的全部 HTTP 请求历史？此操作不可恢复。')
    expect(clearRequestMetricHistory).toHaveBeenCalledOnce()
    expect(fetchRequestMetricOverview).toHaveBeenLastCalledWith(24)
    expect(wrapper.text()).toContain('还没有请求记录')
    confirm.mockRestore()
  })

  it('取消确认时保留历史记录', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()

    await wrapper.find('.clear-button').trigger('click')

    expect(clearRequestMetricHistory).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('/api/projects')
    confirm.mockRestore()
  })
})
