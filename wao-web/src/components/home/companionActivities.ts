export const ACTIVITY_SECONDS = 15
export const companionActivities = [
  { id: 'ball', label: '一起抛小球', greeting: '接力开始啦！' },
  { id: 'ballet', label: '来一段芭蕾', greeting: '踮起脚尖，转个圈。' },
  { id: 'hiphop', label: '一起跳街舞', greeting: '跟上这个小节拍！' },
  { id: 'relay', label: '蹦蹦接龙', greeting: '一个接一个，蹦！' },
  { id: 'stretch', label: '慢慢伸个懒腰', greeting: '一起舒展开来。' },
  { id: 'twirl', label: '转圈圈', greeting: '绕一小圈，再回来。' },
] as const
export type CompanionActivity = typeof companionActivities[number]['id']
const smooth = (v: number) => { const x = Math.max(0, Math.min(1, v)); return x * x * (3 - 2 * x) }

/** 相对各自站位的编舞，最后两秒收势；不累计漂移、不改变个人布局。 */
export function activityPose(kind: CompanionActivity, seconds: number, index: number) {
  if (seconds <= 0 || seconds >= ACTIVITY_SECONDS) return { x: 0, y: 0, tilt: 0, turn: 0, stretch: 1, strength: 0 }
  const t = Math.max(0, seconds)
  const strength = smooth(t / 0.6) * smooth((ACTIVITY_SECONDS - t) / 2)
  const phase = index * Math.PI / 2
  let x = 0, y = 0, tilt = 0, turn = 0, stretch = 1
  if (kind === 'ball') {
    x = Math.sin(t * 2.4 + phase) * 0.28
    y = 0.4 + Math.abs(Math.sin(t * 2.4 + phase)) * 0.5
    tilt = Math.sin(t * 2.4 + phase) * 0.15
  } else if (kind === 'ballet') {
    x = Math.sin(t * 1.8 + phase) * 0.38
    y = 0.5 + Math.max(0, Math.sin(t * 2.8)) * 0.85
    tilt = Math.sin(t * 1.8 + phase) * 0.18
    stretch = 1.06 + Math.max(0, Math.sin(t * 2.8)) * 0.05
    turn = 2 * Math.PI * (smooth((t - 3 - index * 0.12) / 1.4) + smooth((t - 9 - index * 0.12) / 1.4))
  } else if (kind === 'hiphop') {
    const beat = Math.sin(t * 8)
    x = Math.sin(t * 4 + (index % 2) * Math.PI) * 0.32
    y = 0.4 + Math.max(0, beat) * 0.45
    tilt = Math.sin(t * 4) * (index % 2 ? -0.32 : 0.32)
    stretch = 1 - Math.max(0, -beat) * 0.15
    turn = Math.sin(t * 4 + phase) * 0.4
    // 中段按顺序做地板旋转，再回到齐舞。
    tilt += 2 * Math.PI * smooth((t - 5 - index * 0.7) / 1.3)
  } else if (kind === 'relay') {
    const hop = Math.max(0, Math.sin(t * 3.8 - index * 0.85)) ** 2
    y = 0.35 + hop * 1.15
    tilt = Math.sin(t * 3.8 - index * 0.85) * 0.17
    stretch = 1 + hop * 0.09
  } else if (kind === 'stretch') {
    const breathe = Math.sin(t * 1.1)
    y = 0.35 + (breathe + 1) * 0.13
    tilt = Math.sin(t * 0.7) * (index % 2 ? -0.2 : 0.2)
    stretch = 1 + breathe * 0.13
  } else {
    x = Math.sin(t * 2 + phase) * 0.42
    y = 0.7 + Math.cos(t * 2 + phase) * 0.3
    tilt = Math.sin(t * 2 + phase) * 0.18
    turn = 2 * Math.PI * (smooth((t - 2) / 3) + smooth((t - 7) / 3))
  }
  // 整周旋转收势时保持等价朝向，避免倒转多圈。
  const cycles = Math.round(tilt / (2 * Math.PI)) * 2 * Math.PI
  return { x: x * strength, y: y * strength, tilt: cycles + (tilt - cycles) * strength,
    turn: (kind === 'ballet' || kind === 'twirl') ? turn : turn * strength,
    stretch: 1 + (stretch - 1) * strength, strength }
}
