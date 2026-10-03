import type { Doc, RichDocNode } from '@/types'
import { resolvedRichContent } from './richDoc'
import { renderMarkdown } from './markdown'
import type { Node as ProseMirrorNode } from '@tiptap/pm/model'

const textOf = (node: RichDocNode): string => node.text ?? (node.type === 'hardBreak' ? '\n'
  : (node.content ?? []).map(textOf).join(['paragraph', 'heading'].includes(node.type) ? '' : '\n'))

/** Search readable text, including adjacent formatting runs, without image URLs or markup. */
export function documentSearchText(doc: Pick<Doc, 'blocks' | 'content'>): string {
  const content = resolvedRichContent(doc)
  if (content.document) return textOf(content.document)
  const parsed = new DOMParser().parseFromString(renderMarkdown(content.markdown), 'text/html')
  parsed.querySelectorAll('br').forEach(node => node.replaceWith('\n'))
  parsed.querySelectorAll('p,li,h1,h2,h3,h4,h5,h6,pre').forEach(node => node.append('\n'))
  return parsed.body.textContent ?? ''
}

export function contentHit(text: string, query: string, context = 48) {
  const needle = query.trim().toLocaleLowerCase()
  const index = needle ? text.toLocaleLowerCase().indexOf(needle) : -1
  if (index < 0) return null
  const start = Math.max(0, index - context), end = Math.min(text.length, index + needle.length + context)
  return { before: (start ? '…' : '') + text.slice(start, index), match: text.slice(index, index + needle.length),
    after: text.slice(index + needle.length, end) + (end < text.length ? '…' : '') }
}

/** Map matches across inline formatting to selection positions; never change document content. */
export function editorSearchRanges(document: ProseMirrorNode, query: string) {
  const matches: { from: number; to: number }[] = []
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return matches
  document.descendants((block, blockPos) => {
    if (!block.isTextblock) return
    let text = ''; const positions: number[] = []
    block.descendants((node, pos) => {
      const value = node.isText ? node.text ?? '' : node.type.name === 'hardBreak' ? '\n' : ''
      text += value
      for (let i = 0; i < value.length; i++) positions.push(blockPos + 1 + pos + i)
    })
    const lower = text.toLocaleLowerCase()
    for (let at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, at + needle.length)) {
      matches.push({ from: positions[at]!, to: positions[at + needle.length - 1]! + 1 })
    }
    return false
  })
  return matches
}
