import { afterEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/api/http'
import { waitForPolish } from '@/api/polishJobs'
vi.mock('@/api/http', () => ({ http: { post: vi.fn(), get: vi.fn() } }))
const response = (status: string, extra = {}) => ({ data: { data: { id: 'pj1', status, message: status, ...extra } } })
afterEach(() => { vi.useRealTimers(); vi.resetAllMocks() })

describe('持久润色请求', () => {
  it('短请求轮询到结果，不重复调用生成接口', async () => {
    vi.useFakeTimers()
    vi.mocked(http.post).mockResolvedValue(response('QUEUED'))
    vi.mocked(http.get).mockResolvedValueOnce(response('RUNNING')).mockResolvedValueOnce(response('SUCCEEDED', { polished: '结果' }))
    const progress = vi.fn()
    const pending = waitForPolish('原文', 'DOCUMENT', { requestId: 'fixed', onProgress: progress })
    await vi.runAllTimersAsync()
    expect((await pending).polished).toBe('结果')
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(progress.mock.calls.map(([job]) => job.status)).toEqual(['QUEUED', 'RUNNING', 'SUCCEEDED'])
  })
  it('网络错误重试沿用相同标识，失败终态保留明确原因', async () => {
    vi.mocked(http.post).mockRejectedValueOnce(new Error('网络断开'))
      .mockResolvedValueOnce(response('FAILED', { message: 'Codex 登录已失效' }))
    const options = { requestId: 'fixed' }
    await expect(waitForPolish('原文', 'DOCUMENT', options)).rejects.toThrow('网络断开')
    await expect(waitForPolish('原文', 'DOCUMENT', options)).rejects.toThrow('Codex 登录已失效')
    expect(vi.mocked(http.post).mock.calls[0]?.[1]).toEqual(vi.mocked(http.post).mock.calls[1]?.[1])
    expect(http.get).not.toHaveBeenCalled()
  })
  it('离开页面后不再读结果或回调，不取消后台任务', async () => {
    const progress = vi.fn()
    vi.mocked(http.post).mockResolvedValue(response('RUNNING'))
    await expect(waitForPolish('原文', 'DOCUMENT', { isActive: () => false, onProgress: progress }))
      .rejects.toThrow('后台保留')
    expect(progress).not.toHaveBeenCalled()
    expect(http.get).not.toHaveBeenCalled()
  })
})
