import type { Doc, RichDocNode } from '@/types'
import { countCharacters } from '@/utils/characterCount'
import { resolvedRichContent } from '@/utils/richDoc'
import { renderMarkdown } from '@/utils/markdown'

/** 按上海本地日历取得日期键，和 WAO 今日统计保持同一时区。 */
export function shanghaiDateKey(date: Date): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(date)
  const value = (type: Intl.DateTimeFormatPartTypes) => parts.find(part => part.type === type)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')}`
}

function storedDateKey(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T/.test(value)) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : shanghaiDateKey(date)
}

function nodeText(node: RichDocNode): string {
  if (node.type === 'image') return ''
  return node.text ?? (node.content ?? []).map(nodeText).join('')
}

function plainNoteText(note: Doc): string {
  const rich = resolvedRichContent(note)
  if (rich.document?.type === 'doc') return (rich.document.content ?? []).map(nodeText).join('\n')
  return new DOMParser().parseFromString(renderMarkdown(rich.markdown), 'text/html').body.textContent ?? ''
}

/** 统计指定成员在指定日期新建的小记正文；不计标题、空白、图片和格式标记。 */
export function noteCharactersCreatedOn(notes: Doc[], memberId: string, dayKey: string): number {
  if (!memberId) return 0
  return notes.reduce((sum, note) => {
    if (note.author !== memberId || note.auto || storedDateKey(note.created) !== dayKey) return sum
    return sum + countCharacters(plainNoteText(note))
  }, 0)
}
