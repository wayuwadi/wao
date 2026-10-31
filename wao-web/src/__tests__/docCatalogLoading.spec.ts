import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import type { Doc } from '@/types'

const api = vi.hoisted(() => ({ fetchDoc: vi.fn(), fetchDocs: vi.fn(), apiPatchDoc: vi.fn() }))
vi.mock('@/api/board', async (original) => ({
  ...await original<typeof import('@/api/board')>(), ...api,
}))
const summary = (): Doc => ({ id: 'd1', title: '文档', author: 'u1', created: '', updated: '',
  kbId: 'k1', folderId: 'f1', rev: 3, bodyLoaded: false, blocks: [], links: [], backlinks: [] })
const full = (): Doc => ({ ...summary(), bodyLoaded: true,
  content: { version: 1, document: null, markdown: '完整服务器正文' } })

describe('文档目录和正文分开加载', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })
  it('正文加载前拒绝编辑和写回，失败保留目录并允许重试', async () => {
    const data = useDataStore()
    data.docs.d1 = summary()
    api.fetchDoc.mockRejectedValueOnce(new Error('网络超时')).mockResolvedValueOnce(full())
    data.setDocEdit('d1', { text: '' })
    expect(await data.persistDocNow('d1')).toBe(false)
    expect(api.apiPatchDoc).not.toHaveBeenCalled()
    expect(await data.ensureDocLoaded('d1')).toBe(false)
    expect(data.docLoadErrors.d1).toBe('网络超时')
    expect(data.docs.d1?.bodyLoaded).toBe(false)
    expect(await data.ensureDocLoaded('d1')).toBe(true)
    expect(data.docOf('d1').text).toBe('完整服务器正文')
    expect(data.docLoadErrors.d1).toBeUndefined()
  })
  it('多个入口复用同一详情请求', async () => {
    const data = useDataStore()
    data.docs.d1 = summary()
    let finish!: (doc: Doc) => void
    api.fetchDoc.mockReturnValue(new Promise<Doc>(resolve => { finish = resolve }))
    const first = data.ensureDocLoaded('d1')
    const second = data.ensureDocLoaded('d1')
    expect(api.fetchDoc).toHaveBeenCalledTimes(1)
    finish(full())
    expect(await first).toBe(true)
    expect(await second).toBe(true)
  })
  it('旧目录响应不能移除请求期间刚保存的小记，下一次刷新仍能反映删除', async () => {
    const data = useDataStore()
    let finish!: (rows: Doc[]) => void
    api.fetchDocs.mockReturnValueOnce(new Promise<Doc[]>(resolve => { finish = resolve }))
    const loading = data.refreshDocs()
    data.applyRemoteDoc(full())
    finish([])
    await loading
    expect(data.docOf('d1').text).toBe('完整服务器正文')
    api.fetchDocs.mockResolvedValueOnce([])
    await data.refreshDocs()
    expect(data.docs.d1).toBeUndefined()
  })
  it('旧目录版本不能覆盖刚保存的正文或降级已知版本', async () => {
    const data = useDataStore()
    data.docs.d1 = full()
    let finish!: (rows: Doc[]) => void
    api.fetchDocs.mockReturnValueOnce(new Promise<Doc[]>(resolve => { finish = resolve }))
    const loading = data.refreshDocs()
    data.applyRemoteDoc({ ...full(), rev: 4, content: { version: 1, document: null, markdown: '刚保存的新段落' } })
    finish([full()])
    await loading
    expect(data.docOf('d1').text).toBe('刚保存的新段落')
    expect(data.docs.d1?.rev).toBe(4)
  })
  it('目录重载保留同版本正文，也不覆盖未保存草稿', async () => {
    const data = useDataStore()
    data.docs.d1 = full()
    api.fetchDocs.mockResolvedValue([summary()])
    await data.refreshDocs()
    expect(data.docOf('d1').text).toBe('完整服务器正文')
    data.setDocEdit('d1', { text: '未保存的本机草稿' })
    api.fetchDocs.mockResolvedValue([{ ...summary(), rev: 4 }])
    await data.refreshDocs()
    expect(data.docOf('d1').text).toBe('未保存的本机草稿')
    expect(data.docs.d1?.rev).toBe(3)
  })
  it('切换成员后旧目录响应和清理回调都不能覆盖新成员请求', async () => {
    const session = useSessionStore()
    const login = (id: string) => session.accept({ member: { id, name: id, initial: id, color: '', role: '成员' } } as never)
    login('a')
    const data = useDataStore()
    let finishA!: (rows: Doc[]) => void
    let finishB!: (rows: Doc[]) => void
    api.fetchDocs.mockReturnValueOnce(new Promise<Doc[]>(resolve => { finishA = resolve }))
      .mockReturnValueOnce(new Promise<Doc[]>(resolve => { finishB = resolve }))
    const first = data.refreshDocs()
    login('b')
    const second = data.ensureDocsLoaded()
    finishA([{ ...full(), id: 'private-a' }])
    expect(await first).toBe(false)
    expect(data.docs['private-a']).toBeUndefined()
    expect(data.loadStates.docs).toBe('loading')
    const third = data.ensureDocsLoaded()
    expect(api.fetchDocs).toHaveBeenCalledTimes(2)
    finishB([{ ...full(), id: 'private-b' }])
    expect(await second).toBe(true)
    expect(await third).toBe(true)
    expect(Object.keys(data.docs)).toEqual(['private-b'])
  })
})
