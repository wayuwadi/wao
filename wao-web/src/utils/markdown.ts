/**
 * Markdown 渲染 —— 全项目文字描述统一走这里。
 *
 * marked 负责语法解析，DOMPurify 负责净化：描述内容会被 v-html 渲染，
 * 未经净化的用户输入等同于 XSS 入口，所以两步缺一不可（见 ADR-007）。
 */
import DOMPurify from 'dompurify'
import { Marked } from 'marked'

const marked = new Marked({
  /** 单个换行即断行 —— 用户在描述框里敲回车就该换行，不需要敲两次。 */
  breaks: true,
  gfm: true,
})

/** 允许的标签白名单：描述场景够用，且排除 script / iframe / form 等。 */
const ALLOWED_TAGS = [
  'p', 'br', 'hr', 'strong', 'em', 'del', 's', 'code', 'pre', 'blockquote',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'a', 'img',
  'table', 'thead', 'tbody', 'tr', 'th', 'td', 'input', 'span',
]
const ALLOWED_ATTR = ['href', 'title', 'src', 'alt', 'target', 'rel', 'class', 'type', 'checked', 'disabled']

/** 外链一律 target=_blank + noopener，避免反向控制打开者的标签页。 */
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A' && node.getAttribute('href')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

/**
 * 把 Markdown 文本渲染成可直接 v-html 的安全 HTML。
 *
 * @param text 原始 Markdown（空字符串返回空串）
 * @returns 净化后的 HTML 片段
 */
export function renderMarkdown(text: string): string {
  if (!text?.trim()) return ''
  const html = marked.parse(text, { async: false })
  return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR })
}

/** 摘要用：剥掉 Markdown 标记，返回纯文本（列表 / 卡片里显示一行时用）。 */
export function stripMarkdown(text: string): string {
  return String(text || '')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '[图片]')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*>\s?/gm, '')
    .replace(/[*_`~]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * 在光标处套用一个 Markdown 快捷命令，返回新文本与新光标位置。
 *
 * <p>行级命令（标题 / 列表 / 引用）作用于当前行首；行内命令（粗体 / 斜体 / 行内码）
 * 包裹选区，无选区时插入占位符并选中它。
 *
 * @param text  当前全文
 * @param start 选区起点
 * @param end   选区终点
 * @param cmd   命令名
 * @return 新文本与新的选区
 */
export function applyMarkdownCommand(
  text: string,
  start: number,
  end: number,
  cmd: 'h2' | 'bold' | 'italic' | 'code' | 'ul' | 'ol' | 'quote' | 'todo' | 'link',
): { text: string; start: number; end: number } {
  const LINE_PREFIX: Partial<Record<typeof cmd, string>> = {
    h2: '## ', ul: '- ', ol: '1. ', quote: '> ', todo: '- [ ] ',
  }
  const prefix = LINE_PREFIX[cmd]
  if (prefix) {
    const lineStart = text.lastIndexOf('\n', start - 1) + 1
    const had = text.slice(lineStart).startsWith(prefix)
    const next = had
      ? text.slice(0, lineStart) + text.slice(lineStart + prefix.length)
      : text.slice(0, lineStart) + prefix + text.slice(lineStart)
    const delta = had ? -prefix.length : prefix.length
    return { text: next, start: start + delta, end: end + delta }
  }

  const WRAP: Record<string, [string, string, string]> = {
    bold: ['**', '**', '粗体'],
    italic: ['*', '*', '斜体'],
    code: ['`', '`', '代码'],
    link: ['[', '](https://)', '链接'],
  }
  const [open, close, placeholder] = WRAP[cmd]!
  const selected = text.slice(start, end) || placeholder
  const next = text.slice(0, start) + open + selected + close + text.slice(end)
  return { text: next, start: start + open.length, end: start + open.length + selected.length }
}
