import { describe, expect, it } from 'vitest'
import DOMPurify from 'dompurify'
import { normalizedLinkUrl, sanitizeContentHtml } from '@/utils/contentLinks'
import { portableClipboardHtml } from '@/utils/richClipboard'
import { renderChatReviewMarkdown, renderMarkdown } from '@/utils/markdown'

const href = 'codex://threads/01a13855-11d0-7e33-90d9-23496e743d0d'

describe('Codex 内容链接', () => {
  it('Markdown 表格和富文本复制保留可点击聊天地址及安全属性', () => {
    const html = renderMarkdown(`| 工作项 | 聊天 |\n| --- | --- |\n| WAO | [打开聊天](${href}) |`)
    const copied = new DOMParser().parseFromString(portableClipboardHtml(html), 'text/html')
    expect(copied.querySelector('td a')?.getAttribute('href')).toBe(href)
    expect(copied.querySelector('a')?.getAttribute('rel')).toContain('noopener')
    expect(copied.querySelector('a')?.getAttribute('target')).toBe('_blank')
    expect(normalizedLinkUrl(href)).toBe(href)
  })

  it('只放行合法聊天 href，不放行资源、事件、命令、伪装地址或脚本', () => {
    for (const invalid of [
      'codex://settings', `${href}?command=run`, `${href}/extra`,
      'codex://threads/not-a-uuid', 'codex://threads@evil.example/01a13855-11d0-7e33-90d9-23496e743d0d',
      'code\nx://threads/01a13855-11d0-7e33-90d9-23496e743d0d',
      'javascript:alert(1)', 'java\nscript:alert(1)', 'data:text/html,bad', 'file:///C:/secret',
    ]) {
      expect(normalizedLinkUrl(invalid)).toBeNull()
      const doc = new DOMParser().parseFromString(sanitizeContentHtml(`<a href="${invalid}">内容</a>`), 'text/html')
      expect(doc.querySelector('a')?.hasAttribute('href')).toBe(false)
    }
    const safe = sanitizeContentHtml(`<a href="${href}" onclick="bad()">聊天</a><img src="${href}" onerror="bad()">`)
    const doc = new DOMParser().parseFromString(safe, 'text/html')
    expect(doc.querySelector('a')?.getAttribute('href')).toBe(href)
    expect(doc.querySelector('img')?.hasAttribute('src')).toBe(false)
    expect(safe).not.toMatch(/onclick|onerror/)
  })

  it('不污染其他净化调用，也不绕过复盘或显式属性白名单', () => {
    sanitizeContentHtml(`<a href="${href}">聊天</a>`)
    expect(DOMPurify.sanitize(`<a href="${href}">聊天</a>`)).not.toContain('href=')
    expect(sanitizeContentHtml(`<a href="${href}">聊天</a>`, { ALLOWED_ATTR: [] })).not.toContain('href=')
    expect(sanitizeContentHtml(`<a href="${href}">聊天</a>`, { FORBID_ATTR: ['href'] })).not.toContain('href=')
    expect(renderChatReviewMarkdown(`[聊天](${href})`)).not.toContain('<a')
  })
})
