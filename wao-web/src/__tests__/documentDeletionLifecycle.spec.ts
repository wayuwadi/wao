import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import * as drafts from '@/utils/documentDrafts'
import type { Doc, KnowledgeBase } from '@/types'

const api = vi.hoisted(() => ({ apiDeleteKb: vi.fn(), apiDeleteFolder: vi.fn(), apiDeleteDoc: vi.fn(),
  apiPatchDoc: vi.fn(), apiRestoreTrash: vi.fn(), fetchKbs: vi.fn(), fetchDoc: vi.fn(), fetchDocs: vi.fn(), fetchTrash: vi.fn() }))
vi.mock('@/api/board', async original => ({ ...await original<typeof import('@/api/board')>(), ...api }))
const doc = (): Doc => ({ id: 'd1', title: '文档', author: 'a', created: '', updated: '',
  kbId: 'k1', folderId: 'f1', rev: 1, blocks: [{ p: '服务器正文' }], links: [], backlinks: [] })
const kb = (): KnowledgeBase => ({ id: 'k1', name: '资料', icon: '', color: '',
  project: null, desc: '', group: '', perm: '仅自己', folders: [{ id: 'f1', name: '目录', docs: ['d1'] }] })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
function seed() {
  const data = useDataStore()
  data.docs.d1 = doc()
  data.kbs = [kb()]
  data.setDocEdit('d1', { text: '本机输入' })
  data.apiLive = true
  return data
}

describe('确认删除与文档写入生命周期', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    Object.values(api).forEach(mock => mock.mockReset())
    api.apiDeleteKb.mockResolvedValue(undefined)
    api.apiDeleteFolder.mockResolvedValue(undefined)
    api.apiDeleteDoc.mockResolvedValue(undefined)
    api.fetchTrash.mockResolvedValue([])
    api.apiRestoreTrash.mockResolvedValue(undefined)
    api.fetchKbs.mockResolvedValue([kb()])
    api.fetchDocs.mockResolvedValue([doc()])
    vi.spyOn(drafts, 'writeDocumentDraft').mockResolvedValue()
    vi.spyOn(drafts, 'removeDocumentDraft').mockResolvedValue()
  })
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })

  it.each(['kb', 'folder', 'doc'] as const)('%s 删除失败透传异常，目录和输入保持可重试', async type => {
    const data = seed()
    const failure = new Error('暂时无法删除')
    const target = { kb: api.apiDeleteKb, folder: api.apiDeleteFolder, doc: api.apiDeleteDoc }[type]
    target.mockRejectedValueOnce(failure)
    const remove = () => type === 'kb' ? data.deleteKb('k1') : type === 'folder' ? data.deleteFolder('k1', 'f1') : data.deleteDoc('d1')
    await expect(remove()).rejects.toThrow('暂时无法删除')
    expect(data.kbs[0]?.folders[0]?.docs).toEqual(['d1'])
    expect(data.docOf('d1').text).toBe('本机输入')
    await remove()
    expect(data.docs.d1).toBeUndefined()
    expect(data.docEdits.d1).toBeUndefined()
    expect(data.docPending('d1')).toBe(false)
  })

  it('整库删除等服务器确认后才更新界面，切换成员不能删除新成员的同名对象', async () => {
    const data = seed()
    const pending = deferred<void>()
    api.apiDeleteKb.mockReturnValueOnce(pending.promise)
    const deleting = data.deleteKb('k1')
    expect(data.kbs).toHaveLength(1)
    expect(data.docs.d1).toBeDefined()
    useSessionStore().account = { accountId: 'b', member: { id: 'b' } } as never
    data.kbs = [kb()]
    data.docs.d1 = { ...doc(), title: '新成员的资料' }
    pending.resolve()
    await deleting
    expect(data.kbs).toHaveLength(1)
    expect(data.docs.d1?.title).toBe('新成员的资料')
    expect(drafts.removeDocumentDraft).not.toHaveBeenCalled()
  })

  it('整库删除取消保存计时器，迟到保存与详情/目录响应不能复活文档', async () => {
    vi.useFakeTimers()
    const data = seed()
    const saving = deferred<Doc>(), detail = deferred<Doc>(), catalog = deferred<Doc[]>()
    api.apiPatchDoc.mockReturnValueOnce(saving.promise)
    api.fetchDoc.mockReturnValueOnce(detail.promise)
    api.fetchDocs.mockReturnValueOnce(catalog.promise)
    data.setDocEdit('d1', { text: '删除前输入' })
    const save = data.persistDocNow('d1'), read = data.ensureDocLoaded('d1'), load = data.refreshDocs()
    await data.deleteKb('k1')
    saving.resolve({ ...doc(), rev: 2 })
    detail.resolve(doc())
    catalog.resolve([doc()])
    expect(await save).toBe(false)
    expect(await read).toBe(false)
    await load
    await vi.runAllTimersAsync()
    expect(data.docs.d1).toBeUndefined()
    expect(data.docEdits.d1).toBeUndefined()
    expect(data.saveState('doc:d1')).toMatchObject({ saving: false, savedAt: null, failed: false })
    expect(api.apiPatchDoc).toHaveBeenCalledTimes(1)
    expect(data.applyRemoteDoc(doc())).toBe(false)
    data.setDocEdit('d1', { text: '编辑器卸载时迟到的输入事件' })
    expect(data.docEdits.d1).toBeUndefined()
  })

  it('清理持久草稿等待删除前已开始的本机写入，避免写入完成后残留草稿', async () => {
    const write = deferred<void>()
    vi.mocked(drafts.writeDocumentDraft).mockReturnValueOnce(write.promise)
    const data = seed()
    await data.deleteDoc('d1')
    expect(drafts.removeDocumentDraft).not.toHaveBeenCalled()
    write.resolve()
    await flushPromises()
    expect(drafts.removeDocumentDraft).toHaveBeenCalledWith('anonymous', 'd1')
    expect(data.docDraftErrors.d1).toBeUndefined()
  })

  it('从回收站恢复的文档可以继续编辑，旧删除清理不能清掉恢复后的新草稿', async () => {
    const write = deferred<void>()
    vi.mocked(drafts.writeDocumentDraft).mockReturnValueOnce(write.promise)
    const data = seed()
    await data.deleteDoc('d1')
    await data.restoreTrash('doc', 'd1')
    expect(data.docs.d1).toBeDefined()
    data.apiLive = false
    data.setDocEdit('d1', { text: '恢复后的新输入' })
    write.resolve()
    await flushPromises()
    expect(drafts.removeDocumentDraft).not.toHaveBeenCalled()
    expect(data.docOf('d1').text).toBe('恢复后的新输入')
    api.apiPatchDoc.mockResolvedValueOnce({ ...doc(), rev: 2, blocks: [{ p: '恢复后的新输入' }] })
    expect(await data.persistDocNow('d1')).toBe(true)
    expect(data.docs.d1?.rev).toBe(2)
  })

  it('保存期间撤销共享，迟到成功回执不回填正文并保留本机输入', async () => {
    const data = seed()
    const pending = deferred<Doc>()
    api.apiPatchDoc.mockReturnValueOnce(pending.promise)
    const saving = data.persistDocNow('d1')
    api.fetchKbs.mockResolvedValueOnce([])
    api.fetchDocs.mockResolvedValueOnce([])
    await data.refreshKnowledge()
    pending.resolve({ ...doc(), rev: 2 })
    expect(await saving).toBe(false)
    expect(data.docs.d1).toBeUndefined()
    expect(data.docEdits.d1?.text).toBe('本机输入')
    expect(data.saveState('doc:d1').errorMessage).toContain('不可访问')
  })

  it('恢复后新输入取消旧草稿清理，即使旧写入完成前已切换成员', async () => {
    useSessionStore().account = { accountId: 'a', member: { id: 'a' } } as never
    const data = seed()
    const oldWrite = deferred<void>()
    vi.mocked(drafts.writeDocumentDraft).mockReturnValueOnce(oldWrite.promise)
    data.setDocEdit('d1', { text: '删除前尚未写完的输入' })
    await data.deleteDoc('d1')
    await data.restoreTrash('doc', 'd1')
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('QuotaExceededError') })
    data.setDocEdit('d1', { text: '恢复后唯一保存在 IndexedDB 的新输入' })
    storageWrite.mockRestore()
    useSessionStore().account = { accountId: 'b', member: { id: 'b' } } as never
    oldWrite.resolve()
    await flushPromises()
    expect(drafts.writeDocumentDraft).toHaveBeenCalledWith(expect.objectContaining({
      memberId: 'a', id: 'd1', content: expect.objectContaining({ markdown: '恢复后唯一保存在 IndexedDB 的新输入' }),
    }))
    expect(drafts.removeDocumentDraft).not.toHaveBeenCalledWith('a', 'd1')
  })
})
