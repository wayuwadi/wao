import { describe, expect, it, vi } from 'vitest'
import { createNoteTextCache, focusNoteSummary } from '@/utils/noteListContent'
import { coalescedDraftWriter } from '@/utils/coalescedDraftWriter'

describe('小记列表缓存与紧凑摘要', () => {
  it('缓存复用正文提取，草稿修改、删除和账号切换使缓存失效', () => {
    const cache = createNoteTextCache()
    const parse = vi.spyOn(DOMParser.prototype, 'parseFromString')
    const content = { version: 1 as const, markdown: '旧**文字**', document: null }
    expect(cache.text('one', content)).toContain('旧文字')
    const calls = parse.mock.calls.length
    cache.text('one', { ...content })
    expect(parse).toHaveBeenCalledTimes(calls)
    expect(cache.text('one', { ...content, markdown: '新文字' })).toContain('新文字')
    cache.retain(new Set())
    const after = parse.mock.calls.length
    cache.text('one', content)
    expect(parse.mock.calls.length).toBeGreaterThan(after)
    cache.clear()
    parse.mockRestore()
  })
  it('跨日专注保留两天日期，不把普通笔记误判为自动记录', () => {
    const markdown = '专注时长：25 分 0 秒\n开始：2026-10-29 23:50:00\n结束：2026-10-30 00:15:00\n\n用户正文'
    expect(focusNoteSummary('番茄钟 · 阅读', ['番茄钟'], markdown)).toMatchObject({ body: '用户正文', time: '2026-10-29 23:50:00–2026-10-30 00:15:00' })
    expect(focusNoteSummary('自己的笔记', ['番茄钟'], markdown)).toBeNull()
    expect(focusNoteSummary('番茄钟 · 阅读', [], markdown)).toBeNull()
  })
  it('同一次编辑事务只写入最终草稿，离开页面同步落盘，保存后取消排队写入', async () => {
    let value = '文本回调'
    const write = vi.fn(() => value)
    const writer = coalescedDraftWriter(write)
    writer.schedule(); value = '完整结构回调'; writer.schedule()
    await Promise.resolve()
    expect(write).toHaveReturnedWith('完整结构回调')
    expect(write).toHaveBeenCalledTimes(1)
    writer.schedule(); writer.flush()
    expect(write).toHaveBeenCalledTimes(2)
    await Promise.resolve()
    expect(write).toHaveBeenCalledTimes(2)
    writer.schedule(); writer.cancel()
    await Promise.resolve()
    expect(write).toHaveBeenCalledTimes(2)
  })
})
