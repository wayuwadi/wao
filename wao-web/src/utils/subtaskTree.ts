import type { TaskStep } from '@/types'

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
