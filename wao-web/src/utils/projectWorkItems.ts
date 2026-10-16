import type { Task } from '@/types'
import { subtaskRows, subtaskState } from './subtaskTree'

/** 仅用于视图的身份映射，虚拟 id 永不传入任务写接口。 */
export interface ProjectItemRef {
  taskId: string
  subId?: string
  parentKey?: string
  parentTitle?: string
  priorityUnset?: boolean
}
export type ProjectItemRefs = Record<string, ProjectItemRef>
export const subItemKey = (taskId: string, subId: string) => `sub:${taskId}:${subId}`

/** 两类子任务投影成一致的只读展示项，普通子事项不继承父任务业务属性。 */
export function projectWorkItems(tasks: readonly Task[]) {
  const refs: ProjectItemRefs = {}
  const items: Task[] = []
  const byId = new Map(tasks.map(task => [task.id, task]))
  for (const task of tasks) {
    const parent = task.parentTaskId ? byId.get(task.parentTaskId) : undefined
    refs[task.id] = { taskId: task.id, parentKey: parent?.id, parentTitle: parent?.title }
    items.push(task)
    const rows = subtaskRows(task.id, task.subs)
    for (const { sub, key } of rows) {
      const parentSub = rows.find(row => row.key === sub.parentId)
      const parentKey = parentSub ? subItemKey(task.id, parentSub.key) : task.id
      const id = subItemKey(task.id, key)
      refs[id] = { taskId: task.id, subId: key, parentKey, parentTitle: parentSub?.sub.text ?? task.title, priorityUnset: !sub.pr }
      const status = subtaskState(sub)
      items.push({
        id, title: sub.text, number: sub.number, project: task.project, parentTaskId: parentKey,
        status: status === '未开始' ? '待办' : status, pr: sub.pr ?? 'P2', owner: sub.owner ?? '',
        createdBy: sub.createdBy, createdAt: sub.createdAt, groupId: task.groupId,
        due: sub.due ?? '', desc: sub.desc ?? '', parts: [], steps: [],
        // 仅保留直接后代的完成语义，避免已完成父事项隐藏未完成后代。
        subs: rows.filter(row => row.sub.parentId === key).map(row => row.sub),
        files: [], docs: sub.docs ?? [], linkTasks: [], comments: sub.comments ?? [], custom: [], activity: [],
      })
    }
  }
  return { items, refs }
}

/** 筛选只命中后代时补回同项目祖先，作为弱化上下文；环路和缺失父级均安全停止。 */
export function withProjectAncestors<T extends Task>(matches: T[], all: T[]): T[] {
  const byId = new Map(all.map(task => [task.id, task]))
  const keep = new Set(matches.map(task => task.id))
  for (const task of matches) {
    let parentId = task.parentTaskId
    const seen = new Set([task.id])
    while (parentId && !seen.has(parentId)) {
      seen.add(parentId)
      const parent = byId.get(parentId)
      if (!parent || parent.project !== task.project) break
      keep.add(parentId); parentId = parent.parentTaskId
    }
  }
  return all.filter(task => keep.has(task.id))
}

export interface ProjectSubtaskDisplay { show: boolean; collapsed: string[] }
export function decodeProjectSubtaskDisplay(raw: string | null): ProjectSubtaskDisplay {
  try {
    const value = JSON.parse(raw ?? 'null')
    return { show: value?.show !== false, collapsed: Array.isArray(value?.collapsed)
      ? value.collapsed.filter((id: unknown): id is string => typeof id === 'string') : [] }
  } catch { return { show: true, collapsed: [] } }
}
