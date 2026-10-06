import { afterEach, expect, it, vi } from 'vitest'
import { reauthenticateWithFeishu } from '@/utils/reauthPopup'
import { getFeishuAuthorizationUrl } from '@/api/feishu'
vi.mock('@/api/feishu', () => ({
  getFeishuAuthorizationUrl: vi.fn().mockResolvedValue('https://example.invalid/?state=state-1'),
}))
afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})
it('只接受同源、指定窗口及同一state完成信号，并清理窗口监听', async () => {
  const popup = {
    closed: false,
    document: { title: '', body: { textContent: '' } },
    sessionStorage: { setItem: vi.fn() },
    location: { replace: vi.fn() },
    close: vi.fn(),
  }
  vi.spyOn(window, 'open').mockReturnValue(popup as unknown as Window)
  let completed = false
  const request = reauthenticateWithFeishu().then(() => {
    completed = true
  })
  await vi.waitFor(() => expect(popup.location.replace).toHaveBeenCalledOnce())
  const send = (origin: string, source: unknown, state: string) =>
    window.dispatchEvent(
      new MessageEvent('message', {
        origin,
        source: source as Window,
        data: { type: 'wao:reauth-complete', state, ok: true },
      }),
    )
  send('https://evil.invalid', popup, 'state-1')
  send(window.location.origin, window, 'state-1')
  send(window.location.origin, popup, 'wrong-state')
  await Promise.resolve()
  expect(completed).toBe(false)
  send(window.location.origin, popup, 'state-1')
  await request
  expect(completed).toBe(true)
  expect(popup.close).toHaveBeenCalledOnce()
})
it('弹窗被拦截时不申请授权，取消时停止等待', async () => {
  const calls = vi.mocked(getFeishuAuthorizationUrl).mock.calls.length
  vi.spyOn(window, 'open').mockReturnValue(null)
  await expect(reauthenticateWithFeishu()).rejects.toThrow('拦截')
  expect(getFeishuAuthorizationUrl).toHaveBeenCalledTimes(calls)
  const popup = {
    closed: false,
    document: { title: '', body: { textContent: '' } },
    sessionStorage: { setItem: vi.fn() },
    location: { replace: vi.fn() },
    close: vi.fn(),
  }
  vi.mocked(window.open).mockReturnValue(popup as unknown as Window)
  const abort = new AbortController()
  const request = reauthenticateWithFeishu(abort.signal)
  const rejected = expect(request).rejects.toThrow('窗口已关闭')
  await vi.waitFor(() => expect(popup.location.replace).toHaveBeenCalledOnce())
  abort.abort()
  await rejected
  expect(popup.close).toHaveBeenCalled()
})
