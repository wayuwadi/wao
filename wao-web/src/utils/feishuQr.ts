import { getFeishuAuthorizationUrl } from '@/api/feishu'
import { feishuAuthorizationExpiresAt, loadFeishuSdk } from './feishuSdk'

const SDK_URL =
  'https://sf3-cn.feishucdn.com/obj/feishu-static/lark/passport/qrcode/LarkSSOSDKWebQRCode-1.0.2.js'

interface FeishuQrInstance {
  matchOrigin(origin: string): boolean
  matchData?(data: unknown): boolean
}

interface FeishuQrOptions {
  id: string
  goto: string
  width: string
  height: string
  style?: string
}

declare global {
  interface Window {
    QRLogin?: (options: FeishuQrOptions) => FeishuQrInstance
  }
}

/** 在指定容器创建飞书官方二维码，并返回清理函数。 */
export async function mountFeishuQr(
  containerId: string,
  isActive: () => boolean = () => true,
  beforeNavigate?: (url: string) => void | boolean,
  onExpired?: () => void,
) {
  const authorizationUrl = await getFeishuAuthorizationUrl('qr')
  const expiresAt = feishuAuthorizationExpiresAt(authorizationUrl)
  await loadFeishuSdk(SDK_URL, () => !!window.QRLogin)
  if (!window.QRLogin) throw new Error('飞书二维码组件不可用，请改用飞书授权登录。')

  if (!isActive()) throw new Error('二维码请求已取消。')
  const container = document.getElementById(containerId)
  if (!container) throw new Error('飞书二维码容器不存在。')
  // 官方 SDK 采用 appendChild 插入 iframe；必须先移除加载占位，否则二维码会被追加到
  // 固定高度容器下方，并被 overflow:hidden 裁掉。
  container.replaceChildren()
  const qr = window.QRLogin({
    id: containerId,
    goto: authorizationUrl,
    width: '300',
    height: '300',
    style: 'border:0;display:block;overflow:hidden',
  })
  const frame = container.querySelector('iframe')
  if (!frame) throw new Error('飞书二维码未能创建，请改用飞书授权登录。')
  frame.title = '飞书扫码登录'
  frame.scrolling = 'no'
  const fitFrame = () => {
    if (container.clientWidth <= 0) return
    const scale = Math.min(1, container.clientWidth / 300)
    frame.style.transformOrigin = 'top left'
    frame.style.transform = `scale(${scale})`
  }
  fitFrame()
  const resizeObserver =
    typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(fitFrame)
  resizeObserver?.observe(container)
  const onMessage = (event: MessageEvent) => {
    if (!isActive()) return
    if (Date.now() >= expiresAt) {
      expire()
      return
    }
    if (event.source !== frame.contentWindow) return
    if (!qr.matchOrigin(event.origin) || (qr.matchData && !qr.matchData(event.data))) return
    const temporaryCode =
      typeof event.data === 'string'
        ? event.data
        : (event.data as { tmp_code?: string } | null)?.tmp_code
    if (!temporaryCode) return
    if (beforeNavigate?.(authorizationUrl) === false) return
    window.location.assign(`${authorizationUrl}&tmp_code=${encodeURIComponent(temporaryCode)}`)
  }
  let expired = false
  const expire = () => {
    if (expired || !isActive()) return
    expired = true
    onExpired?.()
  }
  const expiryTimer = window.setTimeout(expire, Math.max(0, expiresAt - Date.now()))
  const checkExpiry = () => {
    if (!document.hidden && Date.now() >= expiresAt) expire()
  }
  document.addEventListener('visibilitychange', checkExpiry)
  window.addEventListener('message', onMessage)
  return () => {
    clearTimeout(expiryTimer)
    document.removeEventListener('visibilitychange', checkExpiry)
    window.removeEventListener('message', onMessage)
    resizeObserver?.disconnect()
    frame.remove()
  }
}
