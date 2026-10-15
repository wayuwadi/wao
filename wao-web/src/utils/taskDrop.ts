import { taskDropIsNesting } from './taskHierarchy'

/** 普通清单行上半区插入前方，下半区插入后方。 */
export function taskDropAfter(event: DragEvent): boolean {
  const box = (event.currentTarget as HTMLElement).getBoundingClientRect()
  return box.height > 0 && event.clientY > box.top + box.height / 2
}

/** 项目层级拖动：边缘排序，中间挂到目标下面；与普通清单排序分开。 */
export function taskHierarchyDrop(event: DragEvent): 'before' | 'inside' | 'after' {
  const box = (event.currentTarget as HTMLElement).getBoundingClientRect()
  return taskDropIsNesting(event.clientY, box.top, box.height) ? 'inside' : taskDropAfter(event) ? 'after' : 'before'
}
