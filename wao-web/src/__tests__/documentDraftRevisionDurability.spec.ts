import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { apiPatchDoc } from '@/api/board'
import { DOCS } from '@/data/fixtures'
import { readDocumentDraft } from '@/utils/documentDrafts'
import * as draftStorage from '@/utils/documentDrafts'
import type { Doc } from '@/types'

vi.mock('@/api/board', async (original) => ({
  ...await original<typeof import('@/api/board')>(), apiPatchDoc: vi.fn<typeof apiPatchDoc>(),
}))

const id = 'revision-durability-doc'
const memberId = 'revision-durability-member'
const content = (markdown: string) => ({ version: 1 as const, document: null, markdown })
const original = (): Doc => ({ ...Object.values(DOCS)[0]!, id, title: '版本持久化验收', rev: 7, content: content('服务器原文') })

function freshStore(base = original()) {
  setActivePinia(createPinia())
  useSessionStore().accept({ accountId: 'revision-durability-account', member: {
    id: memberId, name: '测试成员', initial: '测', color: '#64748b', role: '',
  }, identities: [], createdAt: '2026-09-19T00:00:00' })
  const data = useDataStore()
  data.docs[id] = base
  return data
}

async function saveFirstWhileSecondFails() {
  const data = freshStore()
  let resolveFirst!: (doc: Doc) => void
  vi.mocked(apiPatchDoc).mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
  data.setDocEdit(id, { content: content('第一版') })
  const request = data.persistDocNow(id)
  data.setDocEdit(id, { content: content('第二版仍未上传') })
  const saved = { ...original(), rev: 8, content: content('第一版') }
  resolveFirst(saved)
  expect(await request).toBe(true)
  vi.mocked(apiPatchDoc).mockRejectedValueOnce(new Error('暂时断网'))
  expect(await data.persistDocNow(id)).toBe(false)
  return { data, saved }
}

describe('已确认保存版本必须同步进入剩余草稿', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('indexedDB', new IDBFactory())
    vi.mocked(apiPatchDoc).mockReset()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  })
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

  it('补交失败后刷新兼容草稿，使用已确认的新版本且不产生虚假冲突', async () => {
    const { saved } = await saveFirstWhileSecondFails()
    const restored = freshStore(saved)
    expect(await restored.restoreDocumentDraft(id)).toBe(true)
    expect(restored.docOf(id).text).toBe('第二版仍未上传')
    expect(restored.docConflicts[id]).toBeFalsy()
    expect(restored.docEdits[id]?.baseRev).toBe(8)
    vi.mocked(apiPatchDoc).mockResolvedValueOnce({ ...saved, rev: 9, content: content('第二版仍未上传') })
    expect(await restored.persistDocNow(id)).toBe(true)
    expect(apiPatchDoc).toHaveBeenLastCalledWith(id, expect.objectContaining({ expectedRev: 8 }))
  })

  it('仅从 IndexedDB 恢复时，同样保留第二版内容和已经确认的版本', async () => {
    const { saved } = await saveFirstWhileSecondFails()
    expect(await readDocumentDraft(memberId, id)).toMatchObject({ baseRev: 8, content: content('第二版仍未上传') })
    localStorage.removeItem('wao-docs-v1:revision-durability-member')
    const restored = freshStore(saved)
    expect(await restored.restoreDocumentDraft(id)).toBe(true)
    expect(restored.docConflicts[id]).toBeFalsy()
    expect(restored.docOf(id).text).toBe('第二版仍未上传')
  })

  it('其他窗口确实继续更新后仍进入冲突保护，不自动覆盖第三版', async () => {
    const { saved } = await saveFirstWhileSecondFails()
    const external = { ...saved, rev: 9, content: content('另一窗口的新版本') }
    const restored = freshStore(external)
    expect(await restored.restoreDocumentDraft(id)).toBe(true)
    expect(restored.docConflicts[id]?.rev).toBe(9)
    expect(restored.docOf(id).text).toBe('第二版仍未上传')
    vi.mocked(apiPatchDoc).mockClear()
    expect(await restored.persistDocNow(id)).toBe(false)
    expect(apiPatchDoc).not.toHaveBeenCalled()
  })

  it('兼容存储写入失败时，IndexedDB 仍保存剩余正文的新基础版本', async () => {
    const setItem = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key, value) {
      if (key === 'wao-docs-v1:revision-durability-member') throw new DOMException('测试配额不足', 'QuotaExceededError')
      return setItem.call(this, key, value)
    })
    const { saved } = await saveFirstWhileSecondFails()
    const restored = freshStore(saved)
    expect(await restored.restoreDocumentDraft(id)).toBe(true)
    expect(restored.docEdits[id]?.baseRev).toBe(8)
    expect(restored.docOf(id).text).toBe('第二版仍未上传')
    expect(restored.docConflicts[id]).toBeFalsy()
  })

  it('IndexedDB 写入失败时，兼容存储保留正确基线并保持失败提示', async () => {
    vi.spyOn(draftStorage, 'writeDocumentDraft').mockRejectedValue(new Error('测试 IndexedDB 不可写'))
    const { data, saved } = await saveFirstWhileSecondFails()
    expect(data.docDraftErrors[id]).toBe(true)
    const restored = freshStore(saved)
    expect(await restored.restoreDocumentDraft(id)).toBe(true)
    expect(restored.docEdits[id]?.baseRev).toBe(8)
    expect(restored.docOf(id).text).toBe('第二版仍未上传')
    expect(restored.docConflicts[id]).toBeFalsy()
    expect(restored.docDraftErrors[id]).toBe(true)
  })
})
