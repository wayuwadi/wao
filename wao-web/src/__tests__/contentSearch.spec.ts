import { describe, expect, it } from 'vitest'
import { Schema } from '@tiptap/pm/model'
import { EditorState } from '@tiptap/pm/state'
import { contentHit, documentSearchText, editorSearchRanges } from '@/utils/contentSearch'
import { replaceEditorSearchRanges } from '@/utils/editorSearchReplace'

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
  it('Unicode 大小写不会错移后续命中，搜索符号按原文而非正则解释', () => {
    const schema = new Schema({ nodes: { doc: { content: 'paragraph+' }, paragraph: { content: 'text*' }, text: {} } })
    const doc = schema.node('doc', null, [schema.node('paragraph', null, schema.text('İ abc ABC a.b aXb 🙂abc'))])
    const matches = editorSearchRanges(doc, 'abc')
    expect(matches.map(range => doc.textBetween(range.from, range.to))).toEqual(['abc', 'ABC', 'abc'])
    expect(editorSearchRanges(doc, 'a.b').map(range => doc.textBetween(range.from, range.to))).toEqual(['a.b'])
    expect(editorSearchRanges(doc, 'ABC', { caseSensitive: true })).toHaveLength(1)
    expect(editorSearchRanges(doc, ' abc ', { trim: false }).map(range => doc.textBetween(range.from, range.to))).toEqual([' abc '])
  })
  it('不把行内原子节点两边的文字连成可替换命中，换行仍按真实位置匹配', () => {
    const schema = new Schema({ nodes: { doc: { content: 'paragraph+' }, paragraph: { content: 'inline*' },
      text: { group: 'inline' }, atom: { inline: true, group: 'inline', atom: true }, hardBreak: { inline: true, group: 'inline' } } })
    const doc = schema.node('doc', null, [schema.node('paragraph', null,
      [schema.text('桂'), schema.node('atom'), schema.text('花 桂'), schema.node('hardBreak'), schema.text('花')])])
    expect(editorSearchRanges(doc, '桂花')).toEqual([])
    const range = editorSearchRanges(doc, '桂\n花')[0]!
    expect(doc.textBetween(range.from, range.to, '', '\n')).toBe('桂\n花')
  })
  it('批量替换保留嵌套块、UID、内联格式与原子节点，不改未命中节点', () => {
    const schema = new Schema({ nodes: { doc: { content: 'block+' }, paragraph: { group: 'block', content: 'inline*', attrs: { uid: { default: null } } },
      callout: { group: 'block', content: 'block+' }, text: { group: 'inline' }, atom: { inline: true, group: 'inline', atom: true } }, marks: { strong: {} } })
    const paragraph = schema.node('paragraph', { uid: 'keep-id' }, [schema.text('前文 '), schema.text('Match', [schema.mark('strong')]), schema.node('atom'), schema.text(' 后文 match')])
    const unchanged = schema.node('paragraph', { uid: 'unchanged' }, schema.text('无需修改'))
    const doc = schema.node('doc', null, [schema.node('callout', null, paragraph), unchanged])
    const result = replaceEditorSearchRanges(doc, editorSearchRanges(doc, 'match'), '<literal>')
    expect(result.child(0).child(0).attrs.uid).toBe('keep-id')
    expect(result.child(0).child(0).child(1).text).toBe('<literal>')
    expect(result.child(0).child(0).child(1).marks[0]?.type.name).toBe('strong')
    expect(result.child(0).child(0).child(2).type.name).toBe('atom')
    expect(result.child(1)).toBe(unchanged)
    expect(doc.textContent).toContain('Match')
    expect(result.textContent).toBe('前文 <literal> 后文 <literal>无需修改')
  })
  it('替换的内联边界遵循文字事务，非延续链接不会扩展', () => {
    const schema = new Schema({ nodes: { doc: { content: 'paragraph+' }, paragraph: { content: 'text*' }, text: {} },
      marks: { strong: {}, link: { attrs: { href: {} }, inclusive: false } } })
    const doc = schema.node('doc', null, [schema.node('paragraph', null, [
      schema.text('aa', [schema.mark('link', { href: '/first' })]), schema.text('aa', [schema.mark('strong')]),
      schema.text('aa', [schema.mark('link', { href: '/second' })]), schema.text('aaaa'),
    ])])
    for (const query of ['a', 'aa', 'aaa', 'aaaa']) for (const replacement of ['', 'literal']) {
      const ranges = editorSearchRanges(doc, query)
      for (const range of ranges) {
        const transaction = EditorState.create({ doc }).tr.insertText(replacement, range.from, range.to)
        expect(replaceEditorSearchRanges(doc, [range], replacement).toJSON()).toEqual(transaction.doc.toJSON())
      }
    }
  })
  it('单段落一万处交替格式替换保留每处格式与未命中文本', () => {
    const schema = new Schema({ nodes: { doc: { content: 'paragraph+' }, paragraph: { content: 'text*' }, text: {} }, marks: { strong: {} } })
    const doc = schema.node('doc', null, schema.node('paragraph', null,
      Array.from({ length: 10_000 }, (_, index) => schema.text('x ', index % 2 ? [schema.mark('strong')] : []))))
    const result = replaceEditorSearchRanges(doc, editorSearchRanges(doc, 'x'), 'y')
    expect(result.textContent).toBe('y '.repeat(10_000))
    expect(result.firstChild!.childCount).toBe(10_000)
    expect(result.firstChild!.child(9999).marks[0]?.type.name).toBe('strong')
    expect(result.firstChild!.child(9998).marks).toEqual([])
  })
})
