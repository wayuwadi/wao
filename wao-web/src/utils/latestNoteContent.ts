import type { Doc, RichDocContent } from '@/types'
import { resolvedRichContent } from '@/utils/richDoc'
import { notePolishVersions } from '@/utils/autoNoteEnrichment'

export type NoteDraftSnapshot = { content: RichDocContent; title?: string; tags?: string[] }

/** 兼容历史阅读润色记录，编辑和预览始终自动使用同一份有效正文。 */
export function latestNoteContent(note: Pick<Doc, 'id' | 'blocks' | 'content'>): RichDocContent {
  const source = resolvedRichContent(note)
  const polish = notePolishVersions[note.id]
  if (!polish || polish.sourceMarkdown !== source.markdown) return source
  const cover = source.markdown.match(/^!\[文章总览图]\([^\n]+\)\s*/)?.[0] ?? ''
  const body = polish.polishedMarkdown.replace(/^!\[文章总览图]\([^\n]+\)\s*/, '')
  return { version: 1, document: null, markdown: cover + body }
}
