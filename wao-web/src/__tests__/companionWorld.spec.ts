import { describe, expect, it } from 'vitest'
import { createWorldChoices, createWorldCombos, recognizeWorldGesture, worldPose, type WorldKind } from '@/components/home/companionWorld'

describe('隐藏世界的可重复发现', () => {
  it('参与者不会连续重复，稀有回应有冷却，常见回应仍可出现', () => {
    const choices = createWorldChoices(() => 0.99)
    expect(choices.partner()).not.toBe(choices.partner())
    expect(choices.partner(3)).not.toBe(3)
    expect(choices.tier(0)).toBe('rare')
    expect(choices.tier(44000)).toBe('variation')
    expect(choices.tier(45000)).toBe('rare')
    expect(createWorldChoices(() => 0.1).tier(0)).toBe('common')
  })
  it('倒序、不同伙伴快速点击和重复逗一位有确定触发条件，清空后不跨轮次', () => {
    const c = createWorldCombos()
    c.pop(3, 0); c.pop(2, 300); c.pop(1, 600)
    expect(c.pop(0, 900)).toMatchObject({ reverse: true, pair: 1 })
    c.pop(1, 1000); c.pop(1, 2000)
    expect(c.pop(1, 3000).imitate).toBe(true)
    c.pop(3, 4000); c.pop(2, 5000); c.reset(); c.pop(1, 6000)
    expect(c.pop(0, 7000).reverse).toBe(false)
    c.reset(); c.pop(3, 0); c.pop(2, 2000); c.pop(1, 4000)
    expect(c.pop(0, 6100).reverse).toBe(false)
  })
  it('节拍容忍自然误差，过快、超时与不规则连点不会触发', () => {
    const c = createWorldCombos()
    expect(c.beat(0)).toBe(false); expect(c.beat(400)).toBe(false); expect(c.beat(900)).toBe(true)
    c.reset(); c.beat(0); c.beat(10); expect(c.beat(20)).toBe(false)
    c.reset(); c.beat(0); c.beat(600); expect(c.beat(2600)).toBe(false)
    c.reset(); c.beat(0); c.beat(200); expect(c.beat(1200)).toBe(false)
  })
  it('闭合圈识别星系，直划识别风，抖动和原路来回不认作圈', () => {
    const circle = Array.from({ length: 33 }, (_, i) => ({ x: 150 + 80 * Math.cos(i * Math.PI / 16), y: 200 + 75 * Math.sin(i * Math.PI / 16) }))
    expect(recognizeWorldGesture(circle)?.kind).toBe('orbit')
    expect(recognizeWorldGesture([{ x: 0, y: 0 }, { x: 30, y: 2 }, { x: 65, y: 4 }, { x: 100, y: 7 }])).toMatchObject({ kind: 'wind', direction: 1 })
    expect(recognizeWorldGesture([{ x: 0, y: 0 }, { x: 3, y: 2 }, { x: 1, y: 4 }, { x: 0, y: 0 }])).toBeNull()
    expect(recognizeWorldGesture([{ x: 0, y: 0 }, { x: 90, y: 90 }, { x: 45, y: 45 }, { x: 0, y: 0 }])).toBeNull()
  })
  it('所有剧情姿态数值有界并回到原位，书虫与青蛙保留不同节奏', () => {
    const kinds: WorldKind[] = ['watch', 'catch', 'miss', 'wind', 'orbit', 'visit', 'tickle', 'imitate', 'wave', 'reverse', 'yawn', 'peek', 'pass', 'attention']
    for (const kind of kinds) for (let index = 0; index < 4; index++) {
      const event = { kind, source: 0, target: 1, tier: 'rare' as const }
      for (const t of [0, 0.6, 1.2, 2.8, 7]) {
        const p = worldPose(event, t, index)
        Object.values(p).forEach(n => expect(Number.isFinite(n)).toBe(true))
        expect(Math.abs(p.x)).toBeLessThanOrEqual(12)
        expect(p.stretch).toBeGreaterThanOrEqual(0.85)
      }
      expect(worldPose(event, 7, index).stretch).toBeCloseTo(1)
      expect(worldPose(event, 7, index).y).toBeCloseTo(0)
    }
    expect(worldPose({ kind: 'catch', source: 1, tier: 'common' }, 0.6, 1).y)
      .not.toBe(worldPose({ kind: 'catch', source: 1, tier: 'common' }, 0.6, 0).y)
  })
})
