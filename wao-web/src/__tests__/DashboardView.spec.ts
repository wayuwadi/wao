import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import DashboardView from '@/views/DashboardView.vue'
import {
  fetchAiRunTrend,
  fetchAiQuotaHistory,
  fetchAiUsageInsights,
  fetchDashboardSummary,
  fetchEngineUsage,
  fetchTaskTrend,
  type AiRunTrendItem,
  type AiQuotaHistory,
  type AiUsageInsights,
  type DashboardSummary,
  type EngineUsageItem,
  type TaskTrendItem,
} from '@/api/dashboard'

const chart = vi.hoisted(() => ({
  clear: vi.fn<() => void>(),
  setOption: vi.fn<(option: Record<string, unknown>, replace?: boolean) => void>(),
  resize: vi.fn<() => void>(),
  dispose: vi.fn<() => void>(),
}))
const initChart = vi.hoisted(() => vi.fn<() => typeof chart>(() => chart))

vi.mock('echarts/core', () => ({
  use: vi.fn<(components: unknown[]) => void>(),
  init: initChart,
}))

vi.mock('@/api/dashboard', () => ({
  fetchDashboardSummary: vi.fn<() => Promise<DashboardSummary>>().mockResolvedValue({
    totalRuns: 12,
    activeRuns: 1,
    onlineRunners: 2,
    completedThisWeek: 3,
    succeededThisWeek: 4,
    failedThisWeek: 1,
  }),
  fetchAiRunTrend: vi.fn<() => Promise<AiRunTrendItem[]>>().mockResolvedValue([
    {
      date: '2026-08-10',
      total: 2,
      succeeded: 1,
      failed: 1,
      cancelled: 0,
      recordedRuns: 1,
      inputTokens: 4_000,
      outputTokens: 1_000,
      cachedInputTokens: 5_000,
      totalTokens: 10_000,
    },
    {
      date: '2026-08-11',
      total: 3,
      succeeded: 3,
      failed: 0,
      cancelled: 0,
      recordedRuns: 2,
      inputTokens: 8_000,
      outputTokens: 2_000,
      cachedInputTokens: 10_000,
      totalTokens: 20_000,
    },
  ]),
  fetchTaskTrend: vi
    .fn<() => Promise<TaskTrendItem[]>>()
    .mockResolvedValue([{ date: '2026-08-11', completed: 3, created: 4 }]),
  fetchEngineUsage: vi
    .fn<() => Promise<EngineUsageItem[]>>()
    .mockResolvedValue([{ engine: 'codex', label: 'Codex', count: 5 }]),
  fetchAiQuotaHistory: vi.fn<() => Promise<AiQuotaHistory>>().mockResolvedValue({
    recordedSamples: 8,
    series: [
      {
        id: 'quota-1',
        accountLabel: 'Codex 主账号',
        provider: 'codex',
        windowLabel: '七天额度',
        points: [
          { date: '2026-08-10', remainingPercent: 70, decreasedPercent: 0 },
          { date: '2026-08-11', remainingPercent: 100, decreasedPercent: 0 },
        ],
      },
    ],
    refreshes: [
      {
        accountLabel: 'Codex 主账号',
        provider: 'codex',
        windowLabel: '七天额度',
        beforeRemainingPercent: 70,
        afterRemainingPercent: 100,
        refreshedPercent: 30,
        resetsAt: '2026-08-18T09:00:00',
        observedAt: '2026-08-11T09:00:00',
      },
    ],
  }),
  fetchAiUsageInsights: vi.fn<() => Promise<AiUsageInsights>>().mockResolvedValue({
    windowDays: 30,
    totalRuns: 5,
    completedRuns: 5,
    meteredRuns: 3,
    timedRuns: 4,
    deliveredRuns: 4,
    deliveredTasks: 4,
    verifiedTasks: 2,
    totalTokens: 30_000,
    failedTokens: 4_000,
    tokensPerDeliveredTask: 7_500,
    retryRuns: 1,
    retryTokens: 4_000,
    medianDurationSeconds: 600,
    medianAiExecutionSeconds: 180,
    advice: [
      {
        category: 'OUTCOME',
        title: '单位交付消耗',
        recommendation: '以每个有效交付约 7500 Token 作为当前基线',
        evidence: '4 个任务已部署并线上验收',
        sufficientData: true,
      },
      {
        category: 'ROUTING',
        title: '模型与思考深度',
        recommendation: '继续积累同类可比样本',
        evidence: '当前可独立评测的配置组 1 个',
        sufficientData: false,
      },
    ],
  }),
}))

function mountDashboard() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  return mount(DashboardView, { global: { plugins: [router] } })
}

describe('DashboardView AI 用量面板', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('展示每日频次、Token 总量与真实记账覆盖数，并在节点挂载后绘图', async () => {
    const wrapper = mountDashboard()
    await flushPromises()

    expect(wrapper.text()).toContain('每日 AI 使用频次')
    expect(wrapper.text()).toContain('每日 Token 消耗')
    expect(wrapper.find('.kpi-grid').text()).toContain('30K')
    expect(wrapper.find('.kpi-grid').text()).toContain('已记账 3 / 5 次运行')
    expect(wrapper.find('.usage-overview').text()).toContain('未回传2 次')
    expect(wrapper.text()).toContain('额度剩余与每日下降')
    expect(wrapper.find('.refresh-list').text()).toContain('70% → 100%')
    expect(wrapper.find('.insight-card').text()).toContain('AI 交付效能')
    expect(wrapper.find('.insight-card').text()).toContain('7.5K')
    expect(wrapper.find('.insight-card').text()).toContain('2 / 4')
    expect(wrapper.find('.insight-card').text()).toContain('3 分 0 秒')
    expect(wrapper.find('.insight-card').text()).toContain('端到端等待时间不会被当成模型速度')
    expect(initChart).toHaveBeenCalledTimes(5)

    const tokenOption = chart.setOption.mock.calls
      .map(([option]) => option as { series?: Array<{ name: string; data: number[] }> })
      .find((option) => option.series?.some((series) => series.name === '总 Token'))
    expect(tokenOption?.series?.find((series) => series.name === '输入')?.data).toEqual([
      4_000, 8_000,
    ])
    expect(tokenOption?.series?.find((series) => series.name === '总 Token')?.data).toEqual([
      10_000, 20_000,
    ])
    wrapper.unmount()
  })

  it('每 30 秒静默刷新，卸载后停止轮询', async () => {
    const wrapper = mountDashboard()
    await flushPromises()
    expect(fetchAiRunTrend).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(30_000)
    await flushPromises()
    expect(fetchDashboardSummary).toHaveBeenCalledTimes(2)
    expect(fetchAiRunTrend).toHaveBeenCalledTimes(2)
    expect(fetchTaskTrend).toHaveBeenCalledTimes(2)
    expect(fetchEngineUsage).toHaveBeenCalledTimes(2)
    expect(fetchAiQuotaHistory).toHaveBeenCalledTimes(2)
    expect(fetchAiUsageInsights).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(fetchAiRunTrend).toHaveBeenCalledTimes(2)
  })

  it('首次加载失败时保留可理解的错误和重试入口', async () => {
    vi.mocked(fetchDashboardSummary).mockRejectedValueOnce(new Error('统计服务暂时不可用'))
    const wrapper = mountDashboard()
    await flushPromises()

    expect(wrapper.find('.load-error').text()).toContain('统计数据加载失败')
    expect(wrapper.find('.load-error').text()).toContain('统计服务暂时不可用')
    expect(wrapper.find('.load-error button').text()).toContain('重试')
    wrapper.unmount()
  })
})
