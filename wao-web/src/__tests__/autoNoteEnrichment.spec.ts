import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { startAutoNoteEnrichment, noteEnrichmentStatus, noteEnrichmentProgress, discardAutoNoteEnrichment, retryAutoNoteEnrichment, resumeAutoNoteEnrichment } from '@/utils/autoNoteEnrichment'
import { fetchDoc, fetchNoteTags, apiPatchDoc } from '@/api/board'
import { startArticleImage, fetchArticleImage } from '@/api/articleImages'
import { waitForPolish } from '@/api/polishJobs'
import { saveNotePolishVersion } from '@/api/notePolishVersions'
import { fetchPreference } from '@/api/favorites'

vi.mock('@/api/board', () => ({ fetchDoc: vi.fn(), fetchNoteTags: vi.fn().mockResolvedValue([]), apiPatchDoc: vi.fn() }))
vi.mock('@/api/articleImages', () => ({ startArticleImage: vi.fn(), fetchArticleImage: vi.fn() }))
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn().mockResolvedValue('') }))
vi.mock('@/api/polishJobs', () => ({ newPolishRequestId: () => '0123456789abcdef0123456789abcdef', waitForPolish: vi.fn() }))
vi.mock('@/api/notePolishVersions', () => ({ fetchNotePolishVersions: vi.fn().mockResolvedValue([]), saveNotePolishVersion: vi.fn() }))
vi.mock('@/stores/data', () => ({ useDataStore: () => ({ applyRemoteDoc: vi.fn(), docPending: vi.fn().mockReturnValue(false) }) }))

const content = { version: 1 as const, document: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '今天复盘打印流程' }] }] }, markdown: '今天复盘打印流程' }
const doc = (rev: number, title = '今天复盘打印流程') => ({ id: 'n1', rev, title, blocks: [], content })

describe('小记保存后自动生成', () => {
  beforeEach(() => {
    vi.useRealTimers()
    localStorage.clear()
    delete noteEnrichmentStatus.n1
    vi.clearAllMocks()
    vi.mocked(fetchDoc).mockReset()
    vi.mocked(apiPatchDoc).mockReset()
    vi.mocked(fetchNoteTags).mockReset().mockResolvedValue([])
    vi.mocked(waitForPolish).mockReset()
    vi.mocked(startArticleImage).mockReset()
    vi.mocked(fetchArticleImage).mockReset()
    vi.mocked(saveNotePolishVersion).mockReset()
  })

  it('先保存正文，再以版本条件写入标题和总览图', async () => {
    vi.mocked(fetchPreference).mockResolvedValueOnce('gpt-6-sol').mockResolvedValueOnce('标题偏好')
      .mockResolvedValueOnce('图片偏好').mockResolvedValueOnce('正文润色偏好')
    vi.mocked(fetchDoc).mockResolvedValueOnce(doc(1) as never)
      .mockResolvedValueOnce(doc(1) as never)
      .mockResolvedValueOnce(doc(2, '打印流程复盘') as never)
      .mockResolvedValueOnce(doc(3, '打印流程复盘') as never)
    vi.mocked(apiPatchDoc).mockResolvedValueOnce(doc(2, '打印流程复盘') as never)
      .mockResolvedValueOnce(doc(3, '打印流程复盘') as never)
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'pj1', status: 'SUCCEEDED', message: '', polished: '打印流程复盘' })
    vi.mocked(startArticleImage).mockResolvedValue({ id: 'img-job', status: 'QUEUED', message: '', imageUrl: null })
    vi.mocked(fetchArticleImage).mockResolvedValue({ id: 'img-job', status: 'SUCCEEDED', message: '', imageUrl: '/api/images/img1/raw' })
    vi.mocked(saveNotePolishVersion).mockResolvedValue({ docId: 'n1', sourceMarkdown: content.markdown, polishedMarkdown: '打印流程复盘' })

    startAutoNoteEnrichment('me', 'n1', 1)
    await flushPromises()

    expect(waitForPolish).toHaveBeenCalledWith('今天复盘打印流程', 'NOTE_TITLE', expect.objectContaining({ model: 'gpt-6-sol', titlePrompt: '标题偏好' }))
    expect(startArticleImage).toHaveBeenCalledWith('今天复盘打印流程', '图片偏好', 'gpt-6-sol', '0123456789abcdef0123456789abcdef')
    expect(apiPatchDoc).toHaveBeenNthCalledWith(1, 'n1', { title: '打印流程复盘', expectedRev: 1 })
    expect(apiPatchDoc).toHaveBeenNthCalledWith(2, 'n1', expect.objectContaining({
      expectedRev: 2,
      content: expect.objectContaining({ document: expect.objectContaining({ content: expect.arrayContaining([
        expect.objectContaining({ type: 'image', attrs: expect.objectContaining({ src: '/api/images/img1/raw' }) }),
      ]) }) }),
    }))
    expect(waitForPolish).toHaveBeenCalledWith('今天复盘打印流程', 'NOTE_BODY', expect.objectContaining({ model: 'gpt-6-sol', notePolishPrompt: '正文润色偏好' }))
    expect(saveNotePolishVersion).toHaveBeenCalledWith('n1', content.markdown, '打印流程复盘')
    expect(noteEnrichmentStatus.n1).toBe('标题、配图和润色已完成')
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

  it('选择保留原文时只生成标题和配图', async () => {
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    vi.mocked(apiPatchDoc).mockResolvedValueOnce(doc(2, '标题') as never)
      .mockResolvedValueOnce(doc(3, '标题') as never)
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'pj1', status: 'SUCCEEDED', message: '', polished: '标题' })
    vi.mocked(startArticleImage).mockResolvedValue({ id: 'img-job', status: 'QUEUED', message: '', imageUrl: null })
    vi.mocked(fetchArticleImage).mockResolvedValue({ id: 'img-job', status: 'SUCCEEDED', message: '', imageUrl: '/api/images/img1/raw' })
    vi.mocked(fetchDoc).mockResolvedValueOnce(doc(1) as never)
      .mockResolvedValueOnce(doc(1) as never)
      .mockResolvedValueOnce(doc(2, '标题') as never)
      .mockResolvedValueOnce(doc(2, '标题') as never)
    startAutoNoteEnrichment('me', 'n1', 1, { polishEnabled: false })
    await flushPromises()

    expect(waitForPolish).toHaveBeenCalledTimes(1)
    expect(noteEnrichmentProgress.n1?.polish).toBe('skipped')
    expect(noteEnrichmentStatus.n1).toContain('保留原文')
  })

  it('并发恢复导致请求占用时用同一请求号接续配图任务', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    vi.mocked(startArticleImage)
      .mockRejectedValueOnce(new Error('数据刚刚被其他请求占用，请刷新后重试'))
      .mockResolvedValueOnce({ id: 'img-job', status: 'QUEUED', message: '', imageUrl: null })
    vi.mocked(fetchArticleImage).mockResolvedValue({ id: 'img-job', status: 'SUCCEEDED', message: '', imageUrl: '/api/images/img1/raw' })
    vi.mocked(apiPatchDoc).mockResolvedValue(doc(2) as never)

    startAutoNoteEnrichment('me', 'n1', 1, { titleEnabled: false, polishEnabled: false })
    await flushPromises()
    expect(startArticleImage).toHaveBeenCalledTimes(1)
    expect(noteEnrichmentStatus.n1).toContain('其他页面接续')

    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()

    expect(startArticleImage).toHaveBeenCalledTimes(2)
    expect(startArticleImage).toHaveBeenNthCalledWith(1, '今天复盘打印流程', expect.any(String), 'gpt-5.6-sol', '0123456789abcdef0123456789abcdef')
    expect(startArticleImage).toHaveBeenNthCalledWith(2, '今天复盘打印流程', expect.any(String), 'gpt-5.6-sol', '0123456789abcdef0123456789abcdef')
    expect(noteEnrichmentStatus.n1).toContain('已按选择保留原文')
    vi.useRealTimers()
  })

  it('标题阶段失败后仍继续配图和润色，并把失败阶段留给重试', async () => {
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    vi.mocked(waitForPolish).mockRejectedValueOnce(new Error('Codex 执行失败（退出码 1）'))
      .mockResolvedValueOnce({ id: 'pj2', status: 'SUCCEEDED', message: '', polished: '整理后的正文' })
    vi.mocked(saveNotePolishVersion).mockResolvedValue({ docId: 'n1', sourceMarkdown: content.markdown, polishedMarkdown: '整理后的正文' })
    startAutoNoteEnrichment('me', 'n1', 1, { imageEnabled: false })
    await flushPromises()

    expect(waitForPolish).toHaveBeenCalledTimes(2)
    expect(waitForPolish).toHaveBeenNthCalledWith(1, content.markdown, 'NOTE_TITLE', expect.any(Object))
    expect(waitForPolish).toHaveBeenNthCalledWith(2, content.markdown, 'NOTE_BODY', expect.any(Object))
    expect(noteEnrichmentProgress.n1).toEqual(expect.objectContaining({ title: 'failed', polish: 'done', image: 'skipped' }))
    expect(noteEnrichmentStatus.n1).toContain('标题：Codex 执行失败')
  })

  it('未勾选自动配图时跳过图片任务，润色仍执行且刷新后保留跳过状态', async () => {
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'pj1', status: 'SUCCEEDED', message: '', polished: '整理后的正文' })
    vi.mocked(saveNotePolishVersion).mockResolvedValue({ docId: 'n1', sourceMarkdown: content.markdown, polishedMarkdown: '整理后的正文' })

    startAutoNoteEnrichment('me', 'n1', 1, { title: true, imageEnabled: false })
    await flushPromises()

    expect(startArticleImage).not.toHaveBeenCalled()
    expect(waitForPolish).toHaveBeenCalledWith(content.markdown, 'NOTE_BODY', expect.any(Object))
    expect(noteEnrichmentProgress.n1).toEqual(expect.objectContaining({ title: 'done', image: 'skipped', polish: 'done' }))
    expect(localStorage.getItem('wao-note-auto-enrichment-v1:me:n1')).toBeNull()
    delete noteEnrichmentProgress.n1
    resumeAutoNoteEnrichment('me')
    expect(noteEnrichmentProgress.n1).toEqual({ title: 'done', image: 'skipped', polish: 'done', tags: 'skipped' })
  })

  it('快速记录同时关闭配图和润色时不启动两种任务', async () => {
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    startAutoNoteEnrichment('me', 'n1', 1, { title: true, imageEnabled: false, polishEnabled: false })
    await flushPromises()

    expect(startArticleImage).not.toHaveBeenCalled()
    expect(waitForPolish).not.toHaveBeenCalled()
    expect(noteEnrichmentProgress.n1).toEqual(expect.objectContaining({ image: 'skipped', polish: 'skipped' }))
  })

  it('自动标签任务只写入仍存在的旧标签并保留原有标签', async () => {
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    vi.mocked(fetchNoteTags).mockResolvedValue(['创作', '计划'])
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'pj1', status: 'SUCCEEDED', message: '', polished: '创作\n客户端伪造\n计划' })
    vi.mocked(apiPatchDoc).mockResolvedValue(doc(2) as never)
    startAutoNoteEnrichment('me', 'n1', 1, {
      titleEnabled: false, imageEnabled: false, polishEnabled: false,
      tagsEnabled: true, allowedTags: ['创作', '计划'],
    })
    await flushPromises()
    expect(waitForPolish).toHaveBeenCalledWith(content.markdown, 'NOTE_TAGS', expect.objectContaining({ allowedTags: ['创作', '计划'] }))
    expect(apiPatchDoc).toHaveBeenCalledWith('n1', expect.objectContaining({ tags: ['创作', '计划'], expectedRev: 1 }))
    expect(noteEnrichmentProgress.n1?.tags).toBe('done')
  })

  it('关闭自动标题时仍可单独润色，刷新后标题保持已跳过', async () => {
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'pj1', status: 'SUCCEEDED', message: '', polished: '润色后正文' })
    vi.mocked(saveNotePolishVersion).mockResolvedValue({ docId: 'n1', sourceMarkdown: content.markdown, polishedMarkdown: '润色后正文' })
    startAutoNoteEnrichment('me', 'n1', 1, { titleEnabled: false, imageEnabled: false })
    await flushPromises()

    expect(waitForPolish).toHaveBeenCalledTimes(1)
    expect(waitForPolish).toHaveBeenCalledWith(content.markdown, 'NOTE_BODY', expect.any(Object))
    expect(noteEnrichmentProgress).toHaveProperty('n1.title', 'skipped')
    delete noteEnrichmentProgress.n1
    resumeAutoNoteEnrichment('me')
    expect(noteEnrichmentProgress).toHaveProperty('n1.title', 'skipped')
  })

  it('删除小记时丢弃可恢复任务和卡片状态', async () => {
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    vi.mocked(waitForPolish).mockRejectedValue(new Error('系统繁忙'))
    startAutoNoteEnrichment('me', 'n1', 1)
    await flushPromises()
    expect(noteEnrichmentProgress.n1?.title).toBe('failed')

    discardAutoNoteEnrichment('me', 'n1')

    expect(localStorage.getItem('wao-note-auto-enrichment-v1:me:n1')).toBeNull()
    expect(noteEnrichmentProgress.n1).toBeUndefined()
    expect(noteEnrichmentStatus.n1).toBeUndefined()
  })

  it('用户修改正文后重试读取新版本，不用旧版本覆盖', async () => {
    vi.mocked(fetchDoc).mockResolvedValueOnce(doc(1) as never)
      .mockResolvedValueOnce(doc(2) as never)
      .mockResolvedValueOnce(doc(2) as never)
      .mockResolvedValueOnce(doc(2) as never)
      .mockResolvedValueOnce(doc(2) as never)
    vi.mocked(waitForPolish).mockRejectedValueOnce(new Error('系统繁忙'))
      .mockResolvedValue({ id: 'pj2', status: 'SUCCEEDED', message: '', polished: '新标题' })
    vi.mocked(apiPatchDoc).mockResolvedValue(doc(3, '新标题') as never)
    vi.mocked(startArticleImage).mockRejectedValue(new Error('配图暂不可用'))
    startAutoNoteEnrichment('me', 'n1', 1)
    await flushPromises()

    await retryAutoNoteEnrichment('me', 'n1')
    await flushPromises()

    expect(apiPatchDoc).toHaveBeenCalledWith('n1', { title: '新标题', expectedRev: 2 })
  })
})
