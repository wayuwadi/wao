import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { apiPatchDoc } from '@/api/board'
import { DOCS } from '@/data/fixtures'
import { readDocumentDraft } from '@/utils/documentDrafts'
import type { Doc } from '@/types'

vi.mock('@/api/board', async (original) => ({
  ...await original<typeof import('@/api/board')>(), apiPatchDoc: vi.fn<typeof apiPatchDoc>(),
}))

const id = 'draft-lifecycle-document'
const memberId = 'draft-lifecycle-member'
const content = (markdown: string) => ({ version: 1 as const, document: null, markdown })
const baseDoc = (): Doc => ({ ...Object.values(DOCS)[0]!, id, title: '草稿链路验收', rev: 7, content: content('服务器基线') })

function freshStore(base = baseDoc()) {
  setActivePinia(createPinia())
  useSessionStore().accept({ accountId: 'draft-lifecycle-account', member: {
    id: memberId, name: '测试成员', initial: '测', color: '#64748b', role: '',
  }, identities: [], createdAt: '2026-09-19T00:00:00' })
  const data = useDataStore()
  data.docs[id] = base
  return data
}

describe('文档草稿完整生命周期回归', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('indexedDB', new IDBFactory())
    vi.mocked(apiPatchDoc).mockReset()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  })
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals() })

  it('保存请求飞行中继续输入，旧响应不得清除新草稿，随后用新版本号补交', async () => {
    const data = freshStore()
    let firstDone!: (doc: Doc) => void
    vi.mocked(apiPatchDoc).mockImplementationOnce(() => new Promise(resolve => { firstDone = resolve }))
    data.setDocEdit(id, { content: content('第一次输入') })
    const first = data.persistDocNow(id)
    expect(data.saveState(`doc:${id}`).saving).toBe(true)
    data.setDocEdit(id, { content: content('请求未返回时继续输入') })
    firstDone({ ...baseDoc(), rev: 8, content: content('第一次输入') })
    await first
    expect(data.docOf(id).text).toBe('请求未返回时继续输入')
    expect(data.docPending(id)).toBe(true)
    expect(data.docEdits[id]?.baseRev).toBe(8)

    vi.mocked(apiPatchDoc).mockResolvedValueOnce({ ...baseDoc(), rev: 9, content: content('请求未返回时继续输入') })
    await data.persistDocNow(id)
    expect(apiPatchDoc).toHaveBeenLastCalledWith(id, expect.objectContaining({
      expectedRev: 8, content: content('请求未返回时继续输入'),
    }))
    expect(data.docPending(id)).toBe(false)
    expect(data.docEdits[id]).toBeUndefined()
    expect(JSON.parse(localStorage.getItem('wao-docs-v1:draft-lifecycle-member') ?? '{}')[id]).toBeUndefined()
    expect(await readDocumentDraft(memberId, id)).toBeNull()
  })

  it('协同拉取不能覆盖未保存正文；成功后才接受其他窗口更新', async () => {
    const data = freshStore()
    data.setDocEdit(id, { title: '本机标题', content: content('本机输入') })
    const remote = { ...baseDoc(), rev: 8, title: '另一窗口标题', content: content('另一窗口正文') }
    expect(data.applyRemoteDoc(remote)).toBe(false)
    expect(data.docOf(id)).toMatchObject({ title: '本机标题', text: '本机输入' })
    expect(data.docs[id]?.rev).toBe(7)
    vi.mocked(apiPatchDoc).mockResolvedValueOnce({ ...baseDoc(), rev: 8, title: '本机标题', content: content('本机输入') })
    await data.persistDocNow(id)
    expect(data.applyRemoteDoc({ ...remote, rev: 9 })).toBe(true)
    expect(data.docOf(id)).toMatchObject({ title: '另一窗口标题', text: '另一窗口正文' })
  })

  it('失败后重建页面状态仍能恢复草稿，重试成功后刷新只读服务器版本', async () => {
    let data = freshStore()
    data.setDocEdit(id, { content: content('断网前的完整输入') })
    vi.mocked(apiPatchDoc).mockRejectedValueOnce(new Error('暂时无法保存'))
    expect(await data.persistDocNow(id)).toBe(false)
    expect(data.saveState(`doc:${id}`)).toMatchObject({ failed: true, savedAt: null })
    data = freshStore()
    expect(await data.restoreDocumentDraft(id)).toBe(true)
    expect(data.docOf(id).text).toBe('断网前的完整输入')
    expect(data.docPending(id)).toBe(true)
    const saved = { ...baseDoc(), rev: 8, content: content('断网前的完整输入') }
    vi.mocked(apiPatchDoc).mockResolvedValueOnce(saved)
    expect(await data.persistDocNow(id)).toBe(true)
    data = freshStore(saved)
    expect(await data.restoreDocumentDraft(id)).toBe(false)
    expect(data.docOf(id).text).toBe('断网前的完整输入')
    expect(data.docPending(id)).toBe(false)
  })

  it('刷新发现服务器版本变化时保留两版且不自动提交覆盖', async () => {
    let data = freshStore()
    data.setDocEdit(id, { content: content('本机尚未上传的版本') })
    data = freshStore({ ...baseDoc(), rev: 8, content: content('其他窗口已保存的版本') })
    expect(await data.restoreDocumentDraft(id)).toBe(true)
    expect(data.docOf(id).text).toBe('本机尚未上传的版本')
    expect(data.docConflicts[id]?.content?.markdown).toBe('其他窗口已保存的版本')
    expect(await data.persistDocNow(id)).toBe(false)
    expect(apiPatchDoc).not.toHaveBeenCalled()
  })
})
