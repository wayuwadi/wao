export const clamp = (n: number, min: number, max: number) => Math.max(min, Math.min(max, n))
export interface Body {
  x: number
  y: number
  vx: number
  vy: number
  w: number
  h: number
}
export interface Rect {
  x: number
  y: number
  w: number
  h: number
}
export const overlaps = (a: Rect, b: Rect) =>
  a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y
/** 有界步长的重力与轴向碰撞；返回是否落在支撑面。 */
export function stepBody(body: Body, solids: Rect[], dt: number, gravity = 1000) {
  dt = clamp(dt, 0, 1 / 30)
  body.vy = Math.min(body.vy + gravity * dt, 650)
  const oldX = body.x,
    oldY = body.y
  body.x += body.vx * dt
  for (const r of solids)
    if (overlaps(body, r)) {
      body.x = body.vx > 0 ? r.x - body.w : body.vx < 0 ? r.x + r.w : oldX
      body.vx = 0
    }
  body.y += body.vy * dt
  let grounded = false
  for (const r of solids)
    if (overlaps(body, r)) {
      if (body.vy >= 0 && oldY + body.h <= r.y + 8) {
        body.y = r.y - body.h
        grounded = true
      } else if (body.vy < 0 && oldY >= r.y + r.h - 8) body.y = r.y + r.h
      else body.y = oldY
      body.vy = 0
    }
  body.x = clamp(body.x, 0, 1200 - body.w)
  return grounded
}
export const orbitPaths = [
  [0, 1, 2, 3, 4, 9, 14, 19],
  [0, 5, 10, 11, 12, 7, 8, 9, 14, 19],
  [0, 1, 6, 11, 16, 17, 12, 13, 8, 9, 14, 19],
  [0, 5, 6, 1, 2, 3, 8, 7, 12, 17, 18, 13, 14, 19],
  [0, 1, 2, 7, 6, 5, 10, 15, 16, 11, 12, 13, 8, 9, 14, 19],
]
export const direction = (a: number, b: number) =>
  b === a - 5 ? 0 : b === a + 1 ? 1 : b === a + 5 ? 2 : 3
export function initialArrows(level: number) {
  const path = orbitPaths[clamp(level, 0, 4)]!
  return Array.from({ length: 20 }, (_, i) => {
    const at = path.indexOf(i)
    return at >= 0 && at < path.length - 1 ? (direction(i, path[at + 1]!) + 1) % 4 : (i + level) % 4
  })
}
/** 计算完整可观察路线，拒绝回环和跨行穿越。 */
export function traceOrbit(arrows: number[]) {
  const path = [0]
  let at = 0
  for (let n = 0; n < 24; n++) {
    if (at === 19) return { path, success: true, reason: '到家了！' }
    const d = arrows[at] ?? 0,
      next = at + [-5, 1, 5, -1][d]!
    if (next < 0 || next >= 20 || (d === 1 && at % 5 === 4) || (d === 3 && at % 5 === 0))
      return { path, success: false, reason: '飞出边界了，调整最后一颗星球。' }
    if (path.includes(next)) return { path, success: false, reason: '绕进小圈圈了，换个引力方向。' }
    path.push(next)
    at = next
  }
  return { path, success: false, reason: '路线太长了，试试更直接的路。' }
}
export const angleDistance = (a: number, b: number) => Math.abs(((a - b + 540) % 360) - 180)
export function shadowError(
  angles: number[],
  light: number,
  targets: number[],
  targetLight: number,
) {
  return Math.max(
    ...targets.map((t, i) => angleDistance((angles[i] ?? 0) + light, t + targetLight)),
  )
}
export function memoryResult(sequence: number[], step: number, choice: number) {
  return sequence[step] !== choice ? 'wrong' : step === sequence.length - 1 ? 'complete' : 'next'
}
export const platformSolids: Rect[] = [
  { x: 0, y: 330, w: 460, h: 70 },
  { x: 580, y: 330, w: 620, h: 70 },
]
export const platformCollectibles = [
  { x: 110, y: 285, role: 0 },
  { x: 505, y: 286, role: 1 },
  { x: 345, y: 285, role: 2 },
  { x: 850, y: 250, role: 3 },
  { x: 1050, y: 285, role: 4 },
]
