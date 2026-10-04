/**
 * Markdown 渲染 —— 全项目文字描述统一走这里。
 *
 * marked 负责语法解析，DOMPurify 负责净化：描述内容会被 v-html 渲染，
 * 未经净化的用户输入等同于 XSS 入口，所以两步缺一不可（见 ADR-007）。
 */
import DOMPurify from 'dompurify'
import { Marked } from 'marked'
import { listIndent, listIndentStyle, readIndentedList } from './listIndent'

const marked = new Marked({
  /** 单个换行即断行 —— 用户在描述框里敲回车就该换行，不需要敲两次。 */
  breaks: true,
  gfm: true,
})

marked.use({ extensions: [{
  name: 'waoIndentedList', level: 'block',
  start: source => source.indexOf('[wao-list-indent]: # "'),
  tokenizer(source) { return readIndentedList(source, markdown => this.lexer.blockTokens(markdown)) },
  renderer(token) {
    const html = this.parser.parse(token.tokens ?? [])
    return html.replace(/^<(ul|ol)(?=[\s>])/, `<$1 data-list-indent="${listIndent(token.indent)}"`)
  },
}] })

/** 允许的标签白名单：描述场景够用，且排除 script / iframe / form 等。 */
const ALLOWED_TAGS = [
  'p', 'br', 'hr', 'strong', 'em', 'del', 's', 'code', 'pre', 'blockquote',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'a', 'img',
  'table', 'thead', 'tbody', 'tr', 'th', 'td', 'input', 'span',
]
const ALLOWED_ATTR = ['href', 'title', 'src', 'alt', 'target', 'rel', 'class', 'type', 'checked', 'disabled']

/** AI 复盘是不可信模型输出：只允许排版，禁止链接和会自动发起请求的图片。 */
const CHAT_REVIEW_TAGS = [
  'p', 'br', 'hr', 'strong', 'em', 'del', 's', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li',
]

const IMAGE_SIZE_PREFIX = 'wao-image-size:'

function imagePixelDimension(value: string): number | null {
  if (!/^\d{1,4}$/.test(value)) return null
  const parsed = Number(value)
  return parsed >= 1 && parsed <= 4000 ? parsed : null
}

/**
 * 只把编辑器生成的图片尺寸元数据转换成一组固定 CSS 属性。
 * 原始 Markdown 的 style 从不进入这里，因而不能借图片 title 注入任意样式。
 */
function safeImageSize(title: string): { style: string; title: string | null } | null {
  const match = /^wao-image-size:([^x;]+)x([^;]+)(?:;indent=([1-8]))?(?:;title=(.*))?$/.exec(title)
  if (!match) return null
  const widthValue = match[1]!
  const heightValue = match[2]!
  let style = ''
  if (/^(25|50|75|100)%$/.test(widthValue) && heightValue === 'auto') {
    style = `width:${widthValue};height:auto`
  } else {
    const width = widthValue === 'auto' ? null : imagePixelDimension(widthValue)
    const height = heightValue === 'auto' ? null : imagePixelDimension(heightValue)
    if ((widthValue !== 'auto' && width == null)
      || (heightValue !== 'auto' && height == null)
      || (width == null && height == null && !match[3])) return null
    if (width != null && height != null) {
      style = `width:${width}px;aspect-ratio:${width}/${height};height:auto;object-fit:contain`
    } else if (width != null) {
      style = `width:${width}px;height:auto`
    } else if (height != null) {
      style = `height:${height}px;width:auto`
    }
  }
  if (match[3]) {
    const indent = Number(match[3]) * 24
    style += `${style ? ';' : ''}margin-left:min(${indent}px,50%);margin-right:auto;max-width:calc(100% - min(${indent}px,50%))`
  }
  let originalTitle: string | null = null
  if (match[4]) {
    try {
      originalTitle = decodeURIComponent(match[4])
    } catch {
      originalTitle = null
    }
  }
  return { style, title: originalTitle }
}

/** 外链一律 target=_blank + noopener，避免反向控制打开者的标签页。 */
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (['UL', 'OL'].includes(node.tagName)) {
    const style = listIndentStyle(node.getAttribute('data-list-indent'))
    if (style) node.setAttribute('style', style)
    else node.removeAttribute('data-list-indent')
  }
  if (node.tagName === 'A' && node.getAttribute('href')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
  if (node.tagName === 'IMG') {
    const title = node.getAttribute('title') ?? ''
    const size = safeImageSize(title)
    if (size) {
      node.setAttribute('style', size.style)
      if (size.title) node.setAttribute('title', size.title)
      else node.removeAttribute('title')
    } else if (title.startsWith(IMAGE_SIZE_PREFIX)) {
      node.removeAttribute('title')
    }
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
  return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR: [...ALLOWED_ATTR, 'data-list-indent'] })
}

/**
 * 用无外联能力的专用白名单渲染聊天复盘报告。
 *
 * <p>即使后端校验被回归改动，图片、链接和原始 HTML 也不会进入页面 DOM。
 */
export function renderChatReviewMarkdown(text: string): string {
  if (!text?.trim()) return ''
  const html = marked.parse(text, { async: false })
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: CHAT_REVIEW_TAGS,
    ALLOWED_ATTR: [],
    FORBID_TAGS: ['a', 'img', 'video', 'audio', 'source', 'iframe', 'object', 'embed'],
  })
}
