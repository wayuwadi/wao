import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { reactive } from 'vue'
import { readDocumentDraft, removeDocumentDraft, writeDocumentDraft, type DocumentDraft } from '@/utils/documentDrafts'

describe('document drafts in IndexedDB', () => {
  beforeEach(() => vi.stubGlobal('indexedDB', new IDBFactory()))
  afterEach(() => vi.unstubAllGlobals())

  function draft(memberId = 'local-test-member'): DocumentDraft {
    return {
      id: 'local-test-document', memberId, title: '恢复中的文档', baseRev: 7, updatedAt: 100,
      content: {
        version: 1,
        markdown: '离线修改应保留',
        document: { type: 'doc', content: [{ type: 'paragraph', attrs: { uid: 'paragraph-1' },
          content: [{ type: 'text', text: '离线修改应保留', marks: [{ type: 'bold' }] }] }] },
      },
    }
  }

  it('persists nested Vue reactive content and restores the complete document', async () => {
    const restored = reactive(draft())
    await expect(writeDocumentDraft(restored)).resolves.toBeUndefined()
    expect(await readDocumentDraft(restored.memberId, restored.id)).toEqual(draft())
  })

  it('keeps different members drafts separate and removes only the requested draft', async () => {
    const first = draft()
    const second = { ...draft('another-member'), title: '另一位成员的草稿' }
    await writeDocumentDraft(first)
    await writeDocumentDraft(second)
    await removeDocumentDraft(first.memberId, first.id)
    expect(await readDocumentDraft(first.memberId, first.id)).toBeNull()
    expect(await readDocumentDraft(second.memberId, second.id)).toEqual(second)
  })
})
