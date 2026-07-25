/** 任务分享链接的构造。复制动作见 utils/clipboard。 */

/** 把站内相对地址补成可公网访问的绝对地址（分享出去的内容不能带相对路径）。 */
export function absoluteUrl(path: string): string {
  if (/^(https?:)?\/\/|^data:/.test(path)) return path
  return new URL(path, window.location.origin).toString()
}

/** 分享页地址：主任务 `/s/t/<id>`，子任务 `/s/t/<id>/<subId>`。 */
export function shareUrl(taskId: string, subId?: string | null): string {
  const path = subId ? `/s/t/${taskId}/${encodeURIComponent(subId)}` : `/s/t/${taskId}`
  return absoluteUrl(path)
}

/**
 * Markdown 直读地址 —— 复制给 AI 时用这个：一次 GET 就是任务全文，
 * 不需要 AI 有执行前端 JS 的能力。
 */
export function shareMarkdownUrl(taskId: string, subId?: string | null): string {
  const path = subId
    ? `/api/share/tasks/${taskId}/subs/${encodeURIComponent(subId)}.md`
    : `/api/share/tasks/${taskId}.md`
  return absoluteUrl(path)
}
