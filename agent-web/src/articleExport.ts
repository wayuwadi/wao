export interface ArticleBlock {
  call?: string
  h?: string
  hLevel?: number
  p?: string
  ul?: string[]
}

export interface ArticleExportInput {
  title: string
  content: string
  tags: string[]
  coverUrl?: string
}

type ArticleSegment = ArticleBlock | {
  code: { language: string; source: string; raw: string }
} | {
  diagram: { source: string; raw: string }
}

const escapeHtml = (value: string) => value
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#39;')

/** 解析文章 Markdown，并把 fenced code 与 Mermaid 图形保留为可渲染嵌入块。 */
function articleSegments(content: string): ArticleSegment[] {
  const blocks: ArticleSegment[] = []
  let paragraph: string[] = []
  let list: string[] = []
  let fence: { language: string; lines: string[] } | null = null
  const flushParagraph = () => {
    if (paragraph.length) blocks.push({ p: paragraph.join('\n') })
    paragraph = []
  }
  const flushList = () => {
    if (list.length) blocks.push({ ul: list })
    list = []
  }

  for (const raw of content.replaceAll('\r\n', '\n').split('\n')) {
    const line = raw.trim()
    if (fence) {
      if (line === '```') {
        const source = fence.lines.join('\n')
        const rawBlock = `\`\`\`${fence.language}\n${source}\n\`\`\``
        if (fence.language.toLowerCase() === 'mermaid') blocks.push({ diagram: { source, raw: rawBlock } })
        else blocks.push({ code: { language: fence.language || 'text', source, raw: rawBlock } })
        fence = null
      } else {
        fence.lines.push(raw)
      }
    } else if (/^```[\w.+#-]*$/.test(line)) {
      flushParagraph()
      flushList()
      fence = { language: line.slice(3) || 'text', lines: [] }
    } else if (!line) {
      flushParagraph()
      flushList()
    } else if (/^#{1,3}\s+/.test(line)) {
      flushParagraph()
      flushList()
      const marks = line.match(/^#+/)?.[0].length ?? 2
      blocks.push({ h: line.replace(/^#{1,3}\s+/, ''), hLevel: marks })
    } else if (line.startsWith('- ')) {
      flushParagraph()
      list.push(line.slice(2).trim())
    } else if (line.startsWith('> ')) {
      flushParagraph()
      flushList()
      blocks.push({ call: line.slice(2).trim() })
    } else {
      flushList()
      paragraph.push(line)
    }
  }
  if (fence) paragraph.push(`\`\`\`${fence.language}\n${fence.lines.join('\n')}`)
  flushParagraph()
  flushList()
  return blocks
}

/**
 * 把成稿转成 WAO 知识库原生块。知识库暂无专用图形字段，因此嵌入块保留为完整
 * fenced Markdown 段落，打开文档后仍可继续编辑，不会在跨产品保存时静默丢失。
 */
export function articleBlocks(content: string): ArticleBlock[] {
  return articleSegments(content).map((block) => {
    if ('code' in block) return { p: block.code.raw }
    if ('diagram' in block) return { p: block.diagram.raw }
    return block
  })
}

function nodeLabel(value: string) {
  return value.trim()
    .replace(/^[\w.-]+\s*(?:\[|\(|\{){1,2}/, '')
    .replace(/(?:\]|\)|\}){1,2}$/, '')
    .replace(/^['"]|['"]$/g, '')
    .trim()
}

/** 无脚本渲染常用 Mermaid 图形，保证 iframe sandbox 与下载后的单文件 HTML 都可查看。 */
function diagramHtml(source: string) {
  const lines = source.split('\n').filter((line) => line.trim())
  const declaration = lines.shift()?.trim() ?? ''
  const shell = (kind: string, body: string) => `<figure style="margin:24px 0;padding:22px;border:1px solid #cfe3dd;border-radius:16px;background:#f7fbfa;"><figcaption style="margin-bottom:15px;color:#17745e;font-size:12px;font-weight:700;letter-spacing:.08em;">${kind}</figcaption>${body}</figure>`
  if (/^(flowchart|graph)\b/i.test(declaration)) {
    const rows = lines.flatMap((line) => {
      const parts = line.trim().split(/\s*(?:-->|==>|-.->)\s*/).filter(Boolean)
      if (parts.length < 2) return []
      const row = parts.map((part) => `<span style="max-width:180px;padding:9px 12px;border:1px solid #88c8b5;border-radius:9px;background:#fff;color:#214b45;text-align:center;">${escapeHtml(nodeLabel(part))}</span>`).join('<b style="color:#1f9d7a;font-size:18px;">→</b>')
      return [`<div style="display:flex;align-items:center;justify-content:center;gap:9px;flex-wrap:wrap;">${row}</div>`]
    })
    if (rows.length) return shell('流程图', `<div style="display:grid;gap:12px;">${rows.join('')}</div>`)
  }
  if (/^sequenceDiagram$/i.test(declaration)) {
    const rows = lines.flatMap((line) => {
      const match = /^(.+?)(?:--?>>|->>)(.+?):\s*(.+)$/.exec(line.trim())
      if (!match) return []
      return [`<div style="display:grid;grid-template-columns:minmax(70px,1fr) minmax(120px,2fr) minmax(70px,1fr);align-items:center;gap:9px;"><b style="padding:8px;border-radius:8px;background:#e9f6f2;text-align:center;">${escapeHtml(match[1]!.trim())}</b><span style="color:#17745e;text-align:center;">${escapeHtml(match[3]!.trim())}<br>────────→</span><b style="padding:8px;border-radius:8px;background:#e9f6f2;text-align:center;">${escapeHtml(match[2]!.trim())}</b></div>`]
    })
    if (rows.length) return shell('时序图', `<div style="display:grid;gap:14px;font-size:13px;">${rows.join('')}</div>`)
  }
  if (/^mindmap$/i.test(declaration)) {
    const items = lines.map((line) => {
      const indent = Math.min(3, Math.floor((line.match(/^\s*/)?.[0].length ?? 0) / 2))
      return `<div style="margin-left:${indent * 28}px;padding:7px 10px;border-left:${Math.max(2, 5 - indent)}px solid ${indent ? '#88c8b5' : '#1f9d7a'};color:#214b45;">${escapeHtml(nodeLabel(line))}</div>`
    })
    if (items.length) return shell('思维导图', `<div style="display:grid;gap:5px;">${items.join('')}</div>`)
  }
  return shell('图形源码', `<pre style="overflow:auto;margin:0;color:#344946;white-space:pre-wrap;"><code>${escapeHtml(source)}</code></pre>`)
}

function blockHtml(block: ArticleBlock, index: number) {
  if (block.h) return `<h2 style="margin:42px 0 16px;color:#123d38;font-size:24px;line-height:1.4;font-weight:750;">${escapeHtml(block.h)}</h2>`
  if (block.call) return `<aside style="margin:24px 0;padding:18px 20px;border-left:4px solid #1f9d7a;border-radius:4px 14px 14px 4px;background:#eef9f5;color:#28534d;font-size:16px;line-height:1.8;">${escapeHtml(block.call)}</aside>`
  if (block.ul) return `<section style="margin:20px 0;padding:20px 22px;border:1px solid #dce9e5;border-radius:16px;background:#f8fbfa;"><ul style="margin:0;padding-left:22px;color:#314945;font-size:16px;line-height:1.9;">${block.ul.map((item) => `<li style="margin:8px 0;">${escapeHtml(item)}</li>`).join('')}</ul></section>`
  if (block.p) return `<p style="margin:${index === 0 ? '26px' : '16px'} 0;color:${index === 0 ? '#214b45' : '#344946'};font-size:${index === 0 ? '18px' : '16px'};line-height:1.95;letter-spacing:.02em;white-space:pre-line;">${escapeHtml(block.p)}</p>`
  return ''
}

function segmentHtml(block: ArticleSegment, index: number) {
  if ('diagram' in block) return diagramHtml(block.diagram.source)
  if ('code' in block) return `<section style="overflow:hidden;margin:24px 0;border-radius:14px;background:#102824;color:#e4f4ef;"><header style="padding:8px 13px;background:#183b35;color:#8ee0c5;font:12px ui-monospace,SFMono-Regular,Consolas,monospace;">${escapeHtml(block.code.language)}</header><pre style="overflow:auto;margin:0;padding:17px;font:13px/1.65 ui-monospace,SFMono-Regular,Consolas,monospace;white-space:pre;"><code>${escapeHtml(block.code.source)}</code></pre></section>`
  return blockHtml(block, index)
}

export function articleFragment({ title, content, tags, coverUrl }: ArticleExportInput) {
  const cover = coverUrl
    ? `<img src="${escapeHtml(coverUrl)}" alt="《我的 WAO》概念封面" style="display:block;width:100%;height:auto;border-radius:0;" />`
    : ''
  const tagHtml = tags.map((tag) => `<span style="display:inline-block;margin:0 7px 7px 0;padding:6px 11px;border-radius:999px;background:#e9f6f2;color:#17745e;font-size:13px;">#${escapeHtml(tag)}</span>`).join('')
  return `<article style="max-width:760px;margin:0 auto;background:#ffffff;color:#263b38;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;">
  <header style="overflow:hidden;border-radius:22px;background:#173f3a;">${cover}<div style="padding:28px 30px 30px;"><div style="margin-bottom:10px;color:#8ee0c5;font-size:13px;letter-spacing:.16em;">MY WAO · 我的个人 AI 系统</div><h1 style="margin:0;color:#ffffff;font-size:32px;line-height:1.35;font-weight:800;">${escapeHtml(title)}</h1></div></header>
  <main style="padding:6px 22px 36px;">${articleSegments(content).map(segmentHtml).join('')}<footer style="margin-top:42px;padding-top:22px;border-top:1px solid #dce9e5;">${tagHtml}<p style="margin:14px 0 0;color:#78908b;font-size:13px;line-height:1.7;">这是我的第一篇 WAO 创作记录，也是一条从想法、成稿到知识沉淀的真实通路。</p></footer></main>
</article>`
}

/** 可直接保存或粘贴到支持 HTML 源码的平台编辑器的单文件文档。 */
export function articleDocument(input: ArticleExportInput) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(input.title)}</title></head><body style="margin:0;padding:24px 12px;background:#edf3f1;">${articleFragment(input)}</body></html>`
}
