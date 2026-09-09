import { describe, expect, it } from 'vitest'
import {
  canStand,
  initialWorldState,
  jump,
  serializeWorld,
  stepWorld,
} from '@/features/pet-world/world'

describe('庭院移动和存档', () => {
  it('镜头方向决定前进方向，斜向不会加速', () => {
    const straight = initialWorldState(),
      diagonal = initialWorldState(),
      rotated = initialWorldState()
    stepWorld(straight, { forward: 1, right: 0, sprint: false }, 0, 0.05)
    stepWorld(diagonal, { forward: 1, right: 1, sprint: false }, 0, 0.05)
    stepWorld(rotated, { forward: 1, right: 0, sprint: false }, Math.PI / 2, 0.05)
    expect(straight.z).toBeCloseTo(4.85)
    expect(Math.hypot(diagonal.x, diagonal.z - 5)).toBeCloseTo(0.15)
    expect(rotated.x).toBeCloseTo(-0.15)
    expect(rotated.z).toBeCloseTo(5)
  })
  it('建筑、荷池、边界不穿透，贴墙仍能横向滑动', () => {
    const state = initialWorldState()
    state.z = -5.79
    stepWorld(state, { forward: 1, right: 1, sprint: true }, 0, 0.05)
    expect(state.z).toBe(-5.79)
    expect(state.x).toBeGreaterThan(0)
    state.x = 11.29
    state.z = 8
    stepWorld(state, { forward: 0, right: 1, sprint: true }, 0, 0.05)
    expect(state.x).toBe(11.29)
    expect(state.moving).toBe(false)
    expect(canStand(7, 2)).toBe(false)
  })
  it('跳跃只能起跳一次，落地后可再跳；后台大时间步不瞬移', () => {
    const state = initialWorldState()
    jump(state)
    stepWorld(state, { forward: 0, right: 0, sprint: false }, 0, 0.05)
    const velocity = state.velocityY
    jump(state)
    expect(state.velocityY).toBe(velocity)
    for (let i = 0; i < 30; i++) stepWorld(state, { forward: 0, right: 0, sprint: false }, 0, 0.05)
    expect(state.y).toBe(0)
    expect(state.velocityY).toBe(0)
    jump(state)
    expect(state.velocityY).toBe(5)
    stepWorld(state, { forward: 1, right: 0, sprint: true }, 0, 100)
    expect(state.z).toBeCloseTo(4.75)
  })
  it('停止保留朝向；有效位置恢复，坏存档和障碍内坐标回到出生点', () => {
    const state = initialWorldState()
    stepWorld(state, { forward: 0, right: 1, sprint: false }, 0, 0.05)
    const heading = state.heading
    stepWorld(state, { forward: 0, right: 0, sprint: false }, 0, 0.05)
    expect(state.moving).toBe(false)
    expect(state.heading).toBe(heading)
    expect(initialWorldState(serializeWorld(state)).x).toBeCloseTo(state.x)
    for (const saved of [
      'broken',
      '{"version":1,"x":7,"z":2}',
      '{"version":1,"x":300,"z":0}',
      '{"version":1,"x":"0","z":0}',
    ]) {
      expect(initialWorldState(saved).z).toBe(5)
    }
  })
})
