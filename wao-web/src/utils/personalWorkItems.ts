import type { Task } from '@/types'
import type { ProjectItemRefs } from './projectWorkItems'

/** 个人待办按负责人归属；未指派的内嵌子项沿父链归属，显式指派不会被父任务覆盖。 */
export function personalWorkItems(items: Task[], refs: ProjectItemRefs, memberId: string): Task[] {
  if (!memberId) return []
  const byId = new Map(items.map(item => [item.id, item]))
  return items.filter(item => {
    let current: Task | undefined = item
    const seen = new Set<string>()
    while (current && !seen.has(current.id)) {
      seen.add(current.id)
      if (current.owner) return current.owner === memberId
      const ref = refs[current.id]
      if (!ref?.subId) return current.createdBy === memberId
      current = byId.get(ref.parentKey ?? ref.taskId)
    }
    return false
  })
}
