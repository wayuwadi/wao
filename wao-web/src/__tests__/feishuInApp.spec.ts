import { afterEach, expect, it, vi } from 'vitest'
import { feishuInAppLogin } from '@/utils/feishuInApp'
import { exchangeFeishuInAppCode } from '@/api/feishu'
vi.mock('@/api/feishu', () => ({ getFeishuInAppConfig: vi.fn().mockResolvedValue({ enabled: true, appId: 'example' }), exchangeFeishuInAppCode: vi.fn().mockResolvedValue({ claimRequired: false }) }))
afterEach(() => { delete window.h5sdk; delete window.tt; vi.restoreAllMocks(); vi.clearAllMocks(); vi.useRealTimers() })
it('端内ready超时后不再发送迟到请求，重新进入仍可完成免登', async () => {
  vi.useFakeTimers(); vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Feishu')
  let ready = () => {}
  window.h5sdk = { ready: fn => { ready = fn }, error: () => {} }
  window.tt = { requestAuthCode: vi.fn(({ success }) => success({ code: 'test-code' })) }
  const first = feishuInAppLogin(); const failed = expect(first).rejects.toThrow('免登超时')
  await vi.advanceTimersByTimeAsync(8000); await failed
  ready(); expect(window.tt.requestAuthCode).not.toHaveBeenCalled()
  window.h5sdk.ready = fn => fn()
  await feishuInAppLogin()
  expect(exchangeFeishuInAppCode).toHaveBeenCalledOnce()
})
