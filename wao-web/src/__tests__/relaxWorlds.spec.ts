import { describe, it, expect } from 'vitest'
import {
  angleDistance,
  direction,
  initialArrows,
  memoryResult,
  orbitPaths,
  shadowError,
  stepBody,
  traceOrbit,
} from '@/components/relax/engine'
import { freshProgress, progressKey, readProgress } from '@/components/relax/progress'

describe('休息世界核心规则', () => {
  it('动作顺序错误不冒充完成，最后一个正确动作才通关', () => {
    expect(memoryResult([1, 3, 2], 0, 3)).toBe('wrong')
    expect(memoryResult([1, 3, 2], 0, 1)).toBe('next')
    expect(memoryResult([1, 3, 2], 2, 2)).toBe('complete')
  })
  it('五条星图均可通过旋转解开；不能跨行或在回环中假装回家', () => {
    orbitPaths.forEach((path, level) => {
      const a = initialArrows(level)
      expect(traceOrbit(a).success).toBe(false)
      path.slice(0, -1).forEach((p, i) => (a[p] = direction(p, path[i + 1]!)))
      expect(traceOrbit(a)).toMatchObject({ path, success: true })
    })
    expect(traceOrbit(Array(20).fill(1))).toMatchObject({ success: false, path: [0, 1, 2, 3, 4] })
    const a = Array(20).fill(1)
    a[1] = 3
    expect(traceOrbit(a).reason).toContain('小圈圈')
  })
  it('物体稳定落在家具上，不能穿过墙；恢复时不补跑巨大时间步', () => {
    const b = { x: 30, y: 10, w: 30, h: 30, vx: 0, vy: 0 },
      floor = { x: 0, y: 200, w: 700, h: 40 }
    for (let i = 0; i < 120; i++) stepBody(b, [floor], 1 / 60)
    expect(b.y).toBe(170)
    expect(b.vy).toBe(0)
    b.vx = 500
    const wall = { x: 65, y: 20, w: 20, h: 180 }
    for (let i = 0; i < 30; i++) stepBody(b, [wall, floor], 1 / 60)
    expect(b.x).toBeLessThanOrEqual(35)
    const restored = { x: 20, y: 10, w: 30, h: 30, vx: 0, vy: 0 }
    stepBody(restored, [], 60)
    expect(restored.y).toBeLessThan(12)
  })
  it('影子叠加同时受角色和光线角度影响，正确处理跨零度', () => {
    expect(angleDistance(350, 10)).toBe(20)
    expect(shadowError([30, 110], 20, [30, 110], 20)).toBe(0)
    expect(shadowError([10, 90], 40, [30, 110], 20)).toBe(0)
    expect(shadowError([0, 0], 0, [30, 110], 20)).toBe(130)
  })
})
describe('本机存档边界', () => {
  it('写满允许的绘线数量后仍能刷新恢复浮点坐标', () => {
    const p = freshProgress()
    p.journal.strokes = Array.from({ length: 80 }, () => ({
      color: '#3659cf',
      points: Array.from({ length: 160 }, (_, i) => [(i + 1) * Math.PI, (i + 1) * Math.SQRT2]),
    }))
    const raw = JSON.stringify(p)
    expect(raw.length).toBeGreaterThan(250000)
    expect(readProgress(raw).journal.strokes).toEqual(p.journal.strokes)
  })
  it('六种世界、文本、坐标能恢复，访客与每个成员隔离', () => {
    const p = freshProgress()
    p.world = 'journal'
    p.journal.text = '一页草稿'
    p.platform.collected = [0, 2]
    expect(readProgress(JSON.stringify(p))).toEqual(p)
    expect(progressKey('')).not.toBe(progressKey('a'))
    expect(progressKey('a')).not.toBe(progressKey('b'))
  })
  it('拒绝损坏/新版本存档，约束非法字段与数组长度', () => {
    expect(() => readProgress('{')).toThrow()
    expect(() => readProgress('{"version":2}')).toThrow()
    const p = freshProgress()
    const raw = {
      ...p,
      world: 'evil',
      journal: {
        text: 'x'.repeat(500),
        stickers: Array(50).fill({ buddy: 99, x: -999, y: 999 }),
        strokes: [],
      },
      orbit: { level: 99, arrows: [99], wins: -1 },
      platform: { ...p.platform, x: 1e8, collected: [0, 0, 1, 999] },
    }
    const q = readProgress(JSON.stringify(raw))
    expect(q.world).toBe('theater')
    expect(q.journal.text).toHaveLength(240)
    expect(q.journal.stickers).toHaveLength(30)
    expect(q.journal.stickers[0]).toMatchObject({ buddy: 4, x: 0, y: 350 })
    expect(q.orbit.level).toBe(4)
    expect(q.orbit.arrows).toHaveLength(20)
    expect(q.platform.collected).toEqual([0, 1])
    expect(q.platform.x).toBe(1160)
  })
})
