import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useUploadStore } from '@/stores/uploads'
import type { FileItem } from '@/types'
import type { FileUploadProgress } from '@/api/board'
import { useSessionStore } from '@/stores/session'

const uploaded = { id: 'uploaded', name: 'one.txt' } as FileItem
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
})

it('成功记录只保留轻量元信息，失败重试仍使用原文件内容', async () => {
  const upload = vi.spyOn(useDataStore(), 'uploadFile').mockResolvedValueOnce(uploaded).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce(uploaded)
  const queue = useUploadStore()
  const first = new File(['one'], 'one.txt', { type: 'text/plain', lastModified: 100 })
  const second = new File(['two'], 'two.txt')
  await queue.enqueue([first, second], 'files')
  expect(queue.uploadAttempts[0]?.file).toEqual({ name: 'one.txt', size: 3, type: 'text/plain', lastModified: 100 })
  expect(queue.uploadAttempts[0]?.file).not.toBeInstanceOf(Blob)
  expect(queue.failedUploads[0]?.reason).toBe('网络中断')
  await queue.retryFailedUploads()
  expect(upload.mock.calls.map(args => args[0])).toEqual([first, second, second])
  expect(queue.successfulUploads).toBe(2)
  expect(queue.failedUploads).toHaveLength(0)
})

it('移除等待项目不会中断当前请求，也不会上传被移除的文件', async () => {
  let finish!: (item: FileItem) => void
  const upload = vi.spyOn(useDataStore(), 'uploadFile').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const queue = useUploadStore()
  const pumping = queue.enqueue([new File(['one'], 'one.txt'), new File(['two'], 'two.txt')], 'files')
  queue.removeUpload(queue.uploadAttempts[0]!)
  expect(queue.uploadAttempts).toHaveLength(2)
  queue.removeUpload(queue.uploadAttempts[1]!)
  finish(uploaded)
  await pumping
  expect(upload).toHaveBeenCalledTimes(1)
  expect(queue.uploadAttempts.map(item => item.file.name)).toEqual(['one.txt'])
})

it('同一 File 的重复入队回调不会重复上传，完成后允许明确重新选择', async () => {
  let finish!: (item: FileItem) => void
  const upload = vi.spyOn(useDataStore(), 'uploadFile').mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValue(uploaded)
  const queue = useUploadStore()
  const file = new File(['one'], 'one.txt')
  const pumping = queue.enqueue([file, file], 'files')
  await queue.enqueue([file], 'files')
  expect(queue.uploadAttempts).toHaveLength(1)
  finish(uploaded)
  await pumping
  expect(upload).toHaveBeenCalledTimes(1)
  await queue.enqueue([file], 'files')
  expect(upload).toHaveBeenCalledTimes(2)
})

it('未读取系统限制时仍执行硬上限，无法重试的项目不阻断后续上传', async () => {
  const data = useDataStore()
  const upload = vi.spyOn(data, 'uploadFile').mockResolvedValue(uploaded)
  const queue = useUploadStore()
  const oversized = new File(['x'], 'large.zip')
  Object.defineProperty(oversized, 'size', { value: data.MAX_FILE_BYTES + 1 })
  await queue.enqueue([oversized, new File(['small'], 'small.txt')], 'files')
  expect(upload).toHaveBeenCalledTimes(1)
  expect(queue.failedUploads[0]?.retryable).toBe(false)
  expect(queue.failedUploads[0]?.reason).toContain('200MB')
  await queue.retryFailedUploads()
  expect(upload).toHaveBeenCalledTimes(1)
  queue.removeUpload(queue.failedUploads[0]!)
  expect(queue.failedUploads).toHaveLength(0)
})

it('批量重试只排队可重试项目，重复点击不会并行重传', async () => {
  let finish!: (item: FileItem) => void
  const upload = vi.spyOn(useDataStore(), 'uploadFile')
    .mockRejectedValueOnce(new Error('网络中断'))
    .mockRejectedValueOnce(new Error('请求超时'))
    .mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    .mockResolvedValue(uploaded)
  const queue = useUploadStore()
  await queue.enqueue([new File(['a'], 'a.txt'), new File(['b'], 'b.txt')], 'files')
  const retry = queue.retryFailedUploads()
  await queue.retryFailedUploads()
  expect(queue.uploading).toBe(2)
  finish(uploaded)
  await retry
  expect(upload).toHaveBeenCalledTimes(4)
  expect(queue.successfulUploads).toBe(2)
})

it.each([
  { kind: 'files' as const, file: new File([], 'empty.txt'), reason: '文件为空' },
  { kind: 'img' as const, file: new File([], 'empty.png', { type: 'image/png' }), reason: '图片为空' },
  { kind: 'img' as const, file: new File(['text'], 'text.txt', { type: 'text/plain' }), reason: '请选择图片文件' },
])('本地无效文件不可重试并释放原文件：$reason', async ({ kind, file, reason }) => {
  const data = useDataStore()
  const uploadFile = vi.spyOn(data, 'uploadFile').mockResolvedValue(uploaded)
  const uploadImage = vi.spyOn(data, 'uploadImage').mockResolvedValue({ name: 'valid.png', url: '/valid.png' })
  const queue = useUploadStore()
  await queue.enqueue([file], kind)
  expect(queue.failedUploads[0]).toMatchObject({ reason: expect.stringContaining(reason), retryable: false })
  await queue.retryUpload(queue.failedUploads[0]!)
  await queue.retryFailedUploads()
  expect(uploadFile).not.toHaveBeenCalled()
  expect(uploadImage).not.toHaveBeenCalled()
  // The released File no longer participates in pending-file deduplication.
  await queue.enqueue([file], kind)
  expect(queue.uploadAttempts).toHaveLength(2)
  await queue.enqueue([new File(['valid'], 'valid.png', { type: 'image/png' })], kind)
  expect(queue.successfulUploads).toBe(1)
})

it('传输达到 100% 后仍等待服务器确认，旧账号进度不能继续更新', async () => {
  let finish!: (item: FileItem) => void
  let progress!: (value: FileUploadProgress) => void
  vi.spyOn(useDataStore(), 'uploadFile').mockImplementationOnce((_, _project, _private, options) => {
    progress = options!.onProgress!
    return new Promise(resolve => { finish = resolve })
  })
  const queue = useUploadStore()
  const pumping = queue.enqueue([new File(['a'], 'file.txt')], 'files')
  progress({ loaded: 5 })
  expect(queue.uploadAttempts[0]?.progress).toBeUndefined()
  progress({ loaded: 5, total: 10 })
  expect(queue.uploadAttempts[0]?.progress).toBe(50)
  progress({ loaded: 10, total: 10 })
  expect(queue.uploadAttempts[0]?.progress).toBe(100)
  expect(queue.uploading).toBe(1)
  expect(queue.successfulUploads).toBe(0)
  finish(uploaded)
  await pumping
  expect(queue.successfulUploads).toBe(1)
  progress({ loaded: 1, total: 10 })
  expect(queue.uploadAttempts[0]?.progress).toBe(100)

  vi.spyOn(useDataStore(), 'uploadFile').mockImplementationOnce((_, _project, _private, options) => {
    progress = options!.onProgress!
    return new Promise(resolve => { finish = resolve })
  })
  const next = queue.enqueue([new File(['b'], 'old.txt')], 'files')
  const old = queue.uploadAttempts[1]!
  progress({ loaded: 5, total: 10 })
  useSessionStore().account = { accountId: 'another', member: { id: 'new-member' } } as never
  progress({ loaded: 10, total: 10 })
  expect(old.progress).toBe(50)
  expect(queue.uploadAttempts).toHaveLength(0)
  finish(uploaded)
  await next
})
