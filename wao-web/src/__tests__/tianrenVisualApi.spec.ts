import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/api/http'
import { fetchTianrenImage, fetchTianrenVisual, generateTianrenImage, saveTianrenAiSettings } from '@/api/tianren'
vi.mock('@/api/http', () => ({ http: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }))
describe('天人私有配图 API', () => {
  beforeEach(() => { vi.clearAllMocks() })
  it('图片使用统一鉴权客户端与 Blob，路径片段编码', async () => {
    const blob = new Blob(['private'], { type: 'image/png' }); vi.mocked(http.get).mockResolvedValue({ data: blob })
    expect(await fetchTianrenImage('day/a', 'attempt/b')).toBe(blob)
    expect(http.get).toHaveBeenCalledWith('/api/tianren/readings/day%2Fa/visual/images/attempt%2Fb/raw', { responseType: 'blob', headers: { Accept: 'image/png' } })
  })
  it('状态仅查询，生成绑定解读与幂等请求，设置原子携带基准版本', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: { data: { state: 'NOT_GENERATED' } } })
    vi.mocked(http.post).mockResolvedValue({ data: { data: { state: 'QUEUED' } } })
    vi.mocked(http.put).mockResolvedValue({ data: { data: { version: 3 } } })
    expect(await fetchTianrenVisual('day/a')).toEqual({ state: 'NOT_GENERATED' })
    expect(http.post).not.toHaveBeenCalled()
    await generateTianrenImage('day/a', 'run1', 'request1')
    expect(http.post).toHaveBeenCalledWith('/api/tianren/readings/day%2Fa/visual/image', { interpretationRunId: 'run1', requestId: 'request1' })
    const settings = { version: 2, autoImage: true, interpretationPrompt: '解读', imagePrompt: '配图' }
    expect(await saveTianrenAiSettings(settings)).toEqual({ version: 3 })
    expect(http.put).toHaveBeenCalledWith('/api/tianren/settings', settings)
  })
})
