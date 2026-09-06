export interface VisibilityPoller {
  start: (immediate?: boolean) => void
  trigger: () => Promise<void>
  reschedule: () => void
  stop: () => void
}

/**
 * 只在页面可见时串行轮询；下一轮总在上一轮结束后计时，避免慢请求跨轮堆叠。
 */
export function createVisibilityPoller(
  task: () => Promise<unknown>,
  delay: () => number,
  target: Document = document,
): VisibilityPoller {
  let timer: ReturnType<typeof setTimeout> | null = null
  let inFlight: Promise<void> | null = null
  let started = false

  function clearTimer() {
    if (timer === null) return
    clearTimeout(timer)
    timer = null
  }

  function schedule() {
    clearTimer()
    if (!started || target.hidden) return
    timer = setTimeout(() => {
      timer = null
      void trigger()
    }, delay())
  }

  async function trigger() {
    clearTimer()
    if (!started || target.hidden) return
    if (inFlight) return inFlight
    inFlight = Promise.resolve(task()).then(() => undefined)
    try {
      await inFlight
    } finally {
      inFlight = null
      schedule()
    }
  }

  function onVisibilityChange() {
    if (target.hidden) clearTimer()
    else void trigger()
  }

  function start(immediate = true) {
    if (!started) {
      started = true
      target.addEventListener('visibilitychange', onVisibilityChange)
    }
    if (immediate) void trigger()
    else schedule()
  }

  function stop() {
    if (!started) return
    started = false
    clearTimer()
    target.removeEventListener('visibilitychange', onVisibilityChange)
  }

  return { start, trigger, reschedule: schedule, stop }
}
