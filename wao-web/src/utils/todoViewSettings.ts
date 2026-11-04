import type { Task } from '@/types'
import { PROJECT_PRIORITY_LABELS, projectStatusLabel } from '@/utils/projectStatusPresentation'
import { deadlineInfo, shiftDay } from '@/utils/taskDeadline'

export const TODO_VIEWS = [
  { id: 'list', label: '列表视图', icon: 'ri-list-check' },
  { id: 'board', label: '看板视图', icon: 'ri-kanban-view' },
  { id: 'timeline', label: '时间线视图', icon: 'ri-timeline-view' },
  { id: 'quadrant', label: '四象限', icon: 'ri-layout-grid-line' },
] as const
export const TODO_GROUPS = { auto: '默认', list: '清单', time: '时间', created: '创建时间', tags: '标签', priority: '优先级', status: '状态', none: '无' } as const
export const TODO_SORTS = { manual: '手动', time: '时间', created: '创建时间', modified: '修改时间', title: '标题', tags: '标签', priority: '优先级' } as const
export type TodoLayout = typeof TODO_VIEWS[number]['id']
export type TodoGroup = keyof typeof TODO_GROUPS
export type TodoSort = keyof typeof TODO_SORTS
export type TodoDirection = 'asc' | 'desc'
export type TodoViewSettings = { layout: TodoLayout; group: TodoGroup; sort: TodoSort; direction: TodoDirection; showDone: boolean; showDetails: boolean }
export type TodoDisplaySettings = { date: 'time' | 'countdown'; detail: 'sidebar' | 'popup'; sync: boolean }
export const defaultTodoView = (): TodoViewSettings => ({ layout: 'list', group: 'auto', sort: 'manual', direction: 'asc', showDone: false, showDetails: false })

function object(raw: string | null): Record<string, unknown> {
  try { return JSON.parse(raw || '{}') ?? {} } catch { return {} }
}
export function decodeTodoView(raw: string | null): TodoViewSettings {
  const v = object(raw), defaults = defaultTodoView()
  return {
    layout: TODO_VIEWS.find(item => item.id === v.layout)?.id ?? defaults.layout,
    group: typeof v.group === 'string' && Object.prototype.hasOwnProperty.call(TODO_GROUPS, v.group) ? v.group as TodoGroup : defaults.group,
    sort: typeof v.sort === 'string' && Object.prototype.hasOwnProperty.call(TODO_SORTS, v.sort) ? v.sort as TodoSort : defaults.sort,
    direction: v.direction === 'desc' ? 'desc' : 'asc',
    showDone: v.showDone === true, showDetails: v.showDetails === true,
  }
}
export function decodeTodoDisplay(raw: string | null): TodoDisplaySettings {
  const v = object(raw)
  return { date: v.date === 'countdown' ? 'countdown' : 'time', detail: v.detail === 'popup' ? 'popup' : 'sidebar', sync: v.sync !== false }
}
export function todoViewPreferenceKey(scope: string) {
  if (/^[a-z0-9:_-]{1,47}$/i.test(scope) && !scope.startsWith('tag:')) return 'todo-view-v1:' + scope
  // Keep arbitrary tag names out of API paths and within the preference key limit.
  let hash = 0xcbf29ce484222325n
  for (const byte of new TextEncoder().encode(scope)) hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * 0x100000001b3n)
  return 'todo-view-v1:scope-' + hash.toString(16)
}

type TaskMeta = { tags: (id: string) => readonly string[]; list: (task: Task) => string; priorityUnset: (id: string) => boolean; today: string }
const priorityRank = (task: Task, meta: TaskMeta) => meta.priorityUnset(task.id) ? 4 : Number(task.pr.slice(1))
const collator = new Intl.Collator('zh-CN', { numeric: true })
const tagsFor = (task: Task, meta: TaskMeta) => [...meta.tags(task.id)].sort(collator.compare)
const day = (value?: string | null) => /^\d{4}-\d{2}-\d{2}/.test(value ?? '') ? value!.slice(0, 10) : ''
const scheduledDay = (task: Task) => day(task.startAt || task.start)
export const todoTaskDate = (task: Task) => day(task.due) || scheduledDay(task)
const timestamp = (value?: string | null) => {
  if (!value) return ''
  const date = Date.parse(/Z$|[+-]\d{2}:\d{2}$/.test(value) ? value : value + (value.includes('T') ? '+08:00' : 'T00:00:00+08:00'))
  return Number.isFinite(date) ? date : ''
}

export function sortTodoTasks(tasks: Task[], sort: TodoSort, direction: TodoDirection, meta: TaskMeta) {
  if (sort === 'manual') return tasks
  const value = (t: Task) => {
    switch (sort) {
      case 'priority': return priorityRank(t, meta)
      case 'time': return timestamp(!t.due && t.startAt ? t.startAt : todoTaskDate(t))
      case 'created': return timestamp(t.createdAt)
      case 'modified': return timestamp(t.updatedAt || t.createdAt)
      case 'tags': return tagsFor(t, meta).join('\u0000')
      case 'title': return t.title.trim()
    }
  }
  const keys = new Map(tasks.map(task => [task.id, value(task)]))
  return [...tasks].sort((a, b) => {
    const x = keys.get(a.id), y = keys.get(b.id)
    if (!x && x !== 0) return !y && y !== 0 ? 0 : 1
    if (!y && y !== 0) return -1
    if (typeof x === 'number' && typeof y === 'number') return (x - y) * (direction === (sort === 'priority' ? 'desc' : 'asc') ? 1 : -1)
    return collator.compare(String(x), String(y)) * (direction === 'asc' ? 1 : -1)
  })
}

export function todoTaskGroups(tasks: Task[], group: TodoGroup, meta: TaskMeta, todayOnly = false) {
  const statusOrder = ['待办', '收集中', '进行中', 'AI 进行中', '待验证', '已完成', '已放弃']
  function entries(t: Task): { id: string; name: string; rank?: number }[] {
    switch (group) {
      case 'list': return [{ id: 'list:' + t.project, name: meta.list(t), rank: t.project ? 1 : 0 }]
      case 'status': return [{ id: t.status, name: projectStatusLabel(t.status), rank: statusOrder.indexOf(t.status) }]
      case 'priority': return [{ id: t.pr + priorityRank(t, meta), name: meta.priorityUnset(t.id) ? '无优先级' : PROJECT_PRIORITY_LABELS[t.pr], rank: priorityRank(t, meta) }]
      case 'tags': return tagsFor(t, meta).map(tag => ({ id: 'tag:' + tag, name: tag })).concat(meta.tags(t.id).length ? [] : [{ id: 'untagged', name: '无标签' }])
      case 'created': {
        const date = day(t.createdAt)
        return [{ id: date || 'undated', name: date || '无创建时间' }]
      }
      case 'time': {
        const date = todoTaskDate(t)
        if (deadlineInfo(t.due, meta.today, t.status === '已完成').overdue > 0) return [{ id: 'overdue', name: '已过期', rank: 0 }]
        if (todayOnly || date === meta.today) return [{ id: 'today', name: '今天', rank: 1 }]
        if (date === shiftDay(meta.today, 1)) return [{ id: 'tomorrow', name: '明天', rank: 2 }]
        return date ? [{ id: date, name: date, rank: 3 }] : [{ id: 'undated', name: '无日期', rank: 4 }]
      }
      default: return [{ id: 'all', name: '任务' }]
    }
  }
  const groups = new Map<string, { id: string; name: string; rank?: number; tasks: Task[] }>()
  for (const task of tasks) for (const entry of entries(task)) {
    if (!groups.has(entry.id)) groups.set(entry.id, { ...entry, tasks: [] })
    groups.get(entry.id)!.tasks.push(task)
  }
  const missing = (id: string) => id === 'untagged' || id === 'undated' ? 1 : 0
  return [...groups.values()].sort((a, b) => (a.rank ?? 0) - (b.rank ?? 0) || missing(a.id) - missing(b.id) || collator.compare(a.name, b.name))
}

export function todoDateLabel(date: string, today: string, mode: TodoDisplaySettings['date']) {
  const offset = Math.round((Date.parse(date + 'T00:00:00Z') - Date.parse(today + 'T00:00:00Z')) / 86400000)
  if (mode === 'countdown' && offset !== 0) return offset > 0 ? `还有 ${offset} 天` : `${-offset} 天前`
  return offset === 0 ? '今天' : offset === -1 ? '昨天' : offset === 1 ? '明天' : deadlineInfo(date, today).label
}

export const TODO_TIMELINE_DAYS = 14
export function todoTimelinePlacement(task: Task, first: string, last: string) {
  const start = scheduledDay(task), due = day(task.due)
  // A range that ends at midnight does not occupy the next day.
  const end = task.endAt && /T00:00(?::00(?:\.0+)?)?$/.test(task.endAt) && day(task.endAt) > start
    ? shiftDay(day(task.endAt), -1) : day(task.endAt)
  const column = (date: string) => (Date.parse(date + 'T00:00:00Z') - Date.parse(first + 'T00:00:00Z')) / 86400000 + 1
  const execution = start && start <= last && (end || start) >= first
    ? { from: column(start < first ? first : start), to: column(end && end > last ? last : end || start) + 1 } : null
  return { execution, deadline: due && due >= first && due <= last ? column(due) : null, undated: !start && !due }
}
