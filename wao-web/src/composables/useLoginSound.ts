import { onBeforeUnmount, ref } from 'vue'

/** 登录页音效仅在用户手动开启后播放，不存储账号或跨页面保持音频资源。 */
export function useLoginSound() {
  const enabled = ref(false),
    unavailable = ref(false)
  let context: AudioContext | undefined
  let disposed = false
  let toggling = false
  let lastPlayed = -Infinity

  function play(kind: 'click' | 'success' | 'error' | 'buddy', index = 0) {
    if (!enabled.value || !context || context.state !== 'running') return
    const now = context.currentTime
    if ((kind === 'click' || kind === 'buddy') && now - lastPlayed < 0.06) return
    lastPlayed = now
    const notes =
      kind === 'success'
        ? [523.25, 659.25, 783.99, 1046.5]
        : kind === 'error'
          ? [277, 220]
          : kind === 'buddy'
            ? [
                [659, 440, 330, 784, 523][index] ?? 523,
                ([659, 440, 330, 784, 523][index] ?? 523) * 1.5,
              ]
            : [620, 930]
    try {
      notes.forEach((frequency, i) => {
        const oscillator = context!.createOscillator(),
          gain = context!.createGain()
        const start = now + i * (kind === 'click' ? 0.025 : 0.09)
        const duration = kind === 'click' ? 0.075 : 0.24
        oscillator.frequency.setValueAtTime(frequency, start)
        gain.gain.setValueAtTime(0, start)
        gain.gain.linearRampToValueAtTime(kind === 'click' ? 0.02 : 0.035, start + 0.012)
        gain.gain.exponentialRampToValueAtTime(0.0001, start + duration)
        oscillator.connect(gain)
        gain.connect(context!.destination)
        oscillator.onended = () => {
          oscillator.disconnect()
          gain.disconnect()
        }
        oscillator.start(start)
        oscillator.stop(start + duration + 0.02)
      })
      return (notes.length - 1) * (kind === 'click' ? 25 : 90) + (kind === 'click' ? 95 : 260)
    } catch {
      unavailable.value = true
      enabled.value = false
    }
  }

  async function toggle() {
    if (toggling || disposed) return
    toggling = true
    try {
      if (enabled.value) {
        enabled.value = false
        await context?.suspend()
      } else {
        context ??= new window.AudioContext()
        await context.resume()
        if (disposed) return
        enabled.value = true
        unavailable.value = false
        play('success')
      }
    } catch {
      unavailable.value = true
      enabled.value = false
    } finally {
      toggling = false
    }
  }
  onBeforeUnmount(() => {
    disposed = true
    enabled.value = false
    void context?.close().catch(() => {})
  })
  return { enabled, unavailable, toggle, play }
}
