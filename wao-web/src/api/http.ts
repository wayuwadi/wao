import axios, { type InternalAxiosRequestConfig } from 'axios'

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
}

const RETRIABLE_GATEWAY_STATUSES = new Set([502, 503, 504])
const GATEWAY_RETRY_DELAYS = [500, 1_500, 3_500]

function retryGatewayRequest(
  config: RetriableRequestConfig | undefined,
  status: number | undefined,
) {
  if (!config || !status || !RETRIABLE_GATEWAY_STATUSES.has(status)) return null
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
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    credentials: 'include',
  })
  if (!res.ok || !res.body) {
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
        if (line.startsWith('event: ')) eventName = line.slice(7)
        else if (line.startsWith('data:')) payloadLines.push(line.slice(5).trimStart())
      }
      const payload = payloadLines.join('\n')
      if (eventName === 'token' && payload) yield payload
      else if (eventName === 'done') return
      else if (eventName === 'error') throw new Error(payload)
    }
  }
}

http.interceptors.response.use(
  (response) => {
    const envelope = response.data as ApiResponse<unknown>
    if (typeof envelope?.code === 'number' && envelope.code !== 0) {
      return Promise.reject(new Error(envelope.message || '请求失败'))
    }
    return response
  },
  (error: unknown) => {
    if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
      const retry = retryGatewayRequest(
        error.config as RetriableRequestConfig | undefined,
        error.response?.status,
      )
      if (retry) return retry
      const message = error.response?.data?.message
      if (message) return Promise.reject(new Error(message))
    }
    return Promise.reject(error)
  },
)
