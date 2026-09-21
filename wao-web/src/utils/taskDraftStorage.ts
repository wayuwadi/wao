import type { Task } from '@/types'

const PREFIX = 'wao-task-draft-v2:'
const WINDOW_KEY = 'wao-task-draft-window-v2'
interface Source { key: string; revision: string }
interface StoredDraft { task: Task; updatedAt: number; windowId: string; revision?: string; sources?: Source[] }
const recoveredSources = new Map<string, Source[]>()
const revision = (row: StoredDraft) => row.revision ?? JSON.stringify([row.updatedAt, row.task])
const deletedKey = (member: string, id: string) => `wao-task-deleted-v1:${encodeURIComponent(member || 'anonymous')}:${encodeURIComponent(id)}`
export const isTaskDraftDeleted = (member: string, id: string) => localStorage.getItem(deletedKey(member, id)) === 'true'
/** 仅在服务端确认删除后记录；旧窗口的迟到草稿不得复活任务。 */
export function markTaskDraftDeleted(member: string, id: string): void {
  localStorage.setItem(deletedKey(member, id), 'true')
}
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
  if (isTaskDraftDeleted(member, task.id)) return
  const target = key(member, task.id, windowId)
  localStorage.setItem(target, JSON.stringify({ task, windowId, updatedAt: Date.now(), revision: crypto.randomUUID(),
    sources: recoveredSources.get(target) ?? [] } satisfies StoredDraft))
}
export function removeTaskDraft(member: string, windowId: string, id: string): void {
  const target = key(member, id, windowId)
  for (const source of recoveredSources.get(target) ?? []) {
    if (!source.key.startsWith(`${prefix(member)}${encodeURIComponent(id)}:`) || source.key === target) continue
    const raw = localStorage.getItem(source.key)
    if (raw) {
      try { if (revision(JSON.parse(raw) as StoredDraft) === source.revision) localStorage.removeItem(source.key) }
      catch { /* 不清理不能确认来源的内容。 */ }
    }
  }
  localStorage.removeItem(target)
  recoveredSources.delete(target)
}
/** 优先恢复本窗口版本；其他窗口的独立副本始终保留。 */
export function readTaskDrafts(member: string, windowId: string): Record<string, Task> {
  const selected: Record<string, StoredDraft> = {}
  const candidates: StoredDraft[] = []
  for (let index = 0; index < localStorage.length; index++) {
    const storedKey = localStorage.key(index)
    if (!storedKey?.startsWith(prefix(member))) continue
    try {
      const row = JSON.parse(localStorage.getItem(storedKey)!) as StoredDraft
      if (!row.task?.id || !row.windowId) continue
      if (isTaskDraftDeleted(member, row.task.id)) continue
      candidates.push(row)
      const previous = selected[row.task.id]
      if (!previous || (row.windowId === windowId && previous.windowId !== windowId)
        || (previous.windowId !== windowId && row.updatedAt > previous.updatedAt)) selected[row.task.id] = row
    } catch { /* 损坏的一项不能阻止其他草稿恢复 */ }
  }
  for (const [id, row] of Object.entries(selected)) {
    const sources = Array.isArray(row.sources) ? row.sources.filter(source => typeof source?.key === 'string' && typeof source.revision === 'string') : []
    // 同一旧草稿可能被多个窗口恢复；只确认完全相同的副本，独立编辑仍保留。
    for (const candidate of candidates) {
      if (candidate.task.id === id && candidate.windowId !== windowId
        && JSON.stringify(candidate.task) === JSON.stringify(row.task)) {
        sources.push({ key: key(member, id, candidate.windowId), revision: revision(candidate) })
      }
    }
    recoveredSources.set(key(member, id, windowId), sources)
  }
  return Object.fromEntries(Object.entries(selected).map(([id, row]) => [id, row.task]))
}
