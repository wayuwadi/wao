import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { http } from '@/api/http'
import { reportBrowserRequest } from '@/utils/requestMetricReporter'
import { edgeApiRouting } from '@/api/edgeRouting'

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
    vi.restoreAllMocks()
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

  it('节点纯读故障只回云端一次，不叠加旧重试循环', async () => {
    vi.spyOn(edgeApiRouting, 'route').mockReturnValue({ origin: 'https://linux2.wayuwa.cn:9443', csrf: 'test-csrf', validUntil: Infinity })
    vi.spyOn(edgeApiRouting, 'failed').mockImplementation(() => {})
    const bases: string[] = []
    await expect(http.get('/api/projects', { adapter: async config => {
      bases.push(config.baseURL!)
      throw gatewayError(config, 503)
    } })).rejects.toBeInstanceOf(AxiosError)
    expect(bases).toEqual(['https://linux2.wayuwa.cn:9443', '/'])
  })

  it('节点写请求携带CSRF，断网后保留结果未知状态而不重放', async () => {
    vi.spyOn(edgeApiRouting, 'route').mockReturnValue({ origin: 'https://linux2.wayuwa.cn:9443', csrf: 'test-csrf', validUntil: Infinity })
    vi.spyOn(edgeApiRouting, 'failed').mockImplementation(() => {})
    let calls = 0
    await expect(http.post('/api/tasks', { title: '仅单测' }, { adapter: async config => {
      calls++
      expect(config.headers.get('X-WAO-CSRF')).toBe('test-csrf')
      throw new AxiosError('network lost', 'ERR_NETWORK', config)
    } })).rejects.toThrow('保存结果尚未确认')
    expect(calls).toBe(1)
  })

  it('节点401和用户取消都不跨入口重试', async () => {
    vi.spyOn(edgeApiRouting, 'route').mockReturnValue({ origin: 'https://linux2.wayuwa.cn:9443', csrf: 'test-csrf', validUntil: Infinity })
    vi.spyOn(edgeApiRouting, 'failed').mockImplementation(() => {})
    for (const status of [401, 403]) {
      let calls = 0
      await expect(http.get('/api/tasks', { adapter: async config => { calls++; throw gatewayError(config, status) } })).rejects.toBeInstanceOf(AxiosError)
      expect(calls).toBe(1)
    }
    const abort = new AbortController()
    let calls = 0
    await expect(http.get('/api/tasks', { signal: abort.signal, adapter: async config => {
      calls++; abort.abort(); throw new AxiosError('cancel', 'ERR_CANCELED', config)
    } })).rejects.toBeDefined()
    expect(calls).toBe(1)
  })
})
