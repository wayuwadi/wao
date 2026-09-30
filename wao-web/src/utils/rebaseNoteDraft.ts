import type { Doc, RichDocContent, RichDocNode } from '@/types'
import { cloneRichContent, resolvedRichContent } from '@/utils/richDoc'

type NoteBase = Pick<Doc, 'title' | 'tags' | 'blocks' | 'content'>

export interface NoteDraftValues {
  title: string
  tags: string[]
  content: RichDocContent
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

/** 仅识别自动总览图对原正文的单一前缀插入，其他远端正文修改必须显式解决冲突。 */
function addedCover(base: RichDocContent, remote: RichDocContent): RichDocNode | null | false {
  if (!base.markdown || !remote.markdown.endsWith(base.markdown)) return false
  const prefix = remote.markdown.slice(0, -base.markdown.length)
  const match = /^!\[文章总览图]\(([^\n]+)\)\n\n$/.exec(prefix)
  if (!match) return false
  if (!base.document && !remote.document) return null
  if (base.document?.type !== 'doc' || remote.document?.type !== 'doc') return false
  const [cover, ...rest] = remote.document.content ?? []
  if (cover?.type !== 'image' || cover.attrs?.alt !== '文章总览图'
      || !same(rest, base.document.content ?? [])) return false
  return cover
}

/** 保留用户草稿，并把 AI 在后台新增的标题、标签或总览图合入同一篇小记。 */
export function rebaseNoteDraft(base: NoteBase, remote: NoteBase, draft: NoteDraftValues): NoteDraftValues | null {
  const baseContent = resolvedRichContent(base)
  const remoteContent = resolvedRichContent(remote)
  let content = cloneRichContent(draft.content)
  if (!same(baseContent, remoteContent)) {
    const cover = addedCover(baseContent, remoteContent)
    if (cover === false) return null
    if (content.markdown !== baseContent.markdown && content.markdown.startsWith('![文章总览图](')) return null
    const prefix = remoteContent.markdown.slice(0, -baseContent.markdown.length)
    content.markdown = prefix + content.markdown
    if (cover && content.document?.type === 'doc') {
      content.document.content = [cover, ...(content.document.content ?? [])]
    } else if (cover && content.document) return null
  }

  const localTitleChanged = draft.title !== base.title
  const remoteTitleChanged = remote.title !== base.title
  if (localTitleChanged && remoteTitleChanged && draft.title !== remote.title) return null
  const baseTags = base.tags ?? []
  const remoteTags = remote.tags ?? []
  const localTagsChanged = !same(draft.tags, baseTags)
  if (localTagsChanged && !same(remoteTags, baseTags) && !same(draft.tags, remoteTags)) return null

  return {
    title: localTitleChanged ? draft.title : remote.title,
    tags: localTagsChanged ? draft.tags : [...remoteTags],
    content,
  }
}
