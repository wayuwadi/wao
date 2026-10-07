import { expect, it, vi } from 'vitest'
import { fetchDigitalAssetBlob, uploadDigitalAsset } from '@/api/digitalMembers'
import { http } from '@/api/http'
vi.mock('@/api/http', () => ({ http: { get: vi.fn(), post: vi.fn() } }))
it('预览使用私有轻量接口，大照片上传读取有独立传输时间', async () => {
  const blob = new Blob(['preview'], { type: 'image/jpeg' })
  vi.mocked(http.get).mockResolvedValue({ data: blob })
  expect(await fetchDigitalAssetBlob('private id')).toBe(blob)
  expect(http.get).toHaveBeenCalledWith('/api/digital-assets/private%20id/raw?preview=true', {
    responseType: 'blob',
    timeout: 60000,
  })
  vi.mocked(http.post).mockResolvedValue({ data: { code: 0, data: { id: 'asset' } } })
  const file = new File(['photo'], 'photo.jpg', { type: 'image/jpeg' })
  await uploadDigitalAsset('subject', file, 'reference')
  const [, form, config] = vi.mocked(http.post).mock.calls[0]!
  expect((form as FormData).get('file')).toBe(file)
  expect(config?.timeout).toBe(60000)
})
