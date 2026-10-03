import type { Doc, KnowledgeBase } from '@/types'
import { documentSearchText } from '@/utils/contentSearch'

export const inspirationQuestions = [
  '最近有什么小事，让你觉得生活变好了一点？',
  '如果今天只把一件事变简单，你会选什么？',
  '你曾经很想做、后来暂时放下的事是什么？',
  '有什么想法，现在只需要先写下第一句话？',
  '今天有没有一个瞬间，值得留给以后的自己？',
]
/** 只回看当前成员真实的小记；不把共享文档当作“你的回忆”。 */
export function inspirationNotes(docs: Doc[], kbs: KnowledgeBase[], member: string) {
  const ids = new Set(kbs.filter(kb => kb.notes).map(kb => kb.id))
  return docs.filter(doc => member && doc.author === member && ids.has(doc.kbId ?? '') && documentSearchText(doc).trim())
    .sort((a, b) => (Date.parse(b.updated) || 0) - (Date.parse(a.updated) || 0) || a.id.localeCompare(b.id))
}
export function inspirationExcerpt(note: Doc) {
  return documentSearchText(note).replace(/\s+/g, ' ').trim().slice(0, 220)
}
