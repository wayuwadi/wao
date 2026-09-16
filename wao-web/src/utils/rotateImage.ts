/** Rotate pixels into a new image; the original shared asset is never modified. */
export async function rotateImage(source: Blob, clockwise: boolean): Promise<File> {
  const bitmap = await createImageBitmap(source)
  try {
    if (bitmap.width * bitmap.height > 40_000_000) throw new Error('图片像素过大，请先缩小后再旋转')
    const canvas = document.createElement('canvas')
    canvas.width = bitmap.height
    canvas.height = bitmap.width
    const context = canvas.getContext('2d')
    if (!context) throw new Error('当前设备无法处理图片')
    context.translate(canvas.width / 2, canvas.height / 2)
    context.rotate(clockwise ? Math.PI / 2 : -Math.PI / 2)
    context.drawImage(bitmap, -bitmap.width / 2, -bitmap.height / 2)
    const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error('图片生成失败')), 'image/png'))
    if (blob.size > 10 * 1024 * 1024) throw new Error('旋转后的图片超过 10MB，请先缩小后重试')
    return new File([blob], 'rotated-image.png', { type: 'image/png' })
  } finally { bitmap.close() }
}
