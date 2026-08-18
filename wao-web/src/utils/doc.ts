/** 文档块 ↔ 简化 Markdown 互转 —— 与原型 blocksToText / textToBlocks 一致。 */
import type { DocBlock } from '@/types'

/** 块数组 → Markdown 文本。 */
export function blocksToText(blocks: DocBlock[]): string {
  return (blocks || [])
    .map((b) =>
      b.h
        ? `${'#'.repeat(Math.min(6, Math.max(1, b.hLevel ?? 2)))} ${b.h}`
        : b.p
          ? b.p
          : b.call
            ? `!> ${b.call}`
            : b.q
              ? `> ${b.q[0]}${b.q[1] ? ` —— ${b.q[1]}` : ''}`
              : b.ul
                ? b.ul.map((x) => `- ${x}`).join('\n')
                : '',
    )
    .join('\n\n')
}

/** Markdown 文本 → 块数组。支持一至六级标题 / 列表 / 提示 / 引用。 */
export function textToBlocks(t: string): DocBlock[] {
  const out: DocBlock[] = []
  String(t || '')
    .split(/\n/)
    .forEach((line) => {
      const s = line.trim()
      if (!s) return
      const heading = /^(#{1,6})\s+(.+)$/.exec(s)
      if (heading) out.push({ h: heading[2]!, hLevel: heading[1]!.length })
      else if (s.startsWith('- ')) {
        const last = out[out.length - 1]
        if (last && last.ul) last.ul.push(s.slice(2))
        else out.push({ ul: [s.slice(2)] })
      } else if (s.startsWith('!> ')) out.push({ call: s.slice(3) })
      else if (s.startsWith('> ')) {
        const q = s.slice(2).split(' —— ')
        out.push({ q: [q[0] ?? '', q[1] ?? ''] })
      } else out.push({ p: s })
    })
  return out
}
