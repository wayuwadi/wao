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

it('只在服务端确认同一草图和修订后补回内存中的画布数据', async () => {
  const known = { id: 'drawing', sceneJson: '{}', revision: 2, title: '旧名称', createdAt: '', updatedAt: '' }
  const { sceneJson: _scene, ...metadata } = { ...known, title: '新名称' }
  vi.mocked(http.get).mockResolvedValueOnce({ data: { data: metadata } })
  const signal = new AbortController().signal
  expect(await fetchDrawing(known.id, signal, known)).toEqual({ ...metadata, sceneJson: known.sceneJson })
  expect(http.get).toHaveBeenLastCalledWith('/api/drawings/drawing', { timeout: 120_000, signal, params: { knownRevision: 2 } })

  vi.mocked(http.get).mockResolvedValueOnce({ data: { data: { ...metadata, revision: 3, sceneJson: '{"elements":[]}' } } })
  expect((await fetchDrawing(known.id, signal, known)).sceneJson).toBe('{"elements":[]}')
  vi.mocked(http.get).mockResolvedValueOnce({ data: { data: { ...metadata, revision: 3 } } })
  await expect(fetchDrawing(known.id, signal, known)).rejects.toThrow('草图文件缺少画布内容')
})
