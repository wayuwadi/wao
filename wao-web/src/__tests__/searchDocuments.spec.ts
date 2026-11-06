import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSearchDocumentsStore } from '@/stores/searchDocuments'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { fetchKbs, fetchSearchDocuments } from '@/api/board'
import type { Doc, KnowledgeBase } from '@/types'
vi.mock('@/api/board', () => ({ fetchSearchDocuments: vi.fn(), fetchKbs: vi.fn() }))
const doc = { id: 'd1', title: '草稿之外的正文', blocks: [{ p: '保存的内容' }] } as Doc
beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.mocked(fetchSearchDocuments).mockResolvedValue([doc])
  vi.mocked(fetchKbs).mockResolvedValue([
    { id: 'k1', name: '资料', folders: [{ docs: ['d1'] }] } as KnowledgeBase,
  ])
})
afterEach(() => vi.useRealTimers())
it('并发读取合并，缓存到期后台更新；失败保留旧结果并标记错误', async () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-06T12:00:00Z'))
  const cache = useSearchDocumentsStore()
  await Promise.all([cache.load(), cache.load()])
  await cache.load()
  expect(fetchSearchDocuments).toHaveBeenCalledTimes(1)
  expect(cache.documents[0]?.text).toContain('保存的内容')
  vi.advanceTimersByTime(31_000)
  vi.mocked(fetchSearchDocuments).mockRejectedValueOnce(new Error('offline'))
  await cache.load()
  expect(cache.state).toBe('error')
  expect(cache.documents).toHaveLength(1)
  await cache.load(true)
  expect(cache.state).toBe('loaded')
})
it('账号变更立即清空缓存，忽略旧账号迟到响应', async () => {
  const session = useSessionStore()
  session.account = { accountId: 'a', member: { id: 'm' } } as never
  const cache = useSearchDocumentsStore()
  let finish!: (docs: Doc[]) => void
  vi.mocked(fetchSearchDocuments).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const pending = cache.load()
  session.account = { accountId: 'b', member: { id: 'm' } } as never
  vi.mocked(fetchSearchDocuments).mockResolvedValue([])
  await cache.load()
  finish([doc])
  await pending
  expect(cache.documents).toEqual([])
})
it('相同版本载入编辑器不使缓存失效，确认的新修订会触发重新读取', async () => {
  const data = useDataStore(),
    cache = useSearchDocumentsStore()
  vi.mocked(fetchSearchDocuments).mockResolvedValue([{ ...doc, rev: 1 }])
  await cache.load()
  data.kbs = [{ id: 'k1', name: '资料', folders: [{ docs: ['d1'] }] } as KnowledgeBase]
  data.loadStates.knowledge = 'loaded'
  data.docs.d1 = { ...doc, rev: 1 }
  await cache.load()
  expect(fetchSearchDocuments).toHaveBeenCalledTimes(1)
  data.docs.d1 = { ...doc, rev: 2 }
  await cache.load()
  expect(fetchSearchDocuments).toHaveBeenCalledTimes(2)
})
