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

const escapeHtml = (value: string) => value
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#39;')

/** 把成稿中的轻量 Markdown 转成 WAO 知识库原生块。 */
export function articleBlocks(content: string): ArticleBlock[] {
  const blocks: ArticleBlock[] = []
  let paragraph: string[] = []
  let list: string[] = []
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
    if (!line) {
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
  flushParagraph()
  flushList()
  return blocks
}

function blockHtml(block: ArticleBlock, index: number) {
  if (block.h) return `<h2 style="margin:42px 0 16px;color:#123d38;font-size:24px;line-height:1.4;font-weight:750;">${escapeHtml(block.h)}</h2>`
  if (block.call) return `<aside style="margin:24px 0;padding:18px 20px;border-left:4px solid #1f9d7a;border-radius:4px 14px 14px 4px;background:#eef9f5;color:#28534d;font-size:16px;line-height:1.8;">${escapeHtml(block.call)}</aside>`
  if (block.ul) return `<section style="margin:20px 0;padding:20px 22px;border:1px solid #dce9e5;border-radius:16px;background:#f8fbfa;"><ul style="margin:0;padding-left:22px;color:#314945;font-size:16px;line-height:1.9;">${block.ul.map((item) => `<li style="margin:8px 0;">${escapeHtml(item)}</li>`).join('')}</ul></section>`
  if (block.p) return `<p style="margin:${index === 0 ? '26px' : '16px'} 0;color:${index === 0 ? '#214b45' : '#344946'};font-size:${index === 0 ? '18px' : '16px'};line-height:1.95;letter-spacing:.02em;white-space:pre-line;">${escapeHtml(block.p)}</p>`
  return ''
}

export function articleFragment({ title, content, tags, coverUrl }: ArticleExportInput) {
  const cover = coverUrl
    ? `<img src="${escapeHtml(coverUrl)}" alt="《我的 WAO》概念封面" style="display:block;width:100%;height:auto;border-radius:0;" />`
    : ''
  const tagHtml = tags.map((tag) => `<span style="display:inline-block;margin:0 7px 7px 0;padding:6px 11px;border-radius:999px;background:#e9f6f2;color:#17745e;font-size:13px;">#${escapeHtml(tag)}</span>`).join('')
  return `<article style="max-width:760px;margin:0 auto;background:#ffffff;color:#263b38;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;">
  <header style="overflow:hidden;border-radius:22px;background:#173f3a;">${cover}<div style="padding:28px 30px 30px;"><div style="margin-bottom:10px;color:#8ee0c5;font-size:13px;letter-spacing:.16em;">MY WAO · 我的个人 AI 系统</div><h1 style="margin:0;color:#ffffff;font-size:32px;line-height:1.35;font-weight:800;">${escapeHtml(title)}</h1></div></header>
  <main style="padding:6px 22px 36px;">${articleBlocks(content).map(blockHtml).join('')}<footer style="margin-top:42px;padding-top:22px;border-top:1px solid #dce9e5;">${tagHtml}<p style="margin:14px 0 0;color:#78908b;font-size:13px;line-height:1.7;">这是我的第一篇 WAO 创作记录，也是一条从想法、成稿到知识沉淀的真实通路。</p></footer></main>
</article>`
}

/** 可直接保存或粘贴到支持 HTML 源码的平台编辑器的单文件文档。 */
export function articleDocument(input: ArticleExportInput) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(input.title)}</title></head><body style="margin:0;padding:24px 12px;background:#edf3f1;">${articleFragment(input)}</body></html>`
}
