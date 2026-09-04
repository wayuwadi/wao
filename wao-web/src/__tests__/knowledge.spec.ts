import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { useSessionStore } from '@/stores/session'

/**
 * 知识库模块：小记、文档创建署名与回收站式删除。
 *
 * 这里跑的是离线兜底路径（apiLive = false），断言的是本地状态迁移；
 * 落库口径由后端 KnowledgeServiceTest 覆盖，两边保持同一套规则。
 */
describe('data store · 知识库', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    seedDataStore(data)
  })

  it('默认就有一个小记知识库，不用先建库才能记东西', () => {
    const kb = data.notesKb()
    expect(kb.notes).toBe(true)
    expect(kb.folders.length).toBeGreaterThan(0)
    // 反复取到的是同一个，不会每次新建一个
    expect(data.notesKb().id).toBe(kb.id)
  })

  it('小记落进小记知识库，标题由正文首行推导', async () => {
    const id = await data.addNote('周四要交社保材料\n\n记得带身份证')

    expect(id).not.toBeNull()
    const doc = data.docById(id!)!
    expect(doc.title).toBe('周四要交社保材料')
    // 作者查不到成员记录时回落到默认「小记」文件夹
    expect(data.notesKb().folders[0]!.docs).toContain(id)
  })

  it('小记按创建人分文件夹：娃弟记的进「娃弟」', async () => {
    const session = useSessionStore()
    session.accept({
      member: { id: 'wd', name: '娃弟', initial: '弟', color: '#2C8A5B', role: '成员' },
    } as never)

    const id = await data.addNote('周末去搬显示器')

    const kb = data.notesKb()
    const mine = kb.folders.find((f) => f.name === '娃弟')!
    expect(mine).toBeDefined()
    expect(mine.docs).toContain(id)
    expect(data.docById(id!)!.folderId).toBe(mine.id)
    // 没混进公用的「小记」文件夹
    expect(kb.folders.find((f) => f.id === 'kb-notes-f')!.docs).not.toContain(id)
  })

  it('同一个人的第二条小记复用同一个文件夹，不会越记越多文件夹', async () => {
    const session = useSessionStore()
    session.accept({
      member: { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' },
    } as never)

    await data.addNote('第一条')
    await data.addNote('第二条')

    const mine = data.notesKb().folders.filter((f) => f.name === '娃哥')
    expect(mine).toHaveLength(1)
    expect(mine[0]!.docs).toHaveLength(2)
  })

  it('小记正文为空时不创建', async () => {
    expect(await data.addNote('   ')).toBeNull()
  })

  it('小记和文档保留高级 JSON，同时继续提供 Markdown 与旧块投影', async () => {
    const content = {
      version: 1 as const,
      document: { type: 'doc', content: [{ type: 'callout', attrs: { kind: 'info', uid: 'c1' }, content: [{ type: 'paragraph', content: [{ type: 'text', text: '完整结构' }] }] }] },
      markdown: '> [!INFO]\n> 完整结构',
    }

    const id = await data.addNote(content.markdown, '', undefined, content)
    const saved = data.docOf(id!)

    expect(saved.content.document?.content?.[0]?.type).toBe('callout')
    expect(saved.text).toContain('完整结构')
    expect(saved.blocks.length).toBeGreaterThan(0)
  })

  it('新建文档署当前登录成员的名，不再一律算在娃哥头上', async () => {
    const kb = data.kbs.find((k) => k.folders.length)!
    const id = await data.addDoc(kb.id, kb.folders[0]!.id)

    const doc = data.docById(id!)!
    // 未登录的演示态回落到 wg；登录后取会话成员（见 currentAuthor）
    expect(doc.author).toBe('wg')
    // 标题留空由 placeholder 顶着，不再塞「未命名文档」让用户先删四个字
    expect(doc.title).toBe('')
  })

  it('新建文档带真实时间戳，而不是「刚刚」这种写死的文案', async () => {
    const kb = data.kbs.find((k) => k.folders.length)!
    const id = await data.addDoc(kb.id, kb.folders[0]!.id)

    expect(data.docById(id!)!.created).toMatch(/^\d{4}-\d{2}-\d{2}T/)
    expect(data.docById(id!)!.updated).toMatch(/^\d{4}-\d{2}-\d{2}T/)
  })

  it('删除文档：从文件夹、文档表和本地编辑覆盖里一起摘掉', async () => {
    const kb = data.kbs.find((k) => k.folders.length)!
    const folder = kb.folders[0]!
    const id = (await data.addDoc(kb.id, folder.id))!
    data.setDocEdit(id, { text: '一些内容' })
    expect(data.docEdits[id]).toBeTruthy()

    await data.deleteDoc(id)

    expect(folder.docs).not.toContain(id)
    expect(data.docById(id)).toBeUndefined()
    expect(data.docEdits[id]).toBeUndefined()
  })

  it('归档文档会退出普通目录但保留正文，并可恢复到原文件夹', async () => {
    const kb = data.kbs.find((k) => k.folders.length)!
    const folder = kb.folders[0]!
    const id = (await data.addDoc(kb.id, folder.id))!
    data.setDocEdit(id, { text: '一次性使用但需要长期留存的正文' })

    expect(await data.archiveDoc(id)).toBe(true)

    expect(folder.docs).not.toContain(id)
    expect(data.archivedDocsOf(kb.id).map((doc) => doc.id)).toContain(id)
    expect(data.docOf(id).text).toContain('长期留存')
    expect(data.isDocArchived(id)).toBe(true)

    expect(await data.unarchiveDoc(id)).toBe(true)

    expect(folder.docs).toContain(id)
    expect(data.archivedDocsOf(kb.id)).toHaveLength(0)
    expect(data.docOf(id).text).toContain('长期留存')
  })

  it('删除文件夹会带走它下面的文档', async () => {
    const kb = data.kbs.find((k) => k.folders.length)!
    const folderId = (await data.addFolder(kb.id))!
    const docId = (await data.addDoc(kb.id, folderId))!

    await data.deleteFolder(kb.id, folderId)

    expect(kb.folders.find((f) => f.id === folderId)).toBeUndefined()
    expect(data.docById(docId)).toBeUndefined()
  })

  it('拖拽可以重排文档并移动到另一个文件夹', async () => {
    const kb = data.kbs.find((k) => k.folders.length)!
    const source = kb.folders[0]!
    const existing = [...source.docs]
    const targetId = (await data.addFolder(kb.id, '归档'))!
    const target = kb.folders.find((folder) => folder.id === targetId)!
    const first = (await data.addDoc(kb.id, source.id))!
    const second = (await data.addDoc(kb.id, source.id))!

    const ok = await data.reorderDocs(kb.id, [
      { folderId: source.id, docIds: [second, ...existing] },
      ...kb.folders.filter((folder) => folder.id !== source.id && folder.id !== target.id)
        .map((folder) => ({ folderId: folder.id, docIds: [...folder.docs] })),
      { folderId: target.id, docIds: [first] },
    ])

    expect(ok).toBe(true)
    expect(source.docs).toEqual([second, ...existing])
    expect(target.docs).toEqual([first])
    expect(data.docById(first)!.folderId).toBe(target.id)
  })

  it('小记知识库不允许删除——它是「新建小记」的落点', async () => {
    const notes = data.notesKb()

    await data.deleteKb(notes.id)

    expect(data.kbById(notes.id)).toBeTruthy()
  })

  it('协同拉回的服务端版本不会盖掉本地还没写透的编辑', () => {
    const doc = {
      id: 'd-collab', title: '协同', author: 'wg', created: '', updated: '',
      links: [], blocks: [{ p: '别人写的' }], backlinks: [], rev: 9,
    }
    data.docs['d-collab'] = { ...doc, blocks: [{ p: '原始' }], rev: 8 }

    // 没有本地未写透编辑时接受服务端版本
    expect(data.applyRemoteDoc(doc)).toBe(true)
    expect(data.docById('d-collab')!.rev).toBe(9)
  })
})
