import type { DrawingTidyInput, DrawingTidyRequest, DrawingTidyResult } from './drawingTidy'

export type DrawingTidyPreparation = Pick<DrawingTidyResult, 'moved' | 'routed' | 'skipped' | 'mainCount' | 'sections' | 'canExpandFrames' | 'crossings' | 'limited'> & {
  changed: number
  laneCount?: number
  before: string
  after: string
  apply: () => void
}

export type DrawingTidyCanvas = {
  hasSelection: () => boolean
  prepare: (request: DrawingTidyRequest, signal: AbortSignal) => Promise<DrawingTidyPreparation>
}

export function calculateDrawingTidy(input: DrawingTidyInput, signal: AbortSignal) {
  return new Promise<DrawingTidyResult>((resolve, reject) => {
    signal.throwIfAborted()
    const worker = new Worker(new URL('./drawingTidy.worker.ts', import.meta.url), { type: 'module' })
    const abort = () => { stop(); reject(signal.reason) }
    const stop = () => {
      clearTimeout(timeout)
      signal.removeEventListener('abort', abort)
      worker.terminate()
    }
    const fail = (message: string) => { stop(); reject(new Error(message)) }
    const timeout = setTimeout(() => fail('整理计算超时，请缩小选区后重试。'), 20000)
    signal.addEventListener('abort', abort, { once: true })
    worker.onmessage = ({ data }: MessageEvent<{ result?: DrawingTidyResult; error?: string }>) => {
      stop()
      if (data.result) resolve(data.result)
      else reject(new Error(data.error ?? '整理失败，请重试。'))
    }
    worker.onerror = () => fail('整理工具加载失败，请重试。')
    try {
      worker.postMessage(input)
    } catch {
      fail('无法读取当前画布，请重试。')
    }
  })
}
