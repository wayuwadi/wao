import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { DOCS } from '@/data/fixtures'
import { apiPatchDoc } from '@/api/board'

vi.mock('@/api/board', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/api/board')>(),
  apiPatchDoc: vi.fn(),
}))

describe('恢复未同步的文档草稿', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('indexedDB', new IDBFactory())
    setActivePinia(createPinia())
    vi.mocked(apiPatchDoc).mockReset()
  })
  afterEach(() => vi.unstubAllGlobals())

  function restoreSetup(baseRev = 3) {
    localStorage.setItem('wao-docs-v1:anonymous', JSON.stringify({
      'recovery-doc': { title: '恢复验收', content: { version: 1, document: null, markdown: '尚未同步的草稿' }, baseRev },
    }))
    const data = useDataStore()
    data.docs['recovery-doc'] = { ...Object.values(DOCS)[0]!, id: 'recovery-doc', title: '恢复验收', rev: 3,
      content: { version: 1, document: null, markdown: '服务器已有正文' } }
    return data
  }

  it('刷新恢复后标记待保存，连接未就绪时保留重试入口', async () => {
    const data = restoreSetup()
    await data.restoreDocumentDraft('recovery-doc')
    expect(data.docPending('recovery-doc')).toBe(true)
    expect(data.saveState('doc:recovery-doc').failed).toBe(true)
    expect(data.docDraftErrors['recovery-doc']).toBe(false)
    expect(data.docEdits['recovery-doc']?.content?.markdown).toBe('尚未同步的草稿')
  })

  it('兼容草稿同样检查服务器版本，不能绕过冲突处理', async () => {
    const data = restoreSetup(2)
    await data.restoreDocumentDraft('recovery-doc')
    expect(data.docConflicts['recovery-doc']?.rev).toBe(3)
    expect(data.docPending('recovery-doc')).toBe(true)
    expect(data.docs['recovery-doc']?.content?.markdown).toBe('服务器已有正文')
  })

  it('保存被拒绝时保留具体原因与草稿，重试成功后才清除两者', async () => {
    const data = restoreSetup()
    await data.restoreDocumentDraft('recovery-doc')
    const pending = JSON.parse(JSON.stringify(data.docEdits['recovery-doc']))
    vi.mocked(apiPatchDoc).mockRejectedValueOnce(new Error('有序列表编号样式不受支持'))

    expect(await data.persistDocNow('recovery-doc')).toBe(false)
    expect(data.saveState('doc:recovery-doc')).toMatchObject({
      failed: true, saving: false, savedAt: null, errorMessage: '有序列表编号样式不受支持',
    })
    expect(data.docEdits['recovery-doc']).toEqual(pending)
    expect(localStorage.getItem('wao-docs-v1:anonymous')).toContain('尚未同步的草稿')
    expect(data.docs['recovery-doc']?.content?.markdown).toBe('服务器已有正文')

    vi.mocked(apiPatchDoc).mockResolvedValueOnce({
      ...data.docs['recovery-doc']!, content: pending.content, rev: 4,
    })
    expect(await data.persistDocNow('recovery-doc')).toBe(true)
    expect(apiPatchDoc).toHaveBeenLastCalledWith('recovery-doc', expect.objectContaining({
      content: pending.content, expectedRev: 3,
    }))
    expect(data.saveState('doc:recovery-doc')).toMatchObject({ failed: false, errorMessage: '' })
    expect(data.saveState('doc:recovery-doc').savedAt).not.toBeNull()
    expect(data.docEdits['recovery-doc']).toBeUndefined()
    expect(localStorage.getItem('wao-docs-v1:anonymous')).not.toContain('recovery-doc')
    expect(data.docs['recovery-doc']?.content?.markdown).toBe('尚未同步的草稿')
  })
})
