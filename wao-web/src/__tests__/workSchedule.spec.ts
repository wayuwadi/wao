import { describe, expect, it } from 'vitest'
import {
  businessWeekStart,
  parseAlternateWorkWeek,
  workWeekForDate,
  type AlternateWorkWeekSchedule,
} from '@/utils/workSchedule'

describe('大小周轮换计算', () => {
  const schedule: AlternateWorkWeekSchedule = {
    enabled: true,
    anchorWeek: '2026-08-13',
    anchorType: 'small',
  }

  it('把参照日期固定归一到周一，并与日历周起始显示偏好无关', () => {
    expect(businessWeekStart('2026-08-13')).toBe('2026-08-10')
    expect(parseAlternateWorkWeek(JSON.stringify(schedule))).toEqual({
      enabled: true,
      anchorWeek: '2026-08-10',
      anchorType: 'small',
    })
  })

  it('以本周双休为基准，向前和向后都按周交替', () => {
    expect(workWeekForDate('2026-08-16', schedule)).toEqual({ type: 'small', label: '小周', restLabel: '双休' })
    expect(workWeekForDate('2026-08-23', schedule)).toEqual({ type: 'big', label: '大周', restLabel: '周日上班' })
    expect(workWeekForDate('2026-08-30', schedule)?.type).toBe('small')
    expect(workWeekForDate('2026-08-09', schedule)?.type).toBe('big')
  })

  it('未启用或配置损坏时不生成误导性的工作周标识', () => {
    expect(workWeekForDate('2026-08-13', { ...schedule, enabled: false })).toBeNull()
    expect(parseAlternateWorkWeek('{bad json')).toBeNull()
    expect(parseAlternateWorkWeek('{"enabled":true,"anchorWeek":"2026-02-30","anchorType":"small"}')).toBeNull()
  })
})
