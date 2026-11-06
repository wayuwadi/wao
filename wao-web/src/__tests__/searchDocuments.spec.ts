import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSearchDocumentsStore } from '@/stores/searchDocuments'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { fetchKbs, fetchSearchDocuments } from '@/api/board'
import type { Doc, KnowledgeBase } from '@/types'
import { nextTick } from 'vue'
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
it('目录撤权或归档立即移除检索命中，网络失败也不恢复旧内容', async () => {
  const data = useDataStore(), cache = useSearchDocumentsStore()
  data.kbs = [{ id: 'k1', name: '资料', folders: [{ docs: ['d1'] }] } as KnowledgeBase]
  data.loadStates.knowledge = 'loaded'
  await cache.load()
  data.kbs[0]!.folders[0]!.docs.splice(0, 1)
  await nextTick()
  expect(cache.documents).toEqual([])
  vi.mocked(fetchSearchDocuments).mockRejectedValueOnce(new Error('offline'))
  await cache.load()
  expect(cache.state).toBe('error')
  expect(cache.documents).toEqual([])
})
it('索引端点比页面目录新时保留真实新文档，后续目录变化再作废缓存', async () => {
  const data = useDataStore(), cache = useSearchDocumentsStore()
  data.kbs = [{ id: 'k1', name: '资料', folders: [{ docs: [] }] } as unknown as KnowledgeBase]
  data.loadStates.knowledge = 'loaded'
  await cache.load()
  expect(cache.documents.map(entry => entry.doc.id)).toEqual(['d1'])
  await cache.load()
  expect(fetchSearchDocuments).toHaveBeenCalledTimes(1)
  data.kbs[0]!.folders[0]!.docs.push('d1', 'new')
  await cache.load()
  expect(fetchSearchDocuments).toHaveBeenCalledTimes(2)
})
it('批量目录更新合并元数据扫描，避免每条写入重新扫描整个目录', async () => {
  const data = useDataStore(), cache = useSearchDocumentsStore()
  await cache.load()
  let reads = 0
  for (let i = 0; i < 200; i++) data.docs[`d${i}`] = {
    ...doc, id: `d${i}`, get rev() { reads++; return 1 },
  }
  await nextTick()
  expect(reads).toBeLessThan(1000)
})
it('大索引在批次之间让出主线程，换账号会取消剩余解析并保留新账号状态', async () => {
  vi.useFakeTimers()
  const session = useSessionStore()
  session.account = { accountId: 'a', member: { id: 'a' } } as never
  const cache = useSearchDocumentsStore()
  const many = Array.from({ length: 80 }, (_, index) => ({ ...doc, id: `d${index}` }))
  vi.mocked(fetchSearchDocuments).mockResolvedValueOnce(many)
  vi.mocked(fetchKbs).mockResolvedValueOnce([
    { id: 'k1', name: '资料', folders: [{ docs: many.map(item => item.id) }] } as KnowledgeBase,
  ])
  const old = cache.load()
  await Promise.resolve(); await Promise.resolve()
  expect(cache.refreshing).toBe(true)
  expect(cache.documents).toEqual([])
  expect(vi.getTimerCount()).toBeGreaterThan(0)
  session.account = { accountId: 'b', member: { id: 'b' } } as never
  vi.mocked(fetchSearchDocuments).mockResolvedValueOnce([])
  await cache.load()
  await vi.runAllTimersAsync()
  await old
  expect(cache.documents).toEqual([])
  expect(cache.state).toBe('loaded')
  expect(cache.refreshing).toBe(false)
})
