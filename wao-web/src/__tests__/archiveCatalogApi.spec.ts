import { beforeEach, expect, it, vi } from 'vitest'
import { fetchArchivedDocs, fetchDoc } from '@/api/board'
import { http } from '@/api/http'

vi.mock('@/api/http', () => ({ http: { get: vi.fn() } }))
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(http.get).mockResolvedValue({ data: { data: [] } })
})
it('归档目录请求选择轻量响应，单篇详情仍保留完整读取入口', async () => {
  await fetchArchivedDocs('资料 & k1')
  expect(http.get).toHaveBeenLastCalledWith('/api/docs/archived?kbId=%E8%B5%84%E6%96%99%20%26%20k1&catalog=true')
  await fetchDoc('d1')
  expect(http.get).toHaveBeenLastCalledWith('/api/docs/d1')
})
