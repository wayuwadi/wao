/** 任务分享链接的构造与复制。 */

/** 分享页地址：主任务 `/s/t/<id>`，子任务 `/s/t/<id>/<subId>`。 */
export function shareUrl(taskId: string, subId?: string | null): string {
  const path = subId ? `/s/t/${taskId}/${encodeURIComponent(subId)}` : `/s/t/${taskId}`
  return new URL(path, window.location.origin).toString()
}

/**
 * Markdown 直读地址 —— 复制给 AI 时用这个：一次 GET 就是任务全文，
 * 不需要 AI 有执行前端 JS 的能力。
 */
export function shareMarkdownUrl(taskId: string, subId?: string | null): string {
  const path = subId
    ? `/api/share/tasks/${taskId}/subs/${encodeURIComponent(subId)}.md`
    : `/api/share/tasks/${taskId}.md`
  return new URL(path, window.location.origin).toString()
}

/**
 * 复制文本到剪贴板。
 *
 * <p>非 HTTPS / 旧浏览器下 `navigator.clipboard` 不可用，回落到临时 textarea +
 * `execCommand`，否则内网 http 访问时复制会静默失败。
 *
 * @param text 要复制的文本
 * @return 是否复制成功
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 继续走回落方案 */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
