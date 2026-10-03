import type { Task } from '@/types'

export type TaskSchedule = Pick<Task, 'start' | 'startAt' | 'endAt' | 'schedulePeriod'>
export const PERIOD_LABELS = { MORNING: '早晨', AFTERNOON: '下午', EVENING: '晚上' } as const
export const scheduleSnapshot = (task: TaskSchedule): TaskSchedule => ({ start: task.start || '', startAt: task.startAt || null, endAt: task.endAt || null, schedulePeriod: task.schedulePeriod || null })
export const sameSchedule = (a: TaskSchedule, b: TaskSchedule) => JSON.stringify(scheduleSnapshot(a)) === JSON.stringify(scheduleSnapshot(b))

// The contract stores local wall-clock ISO values, without a timezone. UTC arithmetic
// here preserves those components across month/year boundaries without DST conversion.
export function moveTaskSchedule(task: TaskSchedule, day: string, grabbedDay?: string): TaskSchedule {
  const original = task.startAt?.slice(0, 10) || task.start
  if (grabbedDay && original) {
    day = new Date(Date.parse(`${original}T00:00:00Z`) + Date.parse(`${day}T00:00:00Z`) - Date.parse(`${grabbedDay}T00:00:00Z`)).toISOString().slice(0, 10)
  }
  if (!task.startAt) return { start: day, startAt: null, endAt: null, ...(task.schedulePeriod ? { schedulePeriod: task.schedulePeriod } : {}) }
  const startAt = `${day}T${task.startAt.slice(11)}`
  const shift = Date.parse(`${startAt}Z`) - Date.parse(`${task.startAt}Z`)
  return { start: day, startAt, endAt: task.endAt ? new Date(Date.parse(`${task.endAt}Z`) + shift).toISOString().slice(0, 19) : null }
}

export interface TaskCalendarEntry { date: string; time?: string; endTime?: string; label: string; deadline: boolean }
/** Limit expansion to the requested visible horizon, including continuation days. */
export function taskCalendarEntries(task: TaskSchedule & Pick<Task, 'due'>, from: string, to: string): TaskCalendarEntry[] {
  const result: TaskCalendarEntry[] = []
  const start = task.startAt?.slice(0, 10) || task.start
  if (start && task.startAt && task.endAt) {
    const end = task.endAt.slice(0, 10)
    for (let day = start < from ? from : start; day <= end && day <= to; day = new Date(Date.parse(`${day}T00:00:00Z`) + 86400000).toISOString().slice(0, 10)) {
      const endTime = day === end ? task.endAt.slice(11, 16) : '24:00'
      if (day === end && endTime === '00:00' && day !== start) break
      result.push({ date: day, time: day === start ? task.startAt.slice(11, 16) : '00:00', endTime,
        label: day === task.due ? '执行 · 当天截止' : day === start ? '执行' : '执行续段', deadline: false })
    }
  } else if (start && start >= from && start <= to) {
    const period = task.schedulePeriod ? PERIOD_LABELS[task.schedulePeriod] : '未指定时间'
    result.push({ date: start, label: start === task.due ? `${task.schedulePeriod ? period + '执行' : '执行'} · 当天截止` : `执行（${period}）`, deadline: false })
  }
  if (task.due && task.due >= from && task.due <= to && !result.some(item => item.date === task.due)) {
    result.push({ date: task.due, label: '截止', deadline: true })
  }
  return result
}

/** 执行提示与截止日期独立；包含今天的跨日时段显示续段，不补任意时刻。 */
export function taskExecutionLabel(task: TaskSchedule, today: string): string {
  const start = task.startAt?.slice(0, 10) || task.start
  if (!start) return ''
  const current = taskCalendarEntries({ ...task, due: '' }, today, today)[0]
  if (current) return `今天执行${current.time ? ` ${current.time}–${current.endTime}` : `（${task.schedulePeriod ? PERIOD_LABELS[task.schedulePeriod] : '未指定时间'}）`}`
  const date = start.slice(0, 4) === today.slice(0, 4) ? start.slice(5) : start
  return `${date}执行${task.startAt ? ` ${task.startAt.slice(11, 16)}` : `（${task.schedulePeriod ? PERIOD_LABELS[task.schedulePeriod] : '未指定时间'}）`}`
}
