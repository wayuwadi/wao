import type { Doc, RichDocContent, RichDocNode } from '@/types'
import { cloneRichContent, resolvedRichContent } from '@/utils/richDoc'

type NoteBase = Pick<Doc, 'title' | 'tags' | 'blocks' | 'content'>

export interface NoteDraftValues {
  title: string
  tags: string[]
  content: RichDocContent
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

/** 识别自动总览图对原正文的单一前缀插入。 */
function addedCover(base: RichDocContent, remote: RichDocContent): RichDocNode | null | false {
  if (base.markdown.startsWith('![文章总览图](')) return false
  const match = /^!\[文章总览图]\(([^\n]+)\)\n\n/.exec(remote.markdown)
  if (!match) return false
  if (!remote.document) return null
  if (remote.document.type !== 'doc') return false
  const [cover] = remote.document.content ?? []
  if (cover?.type !== 'image' || cover.attrs?.alt !== '文章总览图') return false
  return cover
}

/** 保留用户草稿，并把 AI 在后台新增的标题、标签或总览图合入同一篇小记。 */
export function rebaseNoteDraft(base: NoteBase | null, remote: NoteBase, draft: NoteDraftValues): NoteDraftValues {
  if (!base) return { ...draft, content: cloneRichContent(draft.content) }
  const baseContent = resolvedRichContent(base)
  const remoteContent = resolvedRichContent(remote)
  let content = cloneRichContent(draft.content)
  if (!same(baseContent, remoteContent)) {
    const cover = addedCover(baseContent, remoteContent)
    if (same(content, baseContent)) content = cloneRichContent(remoteContent)
    else if (cover !== false && !content.markdown.startsWith('![文章总览图](')) {
      const prefix = remoteContent.markdown.match(/^!\[文章总览图]\([^\n]+\)\n\n/)![0]
      content.markdown = prefix + content.markdown
      if (cover && content.document?.type === 'doc') {
        content.document.content = [cover, ...(content.document.content ?? [])]
      } else content.document = null
    }
  }

  const localTitleChanged = draft.title !== base.title
  const baseTags = base.tags ?? []
  const remoteTags = remote.tags ?? []
  const localTagsChanged = !same(draft.tags, baseTags)

  return {
    title: localTitleChanged ? draft.title : remote.title,
    tags: localTagsChanged ? draft.tags : [...remoteTags],
    content,
  }
}
