const ease = (n: number) => { const t = Math.max(0, Math.min(1, n)); return t * t * (3 - 2 * t) }
export const BUBBLE_SECONDS = 4.6

/** 升空、泡沫散开、底部重现；按真实经过时间采样，不卡在后台丢失帧。 */
export function bubblePose(seconds: number) {
  const rise = ease(seconds / 2.7)
  const burst = Math.max(0, Math.min(1, (seconds - 2.7) / 1.1))
  const returning = seconds >= 3.8
  return {
    rise,
    burst,
    bubbles: seconds >= 2.7 && seconds < 3.8,
    returning,
    scale: returning ? ease((seconds - 3.8) / 0.8) : 1 - ease((seconds - 2.55) / 0.3),
    done: seconds >= BUBBLE_SECONDS,
  }
}
