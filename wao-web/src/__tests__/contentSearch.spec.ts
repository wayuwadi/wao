import { describe, expect, it } from 'vitest'
import { Schema } from '@tiptap/pm/model'
import { contentHit, documentSearchText, editorSearchRanges } from '@/utils/contentSearch'

describe('可读正文检索与定位', () => {
  it('格式分割不影响关键词，链接地址不是正文，长片段有上下文', () => {
    expect(documentSearchText({ blocks: [], content: { version: 1, document: null, markdown: '桂**花**路线 [查看](https://hidden.example/秘密地址)' } })).toContain('桂花路线')
    expect(documentSearchText({ blocks: [], content: { version: 1, document: null, markdown: '[查看](https://hidden.example/秘密地址)' } })).not.toContain('秘密地址')
    expect(contentHit('前'.repeat(2000) + '桂花' + '后'.repeat(2000), '桂花')).toEqual({ before: '…' + '前'.repeat(48), match: '桂花', after: '后'.repeat(48) + '…' })
  })
  it('跨内联格式定位，大小写不敏感，选区计算不改原内容', () => {
    const schema = new Schema({ nodes: { doc: { content: 'paragraph+' }, paragraph: { content: 'text*' }, text: {} }, marks: { strong: {} } })
    const doc = schema.node('doc', null, [schema.node('paragraph', null, [schema.text('前桂'), schema.text('花', [schema.mark('strong')]), schema.text('ABC 后 桂花')])])
    const before = doc.toJSON()
    const range = editorSearchRanges(doc, '桂花abc')[0]!
    expect(doc.textBetween(range.from, range.to)).toBe('桂花ABC')
    expect(editorSearchRanges(doc, '桂花')).toHaveLength(2)
    expect(doc.toJSON()).toEqual(before)
  })
})
