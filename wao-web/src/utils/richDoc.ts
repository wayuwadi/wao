import type { DocBlock, RichDocContent, RichDocNode } from '@/types'
import { blocksToText, textToBlocks } from '@/utils/doc'

export const EMPTY_RICH_DOCUMENT: RichDocNode = {
  type: 'doc',
  content: [{ type: 'paragraph' }],
}

/** 兼容旧 JSON / Markdown 的段落内图片；当前编辑器图片是块节点，不能留在文本节点内。 */
export function editorBlockImages(document: RichDocNode): RichDocNode {
  function normalize(node: RichDocNode): RichDocNode[] {
    const content = node.content
    if (!['paragraph', 'heading'].includes(node.type) || !content?.some(child => child.type === 'image')) return [node]
    const result: RichDocNode[] = []
    let inline: RichDocNode[] = []
    function flush() {
      if (!inline.length) return
      const attrs = { ...node.attrs }
      // 一个旧段落拆成多段时只保留首段的稳定 id，其余由 UniqueID 生成。
      if (result.length) delete attrs.uid
      result.push({ ...node, attrs, content: inline })
      inline = []
    }
    for (const child of content) {
      if (child.type === 'image') { flush(); result.push(child) }
      else inline.push(child)
    }
    flush()
    return result
  }
  function normalizeContainer(node: RichDocNode): RichDocNode {
    if (!node.content) return node
    const content = node.content.flatMap(child => normalize(normalizeContainer(child)))
    // 列表项的 schema 要求首节点为段落；图片位于开头时补一个空段落。
    if (['listItem', 'taskItem'].includes(node.type) && content[0]?.type !== 'paragraph') content.unshift({ type: 'paragraph' })
    return { ...node, content }
  }
  return normalizeContainer(document)
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
