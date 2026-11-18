import { tidyDrawing, type DrawingTidyInput } from './drawingTidy'

self.onmessage = async ({ data }: MessageEvent<DrawingTidyInput>) => {
  try {
    self.postMessage({ result: await tidyDrawing(data) })
  } catch {
    self.postMessage({ error: '整理失败，请重试或缩小选区。' })
  }
}
