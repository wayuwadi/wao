import type { Task } from '@/types'

/** 首版试用错误地把完整 UUID 拼进任务 ID；仅修复从未保存的该类草稿。 */
export function quickDraftRecoveryId(task: Task): string | null {
  if (task.editVersion || task.subtaskVersion || task.number != null) return null
  const match = /^tquick-([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$/i.exec(task.id)
  return match ? `tq${match[1]!.replace(/-/g, '').slice(0, 30)}` : null
}

/** 仅确认已有明确迁移备份的草稿，普通无版本草稿仍按原冲突流程处理。 */
export function isRecoveredQuickDraft(member: string, id: string): boolean {
  const prefix = `wao-quick-draft-backup:${member}:`
  try {
    for (let index = 0; index < localStorage.length; index++) {
      const key = localStorage.key(index)
      if (key?.startsWith(prefix) && quickDraftRecoveryId(JSON.parse(localStorage.getItem(key)!)) === id) return true
    }
  } catch { /* 无法确认时保留草稿 */ }
  return false
}

export function sameQuickDraftContent(draft: Task, remote: Task): boolean {
  const scalar: (keyof Task)[] = ['title', 'project', 'parentTaskId', 'status', 'pr', 'owner', 'groupId', 'start', 'due', 'startAt', 'endAt', 'schedulePeriod', 'waiting', 'desc']
  const collections: (keyof Task)[] = ['parts', 'steps', 'subs', 'files', 'hostedPages', 'docs', 'linkTasks', 'comments', 'custom', 'images', 'pdcaCycles']
  const normalize = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(normalize)
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([key, val]) => !['number', 'createdAt', 'createdBy'].includes(key) && val != null).sort(([a], [b]) => a.localeCompare(b)).map(([key, val]) => [key, normalize(val)]))
    return value
  }
  return scalar.every(key => (draft[key] ?? '') === (remote[key] ?? ''))
    && collections.every(key => JSON.stringify(normalize(draft[key] ?? [])) === JSON.stringify(normalize(remote[key] ?? [])))
}
