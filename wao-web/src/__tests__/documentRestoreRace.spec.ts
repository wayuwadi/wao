import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import * as drafts from '@/utils/documentDrafts'
import { DOCS } from '@/data/fixtures'
import type { DocumentDraft } from '@/utils/documentDrafts'
const content = (markdown: string) => ({ version: 1 as const, document: null, markdown })
describe('文档异步恢复保护', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.spyOn(drafts, 'writeDocumentDraft').mockResolvedValue() })
  afterEach(() => vi.restoreAllMocks())
  it('恢复期间的新输入不能被迟到草稿覆盖', async () => {
    const data = useDataStore(); data.docs.d = { ...Object.values(DOCS)[0]!, id: 'd', rev: 1, content: content('基线') }
    let done!: (draft: DocumentDraft) => void
    vi.spyOn(drafts, 'readDocumentDraft').mockImplementation(() => new Promise(resolve => { done = resolve }))
    const pending = data.restoreDocumentDraft('d')
    data.setDocEdit('d', { content: content('打开后新输入') })
    done({ id: 'd', memberId: 'anonymous', title: '旧稿', content: content('旧稿'), baseRev: 1, updatedAt: 1 })
    expect(await pending).toBe(false); expect(data.docOf('d').text).toBe('打开后新输入')
  })
  it('恢复期间切换成员，不能把上一成员草稿应用给新成员', async () => {
    const data = useDataStore(); data.docs.d = { ...Object.values(DOCS)[0]!, id: 'd', rev: 1, content: content('基线') }
    let done!: (draft: DocumentDraft) => void
    vi.spyOn(drafts, 'readDocumentDraft').mockImplementation(() => new Promise(resolve => { done = resolve }))
    const pending = data.restoreDocumentDraft('d')
    useSessionStore().$patch({ account: { member: { id: 'new-member' } } } as never)
    done({ id: 'd', memberId: 'anonymous', title: '旧稿', content: content('旧稿'), baseRev: 1, updatedAt: 1 })
    expect(await pending).toBe(false); expect(data.docs.d).toBeUndefined()
  })
})
