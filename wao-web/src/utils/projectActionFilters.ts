import type { Task } from '@/types'
import { isTaskFinished } from './projectCompletion'

export const PROJECT_ACTION_FILTERS = [
  { id: 'verify', label: '待验证' }, { id: 'waiting', label: '等待反馈' }, { id: 'overdue', label: '逾期' },
] as const
export type ProjectActionFilter = 'all' | typeof PROJECT_ACTION_FILTERS[number]['id']

/** 按任务实体判断需处理事项；清单命中保留主任务，不重复累计清单数量。 */
export function matchesProjectAction(task: Task, filter: ProjectActionFilter, today: string): boolean {
  if (filter === 'all') return true
  if (isTaskFinished(task)) return false
  if (filter === 'verify') return task.status === '待验证' || task.subs.some(sub => !sub.done && sub.status === '待验证')
  if (filter === 'waiting') return !!task.waiting?.trim()
  return !!task.due && task.due < today
}
