import { describe, expect, it } from 'vitest'
import { scheduleAtTime, scheduleSummary, timeLanes, validateSchedule } from '@/utils/calendarPlanning'

describe('日历排期边界', () => {
  it('未排期任务拖入时间格默认一小时，午夜正确跨日而不生成24:00', () => {
    expect(scheduleAtTime({}, '2026-12-31', 23 * 60 + 30)).toEqual({ start: '2026-12-31', startAt: '2026-12-31T23:30', endAt: '2027-01-01T00:30', schedulePeriod: null })
  })
  it('已有跨日任务拖动保留持续长度，续段拖动保留相对偏移', () => {
    const task = { startAt: '2026-11-06T23:00', endAt: '2026-11-07T02:30' }
    expect(scheduleAtTime(task, '2026-11-08', 9 * 60).endAt).toBe('2026-11-08T12:30')
    expect(scheduleAtTime(task, '2026-11-08', 9 * 60, '2026-11-07')).toMatchObject({ startAt: '2026-11-08T08:00', endAt: '2026-11-08T11:30' })
  })
  it('拒绝无效日期与倒置区间，接受全天和清除安排', () => {
    expect(() => scheduleAtTime({}, '2026-02-30', 0)).toThrow()
    expect(validateSchedule({ start: '2026-02-30' })).toBeTruthy()
    expect(validateSchedule({ startAt: '2026-11-06T24:00', endAt: '2026-11-07T01:00' })).toBeTruthy()
    expect(validateSchedule({ startAt: '2026-11-06T12:00', endAt: '2026-11-06T11:00' })).toBeTruthy()
    expect(validateSchedule({ start: '2026-11-06', startAt: '2026-11-07T12:00', endAt: '2026-11-07T13:00' })).toBeTruthy()
    expect(validateSchedule({ startAt: '2026-11-06T12:00', endAt: '2026-11-06T12:00:00' })).toBeTruthy()
    expect(validateSchedule({ start: '2026-11-06' })).toBe('')
    expect(validateSchedule({ start: '', startAt: null, endAt: null })).toBe('')
  })
  it('跨日提示包含结束日期，全天与未排期分开', () => {
    expect(scheduleSummary({ startAt: '2026-11-06T23:00', endAt: '2026-11-07T02:30' })).toContain('2026-11-07 02:30')
    expect(scheduleSummary({ start: '2026-11-06' })).toContain('全天')
    expect(scheduleSummary({})).toBe('未安排执行时间')
  })
  it('直接与传递重叠任务分列，相邻时间复用列', () => {
    const a = { time: '09:00', endTime: '10:00' }, b = { time: '09:30', endTime: '10:30' }, c = { time: '10:00', endTime: '11:00' }, d = { time: '12:00', endTime: '13:00' }
    const lanes = timeLanes([a, b, c, d])
    expect(lanes.get(a)).toEqual({ lane: 0, count: 2 })
    expect(lanes.get(b)).toEqual({ lane: 1, count: 2 })
    expect(lanes.get(c)).toEqual({ lane: 0, count: 2 })
    expect(lanes.get(d)).toEqual({ lane: 0, count: 1 })
  })
})
