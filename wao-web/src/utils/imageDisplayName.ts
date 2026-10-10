/** 显示层隐藏导入时的机器文件名；保留有意义的名称，不改图片原件或已有正文。 */
export function imageDisplayName(alt: string | undefined | null, index: number): string {
  const name = (alt || '').trim()
  const stem = name.replace(/\.(?:png|jpe?g|gif|webp|avif|heic|bmp|svg)$/i, '')
  const generated = !name
    || /^(?:image|img|pasted[-_ ]?image|图片)(?:[-_ ()\d]*)$/i.test(stem)
    || /^(?:img_v\d+_|codex-clipboard-|[a-f\d]{16,}|[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-)/i.test(stem)
    || /^(?:screenshot|屏幕截图|截屏|截图)[-_ .()\d]*$/i.test(stem)
  return generated ? `图片 ${index}` : name
}
