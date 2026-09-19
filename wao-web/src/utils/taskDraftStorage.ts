import type { Task } from '@/types'

const PREFIX = 'wao-task-draft-v2:'
const WINDOW_KEY = 'wao-task-draft-window-v2'
interface StoredDraft { task: Task; updatedAt: number; windowId: string }
export function taskDraftWindowId(): string {
  try {
    let id = sessionStorage.getItem(WINDOW_KEY)
    if (!id) { id = crypto.randomUUID(); sessionStorage.setItem(WINDOW_KEY, id) }
    return id
  } catch { return crypto.randomUUID() }
}
const prefix = (member: string) => `${PREFIX}${encodeURIComponent(member || 'anonymous')}:`
const key = (member: string, id: string, windowId: string) => `${prefix(member)}${encodeURIComponent(id)}:${windowId}`

/** 每个任务和编辑窗口独立写入，另一窗口不能整包覆盖或清除草稿。 */
export function writeTaskDraft(member: string, windowId: string, task: Task): void {
  localStorage.setItem(key(member, task.id, windowId), JSON.stringify({ task, windowId, updatedAt: Date.now() } satisfies StoredDraft))
}
export function removeTaskDraft(member: string, windowId: string, id: string): void {
  localStorage.removeItem(key(member, id, windowId))
}
/** 优先恢复本窗口版本；其他窗口的独立副本始终保留。 */
export function readTaskDrafts(member: string, windowId: string): Record<string, Task> {
  const selected: Record<string, StoredDraft> = {}
  for (let index = 0; index < localStorage.length; index++) {
    const storedKey = localStorage.key(index)
    if (!storedKey?.startsWith(prefix(member))) continue
    try {
      const row = JSON.parse(localStorage.getItem(storedKey)!) as StoredDraft
      if (!row.task?.id || !row.windowId) continue
      const previous = selected[row.task.id]
      if (!previous || (row.windowId === windowId && previous.windowId !== windowId)
        || (previous.windowId !== windowId && row.updatedAt > previous.updatedAt)) selected[row.task.id] = row
    } catch { /* 损坏的一项不能阻止其他草稿恢复 */ }
  }
  return Object.fromEntries(Object.entries(selected).map(([id, row]) => [id, row.task]))
}
