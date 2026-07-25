import { describe, expect, it } from 'vitest'
import { absoluteTime, nowIso, relativeTime } from '@/utils/time'

const at = (y: number, mo: number, d: number, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi)
const iso = (...a: Parameters<typeof at>) => at(...a).toISOString()

describe('relativeTime', () => {
  const now = at(2026, 7, 25, 14, 30)

  it('一分钟内显示「刚刚」', () => {
    expect(relativeTime(iso(2026, 7, 25, 14, 30), now)).toBe('刚刚')
  })

  it('一小时内按分钟计', () => {
    expect(relativeTime(iso(2026, 7, 25, 14, 18), now)).toBe('12 分钟前')
    expect(relativeTime(iso(2026, 7, 25, 13, 31), now)).toBe('59 分钟前')
  })

  it('当天更早的时间显示「今天 HH:MM」', () => {
    expect(relativeTime(iso(2026, 7, 25, 9, 5), now)).toBe('今天 09:05')
  })

  it('前一天显示「昨天 HH:MM」', () => {
    expect(relativeTime(iso(2026, 7, 24, 21, 40), now)).toBe('昨天 21:40')
  })

  it('同年更早显示月日', () => {
    expect(relativeTime(iso(2026, 7, 12, 8, 3), now)).toBe('7月12日 08:03')
  })

  it('跨年显示年月日', () => {
    expect(relativeTime(iso(2025, 12, 31, 23, 59), now)).toBe('2025年12月31日')
  })

  it('历史数据里现成的文案原样返回，不会变成 Invalid Date', () => {
    expect(relativeTime('昨天 21:40', now)).toBe('昨天 21:40')
    expect(relativeTime('刚刚', now)).toBe('刚刚')
    expect(relativeTime('', now)).toBe('')
  })

  it('未来时间不显示成负的分钟前', () => {
    const out = relativeTime(iso(2026, 7, 26, 10, 0), now)
    expect(out).toBe('7月26日 10:00')
    expect(out).not.toContain('-')
  })
})

describe('absoluteTime', () => {
  it('给出可悬停查看的完整时间', () => {
    expect(absoluteTime(iso(2026, 7, 25, 14, 30))).toMatch(/^2026年7月25日 14:30:\d{2}$/)
  })

  it('不是时间戳时返回空串——没有 title 好过一个坏 title', () => {
    expect(absoluteTime('昨天 21:40')).toBe('')
  })
})

describe('nowIso', () => {
  it('产出可被 relativeTime 解析的 ISO 时间戳', () => {
    expect(relativeTime(nowIso())).toBe('刚刚')
  })
})
