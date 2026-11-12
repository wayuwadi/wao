const RETRY_DELAYS = [1_000, 3_000, 10_000, 30_000]

export function isTransientReadFailure(cause: unknown): boolean {
  const error = cause as { status?: number; response?: { status?: number }; code?: string; message?: string } | null
  const status = error?.response?.status ?? error?.status
  if (status) return [408, 429, 500, 502, 503, 504].includes(status)
  if (error?.code) return ['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT'].includes(error.code)
  return /timeout|network|offline|failed to fetch|网络|超时|连接中断|离线/i.test(error?.message ?? '')
}

/** Recover failed reads in sequence, with a bounded polling rate and session isolation. */
export function createReadRecovery<Key>(options: {
  pending: Set<Key>
  identity: () => string
  available: () => boolean
  failed: (key: Key) => boolean
  retry: (key: Key) => Promise<unknown>
}) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let running = false
  let generation = 0
  let round = 0
  let disposed = false

  function schedule(delay = RETRY_DELAYS[round]) {
    if (disposed || timer !== undefined || running || !options.pending.size || !options.identity()) return
    timer = setTimeout(() => { timer = undefined; void recover() }, delay)
  }

  async function recover() {
    if (!options.available()) return
    const capturedGeneration = generation
    const identity = options.identity()
    running = true
    try {
      for (const key of [...options.pending]) {
        if (capturedGeneration !== generation || identity !== options.identity() || !options.available()) break
        options.pending.delete(key)
        if (options.failed(key)) {
          try { await options.retry(key) } catch (cause) {
            if (capturedGeneration === generation && identity === options.identity()) queue(key, cause)
          }
        }
      }
    } finally {
      if (capturedGeneration === generation) {
        running = false
        round = options.pending.size ? Math.min(round + 1, RETRY_DELAYS.length - 1) : 0
        schedule()
      }
    }
  }

  function queue(key: Key, cause: unknown) {
    options.pending.delete(key)
    if (disposed || !options.identity() || !isTransientReadFailure(cause)) return
    options.pending.add(key)
    schedule()
  }

  function reset() {
    generation++
    clearTimeout(timer)
    timer = undefined
    running = false
    round = 0
    options.pending.clear()
  }

  function succeeded(key: Key) {
    options.pending.delete(key)
    if (!options.pending.size) {
      clearTimeout(timer)
      timer = undefined
      round = 0
    }
  }

  function resume() {
    if (!options.available()) return
    clearTimeout(timer)
    timer = undefined
    round = 0
    schedule(0)
  }

  return { queue, succeeded, reset, resume, dispose: () => { reset(); disposed = true } }
}
