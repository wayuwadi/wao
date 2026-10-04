import { renderMarkdown } from './markdown'
import type { RichDocContent } from '@/types'

/** 保留正文、资源链接和图号，原图通过独立的图片剪贴板步骤复制。 */
export function noteMediaCopyText(content: RichDocContent): string {
  const root = new DOMParser().parseFromString(renderMarkdown(content.markdown), 'text/html').body
  root.querySelectorAll('img').forEach((image, index) => image.replaceWith(`[图 ${index + 1}${image.alt ? `：${image.alt}` : ''}]`))
  root.querySelectorAll('a').forEach(link => {
    const target = link.getAttribute('href')
    if (target && target !== link.textContent) link.append(` (${target})`)
  })
  root.querySelectorAll('br').forEach(node => node.replaceWith('\n'))
  root.querySelectorAll('li').forEach(node => node.prepend('• '))
  root.querySelectorAll('p,li,h1,h2,h3,h4,h5,h6,pre,tr,blockquote').forEach(node => node.append('\n\n'))
  root.querySelectorAll('td,th').forEach(node => node.append('\t'))
  return (root.textContent ?? '').trim()
}

/** 复制完整尺寸图片，独立 PNG 表示确保接收方识别为图片附件。 */
export async function copyOriginalImage(src: string): Promise<void> {
  if (!navigator.clipboard?.write || typeof ClipboardItem === 'undefined') throw new Error('当前浏览器不支持复制图片，请在 HTTPS 或本机浏览器中重试')
  const png = (async () => {
    const response = await fetch(src, { credentials: 'same-origin' })
    if (!response.ok) throw new Error('原图读取失败，请重试')
    const source = await response.blob()
    if (source.type === 'image/png') return source
    const bitmap = await createImageBitmap(source)
    try {
      const canvas = document.createElement('canvas')
      canvas.width = bitmap.width; canvas.height = bitmap.height
      const context = canvas.getContext('2d')
      if (!context) throw new Error('图片转换失败，请重试')
      context.drawImage(bitmap, 0, 0)
      return await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error('图片转换失败')), 'image/png'))
    } finally { bitmap.close() }
  })()
  void png.catch(() => { /* write 提前被拒绝时，异步图片读取也有接收方。 */ })
  // 在点击手势内调用 write，把异步读取放入 ClipboardItem，以兼容手势限制。
  await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })])
}
