/** 按原始地址读取图片，沿用同源登录身份，不将预览图当作原图。 */
export async function readClipboardImage(src: string): Promise<Blob> {
  const response = await fetch(src, { credentials: 'same-origin' })
  if (!response.ok) throw new Error('原图读取失败，请重试')
  const source = await response.blob()
  if (!source.size || !source.type.startsWith('image/')) throw new Error('图片内容读取失败，请重试')
  return source
}
