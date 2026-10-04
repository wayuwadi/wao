import { companionActivities, type CompanionActivity } from './companionActivities'

export const pointerEffects = [
  'breath',
  'ripple',
  'breeze',
  'approach',
  'hop',
  'audience',
  'constellation',
  'portal',
  'clover',
  'wind',
  'droplets',
  'echo',
  'guide',
  'candy',
  'curious',
  'comet',
  'fan',
  'secret-stars',
] as const
export type PointerEffect = (typeof pointerEffects)[number]
export type CompanionRandomEvent =
  { type: 'effect'; kind: PointerEffect } | { type: 'activity'; kind: CompanionActivity }
const events: CompanionRandomEvent[] = [
  ...pointerEffects.map((kind) => ({ type: 'effect' as const, kind })),
  ...companionActivities.map(({ id }) => ({ type: 'activity' as const, kind: id })),
]

/** 左键按住和右键共享完整事件池；同一停留内不连续抽到相同事件。 */
export function createCompanionRandomEvents(random = Math.random) {
  let previous: CompanionRandomEvent | undefined
  return () => {
    const pool = events.filter((event) => event !== previous)
    previous = pool[Math.min(pool.length - 1, Math.max(0, Math.floor(random() * pool.length)))]!
    return previous
  }
}

/** 普通控件、文字和信息区保留浏览器行为；只有首页留白参与隐藏互动。 */
export function randomEventTarget(event: MouseEvent) {
  return (
    event.target instanceof Element &&
    !event.target.closest(
      'a,button,input,textarea,select,[contenteditable],[role="dialog"],[role="menu"],.home-day,.home-rest-copy,.world-bubble',
    ) &&
    !window.getSelection()?.toString()
  )
}
