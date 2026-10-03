import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { startAutoNoteEnrichment, startNoteEnrichmentStage, activateNoteWorkflowMember, noteEnrichmentStatus, noteEnrichmentProgress, discardAutoNoteEnrichment, retryAutoNoteEnrichment, resumeAutoNoteEnrichment } from '@/utils/autoNoteEnrichment'
import { fetchDoc, fetchNoteTags, apiPatchDoc } from '@/api/board'
import { startArticleImage, fetchArticleImage } from '@/api/articleImages'
import { waitForPolish } from '@/api/polishJobs'
import { saveNotePolishVersion } from '@/api/notePolishVersions'
import { fetchPreference } from '@/api/favorites'
import { readNoteDraft, writeNoteDraft } from '@/utils/noteDraftStorage'

vi.mock('@/api/board', () => ({ fetchDoc: vi.fn(), fetchNoteTags: vi.fn().mockResolvedValue([]), apiPatchDoc: vi.fn() }))
vi.mock('@/api/articleImages', () => ({ startArticleImage: vi.fn(), fetchArticleImage: vi.fn() }))
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn().mockResolvedValue('') }))
vi.mock('@/api/polishJobs', () => ({ newPolishRequestId: () => '0123456789abcdef0123456789abcdef', waitForPolish: vi.fn() }))
vi.mock('@/api/notePolishVersions', () => ({ fetchNotePolishVersions: vi.fn().mockResolvedValue([]), saveNotePolishVersion: vi.fn() }))
vi.mock('@/stores/data', () => ({ useDataStore: () => ({ applyRemoteDoc: vi.fn(), docPending: vi.fn().mockReturnValue(false) }) }))

const content = { version: 1 as const, document: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '今天复盘打印流程' }] }] }, markdown: '今天复盘打印流程' }
const doc = (rev: number, title = '今天复盘打印流程') => ({ id: 'n1', rev, title, blocks: [], content })

describe('服务端三阶段与浏览器标签边界', () => {
  beforeEach(() => {
    vi.useRealTimers()
    localStorage.clear()
    sessionStorage.clear()
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

  it('新保存和历史续跑不再由浏览器调用标题正文或配图', async () => {
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    startAutoNoteEnrichment('me', 'n1', 1)
    await flushPromises()
    expect(waitForPolish).not.toHaveBeenCalled()
    expect(startArticleImage).not.toHaveBeenCalled()
    expect(apiPatchDoc).not.toHaveBeenCalled()
    localStorage.setItem('wao-note-auto-enrichment-v1:me:n1', JSON.stringify({ memberId: 'me', docId: 'n1', rev: 1, titleRequestId: 'old', titleDone: false, imageDone: false, polishDone: false }))
    resumeAutoNoteEnrichment('me'); await flushPromises()
    expect(waitForPolish).not.toHaveBeenCalled()
    expect(apiPatchDoc).not.toHaveBeenCalled()
    expect(saveNotePolishVersion).not.toHaveBeenCalled()
  })
  it.each(['title','image','polish'] as const)('旧浏览器单项 %s 入口不会绕过后端', async stage => {
    startNoteEnrichmentStage('me','n1',1,stage); await flushPromises()
    expect(waitForPolish).not.toHaveBeenCalled(); expect(startArticleImage).not.toHaveBeenCalled()
    expect(noteEnrichmentStatus.n1).toContain('后台处理')
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

  it('后台只更新标题时标签沿用最新版本写入且不覆盖标题', async () => {
    vi.mocked(fetchDoc).mockResolvedValueOnce({ ...doc(1), tags: ['创作'] } as never)
      .mockResolvedValue({ ...doc(2, '后台生成的新标题'), tags: ['创作'] } as never)
    vi.mocked(fetchNoteTags).mockResolvedValue(['创作', '计划'])
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'tags', status: 'SUCCEEDED', message: '', polished: '计划' })
    vi.mocked(apiPatchDoc).mockResolvedValue({ ...doc(3, '后台生成的新标题'), tags: ['创作', '计划'] } as never)
    startAutoNoteEnrichment('me', 'n1', 1, {
      titleEnabled: false, imageEnabled: false, polishEnabled: false,
      tagsEnabled: true, allowedTags: ['计划'],
    })
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenCalledWith('n1', { tags: ['创作', '计划'], expectedRev: 2 })
    expect(noteEnrichmentProgress.n1?.tags).toBe('done')
  })

  it.each(['正文', '标签'] as const)('标签返回前%s已改变时保留用户修改', async changed => {
    vi.mocked(fetchDoc).mockResolvedValueOnce(doc(1) as never).mockResolvedValue({
      ...doc(2, '新标题'),
      ...(changed === '正文' ? { content: { ...content, markdown: '用户已保存的新正文' } } : { tags: ['用户标签'] }),
    } as never)
    vi.mocked(fetchNoteTags).mockResolvedValue(['计划'])
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'tags', status: 'SUCCEEDED', message: '', polished: '计划' })
    startAutoNoteEnrichment('me', 'n1', 1, {
      titleEnabled: false, imageEnabled: false, polishEnabled: false, tagsEnabled: true, allowedTags: ['计划'],
    })
    await flushPromises()
    expect(apiPatchDoc).not.toHaveBeenCalled()
    expect(noteEnrichmentProgress.n1?.tags).toBe('failed')
  })

  it('等待可用标签列表时开始输入仍阻止迟到写入', async () => {
    let finishTags!: (tags: string[]) => void
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    vi.mocked(fetchNoteTags).mockImplementation(() => new Promise(resolve => { finishTags = resolve }))
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'tags', status: 'SUCCEEDED', message: '', polished: '计划' })
    startAutoNoteEnrichment('me', 'n1', 1, {
      titleEnabled: false, imageEnabled: false, polishEnabled: false, tagsEnabled: true, allowedTags: ['计划'],
    })
    await flushPromises()
    const draft = JSON.stringify({ id: 'n1', text: '刚开始输入的内容' })
    writeNoteDraft('wao-note-edit-v1:me:n1', draft)
    finishTags(['计划'])
    await flushPromises()
    expect(apiPatchDoc).not.toHaveBeenCalled()
    expect(readNoteDraft('wao-note-edit-v1:me:n1')).toBe(draft)
    expect(noteEnrichmentProgress.n1?.tags).toBe('failed')
  })

  it.each([
    ['标签：请求内容已改变，请创建新的润色任务', '0123456789abcdef0123456789abcdef'],
    ['标签：网络连接中断，请重试', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'],
  ])('版本已被其他阶段推进后重试标签：%s', async (error, expectedRequestId) => {
    const docId = 'n-tag-request-retry'
    delete noteEnrichmentProgress[docId]
    localStorage.setItem(`wao-note-auto-enrichment-v1:me:${docId}`, JSON.stringify({
      memberId: 'me', docId, rev: 2, titleRequestId: 'old-title', titleDone: true,
      imageDone: true, polishDone: true, tagsDone: false, tagsEnabled: true,
      allowedTags: ['计划'], tagRequestId: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', error,
    }))
    vi.mocked(fetchDoc).mockResolvedValue({ ...doc(2), tags: ['原有标签'] } as never)
    vi.mocked(fetchNoteTags).mockResolvedValue(['计划', '原有标签'])
    vi.mocked(waitForPolish).mockResolvedValue({ id: 'pj-retry', status: 'SUCCEEDED', message: '', polished: '计划' })
    vi.mocked(apiPatchDoc).mockResolvedValue(doc(3) as never)

    await retryAutoNoteEnrichment('me', docId)
    await flushPromises()

    expect(waitForPolish).toHaveBeenCalledTimes(1)
    expect(waitForPolish).toHaveBeenCalledWith(content.markdown, 'NOTE_TAGS', expect.objectContaining({ requestId: expectedRequestId }))
    expect(apiPatchDoc).toHaveBeenCalledWith(docId, { tags: ['原有标签', '计划'], expectedRev: 2 })
    expect(startArticleImage).not.toHaveBeenCalled()
    expect(noteEnrichmentProgress[docId]?.tags).toBe('done')
    expect(localStorage.getItem(`wao-note-auto-enrichment-v1:me:${docId}`)).toBeNull()
  })

})
describe('AI 迟到结果与页签草稿', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    vi.mocked(fetchPreference).mockResolvedValue('')
    vi.mocked(fetchDoc).mockResolvedValue(doc(1) as never)
    vi.mocked(apiPatchDoc).mockResolvedValue(doc(2) as never)
    vi.mocked(fetchNoteTags).mockResolvedValue(['思考'])
    delete noteEnrichmentStatus.n1
  })

  it.each(['tags'] as const)('%s 完成前继续输入时，不写回也不清除当前页签草稿', async stage => {
    let complete!: () => void
    vi.mocked(waitForPolish).mockImplementation(() => new Promise(resolve => {
      complete = () => resolve({ id: 'late', status: 'SUCCEEDED', message: '', polished: stage === 'tags' ? '思考' : 'AI 结果' })
    }))
    vi.mocked(startArticleImage).mockResolvedValue({ id: 'late-image', status: 'QUEUED', message: '', imageUrl: null })
    vi.mocked(fetchArticleImage).mockImplementation(() => new Promise(resolve => {
      complete = () => resolve({ id: 'late-image', status: 'SUCCEEDED', message: '', imageUrl: '/api/images/late/raw' })
    }))
    startAutoNoteEnrichment('me', 'n1', 1, {
      titleEnabled: false, imageEnabled: false,
      polishEnabled: false, tagsEnabled: true, allowedTags: ['思考'],
    })
    await flushPromises()
    expect(complete).toBeTypeOf('function')
    const key = 'wao-note-edit-v1:me:n1'
    const draft = JSON.stringify({ id: 'n1', text: '仍在输入的新正文', generatedTitle: '手写标题', tags: ['思考'] })
    writeNoteDraft(key, draft)
    expect(localStorage.getItem(key)).toBeNull()
    complete()
    await flushPromises()
    expect(apiPatchDoc).not.toHaveBeenCalled()
    expect(saveNotePolishVersion).not.toHaveBeenCalled()
    expect(readNoteDraft(key)).toBe(draft)
    expect(localStorage.getItem('wao-note-auto-enrichment-v1:me:n1')).not.toBeNull()
    expect(noteEnrichmentStatus.n1).toMatch(/未保存|已被修改|正文已变化/)
    discardAutoNoteEnrichment('me', 'n1')
  })
  it('无法读取草稿时保留任务和输入，不把存储异常当作允许回写', async () => {
    const original = Storage.prototype.getItem
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(function(this: Storage, key: string) {
      if (key.startsWith('wao-note-edit-v1:')) throw new Error('storage unavailable')
      return original.call(this, key)
    })
    startAutoNoteEnrichment('me', 'n1', 1, { imageEnabled: false, polishEnabled: false })
    await flushPromises()
    expect(waitForPolish).not.toHaveBeenCalled()
    expect(apiPatchDoc).not.toHaveBeenCalled()
    expect(noteEnrichmentStatus.n1).toContain('未保存编辑')
    expect(localStorage.getItem('wao-note-auto-enrichment-v1:me:n1')).not.toBeNull()
    discardAutoNoteEnrichment('me', 'n1')
  })

})
