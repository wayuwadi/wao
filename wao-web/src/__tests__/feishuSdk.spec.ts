import { afterEach, expect, it, vi } from 'vitest'
import { loadFeishuSdk, feishuAuthorizationExpiresAt, FEISHU_SDK_TIMEOUT } from '@/utils/feishuSdk'
afterEach(() => {
  vi.useRealTimers()
  document.head
    .querySelectorAll('script[data-test],script[src*="example.invalid"]')
    .forEach((s) => s.remove())
})
it('SDK 卡住后及时失败、移除脚本并支持真正的新请求', async () => {
  vi.useFakeTimers()
  const url = 'https://example.invalid/sdk.js'
  let available = false
  const first = loadFeishuSdk(url, () => available)
  expect(loadFeishuSdk(url, () => available)).toBe(first)
  const failed = expect(first).rejects.toThrow('加载超时')
  await vi.advanceTimersByTimeAsync(FEISHU_SDK_TIMEOUT)
  await failed
  expect(document.querySelector(`script[src="${url}"]`)).toBeNull()
  const second = loadFeishuSdk(url, () => available)
  available = true
  document
    .querySelector<HTMLScriptElement>(`script[src="${url}"]`)!
    .dispatchEvent(new Event('load'))
  await expect(second).resolves.toBeUndefined()
})
it('SDK 错误后允许重试，加载完成但缺少接口也不会一直等待', async () => {
  const url = 'https://example.invalid/failed-sdk.js'
  const first = loadFeishuSdk(url, () => false)
  const failed = expect(first).rejects.toThrow('加载失败')
  document.querySelector(`script[src="${url}"]`)!.dispatchEvent(new Event('error'))
  await failed
  const second = loadFeishuSdk(url, () => false)
  const uninitialized = expect(second).rejects.toThrow('未能初始化')
  document.querySelector(`script[src="${url}"]`)!.dispatchEvent(new Event('load'))
  await uninitialized
})
it('二维码有效期按服务器签名载荷计算，缺失载荷保守使用五分钟', () => {
  vi.useFakeTimers()
  vi.setSystemTime(1_000_000)
  const state = btoa('1010:qr:test') + '.signature'
  expect(
    feishuAuthorizationExpiresAt(`https://example.invalid/?state=${encodeURIComponent(state)}`),
  ).toBe(1_010_000)
  expect(feishuAuthorizationExpiresAt('https://example.invalid/')).toBe(1_300_000)
})
