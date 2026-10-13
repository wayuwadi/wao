import { renderMarkdown } from './markdown'
import type { RichDocContent } from '@/types'
import { readClipboardImage } from '@/api/imageClipboard'

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

function canvasPng(image: CanvasImageSource, width: number, height: number): Promise<Blob> {
  const canvas = document.createElement('canvas')
  canvas.width = width; canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('图片转换失败，请重试')
  context.drawImage(image, 0, 0)
  return new Promise<Blob>((resolve, reject) => canvas.toBlob(
    value => value ? resolve(value) : reject(new Error('图片转换失败，请重试')), 'image/png'))
}

async function originalPng(src: string): Promise<Blob> {
  const url = new URL(src, window.location.href).href
  // 只复用确实加载了原地址的图片；data-full-src 指向原图的预览不能代替原图。
  const loaded = [...document.images].find(image => image.complete && image.naturalWidth > 0
    && image.naturalHeight > 0 && (image.currentSrc || image.src) === url)
  if (loaded) {
    try { return await canvasPng(loaded, loaded.naturalWidth, loaded.naturalHeight) }
    catch { /* 跨域画布或解码失败时继续尝试读取原始地址。 */ }
  }
  const source = await readClipboardImage(src)
  if (source.type === 'image/png') return source
  if (typeof createImageBitmap === 'function') {
    try {
      const bitmap = await createImageBitmap(source)
      try { return await canvasPng(bitmap, bitmap.width, bitmap.height) }
      finally { bitmap.close() }
    } catch { /* 部分浏览器不能将 SVG 等格式解码为位图，改用图片元素。 */ }
  }
  const objectUrl = URL.createObjectURL(source)
  try {
    const image = new Image()
    image.src = objectUrl
    await image.decode()
    if (!image.naturalWidth || !image.naturalHeight) throw new Error('图片解码失败，请重试')
    return await canvasPng(image, image.naturalWidth, image.naturalHeight)
  } finally { URL.revokeObjectURL(objectUrl) }
}

/** 点击时立即请求剪贴板写入，图片读取和完整尺寸转换在该请求内完成。 */
export async function copyOriginalImage(src: string): Promise<void> {
  if (!navigator.clipboard?.write || typeof ClipboardItem === 'undefined') throw new Error('当前浏览器不支持复制图片，请在 HTTPS 或本机浏览器中重试')
  const png = originalPng(src)
  let imageError: unknown
  void png.catch(cause => { imageError = cause })
  // 在点击手势内调用 write，把异步读取放入 ClipboardItem，以兼容手势限制。
  try { await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })]) }
  catch (cause) {
    if (imageError instanceof Error) throw imageError
    if (cause && typeof cause === 'object' && 'name' in cause && cause.name === 'NotAllowedError')
      throw new Error('浏览器未允许复制图片，请保持页面在前台后重试')
    throw new Error('图片复制失败，请重试')
  }
}
