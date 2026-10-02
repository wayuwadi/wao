export type BreathingState = 'idle' | 'running' | 'paused'

/** 以绝对时刻计时，后台节流不积累倒计时误差；暂停期间不计入练习。 */
export class BreathingSession {
  state: BreathingState = 'idle'
  total = 0
  private deadline = 0
  private remaining = 0
  start(duration: number, now: number) {
    if (!Number.isFinite(duration) || duration <= 0) return
    this.total = this.remaining = duration
    this.deadline = now + duration
    this.state = 'running'
  }
  left(now: number) {
    return this.state === 'running' ? Math.max(0, this.deadline - now) : this.remaining
  }
  elapsed(now: number) {
    return this.total - this.left(now)
  }
  pause(now: number) {
    if (this.state !== 'running') return
    this.remaining = this.left(now)
    this.state = 'paused'
  }
  resume(now: number) {
    if (this.state !== 'paused') return
    this.deadline = now + this.remaining
    this.state = 'running'
  }
  stop() {
    this.state = 'idle'
    this.remaining = 0
  }
  restore(total: number, remaining: number) {
    if (
      !Number.isFinite(total) ||
      !Number.isFinite(remaining) ||
      total <= 0 ||
      remaining <= 0 ||
      remaining > total
    )
      return
    this.total = total
    this.remaining = remaining
    this.state = 'paused'
  }
}

export type PracticeMode = 'breathing' | 'sitting'
export interface PracticeSnapshot {
  mode: PracticeMode
  total: number
  remaining: number
  inhale: number
  exhale: number
}
export function readPracticeSnapshot(value: unknown): PracticeSnapshot | null {
  if (!value || typeof value !== 'object') return null
  const p = value as PracticeSnapshot
  if (p.mode !== 'breathing' && p.mode !== 'sitting') return null
  if (
    ![p.total, p.remaining, p.inhale, p.exhale].every(Number.isFinite) ||
    p.total > 180 * 60_000 ||
    p.remaining <= 0 ||
    p.remaining > p.total ||
    p.inhale < 2 ||
    p.inhale > 6 ||
    p.exhale < 2 ||
    p.exhale > 8
  )
    return null
  return {
    mode: p.mode,
    total: p.total,
    remaining: p.remaining,
    inhale: p.inhale,
    exhale: p.exhale,
  }
}
export function breathingPreferenceKey(member: string) {
  return member ? `wao-home-breathing:${member}` : 'wao-breathing-guest:v1'
}

export interface BreathingPreferences {
  scene: 'halo' | 'horizon' | 'blank'
  color: 'gold' | 'sage' | 'blue'
  glow: number
  galaxy: boolean
  words: boolean
  guide: boolean
  sound: boolean
  inhale: number
  exhale: number
  minutes: number
}
export const breathingDefaults: BreathingPreferences = {
  scene: 'halo',
  color: 'gold',
  glow: 65,
  galaxy: false,
  words: true,
  guide: true,
  sound: false,
  inhale: 4,
  exhale: 6,
  minutes: 15,
}
/** 从不可信的本机偏好中只接受受支持的字段和值。 */
export function readBreathingPreferences(value: string | null): BreathingPreferences {
  const prefs = { ...breathingDefaults }
  try {
    const saved = JSON.parse(value || 'null') as Partial<BreathingPreferences> | null
    if (!saved || typeof saved !== 'object') return prefs
    for (const key of ['words', 'galaxy', 'guide', 'sound'] as const) {
      if (typeof saved[key] === 'boolean') prefs[key] = saved[key]
    }
    for (const [key, min, max] of [
      ['glow', 20, 100],
      ['inhale', 2, 6],
      ['exhale', 2, 8],
      ['minutes', 1, 180],
    ] as const) {
      const n = saved[key]
      if (typeof n === 'number' && Number.isFinite(n)) prefs[key] = Math.max(min, Math.min(max, n))
    }
    prefs.minutes = Math.round(prefs.minutes)
    if (saved.scene === 'halo' || saved.scene === 'horizon' || saved.scene === 'blank')
      prefs.scene = saved.scene
    if (saved.color === 'gold' || saved.color === 'sage' || saved.color === 'blue')
      prefs.color = saved.color
  } catch {
    /* 损坏或禁用的本机存储不阻止本次呼吸。 */
  }
  return prefs
}
