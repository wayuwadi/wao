import type { Task } from '@/types'

export type TaskDateFilter = 'all' | 'today' | 'tomorrow' | 'week' | 'overdue' | 'undated'
export interface TaskWorkspaceFilter {
  projects: string[]
  tags: string[]
  tagMode: 'any' | 'all' | 'none'
  date: TaskDateFilter
  priorities: string[]
}

/** Calendar arithmetic is independent of the browser's timezone and DST. */
export function shiftTaskDate(day: string, offset: number): string {
  const value = new Date(`${day}T12:00:00Z`)
  value.setUTCDate(value.getUTCDate() + offset)
  return value.toISOString().slice(0, 10)
}

/** Execution dates and deadlines may both qualify, but activity dates never do. */
export function matchesTaskDate(task: Partial<Pick<Task, 'start' | 'startAt' | 'due'>>, date: TaskDateFilter, today: string): boolean {
  const scheduled = task.startAt?.slice(0, 10) || task.start || ''
  const dates = [scheduled, task.due || ''].filter(Boolean)
  switch (date) {
    case 'today': return dates.includes(today)
    case 'tomorrow': return dates.includes(shiftTaskDate(today, 1))
    case 'week': return dates.some(day => day >= today && day <= shiftTaskDate(today, 6))
    case 'overdue': return !!task.due && task.due < today
    case 'undated': return dates.length === 0
    default: return true
  }
}

/** Each dimension intersects; selected tags can match any or all. */
export function matchesTaskWorkspaceFilter(task: Task, tags: readonly string[], filter: TaskWorkspaceFilter, today: string): boolean {
  if (filter.projects.length && !filter.projects.includes(task.project || '')) return false
  if (filter.priorities.length && !filter.priorities.includes(task.pr)) return false
  if (!matchesTaskDate(task, filter.date, today)) return false
  if (filter.tagMode === 'none') return tags.length === 0
  if (!filter.tags.length) return true
  return filter.tagMode === 'all'
    ? filter.tags.every(tag => tags.includes(tag))
    : filter.tags.some(tag => tags.includes(tag))
}
