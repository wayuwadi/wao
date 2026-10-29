import Link from '@tiptap/extension-link'
import { isCodexThreadUrl } from '@/utils/contentLinks'
import type { RichDocNode } from '@/types'

/** 自动识别只接受明确的网址；文件扩展名和表名不是裸域名。 */
export function shouldAutoLink(value: string): boolean {
  return /^(https?:\/\/|www\.)/i.test(value) || isCodexThreadUrl(value)
}

export const DocumentLink = Link.extend({
  parseMarkdown: (token, helpers) => {
    const content = helpers.parseInline(token.tokens || [])
    // Marked 的 GFM 裸域名识别也会把 sellin.py 当成网站。
    if (!/^(\[|<)/.test(token.raw ?? '') && !shouldAutoLink(token.raw ?? '')) return content
    return helpers.applyMark('link', content, { href: token.href, title: token.title || null })
  },
})

/** 修正历史文件路径里的自动链接；独立网址与用户命名的链接保持原样。 */
export function repairFilePathLinks(node: RichDocNode): RichDocNode {
  if (!node.content) return node
  let preceding = ''
  const content = node.content.map(child => {
    let next = repairFilePathLinks(child)
    if (child.type === 'text') {
      const text = child.text ?? ''
      if (/[\\/_]$/.test(preceding) && /^[\w.-]+\.(py|sql|js|ts|json|yaml|yml|md|txt|java|cs|sh)$/i.test(text)) {
        next = { ...next, marks: next.marks?.filter(mark => mark.type !== 'link'
          || !new RegExp('^https?://' + text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '/?$', 'i').test(String(mark.attrs?.href ?? ''))) }
      }
      preceding += text
    } else preceding = ''
    return next
  })
  return { ...node, content }
}
