import { sanitizeContentHtml } from './contentLinks'
import { notePreviewSource } from '@/utils/notePreviewImages'

/** 跨应用复制不能依赖 WAO 的样式表；输出语义 HTML 与基础内联排版。 */
export function portableClipboardHtml(html: string): string {
  const root = document.createElement('div')
  root.innerHTML = sanitizeContentHtml(html)
  root.querySelectorAll('ul,ol').forEach((node) => node.setAttribute('style', `padding-left:24px;list-style-type:${node.tagName === 'OL' ? 'decimal' : 'disc'};margin:8px 0`))
  root.querySelectorAll('pre').forEach((node) => node.setAttribute('style', 'white-space:pre-wrap;font-family:monospace'))
  root.querySelectorAll('table,td,th').forEach((node) => node.setAttribute('style', 'border:1px solid #bbb;border-collapse:collapse;padding:6px'))
  root.querySelectorAll('img').forEach((node) => {
    const full = node.dataset.fullSrc
    if (full && /^(?:https:\/\/wao\.wayuwa\.cn)?\/api\/images\/[A-Za-z0-9_-]+\/raw$/.test(full)
      && node.getAttribute('src') === notePreviewSource(full)) node.setAttribute('src', full)
    else node.src = node.src
    node.removeAttribute('data-full-src')
    node.style.maxWidth = '100%'
  })
  return root.innerHTML
}

/** 纯文本保留可见字符与列表层级，不泄漏 Markdown 转义或链接标记。 */
export function plainClipboardText(html: string): string {
  const root = document.createElement('div')
  root.innerHTML = sanitizeContentHtml(html)
  function render(node: Node, depth = 0): string {
    if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? ''
    if (!(node instanceof Element)) return ''
    const children = () => Array.from(node.childNodes).map(child => render(child, depth)).join('')
    if (node.tagName === 'BR') return '\n'
    if (node.tagName === 'IMG') return node.getAttribute('alt') ?? ''
    if (node.tagName === 'PRE') return `${node.textContent ?? ''}\n`
    if (node.tagName === 'UL' || node.tagName === 'OL') {
      const start = Number(node.getAttribute('start')) || 1
      return '\n' + Array.from(node.children).map((item, index) => {
        const content = Array.from(item.childNodes).map(child => render(child, depth + 1)).join('').trim()
        const marker = node.getAttribute('data-type') === 'taskList'
          ? (item.getAttribute('data-checked') === 'true' ? '[x]' : '[ ]')
          : node.tagName === 'OL' ? `${start + index}.` : '-'
        return `${'  '.repeat(depth)}${marker} ${content}\n`
      }).join('')
    }
    if (node.tagName === 'TR') return Array.from(node.children).map(cell => render(cell, depth).trim()).join('\t') + '\n'
    if (/^(P|DIV|H[1-6]|BLOCKQUOTE|TABLE)$/.test(node.tagName)) return children() + '\n'
    return children()
  }
  return Array.from(root.childNodes).map(node => render(node)).join('').replace(/^\n+|\n+$/g, '')
}

/** 富文本目标读取 HTML；纯文本目标读取原文，不读取 Markdown 源码。 */
export async function copyRichContent(html: string, markdown: string): Promise<boolean> {
  const text = plainClipboardText(html) || markdown
  try {
    if (navigator.clipboard?.write && typeof ClipboardItem !== 'undefined') {
      await navigator.clipboard.write([new ClipboardItem({
        'text/html': new Blob([portableClipboardHtml(html)], { type: 'text/html' }),
        'text/plain': new Blob([text], { type: 'text/plain' }),
      })])
      return true
    }
  } catch { /* 旧客户端继续使用复制事件，保留 HTML 与文本双格式。 */ }
  const listener = (event: ClipboardEvent) => {
    event.preventDefault()
    event.clipboardData?.setData('text/html', portableClipboardHtml(html))
    event.clipboardData?.setData('text/plain', text)
  }
  const active = document.activeElement as HTMLElement | null
  const selection = window.getSelection()
  const ranges = selection ? Array.from({ length: selection.rangeCount }, (_, i) => selection.getRangeAt(i).cloneRange()) : []
  const area = document.createElement('textarea')
  area.value = text
  area.style.cssText = 'position:fixed;opacity:0;pointer-events:none'
  document.body.appendChild(area)
  document.addEventListener('copy', listener)
  try { area.select(); return document.execCommand('copy') }
  catch { return false }
  finally {
    document.removeEventListener('copy', listener)
    area.remove()
    active?.focus({ preventScroll: true })
    selection?.removeAllRanges()
    ranges.forEach(range => selection?.addRange(range))
  }
}
