import { getFeishuAuthorizationUrl } from '@/api/feishu'
import { feishuAuthorizationExpiresAt } from './feishuSdk'
const KEY = 'wao:reauth-popup-state:v1'
const TYPE = 'wao:reauth-complete'

/** 独立窗口完成授权，原页面与草稿保持挂载；只返回已完成信号。 */
export async function reauthenticateWithFeishu(signal?: AbortSignal): Promise<void> {
  const popup = window.open('about:blank', '_blank', 'popup,width=500,height=720')
  if (!popup) throw new Error('登录窗口被浏览器拦截，请允许弹窗后重试，或使用邮箱登录。')
  try {
    popup.document.title = '正在打开飞书登录…'
    popup.document.body.textContent = '正在打开飞书登录…'
    const url = await getFeishuAuthorizationUrl('authorization')
    if (popup.closed || signal?.aborted) throw new Error('登录窗口已关闭，请重试。')
    const state = new URL(url).searchParams.get('state')
    if (!state) throw new Error('飞书授权信息不完整，请重试。')
    popup.sessionStorage.setItem(KEY, state)
    await new Promise<void>((resolve, reject) => {
      const finish = (error?: Error) => {
        clearInterval(watch)
        clearTimeout(deadline)
        window.removeEventListener('message', receive)
        signal?.removeEventListener('abort', abort)
        popup.close()
        if (error) reject(error)
        else resolve()
      }
      const receive = (event: MessageEvent) => {
        if (
          event.origin !== window.location.origin ||
          event.source !== popup ||
          event.data?.type !== TYPE ||
          event.data.state !== state
        )
          return
        finish(event.data.ok === true ? undefined : new Error('飞书登录未完成，请使用原账号重试。'))
      }
      const abort = () => finish(new Error('登录窗口已关闭。'))
      const watch = window.setInterval(() => {
        if (popup.closed) finish(new Error('登录窗口已关闭，请重试。'))
      }, 500)
      const deadline = window.setTimeout(
        () => finish(new Error('飞书授权已过期，请重新登录。')),
        Math.max(0, feishuAuthorizationExpiresAt(url) - Date.now()),
      )
      window.addEventListener('message', receive)
      signal?.addEventListener('abort', abort, { once: true })
      popup.location.replace(url)
    })
  } catch (error) {
    popup.close()
    throw error
  }
}

export function isReauthPopup(state: string) {
  try {
    return !!window.opener && !!state && sessionStorage.getItem(KEY) === state
  } catch {
    return false
  }
}
export function completeReauthPopup(state: string, ok: boolean) {
  if (!isReauthPopup(state)) return
  sessionStorage.removeItem(KEY)
  window.opener.postMessage({ type: TYPE, state, ok }, window.location.origin)
  window.close()
}
