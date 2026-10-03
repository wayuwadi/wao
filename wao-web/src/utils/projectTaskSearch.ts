import type { Task } from '@/types'

export interface ProjectTaskHit {
  field: string
  snippet: string
  subId?: string
  context: string[]
}
export interface ProjectTaskMatch { task: Task; context: string[]; hits: ProjectTaskHit[] }

/** 字面匹配并截取命中附近原文；不把用户输入解释成正则或 HTML。 */
function snippet(value: string | undefined, query: string): string | null {
  if (!value) return null
  const at = value.toLocaleLowerCase().indexOf(query)
  if (at < 0) return null
  const start = Math.max(0, at - 35), end = Math.min(value.length, at + Math.min(query.length, 120) + 75)
  return `${start ? '…' : ''}${value.slice(start, end)}${end < value.length ? '…' : ''}`
}

/** 只接受当前项目任务集合；祖先上下文也严格限制在同项目，坏数据环路安全停止。 */
export function searchProjectTasks(tasks: readonly Task[], rawQuery: string): ProjectTaskMatch[] {
  const query = rawQuery.trim().toLocaleLowerCase()
  if (!query) return []
  const byId = new Map(tasks.map(task => [task.id, task]))
  return tasks.flatMap(task => {
    const hits: ProjectTaskHit[] = []
    const add = (field: string, text: string | undefined, context: string[] = [], subId?: string) => {
      const value = snippet(text, query)
      if (value !== null) hits.push({ field, snippet: value, context, subId })
    }
    add('任务编号', task.number == null ? task.id : `#${task.number}`)
    add('任务标题', task.title)
    add('任务正文', task.desc)
    const subs = new Map(task.subs.map((sub, index) => [sub.id ?? `${task.id}#${index}`, sub]))
    task.subs.forEach((sub, index) => {
      const subId = sub.id ?? `${task.id}#${index}`
      const context = [sub.text], visited = new Set([subId])
      let parentId = sub.parentId
      while (parentId && !visited.has(parentId)) {
        visited.add(parentId)
        const parent = subs.get(parentId)
        if (!parent) break
        context.unshift(parent.text)
        parentId = parent.parentId
      }
      add('子任务编号', sub.number == null ? subId : `#${sub.number}`, context, subId)
      add('子任务标题', sub.text, context, subId)
      add('子任务正文', sub.desc, context, subId)
    })
    if (!hits.length) return []
    const context: string[] = [], visited = new Set([task.id])
    let parentId = task.parentTaskId
    while (parentId && !visited.has(parentId)) {
      visited.add(parentId)
      const parent = byId.get(parentId)
      if (!parent || parent.project !== task.project) break
      context.unshift(parent.title)
      parentId = parent.parentTaskId
    }
    return [{ task, context, hits }]
  })
}
