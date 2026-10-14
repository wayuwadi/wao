import DOMPurify, { type Config } from 'dompurify'

/** 仅允许 Codex 聊天入口，不开放其他自定义协议或 Codex 命令。 */
export function isCodexThreadUrl(value: string): boolean {
  return /^codex:\/\/threads\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
}

/** 保留合法聊天超链接；图片等资源和其他属性仍使用 DOMPurify 默认策略。 */
export function sanitizeContentHtml(html: string, config: Config = {}): string {
  DOMPurify.addHook('uponSanitizeAttribute', (node, attribute) => {
    if (node.nodeName === 'A' && attribute.attrName === 'href'
      && (!config.ALLOWED_ATTR || config.ALLOWED_ATTR.includes('href'))
      && !config.FORBID_ATTR?.includes('href')
      && isCodexThreadUrl(attribute.attrValue)) attribute.forceKeepAttr = true
  })
  try {
    return DOMPurify.sanitize(html, config)
  } finally {
    DOMPurify.removeHook('uponSanitizeAttribute')
  }
}

/** 补全常见裸域名，并校验可由用户主动打开的内容链接。 */
export function normalizedLinkUrl(raw: string): string | null {
  const value = raw.trim()
  const unsafeSpacing = [...value].some((character) => {
    const code = character.charCodeAt(0)
    return /\s/u.test(character) || code < 0x20 || code === 0x7f
  })
  if (!value || unsafeSpacing) return null
  if (isCodexThreadUrl(value)) return value
  if (/^[\w.-]+\.[a-z]{2,}(?:[/:?#].*)?$/i.test(value)) return `https://${value}`
  if (/^(\/|#|\.\.?\/)/.test(value)) return value
  if (/^mailto:[^@]+@[^@]+$/i.test(value)) return value
  if (/^tel:\+?[0-9().-]+$/i.test(value)) return value
  try {
    const parsed = new URL(value)
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') return value
  } catch {
    // 非法绝对地址。
  }
  return null
}
