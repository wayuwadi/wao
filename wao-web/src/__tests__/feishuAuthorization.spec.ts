import { afterEach, describe, expect, it, vi } from 'vitest'
import { startFeishuLogin } from '@/api/feishu'
import { http } from '@/api/http'

vi.mock('@/api/http', () => ({ http: { get: vi.fn() } }))
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('飞书离站授权', () => {
  it('请求结束后再次确认页面仍需要授权，取消时不离站', async () => {
    const assign = vi.fn()
    vi.stubGlobal('window', { location: { assign } })
    const authorizationUrl = 'https://auth.example/?state=one'
    vi.mocked(http.get).mockResolvedValue({ data: { data: { authorizationUrl } } })
    const beforeNavigate = vi.fn(() => false)
    await startFeishuLogin('authorization', beforeNavigate)
    expect(beforeNavigate).toHaveBeenCalledWith(authorizationUrl)
    expect(assign).not.toHaveBeenCalled()
    await startFeishuLogin('authorization', () => {})
    expect(assign).toHaveBeenCalledExactlyOnceWith(authorizationUrl)
  })
})
