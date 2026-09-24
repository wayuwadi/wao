import { marked } from 'marked'
import type { RichDocContent, RichDocNode } from '@/types'

export interface NotePreviewImage { src: string; alt: string }

function imageSource(value: unknown): value is string {
  return typeof value === 'string' && (/^https?:\/\//i.test(value) || /^\/(?!\/)/.test(value))
}

/** 富文本结构是主数据；旧小记没有结构时再从 Markdown 提取图片。 */
export function notePreviewImages(content: RichDocContent): NotePreviewImage[] {
  const images: NotePreviewImage[] = []
  if (content.document?.type === 'doc') {
    const visit = (node: RichDocNode) => {
      if (node.type === 'image' && imageSource(node.attrs?.src)) {
        images.push({ src: node.attrs.src, alt: typeof node.attrs.alt === 'string' ? node.attrs.alt : '' })
      }
      node.content?.forEach(visit)
    }
    visit(content.document)
    return images
  }

  // marked 的词法树包含列表、引用、表格等嵌套 token，按正文顺序递归即可覆盖旧数据。
  const visit = (value: unknown) => {
    if (!value || typeof value !== 'object') return
    if (Array.isArray(value)) { value.forEach(visit); return }
    const token = value as Record<string, unknown>
    if (token.type === 'image' && imageSource(token.href)) {
      images.push({ src: token.href, alt: typeof token.text === 'string' ? token.text : '' })
      return
    }
    for (const key of ['tokens', 'items', 'header', 'rows']) visit(token[key])
  }
  visit(marked.lexer(content.markdown))
  return images
}
