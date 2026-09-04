import type { DocBlock, RichDocContent, RichDocNode } from '@/types'
import { blocksToText, textToBlocks } from '@/utils/doc'

export const EMPTY_RICH_DOCUMENT: RichDocNode = {
  type: 'doc',
  content: [{ type: 'paragraph' }],
}

export function richContentFromLegacy(blocks: DocBlock[]): RichDocContent {
  return { version: 1, document: null, markdown: blocksToText(blocks) }
}

export function resolvedRichContent(value: { blocks: DocBlock[]; content?: RichDocContent | null }): RichDocContent {
  if (value.content?.version === 1) return value.content
  return richContentFromLegacy(value.blocks)
}

/** 旧客户端只理解窄块；保存时同时带一份可读降级投影，完整结构仍以 content 为准。 */
export function legacyProjection(content: RichDocContent): DocBlock[] {
  return textToBlocks(content.markdown)
}

export function cloneRichContent(content: RichDocContent): RichDocContent {
  return JSON.parse(JSON.stringify(content)) as RichDocContent
}

export interface RichHeading {
  id: string
  text: string
  level: number
}

function nodeText(node: RichDocNode): string {
  return node.text ?? (node.content ?? []).map(nodeText).join('')
}

/** 目录只认顶层 H1–H3；稳定块 id 让滚动定位不再依赖标题文字。 */
export function richHeadings(content: RichDocContent): RichHeading[] {
  const document = content.document
  if (document?.type === 'doc') {
    return (document.content ?? [])
      .filter((node) => node.type === 'heading' && Number(node.attrs?.level) <= 3)
      .map((node, index) => ({
        id: String(node.attrs?.uid ?? `heading-${index}`),
        text: nodeText(node),
        level: Math.max(1, Math.min(3, Number(node.attrs?.level) || 2)),
      }))
  }
  return content.markdown.split('\n').flatMap((line, index) => {
    const match = /^(#{1,3})\s+(.+)$/.exec(line.trim())
    return match ? [{ id: `legacy-heading-${index}`, text: match[2]!, level: match[1]!.length }] : []
  })
}
