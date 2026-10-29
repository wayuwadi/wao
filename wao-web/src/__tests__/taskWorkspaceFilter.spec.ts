import { describe, expect, it } from 'vitest'
import type { Task } from '@/types'
import { matchesTaskDate, matchesTaskWorkspaceFilter, shiftTaskDate, type TaskWorkspaceFilter } from '@/utils/taskWorkspaceFilter'

const task = { project: 'project-a', pr: 'P1', start: '2026-10-29', due: '2026-11-03' } as Task
const filter: TaskWorkspaceFilter = { projects: [], tags: [], tagMode: 'any', date: 'all', priorities: [] }
describe('task workspace filters', () => {
  it('crosses month and leap-year boundaries', () => {
    expect(shiftTaskDate('2026-10-31', 1)).toBe('2026-11-01')
    expect(shiftTaskDate('2028-02-28', 1)).toBe('2028-02-29')
  })
  it('qualifies either execution or deadline without conflating them', () => {
    expect(matchesTaskDate(task, 'today', '2026-10-29')).toBe(true)
    expect(matchesTaskDate(task, 'today', '2026-11-03')).toBe(true)
    expect(matchesTaskDate(task, 'today', '2026-10-30')).toBe(false)
    expect(matchesTaskDate(task, 'overdue', '2026-10-30')).toBe(false)
  })
  it('uses seven inclusive calendar days and distinguishes undated tasks', () => {
    expect(matchesTaskDate({ due: '2026-11-04' }, 'week', '2026-10-29')).toBe(true)
    expect(matchesTaskDate({ due: '2026-11-05' }, 'week', '2026-10-29')).toBe(false)
    expect(matchesTaskDate({}, 'undated', '2026-10-29')).toBe(true)
    expect(matchesTaskDate({ startAt: '2026-11-01T09:00:00' }, 'undated', '2026-10-29')).toBe(false)
  })
  it('intersects dimensions while supporting any, all and no tags', () => {
    const selected = { ...filter, tags: ['a', 'b'], projects: ['project-a'] }
    expect(matchesTaskWorkspaceFilter(task, ['a'], selected, '2026-10-29')).toBe(true)
    expect(matchesTaskWorkspaceFilter(task, ['a'], { ...selected, tagMode: 'all' }, '2026-10-29')).toBe(false)
    expect(matchesTaskWorkspaceFilter(task, ['a', 'b'], { ...selected, priorities: ['P0'] }, '2026-10-29')).toBe(false)
    expect(matchesTaskWorkspaceFilter(task, [], { ...filter, tagMode: 'none' }, '2026-10-29')).toBe(true)
    expect(matchesTaskWorkspaceFilter(task, ['a'], { ...filter, tagMode: 'none' }, '2026-10-29')).toBe(false)
  })
})
