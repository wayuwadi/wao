import type { SubtaskState, TaskStep } from '@/types'

export const SUBTASK_DRAG_TYPE = 'application/x-wao-subtask'
/** 深度优先展示，原始下标仍用于兼容历史键；损坏的孤节点也可见。 */
export function subtaskRows(taskId: string, subs: TaskStep[]) {
  const result: { sub: TaskStep; idx: number; key: string; depth: number }[] = []
  const visited = new Set<string>()
  function visit(parent: string | null, depth: number) {
    subs.forEach((sub, idx) => {
      const key = sub.id || `${taskId}#${idx}`
      if ((sub.parentId || null) !== parent || visited.has(key)) return
      visited.add(key)
      result.push({ sub, idx, key, depth })
      visit(key, depth + 1)
    })
  }
  visit(null, 0)
  subs.forEach((sub, idx) => {
    const key = sub.id || `${taskId}#${idx}`
    if (!visited.has(key)) { visited.add(key); result.push({ sub, idx, key, depth: 0 }); visit(key, 1) }
  })
  return result
}

/** 拖拽负载只记录身份，不从浏览器接收任意任务正文。 */
export function readSubtaskDrag(event: DragEvent): { taskId: string; subId: string } | null {
  try {
    const value = JSON.parse(event.dataTransfer?.getData(SUBTASK_DRAG_TYPE) || 'null')
    return typeof value?.taskId === 'string' && typeof value?.subId === 'string' ? value : null
  } catch { return null }
}

/** 子任务三态：完成态以 done 为准，未完成时再看是否标了「进行中」。 */
export function subtaskState(sub: Pick<TaskStep, 'done' | 'status'>): SubtaskState {
  if (sub.done) return '已完成'
  return sub.status === '进行中' ? '进行中' : '未开始'
}

export const SUBTASK_STATES: SubtaskState[] = ['未开始', '进行中', '已完成']

/** 子任务三态的描边颜色，与主任务表格的状态胶囊同一套色。 */
export const SUBTASK_STATE_COLOR: Record<SubtaskState, string> = {
  未开始: '#338fe5',
  进行中: '#f08c00',
  已完成: '#3fb96f',
}
