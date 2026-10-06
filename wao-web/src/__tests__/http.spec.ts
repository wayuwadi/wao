import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { http, streamTextEvents } from '@/api/http'
import { reportBrowserRequest } from '@/utils/requestMetricReporter'
import { edgeApiRouting } from '@/api/edgeRouting'
import {
  isSessionPaused,
  resumeSessionRequests,
  SESSION_EXPIRED_EVENT,
} from '@/utils/sessionRecovery'

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
  it('SSE的401也启动恢复，暂停后不发出第二次正文请求', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({ message: '登录过期' }) }))
    try {
      await expect(streamTextEvents('/api/ai/chat', {}).next()).rejects.toThrow('登录过期')
      expect(isSessionPaused()).toBe(true)
      await expect(streamTextEvents('/api/ai/chat', {}).next()).rejects.toThrow('登录已过期')
      expect(fetch).toHaveBeenCalledOnce()
    } finally { vi.unstubAllGlobals() }
  })
  it('业务401只提示一次恢复，阻止后续写入且不重放；认证接口仍可用', async () => {
    const expired = vi.fn()
    window.addEventListener(SESSION_EXPIRED_EVENT, expired)
    const failedAdapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      throw gatewayError(config, 401)
    })
    try {
      await expect(http.post('/api/tasks', {}, { adapter: failedAdapter })).rejects.toThrow()
      expect(isSessionPaused()).toBe(true)
      expect(expired).toHaveBeenCalledOnce()
      await expect(http.post('/api/tasks', {}, { adapter: failedAdapter })).rejects.toThrow(
        '登录已过期',
      )
      expect(failedAdapter).toHaveBeenCalledOnce()
      const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({
        config,
        data: { code: 0 },
        headers: {},
        status: 200,
        statusText: 'OK',
      }))
      await http.get('/api/auth/session', { adapter })
      expect(adapter).toHaveBeenCalledOnce()
      resumeSessionRequests()
      expect(failedAdapter).toHaveBeenCalledOnce()
    } finally {
      window.removeEventListener(SESSION_EXPIRED_EVENT, expired)
    }
  })
  it('邮箱密码错误的401不触发全局过期恢复', async () => {
    await expect(
      http.post(
        '/api/auth/email/login',
        {},
        {
          adapter: async (config) => {
            throw gatewayError(config, 401)
          },
        },
      ),
    ).rejects.toThrow()
    expect(isSessionPaused()).toBe(false)
  })
  it('飞书未配置的业务 502 立即返回原因，不反复请求或切换节点', async () => {
    const failed = vi.spyOn(edgeApiRouting, 'failed')
    let attempts = 0
    await expect(
      http.get('/api/auth/feishu/authorize', {
        adapter: async (config) => {
          attempts++
          const error = gatewayError(config, 502)
          error.response!.data = {
            code: 50201,
            message: '飞书登录尚未配置，请联系管理员',
            data: null,
            timestamp: 1,
          }
          throw error
        },
      }),
    ).rejects.toThrow('飞书登录尚未配置，请联系管理员')
    expect(attempts).toBe(1)
    expect(failed).not.toHaveBeenCalled()
  })

  it('节点返回明确业务 503 时不回云端重放，也不使节点会话失效', async () => {
    vi.spyOn(edgeApiRouting, 'route').mockReturnValue({
      origin: 'https://linux2.wayuwa.cn:9443',
      csrf: 'test-csrf',
      validUntil: Infinity,
    })
    const failed = vi.spyOn(edgeApiRouting, 'failed').mockImplementation(() => {})
    let attempts = 0
    await expect(
      http.get('/api/projects', {
        adapter: async (config) => {
          attempts++
          const error = gatewayError(config, 503)
          error.response!.data = { code: 50301, message: '业务暂不可用', data: null, timestamp: 1 }
          throw error
        },
      }),
    ).rejects.toThrow('业务暂不可用')
    expect(attempts).toBe(1)
    expect(failed).not.toHaveBeenCalled()
  })

  it('文件上传先建立节点会话，携带CSRF且响应丢失不重发正文', async () => {
    const prepared = vi.spyOn(edgeApiRouting, 'prepareUpload').mockResolvedValue({
      origin: 'https://linux2.wayuwa.cn:9443',
      csrf: 'upload-csrf',
      validUntil: Infinity,
    })
    vi.spyOn(edgeApiRouting, 'failed').mockImplementation(() => {})
    let attempts = 0
    const body = new FormData()
    body.append('file', new Blob(['sample']), 'sample.txt')
    await expect(
      http.post('/api/files', body, {
        adapter: async (config) => {
          attempts++
          expect(config.baseURL).toBe('https://linux2.wayuwa.cn:9443')
          expect(config.headers.get('X-WAO-CSRF')).toBe('upload-csrf')
          expect(config.data).toBe(body)
          throw new AxiosError('lost response', 'ERR_NETWORK', config)
        },
      }),
    ).rejects.toThrow('保存结果尚未确认')
    expect(prepared).toHaveBeenCalledWith('/api/files')
    expect(attempts).toBe(1)
  })

  afterEach(() => {
    resumeSessionRequests()
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
    vi.spyOn(edgeApiRouting, 'route').mockReturnValue({
      origin: 'https://linux2.wayuwa.cn:9443',
      csrf: 'test-csrf',
      validUntil: Infinity,
    })
    vi.spyOn(edgeApiRouting, 'failed').mockImplementation(() => {})
    const bases: string[] = []
    await expect(
      http.get('/api/projects', {
        adapter: async (config) => {
          bases.push(config.baseURL!)
          throw gatewayError(config, 503)
        },
      }),
    ).rejects.toBeInstanceOf(AxiosError)
    expect(bases).toEqual(['https://linux2.wayuwa.cn:9443', '/'])
  })

  it('节点写请求携带CSRF，断网后保留结果未知状态而不重放', async () => {
    vi.spyOn(edgeApiRouting, 'route').mockReturnValue({
      origin: 'https://linux2.wayuwa.cn:9443',
      csrf: 'test-csrf',
      validUntil: Infinity,
    })
    vi.spyOn(edgeApiRouting, 'failed').mockImplementation(() => {})
    let calls = 0
    await expect(
      http.post(
        '/api/tasks',
        { title: '仅单测' },
        {
          adapter: async (config) => {
            calls++
            expect(config.headers.get('X-WAO-CSRF')).toBe('test-csrf')
            throw new AxiosError('network lost', 'ERR_NETWORK', config)
          },
        },
      ),
    ).rejects.toThrow('保存结果尚未确认')
    expect(calls).toBe(1)
  })

  it('节点401和用户取消都不跨入口重试', async () => {
    vi.spyOn(edgeApiRouting, 'route').mockReturnValue({
      origin: 'https://linux2.wayuwa.cn:9443',
      csrf: 'test-csrf',
      validUntil: Infinity,
    })
    vi.spyOn(edgeApiRouting, 'failed').mockImplementation(() => {})
    for (const status of [401, 403]) {
      resumeSessionRequests()
      let calls = 0
      await expect(
        http.get('/api/tasks', {
          adapter: async (config) => {
            calls++
            throw gatewayError(config, status)
          },
        }),
      ).rejects.toBeInstanceOf(AxiosError)
      expect(calls).toBe(1)
    }
    const abort = new AbortController()
    resumeSessionRequests()
    let calls = 0
    await expect(
      http.get('/api/tasks', {
        signal: abort.signal,
        adapter: async (config) => {
          calls++
          abort.abort()
          throw new AxiosError('cancel', 'ERR_CANCELED', config)
        },
      }),
    ).rejects.toBeDefined()
    expect(calls).toBe(1)
  })
})
