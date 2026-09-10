import type { Task } from '@/types'
const fallback = new Map<string, Record<string, string>>()
const priority: Record<string, number> = { 待验证: 0, 'AI 进行中': 1, 待办: 2 }
/** One banner per batch of new task states; unrelated changes cannot replay an old task. */
export function nextPetTaskNotice(tasks: Pick<Task, 'id' | 'status'>[], memberId: string) {
  if (!memberId) return null
  const key = `wao-pet-task-states-v2:${memberId}`
  let seen: Record<string, string> = fallback.get(key) ?? {}
  try {
    const value = JSON.parse(localStorage.getItem(key) || 'null')
    if (value && typeof value === 'object' && !Array.isArray(value)) seen = value
  } catch {
    /* Ignore malformed optional storage. */
  }
  const relevant = tasks.filter((task) => task.status in priority)
  const fresh = relevant
    .filter((task) => seen[task.id] !== task.status)
    .sort((a, b) => priority[a.status]! - priority[b.status]!)
  // Keep the current full snapshot, including completed states, so a later new run
  // can notify again while unchanged pending tasks remain silent.
  const snapshot = Object.fromEntries(tasks.map((task) => [task.id, task.status]))
  fallback.set(key, snapshot)
  try {
    localStorage.setItem(key, JSON.stringify(snapshot))
  } catch {
    /* Storage restrictions must not block the task UI. */
  }
  const task = fresh[0]
  return task
    ? {
        id: task.id,
        label:
          task.status === '待验证'
            ? '有任务等你验收'
            : task.status === 'AI 进行中'
              ? 'AI 正在处理任务'
              : '有待办任务，点击查看',
      }
    : null
}
