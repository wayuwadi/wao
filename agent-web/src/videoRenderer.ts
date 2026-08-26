import type { VideoScene } from '@/types'

export interface RenderProgress {
  progress: number
  elapsedSeconds: number
  totalSeconds: number
  sceneIndex: number
  sceneTitle: string
  paused: boolean
}
export const enabledDuration = (scenes: VideoScene[]) =>
  scenes.filter((scene) => scene.enabled).reduce((sum, scene) => sum + scene.seconds, 0)

export function sceneAt(scenes: VideoScene[], elapsedSeconds: number) {
  const enabled = scenes.filter((scene) => scene.enabled)
  let cursor = 0
  for (let index = 0; index < enabled.length; index += 1) {
    const end = cursor + enabled[index].seconds
    if (elapsedSeconds < end || index === enabled.length - 1) {
      return { scene: enabled[index], index, localSeconds: Math.max(0, elapsedSeconds - cursor) }
    }
    cursor = end
  }
  return undefined
}

export function supportedVideoMime() {
  if (typeof MediaRecorder === 'undefined') return ''
  return [
    'video/webm;codecs=vp9,opus',
    'video/webm;codecs=vp8,opus',
    'video/webm',
  ].find((type) => MediaRecorder.isTypeSupported(type)) ?? ''
}

export function canRenderVideo() {
  return typeof HTMLCanvasElement !== 'undefined'
    && typeof HTMLCanvasElement.prototype.captureStream === 'function'
    && !!supportedVideoMime()
}

function rounded(ctx: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, radius: number) {
  ctx.beginPath()
  ctx.roundRect(x, y, width, height, radius)
}

function wrapLines(ctx: CanvasRenderingContext2D, text: string, maxWidth: number) {
  const lines: string[] = []
  let line = ''
  for (const char of text) {
    const next = line + char
    if (line && ctx.measureText(next).width > maxWidth) {
      lines.push(line)
      line = char
    } else line = next
  }
  if (line) lines.push(line)
  return lines
}

/** 信息图画面只陈述人工确认的分镜，不生成不存在的产品截图或效果数据。 */
export function drawVideoFrame(
  ctx: CanvasRenderingContext2D,
  scene: VideoScene,
  sceneIndex: number,
  sceneCount: number,
  localSeconds: number,
) {
  const { width, height } = ctx.canvas
  const phase = Math.min(1, localSeconds / 0.8)
  const ease = 1 - (1 - phase) ** 3
  ctx.fillStyle = '#F7F6FB'
  ctx.fillRect(0, 0, width, height)

  const glow = ctx.createRadialGradient(width * .84, height * .12, 10, width * .84, height * .12, width * .72)
  glow.addColorStop(0, `${scene.accent}30`)
  glow.addColorStop(1, `${scene.accent}00`)
  ctx.fillStyle = glow
  ctx.fillRect(0, 0, width, height)

  ctx.fillStyle = '#FFFFFF'
  rounded(ctx, 44, 42, width - 88, height - 84, 34)
  ctx.fill()
  ctx.strokeStyle = '#E4E0ED'
  ctx.lineWidth = 2
  ctx.stroke()

  ctx.fillStyle = scene.accent
  rounded(ctx, 76, 82, 72, 72, 20)
  ctx.fill()
  ctx.fillStyle = '#FFFFFF'
  ctx.font = '700 30px "Noto Sans CJK SC", sans-serif'
  ctx.textAlign = 'center'
  ctx.fillText('W', 112, 130)
  ctx.textAlign = 'left'
  ctx.fillStyle = '#272334'
  ctx.font = '700 27px "Noto Sans CJK SC", sans-serif'
  ctx.fillText('WAO', 166, 112)
  ctx.fillStyle = '#7F788F'
  ctx.font = '18px "Noto Sans CJK SC", sans-serif'
  ctx.fillText('Wa All in One', 166, 141)

  ctx.textAlign = 'right'
  ctx.fillStyle = '#9A94A5'
  ctx.font = '18px "Noto Sans CJK SC", sans-serif'
  ctx.fillText(`${String(sceneIndex + 1).padStart(2, '0')} / ${String(sceneCount).padStart(2, '0')}`, width - 76, 123)
  ctx.textAlign = 'left'

  const offset = (1 - ease) * 42
  ctx.save()
  ctx.translate(0, offset)
  ctx.globalAlpha = .2 + .8 * ease
  ctx.fillStyle = scene.accent
  ctx.font = '700 20px "Noto Sans CJK SC", sans-serif'
  ctx.fillText(scene.kicker || 'WAO WORKFLOW', 78, 340)

  ctx.fillStyle = '#211D2C'
  ctx.font = '800 53px "Noto Sans CJK SC", sans-serif'
  const titleLines = wrapLines(ctx, scene.title, width - 156).slice(0, 3)
  titleLines.forEach((line, index) => ctx.fillText(line, 78, 420 + index * 72))

  const bodyY = 420 + titleLines.length * 72 + 72
  ctx.fillStyle = '#625B70'
  ctx.font = '25px "Noto Sans CJK SC", sans-serif'
  const bodyLines = wrapLines(ctx, scene.body, width - 156).slice(0, 7)
  bodyLines.forEach((line, index) => ctx.fillText(line, 78, bodyY + index * 43))
  ctx.restore()

  const barWidth = width - 156
  const ratio = Math.min(1, localSeconds / scene.seconds)
  ctx.fillStyle = '#ECE9F2'
  rounded(ctx, 78, height - 172, barWidth, 8, 4)
  ctx.fill()
  if (ratio > 0) {
    ctx.fillStyle = scene.accent
    rounded(ctx, 78, height - 172, barWidth * ratio, 8, 4)
    ctx.fill()
  }
  ctx.fillStyle = '#8C8597'
  ctx.font = '17px "Noto Sans CJK SC", sans-serif'
  ctx.fillText('真实数据 · 人工可控 · 持续进化', 78, height - 116)
}

function createMusic(context: AudioContext, totalSeconds: number) {
  const destination = context.createMediaStreamDestination()
  const master = context.createGain()
  master.gain.value = .035
  master.connect(destination)
  const notes = [130.81, 164.81, 196, 146.83, 174.61, 220]
  const oscillators = [0, 7].map((detune, voice) => {
    const oscillator = context.createOscillator()
    const gain = context.createGain()
    oscillator.type = voice ? 'triangle' : 'sine'
    oscillator.detune.value = detune
    gain.gain.value = voice ? .22 : .36
    oscillator.connect(gain).connect(master)
    for (let second = 0; second <= totalSeconds; second += 4) {
      oscillator.frequency.setValueAtTime(notes[(second / 4 + voice * 2) % notes.length], context.currentTime + second)
    }
    oscillator.start()
    oscillator.stop(context.currentTime + totalSeconds + 1)
    return oscillator
  })
  return { destination, oscillators }
}

/**
 * 按真实时间编码 WebM。隐藏标签页时暂停编码，避免浏览器节流造成分镜跳跃。
 */
export async function renderVideo(
  scenes: VideoScene[],
  onProgress: (progress: RenderProgress) => void,
  signal?: AbortSignal,
) {
  const activeScenes = scenes.filter((scene) => scene.enabled)
  const totalSeconds = enabledDuration(activeScenes)
  const mimeType = supportedVideoMime()
  if (!activeScenes.length || !totalSeconds) throw new Error('至少启用一个分镜')
  if (!canRenderVideo() || !mimeType) throw new Error('当前浏览器不支持 WebM 合成，请使用最新版 Chrome 或 Edge')

  const canvas = document.createElement('canvas')
  canvas.width = 720
  canvas.height = 1280
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('浏览器无法创建视频画布')
  const videoStream = canvas.captureStream(30)
  const audioContext = new AudioContext()
  await audioContext.resume()
  const music = createMusic(audioContext, totalSeconds)
  const stream = new MediaStream([...videoStream.getVideoTracks(), ...music.destination.stream.getAudioTracks()])
  const recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 2_200_000, audioBitsPerSecond: 96_000 })
  const chunks: Blob[] = []
  let frame = 0
  let startedAt = performance.now()
  let pausedAt = 0
  let pausedTotal = 0

  const result = new Promise<Blob>((resolve, reject) => {
    recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data) }
    recorder.onerror = () => reject(new Error('浏览器视频编码失败'))
    recorder.onstop = () => {
      const blob = new Blob(chunks, { type: 'video/webm' })
      if (!blob.size) reject(new Error('浏览器没有产生有效视频文件'))
      else resolve(blob)
    }
  })

  const stopTracks = () => {
    stream.getTracks().forEach((track) => track.stop())
    void audioContext.close()
  }
  const abort = () => { if (recorder.state !== 'inactive') recorder.stop() }
  signal?.addEventListener('abort', abort, { once: true })

  const visibility = () => {
    if (document.hidden && recorder.state === 'recording') {
      pausedAt = performance.now()
      recorder.pause()
      void audioContext.suspend()
    } else if (!document.hidden && recorder.state === 'paused') {
      pausedTotal += performance.now() - pausedAt
      recorder.resume()
      void audioContext.resume()
    }
  }
  document.addEventListener('visibilitychange', visibility)
  recorder.start(1000)

  try {
    await new Promise<void>((resolve, reject) => {
      const tick = (now: number) => {
        if (signal?.aborted) return reject(new DOMException('已取消合成', 'AbortError'))
        if (recorder.state === 'paused') {
          const current = sceneAt(activeScenes, Math.max(0, (pausedAt - startedAt - pausedTotal) / 1000))
          if (current) onProgress({ progress: 0, elapsedSeconds: 0, totalSeconds, sceneIndex: current.index,
            sceneTitle: current.scene.title, paused: true })
          frame = requestAnimationFrame(tick)
          return
        }
        const elapsedSeconds = Math.min(totalSeconds, Math.max(0, (now - startedAt - pausedTotal) / 1000))
        const current = sceneAt(activeScenes, elapsedSeconds)
        if (current) {
          drawVideoFrame(ctx, current.scene, current.index, activeScenes.length, current.localSeconds)
          onProgress({ progress: Math.round(elapsedSeconds / totalSeconds * 100), elapsedSeconds, totalSeconds,
            sceneIndex: current.index, sceneTitle: current.scene.title, paused: false })
        }
        if (elapsedSeconds >= totalSeconds) resolve()
        else frame = requestAnimationFrame(tick)
      }
      frame = requestAnimationFrame(tick)
    })
    if (recorder.state !== 'inactive') recorder.stop()
    const blob = await result
    if (signal?.aborted) throw new DOMException('已取消合成', 'AbortError')
    return blob
  } finally {
    cancelAnimationFrame(frame)
    document.removeEventListener('visibilitychange', visibility)
    signal?.removeEventListener('abort', abort)
    stopTracks()
  }
}
