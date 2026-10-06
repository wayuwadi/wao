import { invalidateDesktopCache } from './desktopCache'
import axios, { type InternalAxiosRequestConfig } from 'axios'
import { reportBrowserRequest } from '@/utils/requestMetricReporter'
import { edgeApiRouting, isSafeEdgeMethod } from './edgeRouting'
import {
  assertSessionRequest,
  isProtectedRequest,
  requireSessionRecovery,
} from '@/utils/sessionRecovery'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

export const http = axios.create({
  baseURL: '/',
  timeout: 10_000,
  withCredentials: true,
  headers: {
    Accept: 'application/json',
  },
})

interface RetriableRequestConfig extends InternalAxiosRequestConfig {
  waoGatewayRetry?: number
  waoMetricStartedAt?: number
  waoMetricStartedAtEpoch?: number
  waoEdgeOrigin?: string
  waoEdgeFallback?: boolean
}

const RETRIABLE_GATEWAY_STATUSES = new Set([502, 503, 504])
const GATEWAY_RETRY_DELAYS = [500, 1_500, 3_500]

function retryGatewayRequest(
  config: RetriableRequestConfig | undefined,
  status: number | undefined,
) {
  if (!config || !status || !RETRIABLE_GATEWAY_STATUSES.has(status)) return null
  if (config.waoEdgeFallback) return null
  if (!['get', 'head'].includes(config.method?.toLowerCase() ?? '')) return null
  const attempt = config.waoGatewayRetry ?? 0
  if (attempt >= GATEWAY_RETRY_DELAYS.length) return null
  config.waoGatewayRetry = attempt + 1
  return new Promise((resolve) => window.setTimeout(resolve, GATEWAY_RETRY_DELAYS[attempt])).then(
    () => http.request(config),
  )
}

/** 解析 WAO 后端的 token/done/error SSE 事件，供长时 AI 接口复用。 */
export async function* streamTextEvents(url: string, body: unknown): AsyncGenerator<string> {
  assertSessionRequest(url)
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    credentials: 'include',
  })
  if (!res.ok || !res.body) {
    if (res.status === 401 && isProtectedRequest(url)) requireSessionRecovery()
    let message = `请求失败：${res.status}`
    try {
      const error = (await res.json()) as Partial<ApiResponse<unknown>>
      if (error.message) message = error.message
    } catch {
      /* 非 JSON 错误保留状态码。 */
    }
    throw new Error(message)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    buffer = buffer.replace(/\r\n/g, '\n')
    const parts = buffer.split('\n\n')
    buffer = parts.pop() || ''
    for (const part of parts) {
      const lines = part.split('\n')
      let eventName = ''
      const payloadLines: string[] = []
      for (const line of lines) {
        if (line.startsWith('event:')) eventName = line.slice(6).trimStart()
        else if (line.startsWith('data:')) payloadLines.push(line.slice(5).trimStart())
      }
      const payload = payloadLines.join('\n')
      if (eventName === 'token' && payload) yield payload
      else if (eventName === 'done') return
      else if (eventName === 'error') throw new Error(payload)
    }
  }
  throw new Error('回复连接提前结束，请重试；原问题已保留')
}

http.interceptors.request.use(async (config: RetriableRequestConfig) => {
  const url = config.url ?? ''
  assertSessionRequest(url)
  if (/^\/api\/auth\//.test(url)) edgeApiRouting.reset()
  if (
    !config.waoEdgeFallback &&
    !config.waoEdgeOrigin &&
    (!config.baseURL || config.baseURL === '/')
  ) {
    const method = config.method?.toLowerCase() ?? 'get'
    const route =
      method === 'post' && /^\/api\/(?:files|images)$/.test(url)
        ? await edgeApiRouting.prepareUpload(url)
        : edgeApiRouting.route(url, method)
    if (route) {
      config.baseURL = route.origin
      config.waoEdgeOrigin = route.origin
      if (!isSafeEdgeMethod(config.method ?? 'get')) config.headers.set('X-WAO-CSRF', route.csrf)
    }
  }
  assertSessionRequest(url)
  config.waoMetricStartedAt = performance.now()
  config.waoMetricStartedAtEpoch = Date.now()
  return config
})

function report(config: RetriableRequestConfig | undefined, statusCode: number | null) {
  if (config?.waoMetricStartedAt === undefined || config.waoMetricStartedAtEpoch === undefined)
    return
  reportBrowserRequest(
    config.url,
    config.method,
    statusCode,
    performance.now() - config.waoMetricStartedAt,
    config.waoMetricStartedAtEpoch,
    config.waoGatewayRetry ?? 0,
  )
}

http.interceptors.response.use(
  (response) => {
    report(response.config as RetriableRequestConfig, response.status)
    const envelope = response.data as ApiResponse<unknown>
    if (typeof envelope?.code === 'number' && envelope.code !== 0) {
      return Promise.reject(new Error(envelope.message || '请求失败'))
    }
    if (
      !['get', 'head'].includes(response.config.method?.toLowerCase() ?? 'get') &&
      /^\/api\/(tasks|projects|areas|members|org|kbs|kb-groups)(?:\/|$)/.test(
        response.config.url ?? '',
      )
    )
      invalidateDesktopCache()
    return response
  },
  (error: unknown) => {
    if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
      report(error.config as RetriableRequestConfig | undefined, error.response?.status ?? null)
      // WAO 已返回明确业务结论时不按网关断连重试（例如飞书未配置也使用 HTTP 502）。
      const envelope = error.response?.data
      if (
        RETRIABLE_GATEWAY_STATUSES.has(error.response?.status ?? 0) &&
        typeof envelope?.code === 'number' &&
        envelope.code !== 0 &&
        typeof envelope.message === 'string' &&
        envelope.message
      ) {
        return Promise.reject(
          Object.assign(new Error(envelope.message), { status: error.response?.status }),
        )
      }
      const config = error.config as RetriableRequestConfig | undefined
      if (error.response?.status === 401 && isProtectedRequest(config?.url ?? ''))
        requireSessionRecovery()
      if (config?.waoEdgeOrigin) {
        // Cancellation and authorization failures are never transport failover.
        if (axios.isCancel(error) || config.signal?.aborted) return Promise.reject(error)
        const transportFailure =
          !error.response || RETRIABLE_GATEWAY_STATUSES.has(error.response.status)
        if (transportFailure || error.response?.status === 401 || error.response?.status === 403) {
          edgeApiRouting.failed(config.waoEdgeOrigin)
        }
        if (
          transportFailure &&
          isSafeEdgeMethod(config.method ?? 'get') &&
          !config.waoEdgeFallback
        ) {
          config.waoEdgeFallback = true
          config.waoEdgeOrigin = undefined
          config.baseURL = '/'
          config.headers.delete('X-WAO-CSRF')
          return http.request(config)
        }
        if (transportFailure && !isSafeEdgeMethod(config.method ?? 'get')) {
          return Promise.reject(
            Object.assign(
              new Error('连接中断，保存结果尚未确认。请先检查最新内容，再决定是否重试。'),
              { cause: error },
            ),
          )
        }
        // Do not fall through to the legacy cloud retry loop for node requests.
        const message = error.response?.data?.message
        return Promise.reject(
          message ? Object.assign(new Error(message), { status: error.response?.status }) : error,
        )
      }
      const retry = retryGatewayRequest(
        error.config as RetriableRequestConfig | undefined,
        error.response?.status,
      )
      if (retry) return retry
      const message = error.response?.data?.message
      if (message)
        return Promise.reject(Object.assign(new Error(message), { status: error.response?.status }))
    }
    return Promise.reject(error)
  },
)
