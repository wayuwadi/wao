import { afterEach, describe, expect, it, vi } from 'vitest'
import { streamTextEvents } from '@/api/http'

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

function response(text?: string) {
  const cancel = vi.fn()
  const body = new ReadableStream<Uint8Array>({
    start(controller) { if (text) controller.enqueue(new TextEncoder().encode(text)) },
    cancel,
  })
  const fetch = vi.fn().mockResolvedValue(new Response(body))
  vi.stubGlobal('fetch', fetch)
  return { body, cancel, fetch }
}

describe('SSE lifecycle', () => {
  it('done releases the reader and cancels an otherwise open connection', async () => {
    const source = response('event:done\ndata:ok\n\n')
    expect(await streamTextEvents('/api/assistant/chat/stream', {}).next()).toEqual({ done: true, value: undefined })
    expect(source.cancel).toHaveBeenCalledOnce()
    expect(source.body.locked).toBe(false)
    expect(source.fetch.mock.calls[0]![1].signal.aborted).toBe(true)
  })

  it('leaving a partially consumed reply releases its connection', async () => {
    const source = response('event:token\ndata:hello\n\n')
    for await (const chunk of streamTextEvents('/api/assistant/chat/stream', {})) {
      expect(chunk).toBe('hello'); break
    }
    expect(source.cancel).toHaveBeenCalledOnce()
    expect(source.body.locked).toBe(false)
  })

  it('caller cancellation interrupts a stalled read', async () => {
    const source = response()
    const controller = new AbortController()
    const pending = streamTextEvents('/api/assistant/chat/stream', {}, { signal: controller.signal }).next()
    const check = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await Promise.resolve()
    controller.abort()
    await check
    expect(source.cancel).toHaveBeenCalled()
    expect(source.body.locked).toBe(false)
  })

  it('idle timeout stops a stalled read and keeps a recovery message', async () => {
    vi.useFakeTimers()
    const source = response()
    const pending = streamTextEvents('/api/assistant/chat/stream', {}, { idleTimeoutMs: 100 }).next()
    const check = expect(pending).rejects.toThrow('历史记录')
    await vi.advanceTimersByTimeAsync(101)
    await check
    expect(source.body.locked).toBe(false)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('pagehide aborts the fetch and already aborted calls never post', async () => {
    const source = response()
    const pending = streamTextEvents('/api/assistant/chat/stream', {}).next()
    const check = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await Promise.resolve()
    window.dispatchEvent(new Event('pagehide'))
    await check
    const controller = new AbortController(); controller.abort()
    await expect(streamTextEvents('/api/assistant/chat/stream', {}, { signal: controller.signal }).next())
      .rejects.toMatchObject({ name: 'AbortError' })
    expect(source.fetch).toHaveBeenCalledOnce()
  })
})
