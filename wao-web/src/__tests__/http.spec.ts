import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { http } from '@/api/http'
import { reportBrowserRequest } from '@/utils/requestMetricReporter'

vi.mock('@/utils/requestMetricReporter', () => ({
  reportBrowserRequest:
    vi.fn<(typeof import('@/utils/requestMetricReporter'))['reportBrowserRequest']>(),
}))

function gatewayError(config: InternalAxiosRequestConfig, status: number) {
  const response: AxiosResponse = {
    config,
    data: '',
    headers: {},
    status,
    statusText: 'Bad Gateway',
  }
  return new AxiosError('Bad Gateway', 'ERR_BAD_RESPONSE', config, undefined, response)
}

describe('HTTP 网关短暂故障自愈', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.mocked(reportBrowserRequest).mockClear()
  })

  it('对纯读请求的 502/503/504 按退避上限重试', async () => {
    vi.useFakeTimers()
    let attempts = 0
    const statuses = [502, 503, 504] as const
    const request = http.get('/api/test', {
      adapter: async (config) => {
        attempts += 1
        if (attempts < 4) throw gatewayError(config, statuses[attempts - 1]!)
        return {
          config,
          data: { code: 0, message: 'ok', data: true, timestamp: 1 },
          headers: {},
          status: 200,
          statusText: 'OK',
        }
      },
    })

    await vi.runAllTimersAsync()
    await expect(request).resolves.toMatchObject({ status: 200 })
    expect(attempts).toBe(4)
    expect(reportBrowserRequest).toHaveBeenCalledTimes(4)
  })

  it('不重放写请求', async () => {
    let attempts = 0
    await expect(
      http.post(
        '/api/test',
        {},
        {
          adapter: async (config) => {
            attempts += 1
            throw gatewayError(config, 502)
          },
        },
      ),
    ).rejects.toBeInstanceOf(AxiosError)
    expect(attempts).toBe(1)
    expect(reportBrowserRequest).toHaveBeenCalledTimes(1)
  })

  it('不重试业务或权限错误', async () => {
    let attempts = 0
    await expect(
      http.get('/api/test', {
        adapter: async (config) => {
          attempts += 1
          throw gatewayError(config, 401)
        },
      }),
    ).rejects.toBeInstanceOf(AxiosError)
    expect(attempts).toBe(1)
    expect(reportBrowserRequest).toHaveBeenCalledTimes(1)
  })
})
