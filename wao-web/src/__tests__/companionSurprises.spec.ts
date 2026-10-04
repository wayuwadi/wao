import { describe, expect, it } from 'vitest'
import { createBubbleCollection, randomSurprise, surpriseResponse, surpriseStyles } from '@/components/home/companionSurprises'

describe('首页隐藏彩蛋集齐', () => {
  it('任意顺序、同时开始的四位在泡沫散完后只触发一次', () => {
    const collection = createBubbleCollection()
    const tokens = [0, 1, 2, 3].map(i => collection.start(i, 0))
    expect(collection.complete(2, tokens[2]!, 3800)).toBe(false)
    expect(collection.complete(2, tokens[2]!, 4000)).toBe(false)
    expect(collection.complete(0, tokens[0]!, 4000)).toBe(false)
    expect(collection.complete(3, tokens[3]!, 4000)).toBe(false)
    expect(collection.complete(1, tokens[1]!, 4000)).toBe(true)
    expect(collection.complete(1, tokens[1]!, 4100)).toBe(false)
  })
  it('15 秒内完成而非只点击，边界时刻包含在内', () => {
    for (const [time, expected] of [[15000, true], [15001, false]] as const) {
      const c = createBubbleCollection()
      const tokens = [0, 1, 2, 3].map(i => c.start(i, 100))
      for (let i = 0; i < 3; i++) expect(c.complete(i, tokens[i]!, 4000)).toBe(false)
      expect(c.complete(3, tokens[3]!, 100 + time)).toBe(expected)
    }
  })
  it('新轮次拒绝旧轮次仍在半空的伙伴及未点过的伙伴', () => {
    const c = createBubbleCollection(), old = c.start(0, 100)
    const current = c.start(1, 15200)
    expect(c.complete(0, old, 16000)).toBe(false)
    expect(c.complete(0, current, 16000)).toBe(false)
    expect(c.complete(1, current, 16000)).toBe(false)
    for (const i of [0, 2]) { const token = c.start(i, 16100); expect(c.complete(i, token, 20000)).toBe(false) }
    const last = c.start(3, 16100)
    expect(c.complete(3, last, 20000)).toBe(true)
  })
  it('中止后旧回调不能触发，非法角色不能凑数', () => {
    const c = createBubbleCollection(), token = c.start(0, 100)
    c.reset()
    expect(c.complete(0, token, 5000)).toBe(false)
    expect(c.start(-1, 100)).toBe(-1)
    expect(c.start(4, 100)).toBe(-1)
  })
})

describe('随机回应与自然收口', () => {
  it('三种都能抽中，避开上一次而不固定轮播', () => {
    expect([0, 0.4, 0.9].map(r => randomSurprise(undefined, () => r))).toEqual([...surpriseStyles])
    for (const previous of surpriseStyles) for (const r of [0, 0.4, 0.9, 1]) expect(randomSurprise(previous, () => r)).not.toBe(previous)
  })
  it('三种邻居回应结束后都回到中性位置', () => {
    for (const style of surpriseStyles) for (let i = 0; i < 4; i++) {
      const pose = surpriseResponse(style, 2, i, 1)
      expect(pose.y).toBeCloseTo(0)
      expect(pose.tilt).toBeCloseTo(0)
      expect(pose.stretch).toBeCloseTo(1)
    }
  })
})
