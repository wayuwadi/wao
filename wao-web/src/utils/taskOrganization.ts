import type { Project, Task } from '@/types'
import { matchesTaskWorkspaceFilter, type TaskWorkspaceFilter } from './taskWorkspaceFilter'

export interface TaskOrganizationFilter extends TaskWorkspaceFilter {
  status: 'all' | 'unfinished' | 'completed'
  from: string
  to: string
}
export interface SavedTaskFilter { id: string; name: string; filter: TaskOrganizationFilter }
export interface TaskFolder { id: string; name: string }
export interface TaskFolderSettings { folders: TaskFolder[]; assignments: Record<string, string> }
const dates = new Set(['all', 'today', 'tomorrow', 'week', 'overdue', 'undated'])
const strings = (raw: unknown, max = 100): string[] => Array.isArray(raw)
  ? [...new Set(raw.filter((v): v is string => typeof v === 'string' && v.length <= 100))].slice(0, max) : []
export function emptyTaskOrganizationFilter(): TaskOrganizationFilter {
  return { projects: [], tags: [], tagMode: 'any', date: 'all', priorities: [], status: 'all', from: '', to: '' }
}
function day(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return ''
  const parsed = new Date(`${value}T12:00:00Z`)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value ? value : ''
}
export function normalizeTaskOrganizationFilter(raw: unknown): TaskOrganizationFilter {
  const value = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {}
  return {
    projects: strings(value.projects), tags: strings(value.tags, 20),
    tagMode: value.tagMode === 'all' || value.tagMode === 'none' ? value.tagMode : 'any',
    date: dates.has(String(value.date)) ? value.date as TaskOrganizationFilter['date'] : 'all',
    priorities: strings(value.priorities).filter(p => /^P[0-3]$/.test(p)),
    status: value.status === 'completed' || value.status === 'unfinished' ? value.status : 'all',
    from: day(value.from), to: day(value.to),
  }
}
export function taskOrganizationFilterCount(filter: TaskOrganizationFilter): number {
  return [filter.projects.length, filter.tags.length || filter.tagMode === 'none', filter.priorities.length,
    filter.date !== 'all', filter.status !== 'all', filter.from || filter.to].filter(Boolean).length
}
/** Only narrows the caller's authorized scope; never fetches or widens task visibility. */
export function matchesOrganizedTask(task: Task, tags: readonly string[], filter: TaskOrganizationFilter, today: string): boolean {
  if (filter.status === 'completed' && task.status !== '已完成') return false
  if (filter.status === 'unfinished' && ['已完成', '已放弃'].includes(task.status)) return false
  if (filter.date === 'overdue' && ['已完成', '已放弃'].includes(task.status)) return false
  if (filter.from || filter.to) {
    const taskDates = [task.startAt?.slice(0, 10) || task.start, task.due].filter((v): v is string => !!v)
    if (!taskDates.some(value => (!filter.from || value >= filter.from) && (!filter.to || value <= filter.to))) return false
  }
  // No active filter leaves abandoned/note handling to the existing workspace.
  if (!taskOrganizationFilterCount(filter)) return true
  // An explicit abandoned workspace can still narrow by list, tag and priority.
  // Smart date scopes intentionally exclude abandoned tasks in the shared matcher.
  if (task.status === '已放弃' && filter.date === 'all')
    return matchesTaskWorkspaceFilter({ ...task, status: '待办' }, tags, filter, today)
  return matchesTaskWorkspaceFilter(task, tags, filter, today)
}
export function decodeTaskFilters(raw: string | null): SavedTaskFilter[] {
  try {
    const value: unknown = JSON.parse(raw || '[]')
    if (!Array.isArray(value)) return []
    const ids = new Set<string>()
    return value.filter(item => item && typeof item.id === 'string' && item.id.length <= 100 && !ids.has(item.id)
      && typeof item.name === 'string' && item.name.trim() && (ids.add(item.id), true))
      .slice(0, 30).map(item => ({ id: item.id, name: item.name.trim().slice(0, 40), filter: normalizeTaskOrganizationFilter(item.filter) }))
  } catch { return [] }
}
export function decodeTaskFolders(raw: string | null): TaskFolderSettings {
  try {
    const value = JSON.parse(raw || '{}')
    const ids = new Set<string>()
    const folders: TaskFolder[] = Array.isArray(value.folders) ? value.folders.filter((item: TaskFolder) => item
      && typeof item.id === 'string' && item.id.length <= 100 && !ids.has(item.id)
      && typeof item.name === 'string' && item.name.trim() && (ids.add(item.id), true))
      .slice(0, 30).map((item: TaskFolder) => ({ id: item.id, name: item.name.trim().slice(0, 40) })) : []
    const valid = new Set(folders.map(item => item.id))
    const assignments = Object.fromEntries(Object.entries(value.assignments && typeof value.assignments === 'object' ? value.assignments : {})
      .filter(([key, folder]) => key.length <= 100 && typeof folder === 'string' && valid.has(folder))) as Record<string, string>
    return { folders, assignments }
  } catch { return { folders: [], assignments: {} } }
}
export function organizedProjectGroups(projects: readonly Project[], settings: TaskFolderSettings) {
  const groups = settings.folders.map(folder => ({ ...folder, projects: [] as Project[] }))
  const byId = new Map(groups.map(group => [group.id, group]))
  const ungrouped: Project[] = []
  for (const project of projects) {
    const group = byId.get(settings.assignments[project.id] || '')
    if (group) group.projects.push(project)
    else ungrouped.push(project)
  }
  return [...groups, { id: '', name: groups.length ? '未分组' : '', projects: ungrouped }]
}
