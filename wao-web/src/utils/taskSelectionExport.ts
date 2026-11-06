import type { Task } from '@/types'
import { projectWorkItems } from './projectWorkItems'
import { subtaskRows } from './subtaskTree'
import { exportTasksMarkdown, type TaskExportContext } from './taskExport'

/** A task export contains the selected branch, not its parent or siblings. */
export function exportTaskSelection(
  tasks: readonly Task[], taskId: string, subId: string | undefined, context: TaskExportContext,
): { title: string; markdown: string } {
  const root = tasks.find(task => task.id === taskId)
  if (!root) throw new Error('任务已不存在，请刷新后重试。')
  if (subId) {
    const rows = subtaskRows(root.id, root.subs)
    const index = rows.findIndex(row => row.key === subId)
    if (index < 0) throw new Error('子任务已不存在，请刷新后重试。')
    const selected = rows[index]!
    const projected = projectWorkItems([root]).items.find(task => task.id === `sub:${taskId}:${subId}`)
    if (!projected) throw new Error('子任务已不存在，请刷新后重试。')
    const descendants = []
    for (let i = index + 1; i < rows.length && rows[i]!.depth > selected.depth; i++) descendants.push(rows[i]!.sub)
    const snapshot: Task = { ...projected, subs:descendants, images:selected.sub.images, comments:selected.sub.comments ?? [], docs:selected.sub.docs ?? [] }
    return {
      title:snapshot.title,
      markdown:exportTasksMarkdown([snapshot], { ...context, sourcePath:()=>`/api/share/tasks/${encodeURIComponent(taskId)}/subs/${encodeURIComponent(subId)}.md` }),
    }
  }
  const ids = new Set([taskId])
  let changed = true
  while (changed) {
    changed = false
    for (const task of tasks) if (task.project === root.project && task.parentTaskId && ids.has(task.parentTaskId) && !ids.has(task.id)) {
      ids.add(task.id); changed = true
    }
  }
  return { title:root.title, markdown:exportTasksMarkdown([root, ...tasks.filter(task => task.id !== root.id && ids.has(task.id))], context) }
}

/** Download only after the user invokes the menu action. */
export function downloadTaskMarkdown(title: string, markdown: string) {
  const url = URL.createObjectURL(new Blob([markdown], { type:'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `任务-${title.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 80) || '未命名'}.md`
  document.body.appendChild(link)
  try { link.click() }
  finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000) }
}

/** Print text, never interpreting task content as HTML. */
export function printTaskMarkdown(title: string, markdown: string) {
  const frame = document.createElement('iframe')
  frame.style.cssText = 'position:fixed;width:1px;height:1px;left:-10000px;top:0;border:0'
  frame.setAttribute('aria-hidden', 'true')
  document.body.appendChild(frame)
  const doc = frame.contentDocument, win = frame.contentWindow
  if (!doc || !win) { frame.remove(); throw new Error('无法打开打印预览，请重试。') }
  doc.title = title || '任务'
  const body = doc.createElement('pre')
  body.style.cssText = 'white-space:pre-wrap;overflow-wrap:anywhere;font:14px/1.6 sans-serif'
  body.textContent = markdown
  doc.body.appendChild(body)
  const cleanup = () => frame.remove()
  win.addEventListener('afterprint', cleanup, { once:true })
  try { win.focus(); win.print() }
  catch (error) { cleanup(); throw error }
  window.setTimeout(cleanup, 60_000)
}
