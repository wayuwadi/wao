import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { startAutoNoteEnrichment, noteEnrichmentStatus } from '@/utils/autoNoteEnrichment'
import { fetchDoc, apiPatchDoc } from '@/api/board'
import { startArticleImage, fetchArticleImage } from '@/api/articleImages'
import { waitForPolish } from '@/api/polishJobs'

vi.mock('@/api/board', () => ({ fetchDoc: vi.fn(), apiPatchDoc: vi.fn() }))
vi.mock('@/api/articleImages', () => ({ startArticleImage: vi.fn(), fetchArticleImage: vi.fn() }))
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn().mockResolvedValue('') }))
vi.mock('@/api/polishJobs', () => ({ newPolishRequestId: () => '0123456789abcdef0123456789abcdef', waitForPolish: vi.fn() }))
vi.mock('@/stores/data', () => ({ useDataStore: () => ({ applyRemoteDoc: vi.fn(), docPending: vi.fn().mockReturnValue(false) }) }))

const content = { version: 1 as const, document: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '今天复盘打印流程' }] }] }, markdown: '今天复盘打印流程' }
const doc = (rev: number, title = '今天复盘打印流程') => ({ id: 'n1', rev, title, blocks: [], content })

describe('小记保存后自动生成', () => {
  beforeEach(() => {
    localStorage.clear()
    delete noteEnrichmentStatus.n1
    vi.clearAllMocks()
  })

  it('先保存正文，再以版本条件写入标题和总览图', async () => {
    vi.mocked(fetchDoc).mockResolvedValueOnce(doc(1) as never)
      .mockResolvedValueOnce(doc(1) as never)
      .mockResolvedValueOnce(doc(2, '打印流程复盘') as never)
    vi.mocked(apiPatchDoc).mockResolvedValueOnce(doc(2, '打印流程复盘') as never)
      .mockResolvedValueOnce(doc(3, '打印流程复盘') as never)
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'pj1', status: 'SUCCEEDED', message: '', polished: '打印流程复盘' })
    vi.mocked(startArticleImage).mockResolvedValue({ id: 'img-job', status: 'QUEUED', message: '', imageUrl: null })
    vi.mocked(fetchArticleImage).mockResolvedValue({ id: 'img-job', status: 'SUCCEEDED', message: '', imageUrl: '/api/images/img1/raw' })

    startAutoNoteEnrichment('me', 'n1', 1)
    await flushPromises()

    expect(waitForPolish).toHaveBeenCalledWith('今天复盘打印流程', 'NOTE_TITLE', expect.any(Object))
    expect(apiPatchDoc).toHaveBeenNthCalledWith(1, 'n1', { title: '打印流程复盘', expectedRev: 1 })
    expect(apiPatchDoc).toHaveBeenNthCalledWith(2, 'n1', expect.objectContaining({
      expectedRev: 2,
      content: expect.objectContaining({ document: expect.objectContaining({ content: expect.arrayContaining([
        expect.objectContaining({ type: 'image', attrs: expect.objectContaining({ src: '/api/images/img1/raw' }) }),
      ]) }) }),
    }))
    expect(noteEnrichmentStatus.n1).toBe('标题和配图已自动生成')
    expect(localStorage.getItem('wao-note-auto-enrichment-v1:me:n1')).toBeNull()
  })

  it('编辑版本变化时不会覆盖用户的新正文和原图', async () => {
    vi.mocked(fetchDoc).mockResolvedValueOnce(doc(1) as never)
      .mockResolvedValueOnce(doc(2, '用户改过的标题') as never)
      .mockResolvedValueOnce(doc(2, '用户改过的标题') as never)
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'pj1', status: 'SUCCEEDED', message: '', polished: 'AI 标题' })
    vi.mocked(startArticleImage).mockResolvedValue({ id: 'img-job', status: 'QUEUED', message: '', imageUrl: null })
    vi.mocked(fetchArticleImage).mockResolvedValue({ id: 'img-job', status: 'SUCCEEDED', message: '', imageUrl: '/api/images/img1/raw' })

    startAutoNoteEnrichment('me', 'n1', 1)
    await flushPromises()

    expect(apiPatchDoc).not.toHaveBeenCalled()
    expect(noteEnrichmentStatus.n1).toContain('已被修改')
  })
})
