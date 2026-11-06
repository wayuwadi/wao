import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUploadStore } from '@/stores/uploads'
import FilesView from '@/views/FilesView.vue'
import UploadQueueStatus from '@/components/files/UploadQueueStatus.vue'
import type { FileItem } from '@/types'
vi.mock('@/api/system', () => ({
  fetchFileSettings: vi.fn(async () => ({ fileMaxMb: 12, imageMaxMb: 4 })),
}))
const uploaded = { id: 'f1', name: 'one.txt' } as FileItem
beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  sessionStorage.clear()
})
it('离开文件页后继续处理队列，回来仍可只重试失败条目', async () => {
  const data = useDataStore()
  vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
  vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
  vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
  let finish!: (file: FileItem) => void
  const upload = vi
    .spyOn(data, 'uploadFile')
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    .mockResolvedValueOnce(null)
    .mockResolvedValueOnce(uploaded)
  const options = { global: { stubs: { FileSettingsModal: true } } }
  const page = mount(FilesView, options)
  await flushPromises()
  const files = [new File(['1'], 'one.txt'), new File(['2'], 'two.txt')]
  await page.get('.unified-drop').trigger('drop', { dataTransfer: { files } })
  expect(useUploadStore().uploading).toBe(2)
  page.unmount()
  finish(uploaded)
  await flushPromises()
  expect(upload).toHaveBeenCalledTimes(2)
  const returned = mount(FilesView, options)
  await flushPromises()
  expect(returned.get('[aria-label="本次上传结果"]').text()).toContain('已上传 1 项')
  await returned.get('[aria-label="重试上传 two.txt"]').trigger('click')
  await flushPromises()
  expect(upload.mock.calls.map((args) => args[0])).toEqual([files[0], files[1], files[1]])
  returned.unmount()
})
it('同一成员切换到另一账号后旧队列不会推进，迟到响应不污染新队列', async () => {
  const session = useSessionStore()
  session.account = { accountId: 'a', member: { id: 'm' } } as never
  const data = useDataStore(),
    queue = useUploadStore()
  let finish!: (file: FileItem) => void
  const upload = vi
    .spyOn(data, 'uploadFile')
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    .mockResolvedValue(uploaded)
  void queue.enqueue([new File(['1'], 'old.txt'), new File(['2'], 'waiting.txt')], 'files')
  session.account = { accountId: 'b', member: { id: 'm' } } as never
  await queue.enqueue([new File(['3'], 'new.txt')], 'files')
  finish(uploaded)
  await flushPromises()
  expect(upload.mock.calls.map((args) => args[0].name)).toEqual(['old.txt', 'new.txt'])
  expect(queue.uploadAttempts.map((item) => item.file.name)).toEqual(['new.txt'])
  expect(queue.successfulUploads).toBe(1)
})
it('刷新保护只在有未完成或失败上传时出现', async () => {
  vi.spyOn(useDataStore(), 'uploadFile').mockResolvedValue(null)
  const queue = useUploadStore()
  const panel = mount(UploadQueueStatus, { global: { stubs: { RouterLink: true } } })
  await queue.enqueue([new File(['1'], 'failed.txt')], 'files')
  const blocked = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(blocked)
  expect(blocked.defaultPrevented).toBe(true)
  queue.clear()
  const allowed = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(allowed)
  expect(allowed.defaultPrevented).toBe(false)
  panel.unmount()
})

it('返回文件页保留图床页码，读取中不把已存页码错误压回第一页', async () => {
  const session = useSessionStore()
  session.account = { accountId: 'a', member: { id: 'm' } } as never
  const data = useDataStore()
  vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
  vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
  vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
  data.loadStates.images = 'loaded'
  const images = Array.from({ length: 31 }, (_, i) => ({
    id: `i${i}`,
    name: `图片${i}`,
    url: '/image.png',
    time: '',
    size: '1 KB',
    from: '',
    c1: '',
    c2: '',
  }))
  data.imgs = images
  const options = { global: { stubs: { FileSettingsModal: true } } }
  const first = mount(FilesView, options)
  await flushPromises()
  await first.findAll('.seg-btn')[1]!.trigger('click')
  await first.get('[aria-label="图床分页"] [aria-label="第 3 页"]').trigger('click')
  first.unmount()
  data.imgs = []
  data.loadStates.images = 'loading'
  const second = mount(FilesView, options)
  await flushPromises()
  expect(second.get('.seg-btn.on').text()).toBe('图床')
  data.imgs = images
  data.loadStates.images = 'loaded'
  await flushPromises()
  expect(second.get('[aria-label="第 3 页"]').attributes('aria-current')).toBe('page')
  expect(second.findAll('.img-card')).toHaveLength(1)
  session.account = { accountId: 'b', member: { id: 'm' } } as never
  await flushPromises()
  expect(second.get('.seg-btn.on').text()).toBe('文件')
  second.unmount()
})
