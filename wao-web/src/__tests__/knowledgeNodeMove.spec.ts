import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import type { Doc, KnowledgeBase } from '@/types'

const api = vi.hoisted(() => ({ apiMoveKnowledgeNode: vi.fn(), fetchDoc: vi.fn(), fetchDocs: vi.fn(), fetchKbs: vi.fn(),
  apiDeleteKb: vi.fn(), apiDeleteDoc: vi.fn(), apiDeleteFolder: vi.fn(), apiRestoreTrash: vi.fn(), fetchTrash: vi.fn() }))
vi.mock('@/api/board', async original => ({ ...await original<typeof import('@/api/board')>(), ...api }))
const kb = (moved = false): KnowledgeBase => ({ id: 'k1', name: '资料', icon: '', color: '', project: null, desc: '',
  folders: [{ id: 'f1', name: '原目录', docs: moved ? ['d2'] : ['d1', 'd2'] }, { id: 'f2', name: '目标目录', docs: moved ? ['d1'] : [] }],
  directory: { 'f:f1': '', 'f:f2': '', 'd:d1': moved ? 'f:f2' : 'f:f1', 'd:d2': 'f:f1' },
  directoryOrder: ['f:f1', 'd:d2', 'f:f2', 'd:d1'] })
const doc = (id = 'd1', moved = false): Doc => ({ id, title: id, author: 'a', created: '', updated: '', kbId: 'k1',
  folderId: moved ? 'f2' : 'f1', rev: moved ? 2 : 1, blocks: [{ p: '正文' }], links: [], backlinks: [] })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
function login(accountId: string) {
  useSessionStore().account = { accountId, member: { id: 'same-member' } } as never
}
function seed() {
  login('a')
  const data = useDataStore()
  data.apiLive = true
  data.kbs = [kb(), { ...kb(), id: 'k2', name: '另一知识库', folders: [] }]
  data.docs.d1 = doc()
  data.docs.d2 = { ...doc('d2'), bodyLoaded: false, blocks: [] }
  return data
}

describe('目录移动由账号作用域的数据层确认', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    Object.values(api).forEach(mock => mock.mockReset())
    api.fetchDoc.mockResolvedValue(doc('d1', true))
    api.fetchDocs.mockResolvedValue([])
    api.fetchTrash.mockResolvedValue([])
    api.apiDeleteKb.mockResolvedValue(undefined)
    api.apiDeleteDoc.mockResolvedValue(undefined)
    api.apiDeleteFolder.mockResolvedValue(undefined)
    api.apiRestoreTrash.mockResolvedValue(undefined)
  })

  it('同账号查看另一知识库时仍合并当前树对象，返回原库得到已保存的新位置', async () => {
    const data = seed(), response = deferred<KnowledgeBase>()
    api.apiMoveKnowledgeNode.mockReturnValueOnce(response.promise)
    const moving = data.moveKnowledgeNode('k1', 'd:d1', 'f:f2', 'f:f1', null)
    expect(data.kbById('k2')?.name).toBe('另一知识库')
    // A background catalog can replace object identities while another library is displayed.
    data.kbs = [data.kbs[1]!, kb()]
    response.resolve(kb(true))
    expect(await moving).toBe(true)
    expect(api.apiMoveKnowledgeNode).toHaveBeenCalledWith('k1', 'd:d1', 'f:f2', 'f:f1', null)
    expect(data.kbById('k1')?.folders[1]?.docs).toEqual(['d1'])
    expect(data.docs.d1).toMatchObject({ folderId: 'f2', rev: 2 })
    expect(data.kbById('k2')?.folders).toEqual([])
  })

  it.each(['identity', 'kb', 'doc'] as const)('%s 改变后不接受旧移动回执', async change => {
    const data = seed(), response = deferred<KnowledgeBase>()
    api.apiMoveKnowledgeNode.mockReturnValueOnce(response.promise)
    const moving = data.moveKnowledgeNode('k1', 'd:d1', 'f:f2', 'f:f1')
    if (change === 'identity') { login('b'); data.kbs = [kb()] }
    else if (change === 'kb') await data.deleteKb('k1')
    else await data.deleteDoc('d1')
    response.resolve(kb(true))
    expect(await moving).toBe(false)
    expect(data.docs.d1).toBeUndefined()
    expect(api.fetchDoc).not.toHaveBeenCalled()
    if (change === 'identity') expect(data.kbs[0]?.folders[1]?.docs).toEqual([])
  })

  it('移动后旧目录刷新不能把父级移回去，下一次新刷新仍可正常更新', async () => {
    const data = seed(), old = deferred<KnowledgeBase[]>()
    api.fetchKbs.mockReturnValueOnce(old.promise)
    api.fetchDocs.mockResolvedValueOnce([doc(), doc('d2')])
    const refreshing = data.refreshKnowledge()
    api.apiMoveKnowledgeNode.mockResolvedValueOnce(kb(true))
    expect(await data.moveKnowledgeNode('k1', 'd:d1', 'f:f2', 'f:f1')).toBe(true)
    old.resolve([kb()])
    await refreshing
    expect(data.kbById('k1')?.directory?.['d:d1']).toBe('f:f2')
    expect(data.docs.d1?.folderId).toBe('f2')
    api.fetchKbs.mockResolvedValueOnce([{ ...kb(true), name: '别处改名' }])
    api.fetchDocs.mockResolvedValueOnce([doc('d1', true), doc('d2')])
    await data.refreshKnowledge()
    expect(data.kbById('k1')?.name).toBe('别处改名')
  })

  it('移动整树回执保留期间新建节点和改名，不复活已删除的无关文档', async () => {
    const data = seed(), response = deferred<KnowledgeBase>()
    api.apiMoveKnowledgeNode.mockReturnValueOnce(response.promise)
    const moving = data.moveKnowledgeNode('k1', 'd:d1', 'f:f2', 'f:f1')
    await data.deleteDoc('d2')
    data.kbs[0]!.name = '本地改名'
    data.kbs[0]!.folders[0]!.name = '本地目录名'
    data.kbs[0]!.folders[0]!.docs.push('new')
    response.resolve(kb(true))
    expect(await moving).toBe(true)
    expect(data.kbs[0]?.name).toBe('本地改名')
    expect(data.kbs[0]?.folders[0]).toMatchObject({ name: '本地目录名', docs: ['new'] })
    expect(data.kbs[0]?.directory?.['d:d2']).toBeUndefined()
    expect(data.kbs[0]?.folders[1]?.docs).toEqual(['d1'])
  })

  it.each(['identity', 'delete', 'draft'] as const)('移动后的正文补读遇到 %s 变化不能覆盖新作用域或输入', async change => {
    const data = seed(), body = deferred<Doc>()
    api.apiMoveKnowledgeNode.mockResolvedValueOnce(kb(true))
    api.fetchDoc.mockReturnValueOnce(body.promise)
    const moving = data.moveKnowledgeNode('k1', 'd:d1', 'f:f2', 'f:f1')
    await flushPromises()
    expect(data.docs.d1?.folderId).toBe('f2')
    if (change === 'identity') { login('b'); data.kbs = [kb()]; data.docs.d1 = { ...doc(), title: '新账号' } }
    else if (change === 'delete') await data.deleteDoc('d1')
    else { data.apiLive = false; data.setDocEdit('d1', { text: '移动后继续写的新输入' }) }
    body.resolve(doc('d1', true))
    await moving
    if (change === 'identity') expect(data.docs.d1?.title).toBe('新账号')
    else if (change === 'delete') expect(data.docs.d1).toBeUndefined()
    else expect(data.docOf('d1').text).toBe('移动后继续写的新输入')
  })

  it('接口冲突抛给入口并保持原目录；仅元数据条目不主动拉全量正文', async () => {
    const data = seed(), conflict = new Error('目录已在其他窗口移动')
    api.apiMoveKnowledgeNode.mockRejectedValueOnce(conflict)
    await expect(data.moveKnowledgeNode('k1', 'd:d1', 'f:f2', 'f:f1')).rejects.toThrow(conflict)
    expect(data.kbs[0]?.directory?.['d:d1']).toBe('f:f1')
    data.docs.d1 = { ...doc(), bodyLoaded: false, blocks: [] }
    api.apiMoveKnowledgeNode.mockResolvedValueOnce(kb(true))
    expect(await data.moveKnowledgeNode('k1', 'd:d1', 'f:f2', 'f:f1')).toBe(true)
    expect(data.docs.d1?.folderId).toBe('f2')
    expect(api.fetchDoc).not.toHaveBeenCalled()
  })

  it('移动元数据条目后旧正文目录不能回滚folderId，后续新请求正常接受服务端更新', async () => {
    const data = seed(), catalog = deferred<Doc[]>()
    data.docs.d1 = { ...doc(), bodyLoaded: false, blocks: [] }
    api.fetchDocs.mockReturnValueOnce(catalog.promise)
    const refreshing = data.refreshDocs()
    api.apiMoveKnowledgeNode.mockResolvedValueOnce(kb(true))
    expect(await data.moveKnowledgeNode('k1', 'd:d1', 'f:f2', 'f:f1')).toBe(true)
    catalog.resolve([{ ...doc(), bodyLoaded: false, blocks: [] }])
    await refreshing
    expect(data.docs.d1?.folderId).toBe('f2')
    expect(data.kbs[0]?.directory?.['d:d1']).toBe('f:f2')
    expect(api.fetchDoc).not.toHaveBeenCalled()
    api.fetchKbs.mockResolvedValueOnce([kb()])
    api.fetchDocs.mockResolvedValueOnce([{ ...doc(), bodyLoaded: false, blocks: [] }])
    await data.refreshKnowledge()
    expect(data.docs.d1?.folderId).toBe('f1')
    expect(data.kbs[0]?.directory?.['d:d1']).toBe('f:f1')
  })

  it('移动请求开始后才出现又被删除的分组不能被旧整树回执复活', async () => {
    const data = seed(), response = deferred<KnowledgeBase>()
    api.apiMoveKnowledgeNode.mockReturnValueOnce(response.promise)
    const moving = data.moveKnowledgeNode('k1', 'd:d1', 'f:f2', 'f:f1')
    const folder = { id: 'new-folder', name: '期间新建又删除', docs: [] }
    data.kbs[0]!.folders.push(folder)
    await data.deleteFolder('k1', folder.id)
    const saved = kb(true)
    saved.folders.push(folder)
    saved.directory![`f:${folder.id}`] = ''
    saved.directoryOrder!.push(`f:${folder.id}`)
    response.resolve(saved)
    expect(await moving).toBe(true)
    expect(data.kbs[0]?.folders.some(item => item.id === folder.id)).toBe(false)
    expect(data.kbs[0]?.directory?.[`f:${folder.id}`]).toBeUndefined()
    expect(data.kbs[0]?.directoryOrder).not.toContain(`f:${folder.id}`)
  })

  it.each(['source-folder', 'parent-folder', 'kb'] as const)('%s 删除后即使恢复相同ID，也不接收删除前的移动回执', async kind => {
    const data = seed(), response = deferred<KnowledgeBase>()
    api.apiMoveKnowledgeNode.mockReturnValueOnce(response.promise)
    const moving = kind === 'source-folder'
      ? data.moveKnowledgeNode('k1', 'f:f1', 'f:f2', '')
      : data.moveKnowledgeNode('k1', 'd:d1', 'f:f2', 'f:f1')
    if (kind === 'kb') await data.deleteKb('k1')
    else await data.deleteFolder('k1', kind === 'source-folder' ? 'f1' : 'f2')
    api.fetchKbs.mockResolvedValueOnce([kb()])
    api.fetchDocs.mockResolvedValueOnce([doc(), doc('d2')])
    await data.restoreTrash(kind === 'kb' ? 'kb' : 'folder', kind === 'kb' ? 'k1' : kind === 'source-folder' ? 'f1' : 'f2')
    const restored = JSON.stringify(data.kbs)
    response.resolve(kb(true))
    expect(await moving).toBe(false)
    expect(JSON.stringify(data.kbs)).toBe(restored)
    expect(data.docs.d1?.folderId).toBe('f1')
    expect(api.fetchDoc).not.toHaveBeenCalled()
  })
})
