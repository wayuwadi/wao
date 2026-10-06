const pending = new Map<string, Promise<void>>()
export const FEISHU_SDK_TIMEOUT = 8_000

/** 官方脚本有确定的等待上限；失败清理后允许下一次重试。 */
export function loadFeishuSdk(url: string, available: () => boolean): Promise<void> {
  if (available()) return Promise.resolve()
  const existing = pending.get(url)
  if (existing) return existing
  const request = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = url
    script.async = true
    const finish = (error?: Error) => {
      clearTimeout(timer)
      script.onload = null
      script.onerror = null
      if (error) {
        script.remove()
        reject(error)
      } else resolve()
    }
    const timer = window.setTimeout(
      () => finish(new Error('飞书组件加载超时，请重试或使用其他登录方式。')),
      FEISHU_SDK_TIMEOUT,
    )
    script.onload = () =>
      finish(available() ? undefined : new Error('飞书组件未能初始化，请重试或使用其他登录方式。'))
    script.onerror = () => finish(new Error('飞书组件加载失败，请重试或使用其他登录方式。'))
    document.head.appendChild(script)
  }).finally(() => {
    pending.delete(url)
  })
  pending.set(url, request)
  return request
}

/** 授权有效期仅用于界面刷新提示，真实性仍由后端签名校验决定。 */
export function feishuAuthorizationExpiresAt(url: string): number {
  try {
    const state = new URL(url).searchParams.get('state')?.split('.')[0]
    if (!state) throw new Error('missing state')
    const timestamp = Number(atob(state.replace(/-/g, '+').replace(/_/g, '/')).split(':')[0]) * 1000
    if (!Number.isFinite(timestamp) || timestamp <= 0) throw new Error('invalid timestamp')
    return Math.min(timestamp, Date.now() + 300_000)
  } catch {
    return Date.now() + 300_000
  }
}
