import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import type { Doc, KnowledgeBase } from '@/types'

const api = vi.hoisted(() => ({ fetchArchivedDocs: vi.fn(), fetchKbs: vi.fn(), fetchKbGroups: vi.fn(), fetchDocs: vi.fn(), fetchDoc: vi.fn(),
  apiCreateKb: vi.fn(), apiCreateDoc: vi.fn(), apiCreateFolder: vi.fn(), apiPatchKb: vi.fn(), apiRenameFolder: vi.fn(), apiDeleteKb: vi.fn(), apiDeleteFolder: vi.fn(), apiRestoreTrash: vi.fn(), fetchTrash: vi.fn() }))
vi.mock('@/api/board', async original => ({ ...await original<typeof import('@/api/board')>(), ...api }))
const doc = (id = 'd1', rev = 1): Doc => ({ id, title: id, author: 'a', created: '', updated: '',
  kbId: 'k1', folderId: 'f1', rev, blocks: [{ p: `正文 ${rev}` }], links: [], backlinks: [] })
const kb = (ids: string[] = []): KnowledgeBase => ({ id: 'k1', name: '资料', icon: '', color: '',
  project: null, desc: '', group: '', perm: '仅自己', folders: [{ id: 'f1', name: '目录', docs: ids }] })
function login(accountId: string, member = accountId) {
  useSessionStore().accept({ accountId, member: { id: member, name: member, initial: member, role: '成员', color: '' } } as never)
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('知识库和归档刷新边界', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    Object.values(api).forEach(mock => mock.mockReset())
    api.fetchKbs.mockResolvedValue([kb()])
    api.fetchDocs.mockResolvedValue([])
    api.fetchKbGroups.mockResolvedValue([])
    api.fetchArchivedDocs.mockResolvedValue([])
    api.apiDeleteKb.mockResolvedValue(undefined)
    api.apiDeleteFolder.mockResolvedValue(undefined)
    api.apiRestoreTrash.mockResolvedValue(undefined)
    api.fetchTrash.mockResolvedValue([])
  })
  afterEach(() => vi.restoreAllMocks())

  it('知识库刷新复用在途请求；账号切换后忽略旧响应，不能清理新账号请求', async () => {
    login('a', 'same-member')
    const data = useDataStore()
    data.apiLive = true
    const old = deferred<KnowledgeBase[]>(), fresh = deferred<KnowledgeBase[]>()
    api.fetchKbs.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const first = data.refreshKnowledge()
    const duplicate = data.refreshKnowledge()
    expect(api.fetchKbs).toHaveBeenCalledTimes(1)
    login('b', 'same-member')
    const second = data.refreshKnowledge()
    old.resolve([{ ...kb(), name: '旧账号' }])
    expect(await first).toBe(false)
    expect(await duplicate).toBe(false)
    expect(data.kbs.some(item => item.name === '旧账号')).toBe(false)
    const third = data.refreshKnowledge()
    expect(api.fetchKbs).toHaveBeenCalledTimes(2)
    fresh.resolve([{ ...kb(), name: '新账号' }])
    expect(await second).toBe(true)
    expect(await third).toBe(true)
    expect(data.kbs[0]?.name).toBe('新账号')
    expect(api.fetchKbs).toHaveBeenCalledTimes(2)
  })

  it('归档按知识库去重，账号切换立即清空归档索引并隔离迟到结果', async () => {
    login('a')
    const data = useDataStore()
    data.apiLive = true
    data.docs.old = doc('old')
    data.archivedDocIds = { k1: ['old'] }
    const old = deferred<Doc[]>(), fresh = deferred<Doc[]>()
    api.fetchArchivedDocs.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const first = data.refreshArchivedDocs('k1')
    const duplicate = data.refreshArchivedDocs('k1')
    expect(api.fetchArchivedDocs).toHaveBeenCalledTimes(1)
    login('b')
    expect(data.archivedDocIds).toEqual({})
    const second = data.refreshArchivedDocs('k1')
    old.resolve([doc('private-a')])
    expect(await first).toBe(false)
    expect(await duplicate).toBe(false)
    expect(data.docs['private-a']).toBeUndefined()
    const third = data.refreshArchivedDocs('k1')
    expect(api.fetchArchivedDocs).toHaveBeenCalledTimes(2)
    fresh.resolve([doc('private-b')])
    expect(await second).toBe(true)
    expect(await third).toBe(true)
    expect(data.archivedDocIds.k1).toEqual(['private-b'])
    expect(api.fetchArchivedDocs).toHaveBeenCalledTimes(2)
  })

  it('归档迟到快照不撤销恢复、不降级新版本，也保留请求期间新增归档', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb()]
    data.docs.d1 = doc()
    data.docs.d2 = doc('d2', 3)
    data.archivedDocIds = { k1: ['d1', 'd2'] }
    const pending = deferred<Doc[]>()
    api.fetchArchivedDocs.mockReturnValueOnce(pending.promise)
    const refreshing = data.refreshArchivedDocs('k1')
    data.kbs[0]!.folders[0]!.docs.push('d1')
    data.archivedDocIds.k1 = ['d2', 'new']
    data.docs.new = doc('new')
    pending.resolve([doc(), doc('d2', 1)])
    expect(await refreshing).toBe(true)
    expect(data.archivedDocIds.k1).toEqual(['d2', 'new'])
    expect(data.docs.d2?.rev).toBe(3)
    expect(data.docs.d1).toBeDefined()
  })

  it('读取失败保留已有知识库，明确暴露错误并允许重试', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb(['d1'])]
    data.docs.d1 = doc()
    api.fetchDocs.mockResolvedValue([doc()])
    api.fetchKbs.mockRejectedValueOnce(new Error('网络中断'))
    expect(await data.refreshKnowledge()).toBe(false)
    expect(data.kbs[0]?.id).toBe('k1')
    expect(data.loadStates.knowledge).toBe('error')
    expect(data.loadErrors.knowledge).toBe('网络中断')
    expect(await data.refreshKnowledge()).toBe(true)
    expect(data.loadErrors.knowledge).toBeUndefined()
    expect(data.loadStates.knowledge).toBe('loaded')
  })

  it('撤销共享清除正文和归档缓存，保留本机待恢复草稿', async () => {
    const data = useDataStore()
    data.kbs = [kb(['d1'])]
    data.docs.d1 = doc()
    data.setDocEdit('d1', { text: '本机输入' })
    data.archivedDocIds = { k1: ['d1'] }
    data.apiLive = true
    api.fetchKbs.mockResolvedValue([])
    await data.refreshKnowledge()
    expect(data.docs.d1).toBeUndefined()
    expect(data.archivedDocIds).toEqual({})
    expect(data.docEdits.d1?.text).toBe('本机输入')
  })

  it('目录刷新不使保留的正文短暂消失，也不触发无变化列表重建', async () => {
    const data = useDataStore()
    data.docs.d1 = doc()
    const before = data.docs.d1
    const observed: (Doc | undefined)[] = []
    const keys = vi.fn()
    const stopDoc = watch(() => data.docs.d1, value => observed.push(value), { flush: 'sync' })
    const stopKeys = watch(() => Object.keys(data.docs).length, keys, { flush: 'sync' })
    api.fetchDocs.mockResolvedValue([{ ...doc(), bodyLoaded: false }])
    await data.refreshDocs()
    expect(data.docs.d1).toBe(before)
    expect(observed).toEqual([])
    expect(keys).not.toHaveBeenCalled()
    stopDoc(); stopKeys()
  })

  it('共享撤销后，在途正文和归档响应不能重新填入缓存', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb(['d1'])]
    data.docs.d1 = doc()
    const detail = deferred<Doc>(), archive = deferred<Doc[]>()
    api.fetchDoc.mockReturnValueOnce(detail.promise)
    api.fetchArchivedDocs.mockReturnValueOnce(archive.promise)
    const detailRead = data.ensureDocLoaded('d1'), archiveRead = data.refreshArchivedDocs('k1')
    api.fetchKbs.mockResolvedValueOnce([])
    await data.refreshKnowledge()
    detail.resolve(doc())
    archive.resolve([doc('archived')])
    expect(await detailRead).toBe(false)
    expect(await archiveRead).toBe(false)
    expect(data.docs).toEqual({})
    expect(data.archivedDocIds).toEqual({})
    expect(data.docLoadErrors.d1).toBe('文档已删除或不可访问')
  })

  it('知识库授权目录先刷新时，迟到的全量目录只保留仍有权读取的库', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb(['d1'])]
    data.docs.d1 = doc()
    const catalog = deferred<Doc[]>()
    api.fetchDocs.mockReturnValueOnce(catalog.promise)
    const pending = data.refreshDocs()
    api.fetchKbs.mockResolvedValueOnce([])
    await data.hydrate(['knowledge'])
    expect(data.docs.d1).toBeUndefined()
    catalog.resolve([doc()])
    expect(await pending).toBe(true)
    expect(data.docs).toEqual({})
  })

  it('归档轻量目录保留已读取的同版本正文，新版本需要重新加载后才能编辑', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb()]
    data.docs.d1 = doc()
    data.archivedDocIds = { k1: ['d1'] }
    const before = data.docs.d1
    api.fetchArchivedDocs.mockResolvedValueOnce([{ ...doc(), blocks: [], bodyLoaded: false }])
    await data.refreshArchivedDocs('k1')
    expect(data.docs.d1).toBe(before)
    api.fetchArchivedDocs.mockResolvedValueOnce([{ ...doc('d1', 2), blocks: [], bodyLoaded: false }])
    await data.refreshArchivedDocs('k1')
    expect(data.docs.d1?.bodyLoaded).toBe(false)
    data.setDocEdit('d1', { text: '' })
    expect(data.docEdits.d1).toBeUndefined()
    api.fetchDoc.mockResolvedValueOnce(doc('d1', 2))
    expect(await data.ensureDocLoaded('d1')).toBe(true)
    expect(data.docOf('d1').text).toContain('正文 2')
    const loaded = data.docs.d1
    api.fetchArchivedDocs.mockResolvedValueOnce([{ ...doc('d1', 2), blocks: [], bodyLoaded: false }])
    await data.refreshArchivedDocs('k1')
    expect(data.docs.d1).toBe(loaded)
    expect(data.docs.d1?.bodyLoaded).not.toBe(false)
    expect(api.fetchDoc).toHaveBeenCalledTimes(1)
  })

  it.each(['kb', 'folder'] as const)('删除 %s 后迟到的未知归档、详情和全量目录不能重建缓存', async type => {
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb()]
    const archived = deferred<Doc[]>(), detail = deferred<Doc>(), catalog = deferred<Doc[]>()
    api.fetchArchivedDocs.mockReturnValueOnce(archived.promise)
    api.fetchDoc.mockReturnValueOnce(detail.promise)
    api.fetchDocs.mockReturnValueOnce(catalog.promise)
    const pending = [data.refreshArchivedDocs('k1'), data.ensureDocLoaded('unseen'), data.refreshDocs()]
    if (type === 'kb') await data.deleteKb('k1')
    else await data.deleteFolder('k1', 'f1')
    archived.resolve([{ ...doc('unseen'), bodyLoaded: false }])
    detail.resolve(doc('unseen'))
    catalog.resolve([doc('unseen')])
    await Promise.all(pending)
    expect(data.docs.unseen).toBeUndefined()
    expect(Object.values(data.archivedDocIds).flat()).toEqual([])
  })

  it.each(['knowledge', 'docs'] as const)('恢复回收站等待旧 %s 请求，再读取包含恢复条目的新快照', async source => {
    const data = useDataStore()
    data.apiLive = true
    const oldCatalog = deferred<Doc[]>(), oldKnowledge = deferred<KnowledgeBase[]>()
    api.fetchDocs.mockReturnValueOnce(oldCatalog.promise).mockResolvedValueOnce([doc('restored')])
    if (source === 'knowledge') api.fetchKbs.mockReturnValueOnce(oldKnowledge.promise)
    api.fetchKbs.mockResolvedValueOnce([kb(['restored'])])
    const reading = source === 'knowledge' ? data.refreshKnowledge() : data.refreshDocs()
    const restoring = data.restoreTrash('doc', 'restored')
    await flushPromises()
    expect(api.fetchDocs).toHaveBeenCalledTimes(1)
    oldCatalog.resolve([])
    oldKnowledge.resolve([])
    await Promise.all([reading, restoring])
    expect(api.fetchDocs).toHaveBeenCalledTimes(2)
    expect(data.kbs[0]?.folders[0]?.docs).toEqual(['restored'])
    expect(data.docs.restored).toBeDefined()
  })

  it('新建知识库/目录/文档期间切账号，旧回执不写入也不返回可导航对象', async () => {
    login('a')
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb()]
    const library = deferred<KnowledgeBase>(), folder = deferred<KnowledgeBase['folders'][number]>(), document = deferred<Doc>()
    api.apiCreateKb.mockReturnValueOnce(library.promise)
    api.apiCreateFolder.mockReturnValueOnce(folder.promise)
    api.apiCreateDoc.mockReturnValueOnce(document.promise)
    const pending = [data.addKb({ name: '新库', group: '', perm: '仅自己' }), data.addFolder('k1'), data.addDoc('k1', 'f1')]
    login('b')
    data.kbs = [{ ...kb(), name: '账号 B 的库' }]
    library.resolve({ ...kb(), id: 'private-a' })
    folder.resolve({ id: 'private-folder', name: '私人目录', docs: [] })
    document.resolve(doc('private-doc'))
    expect(await Promise.all(pending)).toEqual([null, null, null])
    expect(data.kbs.map(item => item.id)).toEqual(['k1'])
    expect(data.kbs[0]?.folders.map(item => item.id)).toEqual(['f1'])
    expect(data.docs).toEqual({})
  })

  it('知识库设置和目录改名期间切账号，迟到回执不能修改新账号同ID资料', async () => {
    login('a')
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb()]
    const library = deferred<KnowledgeBase>(), folder = deferred<void>()
    api.apiPatchKb.mockReturnValueOnce(library.promise)
    api.apiRenameFolder.mockReturnValueOnce(folder.promise)
    const update = data.updateKb('k1', { name: '旧账号改名' }), rename = data.renameFolder('k1', 'f1', '旧目录名')
    login('b')
    data.kbs = [kb()]
    library.resolve({ ...kb(), name: '旧账号改名' })
    folder.resolve()
    expect(await update).toBe(false)
    expect(await rename).toBe(false)
    expect(data.kbs[0]?.name).toBe('资料')
    expect(data.kbs[0]?.folders[0]?.name).toBe('目录')
  })

  it.each(['refresh', 'hydrate'] as const)('%s 的旧目录响应不能复活请求期间已删除的库；下一次仍接受外部新建库', async source => {
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb(['d1'])]
    data.docs.d1 = doc()
    const old = deferred<KnowledgeBase[]>()
    api.fetchKbs.mockReturnValueOnce(old.promise)
    const refreshing = source === 'refresh' ? data.refreshKnowledge() : data.hydrate(['knowledge'])
    await data.deleteKb('k1')
    old.resolve([kb(['d1'])])
    await refreshing
    expect(data.kbs).toEqual([])
    expect(data.docs.d1).toBeUndefined()
    api.fetchKbs.mockResolvedValueOnce([{ ...kb(), id: 'external', name: '其他窗口新建' }])
    await data.refreshKnowledge()
    expect(data.kbs[0]?.id).toBe('external')
  })

  it('新建文档完成后旧目录不能丢失它，后续刷新仍接收其他窗口的新增', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb(['d1'])]
    data.docs.d1 = doc()
    api.fetchDocs.mockResolvedValueOnce([doc()])
    const old = deferred<KnowledgeBase[]>()
    api.fetchKbs.mockReturnValueOnce(old.promise)
    const refreshing = data.refreshKnowledge()
    api.apiCreateDoc.mockResolvedValueOnce(doc('new'))
    expect(await data.addDoc('k1', 'f1')).toBe('new')
    old.resolve([kb(['d1'])])
    await refreshing
    expect(data.kbs[0]?.folders[0]?.docs).toEqual(['d1', 'new'])
    expect(data.docs.new).toBeDefined()
    api.fetchKbs.mockResolvedValueOnce([kb(['d1', 'new', 'external'])])
    api.fetchDocs.mockResolvedValueOnce([doc(), doc('new'), doc('external')])
    await data.refreshKnowledge()
    expect(data.kbs[0]?.folders[0]?.docs).toEqual(['d1', 'new', 'external'])
  })

  it('目录刷新先完成时新建与改名回执定位当前对象，不能写进被替换的旧树', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb()]
    const folder = deferred<KnowledgeBase['folders'][number]>(), document = deferred<Doc>(), update = deferred<KnowledgeBase>()
    api.apiCreateFolder.mockReturnValueOnce(folder.promise)
    api.apiCreateDoc.mockReturnValueOnce(document.promise)
    api.apiPatchKb.mockReturnValueOnce(update.promise)
    const addingFolder = data.addFolder('k1'), addingDocument = data.addDoc('k1', 'f1'), updating = data.updateKb('k1', { name: '新名字' })
    await data.refreshKnowledge()
    update.resolve({ ...kb(), name: '新名字' })
    expect(await updating).toBe(true)
    folder.resolve({ id: 'f2', name: '新目录', docs: [] })
    document.resolve(doc('new'))
    expect(await addingFolder).toBe('f2')
    expect(await addingDocument).toBe('new')
    expect(data.kbs[0]?.name).toBe('新名字')
    expect(data.kbs[0]?.folders.map(folder => folder.id)).toEqual(['f1', 'f2'])
    expect(data.kbs[0]?.folders[0]?.docs).toEqual(['new'])
  })

  it('刷新已经看见的新建结果不重复插入，已删除目标目录不接收迟到创建回执', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.kbs = [kb()]
    const library = deferred<KnowledgeBase>(), folder = deferred<KnowledgeBase['folders'][number]>(), document = deferred<Doc>()
    api.apiCreateKb.mockReturnValueOnce(library.promise)
    api.apiCreateFolder.mockReturnValueOnce(folder.promise)
    api.apiCreateDoc.mockReturnValueOnce(document.promise)
    const pending = [data.addKb({ name: '新库', group: '', perm: '仅自己' }), data.addFolder('k1'), data.addDoc('k1', 'f1')]
    const visible = { ...kb(['new']), folders: [...kb(['new']).folders, { id: 'f2', name: '新目录', docs: [] }] }
    api.fetchKbs.mockResolvedValueOnce([visible, { ...kb(), id: 'k2' }])
    await data.refreshKnowledge()
    library.resolve({ ...kb(), id: 'k2' }); folder.resolve(visible.folders[1]!); document.resolve(doc('new'))
    await Promise.all(pending)
    expect(data.kbs).toHaveLength(2)
    expect(data.kbs[0]?.folders).toHaveLength(2)
    expect(data.kbs[0]?.folders[0]?.docs).toEqual(['new'])
    const deletedFolder = deferred<Doc>()
    api.apiCreateDoc.mockReturnValueOnce(deletedFolder.promise)
    const creating = data.addDoc('k1', 'f1')
    await data.deleteFolder('k1', 'f1')
    deletedFolder.resolve(doc('too-late'))
    expect(await creating).toBeNull()
    expect(data.docs['too-late']).toBeUndefined()
  })
})
