import { exportToBlob, exportToSvg, restore } from '@excalidraw/excalidraw'

export type DrawingImageFormat = 'png' | 'jpg' | 'svg'

export async function renderDrawingImage(sceneJson: string, format: DrawingImageFormat, maxWidthOrHeight = 4096) {
  const { elements, appState, files } = restore(JSON.parse(sceneJson), null, null)
  if (!elements.some((element) => !element.isDeleted)) {
    throw new Error('草图还没有可导出的内容')
  }

  const options = {
    elements,
    files,
    appState: {
      viewBackgroundColor: appState.viewBackgroundColor,
      exportBackground: true,
      exportEmbedScene: false,
    },
  }
  if (format === 'svg') {
    const svg = await exportToSvg({ ...options, renderEmbeddables: false })
    return new Blob([new XMLSerializer().serializeToString(svg)], { type: 'image/svg+xml' })
  }

  const mimeType = format === 'jpg' ? 'image/jpeg' : 'image/png'
  const blob = await exportToBlob({
    ...options,
    mimeType,
    quality: 0.92,
    maxWidthOrHeight,
  })
  if (blob.type !== mimeType) throw new Error('当前浏览器不支持此图片格式')
  return blob
}
