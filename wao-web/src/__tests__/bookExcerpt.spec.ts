import { describe, expect, it } from 'vitest'
import { bookExcerptContent } from '@/utils/bookExcerpt'

describe('书摘与感想', () => {
  it('原文段落位于引用中，来源与感想在引用外', () => {
    expect(bookExcerptContent('第一行\r\n\r\n第二行', '书名 · 第 12 页', '我的想法')).toEqual([
      { type: 'blockquote', content: [
        { type: 'paragraph', content: [{ type: 'text', text: '第一行' }] },
        { type: 'paragraph' },
        { type: 'paragraph', content: [{ type: 'text', text: '第二行' }] },
      ] },
      { type: 'paragraph', content: [{ type: 'text', text: '来源：书名 · 第 12 页' }] },
      { type: 'paragraph', content: [{ type: 'text', text: '我的想法' }] },
    ])
  })
  it('HTML 与 Markdown 保持字面文字，不引入图片或链接节点', () => {
    const value = '<script>alert(1)</script>\n![图片](https://example.com/p.png)\n# 标题'
    const quote = bookExcerptContent(value)[0]!
    expect(quote.content?.map(p => p.content?.[0]?.text).join('\n')).toBe(value)
    const nodes = bookExcerptContent(value)
    expect(nodes[nodes.length - 1]).toEqual({ type: 'paragraph' })
  })
  it('空书摘不产生引用，也不把感想误当书摘', () => {
    expect(bookExcerptContent('  ', '书名', '感想')).toEqual([])
  })
})
