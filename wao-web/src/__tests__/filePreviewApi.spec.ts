import { expect, it, vi } from 'vitest'
import { apiUploadFile, apiUploadImage, fetchFilePreview } from '@/api/board'
import { http } from '@/api/http'
vi.mock('@/api/http', () => ({ http: { get: vi.fn(), post: vi.fn() } }))

it('每次预览读取当前文件内容，并将取消信号传到实际请求', async () => {
  const current = { id: 'a/b', name: 'new.md', contentType: 'text/markdown', content: '更新后的内容' }
  vi.mocked(http.get).mockResolvedValue({ data: { data: current } })
  const controller = new AbortController()
  expect(await fetchFilePreview('a/b', { signal: controller.signal })).toEqual(current)
  expect(http.get).toHaveBeenCalledWith('/api/files/a%2Fb/preview', { signal: controller.signal })
  await fetchFilePreview('a/b')
  expect(http.get).toHaveBeenCalledTimes(2)
})

it('文件与图片的传输回调到达 HTTP 层并保留项目和图片来源', async () => {
  vi.mocked(http.post).mockResolvedValue({ data: { data: { id: 'asset' } } })
  const onProgress = vi.fn()
  const controller = new AbortController()
  const file = new File(['file'], 'file.png', { type: 'image/png' })
  await apiUploadFile(file, 'project', false, { onProgress, signal: controller.signal })
  await apiUploadImage(file, '项目 · 测试', 'project', { onProgress, signal: controller.signal })
  for (const [, body, config] of vi.mocked(http.post).mock.calls) {
    expect((body as FormData).get('file')).toBe(file)
    expect((body as FormData).get('projectId')).toBe('project')
    expect(config?.signal).toBe(controller.signal)
    config!.onUploadProgress!({ loaded: 5, total: 10 } as never)
  }
  expect((vi.mocked(http.post).mock.calls[1]![1] as FormData).get('source')).toBe('项目 · 测试')
  expect(onProgress).toHaveBeenCalledTimes(2)
})
