import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import * as drafts from '@/utils/documentDrafts'
import { DOCS } from '@/data/fixtures'
const content = (markdown: string) => ({ version: 1 as const, document: null, markdown })
describe('文档兼容草稿成员隔离', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.spyOn(drafts, 'writeDocumentDraft').mockResolvedValue(); vi.spyOn(drafts, 'readDocumentDraft').mockResolvedValue(null) })
  afterEach(() => vi.restoreAllMocks())
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
})
