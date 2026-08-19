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

/** AI 复盘是不可信模型输出：只允许排版，禁止链接和会自动发起请求的图片。 */
const CHAT_REVIEW_TAGS = [
  'p', 'br', 'hr', 'strong', 'em', 'del', 's', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li',
]

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
