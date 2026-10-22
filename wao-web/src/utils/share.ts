/** 任务分享链接的构造。复制动作见 utils/clipboard。 */

/** 把站内相对地址补成可公网访问的绝对地址（分享出去的内容不能带相对路径）。 */
export function absoluteUrl(path: string): string {
  if (/^(https?:)?\/\/|^data:/.test(path)) return path
  return new URL(path, window.location.origin).toString()
}

/**
 * 分享页地址：主任务 `/s/t/<id>`，子任务 `/s/t/<id>/<subId>`。
 *
 * @param openOnly 只分享未完成的子任务（子任务链接上无意义，会被忽略）
 */
export function shareUrl(taskId: string, subId?: string | null, openOnly = false): string {
  const path = subId ? `/s/t/${taskId}/${encodeURIComponent(subId)}` : `/s/t/${taskId}`
  return absoluteUrl(path + (!subId && openOnly ? '?openOnly=true' : ''))
}

/**
 * 文件下载页地址 `/f/<id>` —— 对外分享的就是这一个。
 *
 * <p>不再直接给 `/api/files/<id>/download`：那是个谁都能无限次拉的直链，
 * 一个脚本就能把带宽刷满。下载页要人点一下、过一道校验才换得到一次下载。
 */
export function fileShareUrl(fileId: string): string {
  return absoluteUrl(`/f/${encodeURIComponent(fileId)}`)
}

/**
 * Markdown 直读地址 —— 复制给 AI 时用这个：一次 GET 就是任务全文，
 * 不需要 AI 有执行前端 JS 的能力。
 *
 * @param openOnly 只分享未完成的子任务
 */
export function shareMarkdownUrl(taskId: string, subId?: string | null, openOnly = false): string {
  if (subId) return absoluteUrl(`/api/share/tasks/${taskId}/subs/${encodeURIComponent(subId)}.md`)
  return absoluteUrl(`/api/share/tasks/${taskId}.md${openOnly ? '?openOnly=true' : ''}`)
}

/** 文档 / 小记分享页地址 `/s/d/<id>`（免登录只读）。 */
export function docShareUrl(docId: string): string {
  return absoluteUrl(`/s/d/${encodeURIComponent(docId)}`)
}

/** 文档 / 小记的 Markdown 直读地址 —— 复制给 AI 时用这个。 */
export function docShareMarkdownUrl(docId: string): string {
  return absoluteUrl(`/api/share/docs/${encodeURIComponent(docId)}.md`)
}

/** Recognize only same-origin task shares; external links retain normal browser behavior. */
export function taskShareTarget(href: string): { taskId: string; subId?: string } | null {
  try {
    const url = new URL(href, window.location.origin)
    if (url.origin !== window.location.origin) return null
    const match =
      url.pathname.match(/^\/s\/t\/([^/]+)(?:\/([^/]+))?\/?$/) ??
      url.pathname.match(/^\/api\/share\/tasks\/([^/]+?)(?:\/subs\/([^/]+?))?(?:\.md)?$/)
    if (!match?.[1]) return null
    return {
      taskId: decodeURIComponent(match[1]),
      subId: match[2] ? decodeURIComponent(match[2]) : undefined,
    }
  } catch {
    return null
  }
}

/** Recognize only same-origin document / note shares for in-workspace navigation. */
export function docShareTarget(href: string): { docId: string } | null {
  try {
    const url = new URL(href, window.location.origin)
    if (url.origin !== window.location.origin) return null
    const match =
      url.pathname.match(/^\/s\/d\/([^/]+)\/?$/) ??
      url.pathname.match(/^\/api\/share\/docs\/([^/]+?)(?:\.md)?$/)
    return match?.[1] ? { docId: decodeURIComponent(match[1]) } : null
  } catch {
    return null
  }
}

/** 分享草稿中的标题按纯文本转义，避免括号破坏链接。 */
export function chatShareLink(title: string, url: string): string {
  return `[${title
    .replace(/\\/g, '\\\\')
    .replace(/\[/g, '\\[')
    .replace(/\]/g, '\\]')
    .replace(/\s+/g, ' ')
    .slice(0, 240)}](<${url.replace(/>/g, '%3E')}>)`
}

/** 登录后直接定位知识库原文，保留原有访问权限。 */
export function docOriginalUrl(kbId: string, docId: string): string {
  return absoluteUrl(`/kb/${encodeURIComponent(kbId)}?doc=${encodeURIComponent(docId)}`)
}
