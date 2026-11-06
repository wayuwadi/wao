import { Fragment, type Node as ProseMirrorNode } from '@tiptap/pm/model'
import type { EditorSearchRange } from './contentSearch'

/** Build a bulk replacement without N editor transactions, preserving block attributes and marks. */
export function replaceEditorSearchRanges(document: ProseMirrorNode, ranges: EditorSearchRange[], replacement: string): ProseMirrorNode {
  const matches = ranges.filter(range => range.from >= 0 && range.to <= document.content.size && range.to > range.from)
    .slice().sort((a, b) => a.from - b.from)
  let matchIndex = 0
  const replaceNode = (node: ProseMirrorNode, position: number): ProseMirrorNode => {
    if (matchIndex >= matches.length) return node
    if (node.isTextblock) {
      const start = position + 1, end = start + node.content.size
      while (matches[matchIndex] && matches[matchIndex]!.from < start) matchIndex++
      if (!matches[matchIndex] || matches[matchIndex]!.from >= end) return node
      const children: ProseMirrorNode[] = []
      node.forEach(child => children.push(child))
      const parts: ProseMirrorNode[] = []
      let offset = 0, childIndex = 0, childStart = 0, changed = false
      // Walk inline children once. Repeated Fragment.cut / resolve calls rescan all
      // prior children and become quadratic in heavily formatted long paragraphs.
      const advanceTo = (target: number, keep: boolean) => {
        while (offset < target) {
          const child = children[childIndex]!
          const childEnd = childStart + child.nodeSize
          const next = Math.min(target, childEnd)
          if (keep) parts.push(offset === childStart && next === childEnd ? child : child.cut(offset - childStart, next - childStart))
          offset = next
          if (offset === childEnd) { childIndex++; childStart = childEnd }
        }
      }
      while (matches[matchIndex] && matches[matchIndex]!.from < end) {
        const range = matches[matchIndex++]!
        const from = range.from - position - 1, to = range.to - position - 1
        if (from < offset || range.to > end) continue
        advanceTo(from, true)
        let marks = children[childIndex]?.marks ?? []
        advanceTo(to, false)
        if (replacement) {
          // Same inclusive-mark behavior as ResolvedPos.marksAcross, without resolving
          // two absolute positions (and scanning all preceding children) per match.
          const nextMarks = children[childIndex]?.marks
          marks = marks.filter(mark => mark.type.spec.inclusive !== false || (nextMarks && mark.isInSet(nextMarks)))
          parts.push(document.type.schema.text(replacement, marks))
        }
        changed = true
      }
      if (!changed) return node
      advanceTo(node.content.size, true)
      return node.copy(Fragment.fromArray(parts))
    }
    if (node.isLeaf) return node
    let changed = false
    const children: ProseMirrorNode[] = []
    node.forEach((child, offset) => {
      const next = replaceNode(child, position + 1 + offset)
      changed ||= next !== child
      children.push(next)
    })
    return changed ? node.copy(Fragment.fromArray(children)) : node
  }
  return replaceNode(document, -1)
}
