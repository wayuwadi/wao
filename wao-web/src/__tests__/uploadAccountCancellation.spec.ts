import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import type { InternalAxiosRequestConfig } from 'axios'
import { http } from '@/api/http'
import { edgeApiRouting } from '@/api/edgeRouting'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUploadStore } from '@/stores/uploads'

vi.mock('@/utils/requestMetricReporter', () => ({ reportBrowserRequest: vi.fn() }))
const originalAdapter = http.defaults.adapter
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
})
afterEach(() => { http.defaults.adapter = originalAdapter; vi.restoreAllMocks() })

it.each(['files', 'img'] as const)('账号切换中止等待节点准备的 %s 正文，旧项目排队项不会发给新账号', async kind => {
  const session = useSessionStore()
  session.account = { accountId: 'old-account', member: { id: 'member' } } as never
  const data = useDataStore()
  const queue = useUploadStore()
  let finishPreparation!: (value: Awaited<ReturnType<typeof edgeApiRouting.prepareUpload>>) => void
  const prepare = vi.spyOn(edgeApiRouting, 'prepareUpload')
    .mockImplementationOnce(() => new Promise(resolve => { finishPreparation = resolve }))
    .mockResolvedValue(null)
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({
    config, status: 200, statusText: 'OK', headers: {},
    data: { code: 0, data: { id: 'new-id', name: 'new.png', url: '/new.png', link: '/new', size: '1 B', time: '刚刚', by: 'member', from: '项目', c1: '', c2: '', projectId: 'new-project' } },
  }))
  http.defaults.adapter = adapter
  const old = new File(['old'], 'old.png', { type: 'image/png' })
  const waiting = new File(['waiting'], 'waiting.png', { type: 'image/png' })
  const oldRequest = queue.enqueue([old, waiting], kind, undefined, { projectId: 'old-project' })
  await flushPromises()
  expect(prepare).toHaveBeenCalledTimes(1)
  expect(adapter).not.toHaveBeenCalled()

  session.account = { accountId: 'new-account', member: { id: 'member' } } as never
  const fresh = new File(['new'], 'new.png', { type: 'image/png' })
  await queue.enqueue([fresh], kind, undefined, { projectId: 'new-project' })
  finishPreparation(null)
  await oldRequest
  await flushPromises()

  expect(adapter).toHaveBeenCalledTimes(1)
  const body = adapter.mock.calls[0]![0].data as FormData
  expect(body.get('file')).toBe(fresh)
  expect(body.get('projectId')).toBe('new-project')
  expect(prepare).toHaveBeenCalledTimes(2)
  expect(queue.uploadAttempts.map(item => item.file.name)).toEqual(['new.png'])
  expect(queue.successfulUploads).toBe(1)
  expect(kind === 'files' ? data.files.map(item => item.id) : data.imgs.map(item => item.id)).toEqual(['new-id'])
})
