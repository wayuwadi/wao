import type { Task } from '@/types'

type CompletionTask = Pick<Task, 'id' | 'status' | 'subs'>

/** 一条任务实体仅计一次；内嵌清单为扁平存储，所有深度的条目各计一次。 */
export function isTaskFinished(task: Pick<Task, 'status' | 'subs'>): boolean {
  return task.status === '已完成' && task.subs.every(sub => sub.done)
}

/** parentTaskId 关联的任务仍是独立实体，不能再作为内嵌清单重复累计。 */
export function projectCompletion(tasks: CompletionTask[]) {
  const entities = [...new Map(tasks.map(task => [task.id, task])).values()]
  const total = entities.length
  const open = entities.filter(task => !isTaskFinished(task)).length
  const subs = entities.flatMap(task => task.subs)
  return {
    total, open, finished: total - open,
    subTotal: subs.length, subOpen: subs.filter(sub => !sub.done).length,
    completionRate: total ? (total - open) / total : null,
  }
}

export function projectCompletionLabel(tasks: CompletionTask[]) {
  const { total, open } = projectCompletion(tasks)
  return total ? `未收尾 ${open} 项／共 ${total} 项任务` : '暂无任务'
}
