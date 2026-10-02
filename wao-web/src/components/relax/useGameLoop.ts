import { onBeforeUnmount, onMounted, watch, type Ref } from 'vue'
/** 隐藏场景不推进模拟时间；恢复后首帧从零累计，不补跑后台时间。 */
export function useGameLoop(active: Ref<boolean>, step: (seconds: number) => void) {
  let frame = 0,
    last = 0
  function tick(t: number) {
    if (!active.value || document.hidden) {
      last = 0
      frame = 0
      return
    }
    if (last) step(Math.min((t - last) / 1000, 1 / 30))
    last = t
    frame = requestAnimationFrame(tick)
  }
  function sync() {
    cancelAnimationFrame(frame)
    frame = 0
    last = 0
    if (active.value && !document.hidden) frame = requestAnimationFrame(tick)
  }
  watch(active, sync)
  onMounted(() => {
    sync()
    document.addEventListener('visibilitychange', sync)
  })
  onBeforeUnmount(() => {
    cancelAnimationFrame(frame)
    document.removeEventListener('visibilitychange', sync)
  })
}
