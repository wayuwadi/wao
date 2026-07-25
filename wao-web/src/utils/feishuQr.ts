import { getFeishuAuthorizationUrl } from '@/api/feishu'

const SDK_URL = 'https://sf3-cn.feishucdn.com/obj/feishu-static/lark/passport/qrcode/LarkSSOSDKWebQRCode-1.0.2.js'

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

let sdkPromise: Promise<void> | null = null

function loadSdk() {
  if (window.QRLogin) return Promise.resolve()
  if (sdkPromise) return sdkPromise
  sdkPromise = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SDK_URL
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('飞书二维码组件加载失败，请改用飞书授权登录。'))
    document.head.appendChild(script)
  })
  return sdkPromise
}

/** 在指定容器创建飞书官方二维码，并返回清理函数。 */
export async function mountFeishuQr(containerId: string) {
  const authorizationUrl = await getFeishuAuthorizationUrl('qr')
  await loadSdk()
  if (!window.QRLogin) throw new Error('飞书二维码组件不可用，请改用飞书授权登录。')

  const container = document.getElementById(containerId)
  if (!container) throw new Error('飞书二维码容器不存在。')
  // 官方 SDK 采用 appendChild 插入 iframe；必须先移除加载占位，否则二维码会被追加到
  // 固定高度容器下方，并被 overflow:hidden 裁掉。
  container.replaceChildren()
  const qr = window.QRLogin({
    id: containerId,
    goto: authorizationUrl,
    width: '220',
    height: '220',
    style: 'border:0;display:block',
  })
  const frame = container.querySelector('iframe')
  if (!frame) throw new Error('飞书二维码未能创建，请改用飞书授权登录。')
  frame.title = '飞书扫码登录'
  const onMessage = (event: MessageEvent) => {
    if (!qr.matchOrigin(event.origin) || (qr.matchData && !qr.matchData(event.data))) return
    const temporaryCode = typeof event.data === 'string'
      ? event.data
      : (event.data as { tmp_code?: string } | null)?.tmp_code
    if (!temporaryCode) return
    window.location.assign(`${authorizationUrl}&tmp_code=${encodeURIComponent(temporaryCode)}`)
  }
  window.addEventListener('message', onMessage)
  return () => {
    window.removeEventListener('message', onMessage)
    frame.remove()
  }
}
