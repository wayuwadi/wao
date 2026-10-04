export const surpriseStyles = ['bubbles', 'stars', 'chorus'] as const
export type SurpriseStyle = typeof surpriseStyles[number]
export const COLLECTION_MILLISECONDS = 15_000

/** 从其余风格随机抽取，连续玩耍不会一直看到同一种回应。 */
export function randomSurprise(previous?: SurpriseStyle, random = Math.random): SurpriseStyle {
  const choices = surpriseStyles.filter(style => style !== previous)
  const value = Math.max(0, Math.min(0.999999, random()))
  return choices[Math.floor(value * choices.length)]!
}

/** 按真实经过时间集齐四位；批次令牌防止上一轮半空的伙伴混入新一轮。 */
export function createBubbleCollection() {
  let generation = 0
  let startedAt: number | null = null
  const completed = new Set<number>()
  const started = new Set<number>()
  return {
    start(index: number, now: number) {
      if (!Number.isInteger(index) || index < 0 || index > 3) return -1
      if (startedAt === null || now - startedAt > COLLECTION_MILLISECONDS) {
        generation++
        startedAt = now
        completed.clear()
        started.clear()
      }
      started.add(index)
      return generation
    },
    complete(index: number, token: number, now: number) {
      if (startedAt === null || token !== generation || now - startedAt > COLLECTION_MILLISECONDS
        || now < startedAt || !started.has(index)) return false
      completed.add(index)
      if (completed.size !== 4) return false
      this.reset()
      return true
    },
    reset() {
      generation++
      startedAt = null
      completed.clear()
      started.clear()
    },
  }
}

/** 短暂邻居回应只叠加到原有动作，不改变拖动位置或业务状态。 */
export function surpriseResponse(style: SurpriseStyle, seconds: number, index: number, source: number) {
  const distance = Math.abs(index - source)
  const t = Math.max(0, Math.min(1, (seconds - distance * 0.07) / 0.95))
  const wave = Math.sin(t * Math.PI)
  return {
    y: wave * (style === 'chorus' ? 10 : style === 'bubbles' ? 6 : 3),
    tilt: wave * (style === 'stars' ? Math.sign(source - index) * 0.12 : Math.sin(t * Math.PI * 2) * 0.12),
    stretch: 1 + wave * (style === 'bubbles' ? -0.07 : style === 'chorus' ? 0.05 : 0),
  }
}
