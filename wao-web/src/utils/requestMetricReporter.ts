export interface BrowserRequestMetric {
  method: string
  path: string
  statusCode: number | null
  durationMs: number
  networkType: string | null
  retryAttempt: number
  startedAt: string
}

const ENDPOINT = '/api/request-metrics/batch'
const MAX_QUEUE = 500
const BATCH_SIZE = 100
const FLUSH_DELAY_MS = 2_000
const RETRY_DELAY_MS = 5_000
const PUBLIC_PREFIXES = ['/api/auth/', '/api/share/', '/api/public/', '/api/ai/runner/']
let queue: BrowserRequestMetric[] = []
let timer: number | undefined
let flushing = false
let pagehideBound = false

function normalizedPath(raw: string | undefined): string | null {
  if (!raw || typeof window === 'undefined') return null
  try {
    const path = new URL(raw, window.location.origin).pathname
    if (!path.startsWith('/api/') || path.startsWith('/api/request-metrics')) return null
    if (path === '/api/hello' || PUBLIC_PREFIXES.some((prefix) => path.startsWith(prefix)))
      return null
    return path.slice(0, 255)
  } catch {
    return null
  }
}

function currentNetworkType(): string | null {
  if (typeof navigator === 'undefined') return null
  const connection = (navigator as Navigator & { connection?: { effectiveType?: string } })
    .connection
  return connection?.effectiveType?.slice(0, 16) || null
}

function schedule(delay = FLUSH_DELAY_MS) {
  if (typeof window === 'undefined' || timer !== undefined) return
  timer = window.setTimeout(() => {
    timer = undefined
    void flushRequestMetrics()
  }, delay)
}

function bindPagehide() {
  if (pagehideBound || typeof window === 'undefined') return
  pagehideBound = true
  window.addEventListener('pagehide', () => {
    if (!queue.length || typeof navigator.sendBeacon !== 'function') return
    const batch = queue.splice(0, BATCH_SIZE)
    const accepted = navigator.sendBeacon(
      ENDPOINT,
      new Blob([JSON.stringify({ metrics: batch })], { type: 'application/json' }),
    )
    if (!accepted) queue.unshift(...batch)
  })
}

/** 把一次 Axios 网络尝试加入有界队列；采集绝不阻塞原业务请求。 */
export function reportBrowserRequest(
  rawPath: string | undefined,
  method: string | undefined,
  statusCode: number | null,
  durationMs: number,
  startedAtEpochMs: number,
  retryAttempt = 0,
) {
  const path = normalizedPath(rawPath)
  if (!path) return
  queue.push({
    method: (method || 'GET').toUpperCase().slice(0, 8),
    path,
    statusCode,
    durationMs: Math.min(600_000, Math.max(0, Math.round(durationMs))),
    networkType: currentNetworkType(),
    retryAttempt: Math.min(3, Math.max(0, retryAttempt)),
    startedAt: new Date(startedAtEpochMs).toISOString(),
  })
  if (queue.length > MAX_QUEUE) queue = queue.slice(-MAX_QUEUE)
  bindPagehide()
  schedule()
}

/** 使用原生 fetch 上报，绕开 Axios 拦截器，防止监控请求递归采集。 */
export async function flushRequestMetrics() {
  if (flushing || !queue.length || typeof fetch === 'undefined') return
  flushing = true
  const batch = queue.splice(0, BATCH_SIZE)
  try {
    const response = await fetch(ENDPOINT, {
      method: 'POST',
      credentials: 'include',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ metrics: batch }),
    })
    // 4xx 表示当前会话无权接收或批次本身无效，不能留到另一个账号登录后再补报；
    // 只有网络故障和服务端短暂失败才保留重试。
    if (!response.ok && response.status >= 500) throw new Error(`指标上报失败：${response.status}`)
  } catch {
    queue.unshift(...batch)
    if (queue.length > MAX_QUEUE) queue = queue.slice(0, MAX_QUEUE)
    schedule(RETRY_DELAY_MS)
  } finally {
    flushing = false
    if (queue.length && timer === undefined) schedule()
  }
}

/** 仅供单元测试隔离模块级队列。 */
export function resetRequestMetricReporterForTest() {
  queue = []
  flushing = false
  if (timer !== undefined && typeof window !== 'undefined') window.clearTimeout(timer)
  timer = undefined
}
