/** 只在既有伙伴区域里运动的小球；坐标与伙伴场景一致。 */
export interface PlayBall { x: number; y: number; vx: number; vy: number; age: number }
export function stepPlayBall(ball: PlayBall, dt: number, width: number, height: number) {
  dt = Math.min(0.034, Math.max(0, dt))
  ball.age += dt
  ball.vy -= 520 * dt
  ball.x += ball.vx * dt
  ball.y += ball.vy * dt
  const side = Math.max(0, width / 2 - 11), floor = -height / 2 + 11, ceiling = height / 2 - 11
  if (Math.abs(ball.x) > side) { ball.x = Math.sign(ball.x) * side; ball.vx *= -0.72 }
  if (ball.y < floor) { ball.y = floor; ball.vy = Math.abs(ball.vy) * 0.68; ball.vx *= 0.86 }
  if (ball.y > ceiling) { ball.y = ceiling; ball.vy = -Math.abs(ball.vy) }
  return ball.age < 15
}

export const CAPTURE_LIMIT = 10000
export function captureText(value: string): string | null {
  const text = value.replace(/\r\n?/g, '\n').trim()
  return text && text.length <= CAPTURE_LIMIT ? text : null
}
