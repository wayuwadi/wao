import type { RichDocContent } from '@/types'
import { documentSearchText } from '@/utils/contentSearch'

/** Page-owned cache: draft/AI content participates in the key, not just the server revision. */
export function createNoteTextCache() {
  const entries = new Map<string, { markdown: string; document: RichDocContent['document']; text: string }>()
  return {
    text(id: string, content: RichDocContent) {
      const cached = entries.get(id)
      if (cached?.markdown === content.markdown && cached.document === content.document) return cached.text
      const text = documentSearchText({ blocks: [], content })
      entries.set(id, { markdown: content.markdown, document: content.document, text })
      return text
    },
    retain(ids: Set<string>) { for (const id of entries.keys()) if (!ids.has(id)) entries.delete(id) },
    clear() { entries.clear() },
  }
}

/** Only collapse the exact generated header; the original content is never rewritten. */
export function focusNoteSummary(title: string, tags: string[], markdown: string) {
  if (!title.startsWith('番茄钟 · ') || !tags.includes('番茄钟')) return null
  const match = /^专注时长：([^\n]+)\r?\n开始：(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})\r?\n结束：(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})(?:\r?\n|$)/.exec(markdown)
  if (!match) return null
  return { duration: match[1]!.trim(), start: `${match[2]} ${match[3]}`, end: `${match[4]} ${match[5]}`,
    time: match[2] === match[4] ? `${match[2]} · ${match[3]}–${match[5]}` : `${match[2]} ${match[3]}–${match[4]} ${match[5]}`,
    body: markdown.slice(match[0].length).trim() }
}
