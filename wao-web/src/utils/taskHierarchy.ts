/** 筛选隐藏父任务时将匹配的子任务提升显示，避免结果凭空消失。 */
export function taskTreeRows<T extends { id: string; parentTaskId?: string | null }>(tasks: T[], collapsed: string[]) {
  const ids = new Set(tasks.map(t => t.id))
  const children = new Map<string, T[]>()
  const roots: T[] = []
  for (const task of tasks) {
    if (task.parentTaskId && ids.has(task.parentTaskId)) {
      const siblings = children.get(task.parentTaskId) ?? []
      siblings.push(task)
      children.set(task.parentTaskId, siblings)
    } else roots.push(task)
  }
  const rows: { task: T; depth: number }[] = []
  const visited = new Set<string>()
  const visit = (task: T, depth: number) => {
    if (visited.has(task.id)) return
    visited.add(task.id)
    rows.push({ task, depth })
    if (!collapsed.includes(task.id)) for (const child of children.get(task.id) ?? []) visit(child, depth + 1)
  }
  roots.forEach(task => visit(task, 0))
  return rows
}

/** 中间一半是父子归属，顶部/底部四分之一保留排序手势。 */
export function taskDropIsNesting(clientY: number, top: number, height: number) {
  return height <= 0 || (clientY >= top + height * .25 && clientY <= top + height * .75)
}
