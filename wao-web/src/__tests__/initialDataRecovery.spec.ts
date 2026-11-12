import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import * as api from '@/api/board'
import type { Area, KnowledgeBase } from '@/types'

let pinia: ReturnType<typeof createPinia>
const timeout = () => Object.assign(new Error('timeout of 10000ms exceeded'), { code: 'ECONNABORTED' })
const library = { id: 'k1', name: '合成知识库', folders: [] } as unknown as KnowledgeBase
beforeEach(() => {
  localStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore().account = { accountId: 'a', member: { id: 'member' } } as never
  vi.useFakeTimers()
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
})
afterEach(() => { disposePinia(pinia); vi.restoreAllMocks(); vi.useRealTimers() })

it('首次多个模块超时后自动补齐，仅重读失败模块', async () => {
  const areas = vi.spyOn(api, 'fetchAreas').mockResolvedValue([])
  const kbs = vi.spyOn(api, 'fetchKbs').mockRejectedValueOnce(timeout()).mockResolvedValue([library])
  vi.spyOn(api, 'fetchKbGroups').mockResolvedValue([])
  const files = vi.spyOn(api, 'fetchFiles').mockRejectedValueOnce(timeout()).mockResolvedValue([])
  const data = useDataStore()
  await data.hydrate(['areas', 'knowledge', 'files'])
  expect(data.recoveringDomains).toEqual(new Set(['knowledge', 'files']))
  expect(data.loadStates.knowledge).toBe('error')
  await vi.advanceTimersByTimeAsync(1000)
  expect(data.kbs).toEqual([library])
  expect(data.loadStates.knowledge).toBe('loaded')
  expect(data.loadStates.files).toBe('loaded')
  expect(data.recoveringDomains.size).toBe(0)
  expect(areas).toHaveBeenCalledOnce()
  expect(kbs).toHaveBeenCalledTimes(2)
  expect(files).toHaveBeenCalledTimes(2)
})

it('重复失败保留原内容，自动恢复后更新为服务器内容', async () => {
  const loaded = { id: 'new', name: '新领域' } as Area
  const read = vi.spyOn(api, 'fetchAreas').mockRejectedValueOnce(timeout()).mockRejectedValueOnce(timeout()).mockResolvedValue([loaded])
  const data = useDataStore()
  data.areas = [{ id: 'old', name: '已读取内容' } as Area]
  await data.hydrate(['areas'])
  await vi.advanceTimersByTimeAsync(1000)
  expect(data.areas[0]?.id).toBe('old')
  await vi.advanceTimersByTimeAsync(3000)
  expect(data.areas).toEqual([loaded])
  expect(read).toHaveBeenCalledTimes(3)
})

it('权限错误保留错误提示，不自动循环请求', async () => {
  const read = vi.spyOn(api, 'fetchAreas').mockRejectedValue(Object.assign(new Error('无权访问'), { status: 403 }))
  const data = useDataStore()
  await data.hydrate(['areas'])
  await vi.advanceTimersByTimeAsync(60000)
  expect(read).toHaveBeenCalledOnce()
  expect(data.recoveringDomains.size).toBe(0)
  expect(data.loadErrors.areas).toBe('无权访问')
})

it('同成员账号切换后忽略旧读取的迟到响应和自动重试', async () => {
  let reject!: (cause: Error) => void
  const read = vi.spyOn(api, 'fetchAreas').mockReturnValue(new Promise((_resolve, fail) => { reject = fail }))
  const data = useDataStore()
  const loading = data.hydrate(['areas'])
  useSessionStore().account = { accountId: 'b', member: { id: 'member' } } as never
  reject(timeout())
  await loading
  await vi.advanceTimersByTimeAsync(60000)
  expect(read).toHaveBeenCalledOnce()
  expect(data.recoveringDomains.size).toBe(0)
  expect(data.loadErrors.areas).toBeUndefined()
})

it('离线时不发送恢复请求，online后自动继续', async () => {
  const read = vi.spyOn(api, 'fetchAreas').mockRejectedValueOnce(timeout()).mockResolvedValue([])
  const data = useDataStore()
  await data.hydrate(['areas'])
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
  await vi.advanceTimersByTimeAsync(60000)
  expect(read).toHaveBeenCalledOnce()
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
  window.dispatchEvent(new Event('online'))
  await vi.advanceTimersByTimeAsync(0)
  expect(read).toHaveBeenCalledTimes(2)
})
