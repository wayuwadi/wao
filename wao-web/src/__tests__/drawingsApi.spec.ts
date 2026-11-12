import { expect, it, vi } from 'vitest'
import { fetchDrawing, saveDrawing } from '@/api/drawings'
import { http } from '@/api/http'

vi.mock('@/api/http', () => ({ http: { get: vi.fn(), put: vi.fn() } }))

it('草图场景读写为内嵌图片留足等待时间，仍只发送一次写入', async () => {
  const file = { id: 'drawing', sceneJson: '{}', revision: 2 }
  vi.mocked(http.get).mockResolvedValue({ data: { data: file } })
  vi.mocked(http.put).mockResolvedValue({ data: { data: file } })
  expect(await fetchDrawing('drawing')).toEqual(file)
  expect(http.get).toHaveBeenCalledWith('/api/drawings/drawing', { timeout: 120_000 })
  expect(await saveDrawing('drawing', '{}', 1)).toEqual(file)
  expect(http.put).toHaveBeenCalledExactlyOnceWith('/api/drawings/drawing', { sceneJson: '{}', revision: 1 }, { timeout: 120_000 })
})
