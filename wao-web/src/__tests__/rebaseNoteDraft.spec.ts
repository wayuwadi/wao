import { describe, expect, it } from 'vitest'
import { rebaseNoteDraft } from '@/utils/rebaseNoteDraft'
import type { Doc, RichDocContent } from '@/types'

const content = (markdown: string): RichDocContent => ({ version: 1, document: {
  type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: markdown }] }],
}, markdown })
const note = (markdown: string, title = '原标题', tags: string[] = []): Doc => ({
  id: 'one', title, tags, author: 'wg', created: '', updated: '', kbId: 'kb-notes', rev: 3,
  folderId: 'f', blocks: [{ p: markdown }], content: content(markdown), links: [], backlinks: [],
})

describe('小记草稿与后台 AI 更新合并', () => {
  it('保留续写，并接收远端标题和总览图', () => {
    const base = note('原文')
    const remote = note('原文', 'AI 标题')
    remote.content = {
      version: 1,
      markdown: '![文章总览图](/api/images/a/raw "wao-image-size:50%xauto")\n\n原文',
      document: { type: 'doc', content: [
        { type: 'image', attrs: { src: '/api/images/a/raw', alt: '文章总览图', width: '50%', height: null, align: 'center' } },
        ...base.content!.document!.content!,
      ] },
    }
    const merged = rebaseNoteDraft(base, remote, { title: '原标题', tags: [], content: content('原文，继续写') })
    expect(merged?.title).toBe('AI 标题')
    expect(merged?.content.markdown).toContain('原文，继续写')
    expect(merged?.content.document?.content?.[0]?.type).toBe('image')
    expect(merged?.content.document?.content?.[1]?.content?.[0]?.text).toBe('原文，继续写')
  })

  it('远端正文改写或双方更改标题时保留冲突', () => {
    const base = note('原文')
    const draft = { title: '我的标题', tags: [], content: content('原文，继续写') }
    expect(rebaseNoteDraft(base, note('另一处改写'), draft)).toBeNull()
    expect(rebaseNoteDraft(base, note('原文', '另一处标题'), draft)).toBeNull()
  })
})
