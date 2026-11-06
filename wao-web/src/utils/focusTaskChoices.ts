import type { FocusSession } from '@/api/focus'
import type { Task } from '@/types'
import type { ProjectItemRefs } from './projectWorkItems'

/** Index once per source change; each render/search does not repeatedly scan history for every row. */
export function focusTaskChoices(items: readonly Task[], refs: ProjectItemRefs, history: readonly FocusSession[], query: string, includeCompleted: boolean) {
  const recent = new Map<string, number>()
  for (const session of history) {
    const key = JSON.stringify([session.taskId, session.subId || ''])
    recent.set(key, Math.max(recent.get(key) || 0, session.endedAt || session.startedAt))
  }
  const needle = query.trim().toLocaleLowerCase()
  return items.filter(item => item.workItemType !== 'note' && item.status !== '已放弃'
    && (includeCompleted || item.status !== '已完成')
    && (!needle || `${item.title}\n${item.desc || ''}\n${item.number || ''}`.toLocaleLowerCase().includes(needle)))
    .map((item, index) => ({ item, index, last: recent.get(JSON.stringify([refs[item.id]?.taskId || item.id, refs[item.id]?.subId || ''])) || 0 }))
    .sort((a, b) => b.last - a.last || a.index - b.index)
    .map(row => row.item)
}
export function linkedFocusTask(record: Pick<FocusSession, 'taskId' | 'subId'>, refs: ProjectItemRefs): string | undefined {
  if (!record.taskId) return undefined
  return Object.keys(refs).find(key => refs[key]?.taskId === record.taskId && (refs[key]?.subId || '') === (record.subId || ''))
}
