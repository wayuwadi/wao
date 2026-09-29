import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

export const loginGreetings = [
  { text: '嘿，有新点子吗？', anchor: 13 },
  { text: '今天有什么想记下的？', anchor: 31 },
  { text: '准备好了，一起开工！', anchor: 52 },
  { text: '来，把想法变成现实！', anchor: 74 },
  { text: '你的积累，都在这里。', anchor: 91 },
] as const

/** 角色展示文字问候并触发短音效，不朗读；冷却期间气泡仍可展示。 */
export function useLoginGreetings(
  enabled: Ref<boolean>,
  playEffect: (index: number) => number | undefined,
) {
  const active = ref<number | null>(null)
  const sounding = ref(false)
  const lastPlayed = new Map<number, number>()
  let bubbleTimer: ReturnType<typeof setTimeout> | undefined
  let effectTimer: ReturnType<typeof setTimeout> | undefined
  let disposed = false

  function stopEffectState() {
    clearTimeout(effectTimer)
    sounding.value = false
  }
  function dismiss() {
    clearTimeout(bubbleTimer)
    active.value = null
    stopEffectState()
  }
  function greet(index: number) {
    if (!loginGreetings[index] || disposed) return
    active.value = index
    clearTimeout(bubbleTimer)
    bubbleTimer = setTimeout(() => {
      active.value = null
    }, 3200)
    const now = Date.now()
    if (!enabled.value || sounding.value || now - (lastPlayed.get(index) ?? -Infinity) < 6000)
      return
    const duration = playEffect(index)
    if (!duration) return
    lastPlayed.set(index, now)
    sounding.value = true
    effectTimer = setTimeout(stopEffectState, duration)
  }
  watch(
    enabled,
    (value) => {
      if (!value) stopEffectState()
    },
    { flush: 'sync' },
  )
  onBeforeUnmount(() => {
    disposed = true
    dismiss()
  })
  return { active, sounding, greet, dismiss }
}
