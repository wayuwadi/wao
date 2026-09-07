import type { Priority } from '@/types'

/** 用户确认：四象限直接对应现有优先级，不从截止日期推断。 */
export const TASK_QUADRANTS: { priority: Priority; label: string; hint: string; color: string }[] = [
  { priority: 'P0', label: '重要紧急', hint: '优先处理', color: '#BC4141' },
  { priority: 'P1', label: '重要不紧急', hint: '安排计划', color: '#A96A10' },
  { priority: 'P2', label: '不重要紧急', hint: '协调处理', color: '#3A67C2' },
  { priority: 'P3', label: '不重要不紧急', hint: '有空再做', color: '#25866F' },
]

export type TodoTaskView = 'list' | 'board' | 'quadrant'
export function readTodoTaskView(): TodoTaskView {
  try {
    const view = localStorage.getItem('wao-todo-view-v1')
    return view === 'board' || view === 'quadrant' ? view : 'list'
  } catch { return 'list' }
}
