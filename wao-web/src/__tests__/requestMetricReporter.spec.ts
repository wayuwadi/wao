import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { requireSessionRecovery, resumeSessionRequests } from '@/utils/sessionRecovery'
import {
  flushRequestMetrics,
  pauseRequestMetricReporter,
  reportBrowserRequest,
  resetRequestMetricReporterForTest,
  resumeRequestMetricReporter,
} from '@/utils/requestMetricReporter'

describe('浏览器请求指标上报', () => {
  beforeEach(() => {
    resumeSessionRequests()
    vi.useFakeTimers()
    resetRequestMetricReporterForTest()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
  })

  afterEach(() => {
    resumeSessionRequests()
    resetRequestMetricReporterForTest()
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('会话恢复期间停止原生上报与离页Beacon，恢复后不补报旧批次', async () => {
    const beacon = vi.fn(); vi.stubGlobal('navigator', { sendBeacon: beacon })
    reportBrowserRequest('/api/projects', 'get', 401, 10, Date.now())
    requireSessionRecovery()
    window.dispatchEvent(new Event('pagehide'))
    await flushRequestMetrics()
    expect(fetch).not.toHaveBeenCalled(); expect(beacon).not.toHaveBeenCalled()
    resumeSessionRequests(); await flushRequestMetrics()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('批量上报真实 API 耗时并去掉查询参数', async () => {
    reportBrowserRequest('/api/projects?owner=private', 'get', 200, 123.6, Date.now(), 0)
    await flushRequestMetrics()

    expect(fetch).toHaveBeenCalledTimes(1)
    const [, init] = vi.mocked(fetch).mock.calls[0]!
    const payload = JSON.parse(String(init?.body))
    expect(payload.metrics).toMatchObject([
      { method: 'GET', path: '/api/projects', statusCode: 200, durationMs: 124, retryAttempt: 0 },
    ])
    expect(String(init?.body)).not.toContain('owner=private')
  })

  it('排除监控自身和公开接口，避免递归或错误归属', async () => {
    reportBrowserRequest('/api/request-metrics/overview', 'get', 200, 10, Date.now())
    reportBrowserRequest('/api/auth/session', 'get', 200, 10, Date.now())
    reportBrowserRequest('/api/projects', 'get', 200, 10, Date.now())
    await flushRequestMetrics()
    expect(fetch).toHaveBeenCalledTimes(1)
    const payload = JSON.parse(String(vi.mocked(fetch).mock.calls[0]![1]?.body))
    expect(payload.metrics).toHaveLength(1)
    expect(payload.metrics[0].path).toBe('/api/projects')
  })

  it('上报失败时保留批次并可再次重试', async () => {
    vi.mocked(fetch)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ ok: true } as Response)
    reportBrowserRequest('/api/vault/groups', 'get', null, 10_000, Date.now())
    await flushRequestMetrics()
    await flushRequestMetrics()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(String(vi.mocked(fetch).mock.calls[1]![1]?.body)).toContain('/api/vault/groups')
  })

  it('会话 4xx 时丢弃旧成员批次，避免换账号后错误归属', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({ ok: false, status: 401 } as Response)
    reportBrowserRequest('/api/projects', 'get', 401, 20, Date.now())
    await flushRequestMetrics()
    await flushRequestMetrics()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('清空历史时等待在途批次并丢弃暂停期间和待重试的旧样本', async () => {
    let finishUpload!: () => void
    vi.mocked(fetch).mockReturnValueOnce(new Promise((resolve) => {
      finishUpload = () => resolve({ ok: true } as Response)
    }))
    reportBrowserRequest('/api/projects', 'get', 200, 20, Date.now())
    const uploading = flushRequestMetrics()
    const pausing = pauseRequestMetricReporter()
    reportBrowserRequest('/api/tasks', 'get', 200, 30, Date.now())
    finishUpload()
    await uploading
    await pausing

    resumeRequestMetricReporter()
    await flushRequestMetrics()
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
