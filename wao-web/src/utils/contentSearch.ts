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

export interface EditorSearchRange { from: number; to: number }
export interface EditorSearchOptions { caseSensitive?: boolean; trim?: boolean }

/**
 * Match literal readable text across formatting runs, keeping original UTF-16 positions.
 * A Unicode regular expression avoids offset drift caused by lowercasing İ to two code units.
 * Non-text inline nodes break a run: replacing a match must never delete an intervening atom.
 */
export function editorSearchRanges(document: ProseMirrorNode, query: string, options: EditorSearchOptions = {}) {
  const matches: EditorSearchRange[] = []
  const needle = options.trim === false ? query : query.trim()
  if (!needle) return matches
  const pattern = new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), options.caseSensitive ? 'gu' : 'giu')
  document.descendants((block, blockPos) => {
    if (!block.isTextblock) return
    let text = '', start = 0
    const collect = () => {
      for (const match of text.matchAll(pattern)) {
        matches.push({ from: start + match.index, to: start + match.index + match[0].length })
      }
      text = ''
    }
    block.descendants((node, pos) => {
      const value = node.isText ? node.text ?? '' : node.type.name === 'hardBreak' ? '\n' : ''
      if (!value) { collect(); return false }
      const absolute = blockPos + 1 + pos
      if (text && start + text.length !== absolute) collect()
      if (!text) start = absolute
      text += value
    })
    collect()
    return false
  })
  return matches
}
