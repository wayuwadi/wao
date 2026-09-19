import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clearRequestMetricHistory,
  fetchRequestMetricOverview,
  type RequestMetricOverview,
} from '@/api/requestMetrics'
import RequestMonitoringView from '@/views/RequestMonitoringView.vue'
import { apiRunJob, fetchAutomationJob } from '@/api/board'

vi.mock('@/api/requestMetrics', () => ({
  clearRequestMetricHistory:
    vi.fn<(typeof import('@/api/requestMetrics'))['clearRequestMetricHistory']>(),
  fetchRequestMetricOverview:
    vi.fn<(typeof import('@/api/requestMetrics'))['fetchRequestMetricOverview']>(),
}))
vi.mock('@/api/board', () => ({
  apiRunJob: vi.fn<(typeof import('@/api/board'))['apiRunJob']>(),
  fetchAutomationJob: vi.fn<(typeof import('@/api/board'))['fetchAutomationJob']>(),
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
    vi.mocked(apiRunJob).mockReset()
    vi.mocked(apiRunJob).mockResolvedValue({
      id: 'job-http-performance-ai-run',
      name: 'HTTP 请求性能夜间 AI 巡检',
      icon: '',
      schedule: '每日 06:30',
      status: '正常',
      last: '刚刚 · 成功',
      desc: '',
      source: '',
      target: '',
      steps: [],
      handler: 'http-performance-ai-run',
      runs: [{ time: '刚刚', executor: '你', result: '成功 · 已创建 AI Run ar1' }],
    })
    vi.mocked(fetchAutomationJob).mockReset()
    vi.mocked(fetchAutomationJob).mockResolvedValue({
      id: 'job-http-performance-ai-run',
      name: 'HTTP 请求性能夜间 AI 巡检',
      icon: '',
      schedule: '每日 06:30',
      status: '正常',
      last: '今天 06:30 · 成功',
      desc: '',
      source: '',
      target: '',
      steps: [],
      handler: 'http-performance-ai-run',
      runs: [
        {
          time: '今天 06:30',
          executor: '定时任务',
          result: '成功 · 已创建 AI Run ar-history',
          runAt: '2026-08-30T06:30:00',
          durationMs: 1234,
        },
      ],
    })
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

  it('解释成员样本并从摘要定位失败接口，不自动启动巡检', async () => {
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()
    const summary = wrapper.get('[aria-label="请求体验摘要"]')
    expect(summary.text()).toContain('当前账号 · 最近 24 小时')
    expect(summary.text()).toContain('先关注未成功的请求')
    expect(summary.text()).toContain('不能直接相加')
    const section = wrapper.get('[aria-label="优先关注的接口"]').element as HTMLElement
    section.scrollIntoView = vi.fn()
    const focus = vi.spyOn(section, 'focus')
    await summary.find('button').trigger('click')
    expect(section.scrollIntoView).toHaveBeenCalledOnce()
    expect(focus).toHaveBeenCalledOnce()
    expect(apiRunJob).not.toHaveBeenCalled()
    await summary.findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(fetchAutomationJob).toHaveBeenCalledWith('job-http-performance-ai-run')
  })

  it.each([
    [0, 1, '有等待较久的请求'],
    [0, 0, '当前样本未发现失败或慢请求'],
  ])('根据真实失败数 %s 和慢请求数 %s 给出有限结论', async (failedRequests, slowRequests, title) => {
    vi.mocked(fetchRequestMetricOverview).mockResolvedValue({ ...overview, failedRequests, slowRequests })
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()
    expect(wrapper.get('[aria-label="请求体验摘要"]').text()).toContain(title)
  })

  it('切换时间范围清除旧样本，晚返回的旧请求不能覆盖新范围', async () => {
    let resolveOld!: (value: RequestMetricOverview) => void
    vi.mocked(fetchRequestMetricOverview).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
    const wrapper = mount(RequestMonitoringView)
    vi.mocked(fetchRequestMetricOverview).mockResolvedValueOnce({ ...overview, windowHours: 1 })
    await wrapper.get('select').setValue(1)
    await flushPromises()
    resolveOld(overview)
    await flushPromises()
    expect(wrapper.get('.sample-context').text()).toContain('最近 1 小时')
    vi.mocked(fetchRequestMetricOverview).mockRejectedValueOnce(new Error('offline'))
    await wrapper.get('select').setValue(6)
    await flushPromises()
    expect(wrapper.find('.attention-summary').exists()).toBe(false)
    expect(wrapper.text()).toContain('重新加载')
  })

  it('同范围刷新失败保留已标明时间的旧样本与重试入口', async () => {
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()
    vi.mocked(fetchRequestMetricOverview).mockRejectedValueOnce(new Error('offline'))
    await wrapper.findAll('.actions button').find(button => button.text() === '刷新')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已保留上一次结果')
    expect(wrapper.get('.sample-context').text()).toContain('最近 24 小时')
    vi.mocked(fetchRequestMetricOverview).mockResolvedValueOnce(overview)
    await wrapper.get('.inline-error button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.inline-error').exists()).toBe(false)
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

  it('两块列表左右布局且各自默认只展示20条并可翻页', async () => {
    vi.mocked(fetchRequestMetricOverview).mockResolvedValue({
      ...overview,
      totalRequests: 21,
      endpoints: Array.from({ length: 21 }, (_, index) => ({
        ...overview.endpoints[0]!,
        path: `/api/endpoint-${index + 1}`,
      })),
      recentRequests: Array.from({ length: 21 }, (_, index) => ({
        ...overview.recentRequests[0]!,
        id: index + 1,
        path: `/api/recent-${index + 1}`,
      })),
    })
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()

    expect(wrapper.find('.request-tables').exists()).toBe(true)
    expect(wrapper.findAll('.table-card:first-child tbody tr')).toHaveLength(20)
    expect(wrapper.text()).not.toContain('/api/endpoint-9')
    expect(wrapper.findAll('.pagination')).toHaveLength(2)

    await wrapper.findAll('.pagination')[0]!.get('[aria-label="第 2 页"]').trigger('click')
    expect(wrapper.findAll('.table-card:first-child tbody tr')).toHaveLength(1)
    expect(wrapper.text()).toContain('/api/endpoint-9')
  })

  it('默认按失败数倒序，并支持改按平均耗时或 P95 倒序', async () => {
    vi.mocked(fetchRequestMetricOverview).mockResolvedValue({
      ...overview,
      totalRequests: 3,
      endpoints: [
        {
          ...overview.endpoints[0]!,
          path: '/api/failures',
          failedCount: 8,
          averageDurationMs: 100,
          p95DurationMs: 200,
        },
        {
          ...overview.endpoints[0]!,
          path: '/api/average',
          failedCount: 1,
          averageDurationMs: 9000,
          p95DurationMs: 9500,
        },
        {
          ...overview.endpoints[0]!,
          path: '/api/p95',
          failedCount: 2,
          averageDurationMs: 800,
          p95DurationMs: 10000,
        },
      ],
    })
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()

    const paths = () =>
      wrapper.findAll('.table-card:first-child tbody code').map((item) => item.text())
    expect(paths()).toEqual(['/api/failures', '/api/p95', '/api/average'])

    await wrapper.findAll('.sort-button')[0]!.trigger('click')
    expect(paths()[0]).toBe('/api/average')

    await wrapper.findAll('.sort-button')[1]!.trigger('click')
    expect(paths()[0]).toBe('/api/p95')
  })

  it('从监控页按钮查看真实自动化巡检历史', async () => {
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()

    await wrapper.find('.history-button').trigger('click')
    await flushPromises()

    expect(fetchAutomationJob).toHaveBeenCalledWith('job-http-performance-ai-run')
    expect(wrapper.find('[aria-labelledby="inspection-history-title"]').text()).toContain(
      '成功 · 已创建 AI Run ar-history',
    )
  })

  it('从监控页直接触发现有真实巡检任务', async () => {
    const wrapper = mount(RequestMonitoringView)
    await flushPromises()

    await wrapper.find('.inspection-button').trigger('click')
    await flushPromises()

    expect(apiRunJob).toHaveBeenCalledWith('job-http-performance-ai-run')
  })
})
