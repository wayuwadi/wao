export interface CompanionMotion {
  x: number
  y: number
  vx: number
  vy: number
  homeX: number
  homeY: number
}

export function companionLayout(width: number, height: number, index: number) {
  const size = Math.max(22, Math.min(width < 600 ? 46 : 78, (width - 36) / 9.4))
  return { size, homeX: (index - 1.5) * size * 2.02, homeY: -height / 2 + size * 0.61 }
}

/** 后台恢复不追赶丢失帧，弹簧每次最多推进 34ms。 */
export function stepCompanion(p: CompanionMotion, elapsed: number) {
  const dt = Math.max(0, Math.min(0.034, elapsed))
  p.vx += ((p.homeX - p.x) * 23 - p.vx * 7) * dt
  p.vy += ((p.homeY - p.y) * 23 - p.vy * 7) * dt
  p.x += p.vx * dt
  p.y += p.vy * dt
}

export function boundCompanion(x: number, y: number, width: number, height: number, size: number) {
  return {
    x: Math.max(-width / 2 + size, Math.min(width / 2 - size, x)),
    y: Math.max(-height / 2 + size * 0.3, Math.min(height / 2 - size * 1.4, y)),
  }
}
