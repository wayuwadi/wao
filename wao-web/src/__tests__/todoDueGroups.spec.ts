import { describe, expect, it } from 'vitest'
import { todoDueGroup } from '@/utils/todoDueGroups'
import { taskExecutionLabel } from '@/utils/taskSchedule'

describe('待办截止分组与执行提示', () => {
  it.each([
    ['2026-10-02', '2026-10-03', '逾期'], ['2026-10-03', '2026-10-03', '今天到期'],
    ['2026-10-04', '2026-10-03', '本周到期'], ['2026-10-05', '2026-10-03', '以后到期'],
    ['', '2026-10-03', '无截止日期'], [null, '2026-10-03', '无截止日期'],
    ['2026-10-05', '2026-10-04', '以后到期'], ['2026-10-11', '2026-10-05', '本周到期'],
    ['2026-11-01', '2026-10-31', '本周到期'], ['2027-01-03', '2026-12-31', '本周到期'],
    ['2027-01-04', '2026-12-31', '以后到期'],
  ])('%s 截止，相对 %s 归到 %s', (due, today, group) => expect(todoDueGroup(due, today)).toBe(group))
  it('无截止仍能识别全天、跨日今天执行，午夜结束不算今天', () => {
    expect(taskExecutionLabel({ start: '2026-10-03' }, '2026-10-03')).toBe('今天执行（未指定时间）')
    expect(taskExecutionLabel({ start: '' }, '2026-10-03')).toBe('')
    expect(taskExecutionLabel({ startAt: '2026-10-02T23:00', endAt: '2026-10-03T02:00' }, '2026-10-03')).toBe('今天执行 00:00–02:00')
    expect(taskExecutionLabel({ startAt: '2026-10-02T23:00', endAt: '2026-10-03T00:00' }, '2026-10-03')).not.toContain('今天')
    expect(taskExecutionLabel({ start: '2027-01-02' }, '2026-12-31')).toContain('2027-01-02')
  })
})
