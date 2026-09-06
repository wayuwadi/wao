import { afterEach, describe, expect, it, vi } from 'vitest'
import { createVisibilityPoller } from '@/composables/visibilityPolling'

function visibilityDocument(initiallyHidden = false) {
  const target = new EventTarget() as Document
  Object.defineProperty(target, 'hidden', { value: initiallyHidden, writable: true })
  return target
}

describe('可见页面串行轮询', () => {
  afterEach(() => vi.useRealTimers())

  it('上一请求结束后才安排下一轮，慢请求不会跨轮重叠', async () => {
    vi.useFakeTimers()
    const target = visibilityDocument()
    let resolveRequest!: () => void
    const task = vi.fn(() => new Promise<void>((resolve) => { resolveRequest = resolve }))
    const poller = createVisibilityPoller(task, () => 5_000, target)

    poller.start()
    expect(task).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(15_000)
    expect(task).toHaveBeenCalledOnce()

    resolveRequest()
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(4_999)
    expect(task).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(1)
    expect(task).toHaveBeenCalledTimes(2)
    poller.stop()
  })

  it('页面隐藏时暂停，重新可见后立即补一次并继续轮询', async () => {
    vi.useFakeTimers()
    const target = visibilityDocument()
    const task = vi.fn().mockResolvedValue(undefined)
    const poller = createVisibilityPoller(task, () => 5_000, target)
    poller.start()
    await Promise.resolve()

    Object.defineProperty(target, 'hidden', { value: true })
    target.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(20_000)
    expect(task).toHaveBeenCalledOnce()

    Object.defineProperty(target, 'hidden', { value: false })
    target.dispatchEvent(new Event('visibilitychange'))
    await Promise.resolve()
    expect(task).toHaveBeenCalledTimes(2)
    poller.stop()
  })
})
