import DOMPurify from 'dompurify'

/** 跨应用复制不能依赖 WAO 的样式表；输出语义 HTML 与基础内联排版。 */
export function portableClipboardHtml(html: string): string {
  const root = document.createElement('div')
  root.innerHTML = DOMPurify.sanitize(html)
  root.querySelectorAll('ul,ol').forEach((node) => node.setAttribute('style', `padding-left:24px;list-style-type:${node.tagName === 'OL' ? 'decimal' : 'disc'};margin:8px 0`))
  root.querySelectorAll('pre').forEach((node) => node.setAttribute('style', 'white-space:pre-wrap;font-family:monospace'))
  root.querySelectorAll('table,td,th').forEach((node) => node.setAttribute('style', 'border:1px solid #bbb;border-collapse:collapse;padding:6px'))
  root.querySelectorAll('img').forEach((node) => { node.src = node.src; node.style.maxWidth = '100%' })
  return root.innerHTML
}

/** 支持富文本的目标读取 HTML；纯文本目标读取有层级的 Markdown。 */
export async function copyRichContent(html: string, markdown: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.write && typeof ClipboardItem !== 'undefined') {
      await navigator.clipboard.write([new ClipboardItem({
        'text/html': new Blob([portableClipboardHtml(html)], { type: 'text/html' }),
        'text/plain': new Blob([markdown], { type: 'text/plain' }),
      })])
      return true
    }
  } catch { /* 旧客户端继续使用复制事件，保留 HTML 与文本双格式。 */ }
  const listener = (event: ClipboardEvent) => {
    event.preventDefault()
    event.clipboardData?.setData('text/html', portableClipboardHtml(html))
    event.clipboardData?.setData('text/plain', markdown)
  }
  const active = document.activeElement as HTMLElement | null
  const selection = window.getSelection()
  const ranges = selection ? Array.from({ length: selection.rangeCount }, (_, i) => selection.getRangeAt(i).cloneRange()) : []
  const area = document.createElement('textarea')
  area.value = markdown
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
