import type { JSONContent } from '@tiptap/core'

/** 书摘只作为纯文本进入引用节点，不解释剪贴板里的 HTML 或 Markdown。 */
export function bookExcerptContent(excerpt: string, source = '', reflection = ''): JSONContent[] {
  const paragraphs = (value: string): JSONContent[] => value.replace(/\r\n?/g, '\n').split('\n').map(text => ({
    type: 'paragraph', ...(text ? { content: [{ type: 'text', text }] } : {}),
  }))
  if (!excerpt.trim()) return []
  return [
    { type: 'blockquote', content: paragraphs(excerpt.trim()) },
    ...(source.trim() ? paragraphs(`来源：${source.trim()}`) : []),
    ...paragraphs(reflection.trim()),
  ]
}
