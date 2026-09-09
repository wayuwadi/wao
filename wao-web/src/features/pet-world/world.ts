export interface WorldState {
  x: number
  z: number
  y: number
  velocityY: number
  heading: number
  moving: boolean
}
export interface WorldInput {
  right: number
  forward: number
  sprint: boolean
}
export const SPAWN = { x: 0, z: 5 }
export const WORLD_LIMIT = 11.3
export const OBSTACLES = [
  { x: 0, z: -8, halfX: 3.7, halfZ: 1.9 },
  { x: -7.5, z: -2.8, halfX: 1.9, halfZ: 1.9 },
  { x: 7.2, z: 2, halfX: 2.2, halfZ: 3.2 },
] as const
export const LANDMARKS = [
  { name: '太极台', x: 0, z: 1, description: '一呼一吸，动静相宜。' },
  { name: '山门', x: 0, z: -5, description: '松风有信，山水相逢。' },
  { name: '听雨亭', x: -5, z: -2.8, description: '暂歇片刻，听风穿过竹林。' },
  { name: '荷池', x: 4.5, z: 2, description: '池水映天，心静如水。' },
] as const

export function canStand(x: number, z: number) {
  return (
    Number.isFinite(x) &&
    Number.isFinite(z) &&
    Math.abs(x) <= WORLD_LIMIT &&
    Math.abs(z) <= WORLD_LIMIT &&
    !OBSTACLES.some((o) => Math.abs(x - o.x) < o.halfX + 0.3 && Math.abs(z - o.z) < o.halfZ + 0.3)
  )
}
export function initialWorldState(saved?: string | null): WorldState {
  const state: WorldState = { ...SPAWN, y: 0, velocityY: 0, heading: 0, moving: false }
  try {
    const data = saved ? JSON.parse(saved) : null
    if (
      data?.version === 1 &&
      typeof data.x === 'number' &&
      typeof data.z === 'number' &&
      canStand(data.x, data.z)
    ) {
      state.x = data.x
      state.z = data.z
    }
  } catch {
    /* 无效的本地存档不阻止进入。 */
  }
  return state
}
export function serializeWorld(state: WorldState) {
  return JSON.stringify({ version: 1, x: state.x, z: state.z })
}
export function jump(state: WorldState) {
  if (state.y === 0) state.velocityY = 5
}

/** 有界时间步和逐轴碰撞，防止恢复后台时瞬移并允许沿墙滑动。 */
export function stepWorld(state: WorldState, input: WorldInput, yaw: number, delta: number) {
  const dt = Math.min(0.05, Math.max(0, Number.isFinite(delta) ? delta : 0))
  const length = Math.hypot(input.right, input.forward)
  const right = length > 0 ? input.right / length : 0
  const forward = length > 0 ? input.forward / length : 0
  const dx = (right * Math.cos(yaw) - forward * Math.sin(yaw)) * (input.sprint ? 5 : 3) * dt
  const dz = (-right * Math.sin(yaw) - forward * Math.cos(yaw)) * (input.sprint ? 5 : 3) * dt
  const oldX = state.x,
    oldZ = state.z
  if (canStand(state.x + dx, state.z)) state.x += dx
  if (canStand(state.x, state.z + dz)) state.z += dz
  state.moving = Math.hypot(state.x - oldX, state.z - oldZ) > 0.00001
  if (state.moving) state.heading = Math.atan2(state.x - oldX, -(state.z - oldZ))
  if (state.y > 0 || state.velocityY > 0) {
    state.velocityY -= 12 * dt
    state.y = Math.max(0, state.y + state.velocityY * dt)
    if (state.y === 0) state.velocityY = 0
  }
}
