import { describe, expect, it } from 'vitest'
import { moveTaskSchedule, taskCalendarEntries } from '@/utils/taskSchedule'

describe('执行安排与截止', () => {
  it('粗粒度安排移动保留时段，日历不伪造时刻', () => {
    expect(moveTaskSchedule({ start: '2026-10-03', schedulePeriod: 'EVENING' }, '2026-10-04')).toEqual({ start: '2026-10-04', startAt: null, endAt: null, schedulePeriod: 'EVENING' })
    expect(taskCalendarEntries({ start: '2026-10-03', schedulePeriod: 'MORNING', due: '' }, '2026-10-03', '2026-10-03')).toEqual([{ date: '2026-10-03', label: '执行（早晨）', deadline: false }])
  })
  const from = '2026-09-01', to = '2027-01-31'
  it('全天移动只填开始，不发明时间或截止', () => {
    expect(moveTaskSchedule({ start: '' }, '2026-10-08')).toEqual({ start: '2026-10-08', startAt: null, endAt: null })
  })
  it('跨日、跨月和跨年保留本地时钟持续长度', () => {
    expect(moveTaskSchedule({ startAt: '2026-10-07T23:30', endAt: '2026-10-08T02:15' }, '2026-12-31'))
      .toEqual({ start: '2026-12-31', startAt: '2026-12-31T23:30', endAt: '2027-01-01T02:15:00' })
    expect(moveTaskSchedule({ startAt: '2026-10-07T23:30', endAt: '2026-10-08T02:15' }, '2026-10-09', '2026-10-08').startAt).toBe('2026-10-08T23:30')
  })
  it('执行与不同日截止分开，同一天合并', () => {
    expect(taskCalendarEntries({ start: '2026-10-07', due: '2026-10-09' }, from, to).map(x => x.label))
      .toEqual(['执行（未指定时间）', '截止'])
    expect(taskCalendarEntries({ start: '2026-10-07', due: '2026-10-07' }, from, to)).toHaveLength(1)
    expect(taskCalendarEntries({ start: '', due: '' }, from, to)).toEqual([])
  })
  it('跨日时段拆分并与续段当天截止合并，午夜不产生空事件', () => {
    const task = { startAt: '2026-10-07T23:30', endAt: '2026-10-09T00:00', due: '2026-10-08' }
    expect(taskCalendarEntries(task, from, to)).toEqual([
      { date: '2026-10-07', time: '23:30', endTime: '24:00', label: '执行', deadline: false },
      { date: '2026-10-08', time: '00:00', endTime: '24:00', label: '执行 · 当天截止', deadline: false },
    ])
    expect(taskCalendarEntries(task, '2026-10-08', to)).toHaveLength(1)
  })
})
