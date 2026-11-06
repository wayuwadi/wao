import type { TaskSchedule } from './taskSchedule'

const MINUTE = 60_000
/** Local wall-clock arithmetic; do not reinterpret Shanghai schedule values in the browser's timezone. */
export function scheduleAtTime(task: TaskSchedule, day: string, minute: number, grabbedDay?: string): TaskSchedule {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || !Number.isFinite(minute) || minute < 0 || minute >= 1440) throw new Error('请选择有效的执行时间')
  const base = Date.parse(`${day}T00:00:00Z`)
  if (!Number.isFinite(base) || new Date(base).toISOString().slice(0, 10) !== day) throw new Error('请选择有效日期')
  const previousDuration = Date.parse(`${task.endAt}Z`) - Date.parse(`${task.startAt}Z`)
  const duration = previousDuration > 0 ? previousDuration : 60 * MINUTE
  const continuationOffset = grabbedDay && task.startAt && grabbedDay > task.startAt.slice(0, 10)
    ? Date.parse(`${grabbedDay}T00:00:00Z`) - Date.parse(`${task.startAt}Z`) : 0
  const start = base + Math.round(minute) * MINUTE - (Number.isFinite(continuationOffset) ? continuationOffset : 0)
  return { start: new Date(start).toISOString().slice(0, 10), startAt: new Date(start).toISOString().slice(0, 16), endAt: new Date(start + duration).toISOString().slice(0, 16), schedulePeriod: null }
}

export function validateSchedule(value: TaskSchedule): string {
  const date = (raw: string) => /^\d{4}-\d{2}-\d{2}$/.test(raw) && Number.isFinite(Date.parse(`${raw}T00:00:00Z`)) && new Date(`${raw}T00:00:00Z`).toISOString().slice(0, 10) === raw
  const time = (raw: string) => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(raw) && Number.isFinite(Date.parse(`${raw}Z`)) && new Date(`${raw}Z`).toISOString().slice(0, 16) === raw.slice(0, 16)
  if (value.start && !date(value.start)) return '请选择有效的执行日期'
  if (value.startAt || value.endAt) {
    if (!value.startAt || !value.endAt || !time(value.startAt) || !time(value.endAt)) return '请填写有效的开始和结束时间'
    if (Date.parse(`${value.endAt}Z`) <= Date.parse(`${value.startAt}Z`)) return '结束时间必须晚于开始时间'
    if (value.start && value.start !== value.startAt.slice(0, 10)) return '执行日期应与开始时间一致'
  }
  return ''
}

export function scheduleSummary(value: TaskSchedule): string {
  if (value.startAt && value.endAt) {
    const start = value.startAt.replace('T', ' ').slice(0, 16)
    const end = value.endAt.slice(0, 10) === value.startAt.slice(0, 10) ? value.endAt.slice(11, 16) : value.endAt.replace('T', ' ').slice(0, 16)
    return `${start}–${end}`
  }
  return value.start ? `${value.start} · ${value.schedulePeriod ? ({ MORNING: '早晨', AFTERNOON: '下午', EVENING: '晚上' } as const)[value.schedulePeriod] : '全天'}` : '未安排执行时间'
}

/** Greedy interval lanes: overlapping blocks stay separately reachable, including transitive overlap. */
export function timeLanes<T extends { time?: string; endTime?: string }>(events: T[]) {
  const minutes = (raw: string) => Number(raw.slice(0, 2)) * 60 + Number(raw.slice(3, 5))
  const values = events.filter(event => event.time).map(event => ({ event, start: minutes(event.time!), end: event.endTime ? minutes(event.endTime) : minutes(event.time!) + 45 }))
    .sort((a, b) => a.start - b.start || b.end - a.end)
  const result = new Map<T, { lane: number; count: number }>()
  let group: typeof values = [], end = -1
  function flush() {
    const laneEnds: number[] = []
    for (const item of group) {
      const available = laneEnds.findIndex(value => value <= item.start)
      const lane = available < 0 ? laneEnds.length : available
      laneEnds[lane] = Math.max(item.end, item.start + 24)
      result.set(item.event, { lane, count: 1 })
    }
    for (const item of group) result.get(item.event)!.count = laneEnds.length
    group = []
  }
  for (const item of values) {
    if (item.start >= end && group.length) flush()
    group.push(item); end = Math.max(group.length === 1 ? -1 : end, item.end, item.start + 24)
  }
  flush()
  return result
}
