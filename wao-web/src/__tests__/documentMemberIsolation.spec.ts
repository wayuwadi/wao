import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import * as drafts from '@/utils/documentDrafts'
import { DOCS } from '@/data/fixtures'
import { apiPatchDoc, fetchDoc } from '@/api/board'
vi.mock('@/api/board', async original => ({ ...await original<typeof import('@/api/board')>(), apiPatchDoc: vi.fn(), fetchDoc: vi.fn() }))
const content = (markdown: string) => ({ version: 1 as const, document: null, markdown })
describe('文档兼容草稿成员隔离', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.spyOn(drafts, 'writeDocumentDraft').mockResolvedValue(); vi.spyOn(drafts, 'readDocumentDraft').mockResolvedValue(null) })
  afterEach(() => { vi.restoreAllMocks(); vi.clearAllMocks() })
  it('换账号不恢复上一成员草稿，切回原账号仍能恢复', async () => {
    const session = useSessionStore(); session.$patch({ account: { member: { id: 'A' } } } as never)
    const data = useDataStore(); data.docs.d = { ...Object.values(DOCS)[0]!, id: 'd', rev: 1, content: content('基线') }
    data.setDocEdit('d', { content: content('A 的私人草稿') })
    session.$patch({ account: { member: { id: 'B' } } } as never)
    expect(data.docOf('d').text).toBe('基线')
    expect(await data.restoreDocumentDraft('d')).toBe(false)
    session.$patch({ account: { member: { id: 'A' } } } as never)
    expect(data.docOf('d').text).toBe('A 的私人草稿')
  })
  it('未知归属旧草稿保留原处，不自动赋给当前成员', async () => {
    localStorage.setItem('wao-docs-v1', JSON.stringify({ d: { text: '未知所有者' } }))
    const data = useDataStore()
    expect(data.docEdits.d).toBeUndefined()
    expect(localStorage.getItem('wao-docs-v1')).toContain('未知所有者')
  })
  it('账号切换后迟到的本机写入失败不能污染新账号保存状态', async () => {
    const session = useSessionStore(); session.$patch({ account: { member: { id: 'A' } } } as never)
    const data = useDataStore(); data.docs.d = { ...Object.values(DOCS)[0]!, id: 'd', rev: 1, content: content('基线') }
    let rejectWrite!: (error: Error) => void
    vi.mocked(drafts.writeDocumentDraft).mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectWrite = reject }))
    data.setDocEdit('d', { content: content('A 私人草稿') })
    session.$patch({ account: { member: { id: 'B' } } } as never)
    rejectWrite(new Error('旧账号存储失败'))
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    expect(data.docDraftErrors.d).toBeUndefined()
    expect(data.docOf('d').text).toBe('基线')
  })
  it('读取冲突版本期间切换账号，迟到结果不能回填新账号', async () => {
    const session = useSessionStore(); session.$patch({ account: { member: { id: 'A' } } } as never)
    const data = useDataStore(); const base = { ...Object.values(DOCS)[0]!, id: 'd', rev: 1, content: content('基线') }; data.docs.d = base
    data.setDocEdit('d', { content: content('A 私人草稿') })
    vi.mocked(apiPatchDoc).mockRejectedValueOnce(new Error('其他窗口已更新'))
    let finishRead!: (doc: typeof base) => void
    vi.mocked(fetchDoc).mockImplementationOnce(() => new Promise(resolve => { finishRead = resolve }))
    const saving = data.persistDocNow('d')
    await vi.waitFor(() => expect(fetchDoc).toHaveBeenCalled())
    session.$patch({ account: { member: { id: 'B' } } } as never)
    finishRead({ ...base, content: content('A 的冲突版本') })
    expect(await saving).toBe(false)
    expect(data.docConflicts.d).toBeUndefined()
  })
})
