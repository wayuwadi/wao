import { exchangeFeishuInAppCode, getFeishuInAppConfig } from '@/api/feishu'
import type { AuthResolution } from '@/api/auth'

// 飞书 H5 JSSDK：只在飞书客户端容器内有效，版本升级时按官方「网页应用开发指南」更新地址。
const SDK_URL = 'https://lf1-cdn-tos.bytegoofy.com/goofy/lark/op/h5-js-sdk-1.5.23.js'

interface FeishuH5Sdk {
  ready(callback: () => void): void
  error(callback: (error: unknown) => void): void
}

interface FeishuTt {
  requestAuthCode(options: {
    appId: string
    success(result: { code: string }): void
    fail(error: unknown): void
  }): void
}

declare global {
  interface Window {
    h5sdk?: FeishuH5Sdk
    tt?: FeishuTt
  }
}

/** 是否运行在飞书客户端容器里（卡片按钮经 AppLink 打开时就是这种环境）。 */
export function isInFeishu() {
  return /Lark|Feishu/i.test(navigator.userAgent)
}

let sdkPromise: Promise<void> | null = null

function loadSdk() {
  if (window.h5sdk) return Promise.resolve()
  if (sdkPromise) return sdkPromise
  sdkPromise = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SDK_URL
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('飞书组件加载失败，请改用其他方式登录。'))
    document.head.appendChild(script)
  })
  return sdkPromise
}

/** 等飞书容器就绪后取一个免登临时码；容器永不就绪时靠超时兜底，不能让登录页卡死。 */
function requestAuthCode(appId: string) {
  return new Promise<string>((resolve, reject) => {
    const sdk = window.h5sdk
    if (!sdk) { reject(new Error('当前环境不支持飞书免登。')); return }
    const timer = setTimeout(() => reject(new Error('飞书免登超时，请改用其他方式登录。')), 8000)
    const done = (run: () => void) => { clearTimeout(timer); run() }
    // 容器环境异常（例如应用未发布给当前用户）时 ready 不会触发，只会走 error。
    sdk.error(() => done(() => reject(new Error('飞书免登不可用，请确认应用已发布且你在可用范围内。'))))
    sdk.ready(() => {
      window.tt?.requestAuthCode({
        appId,
        success: ({ code }) => done(() => resolve(code)),
        fail: () => done(() => reject(new Error('飞书免登授权失败，请改用其他方式登录。'))),
      })
    })
  })
}

/**
 * 飞书容器内免登：JSSDK 拿临时码 → 后端换 WAO 会话。
 *
 * @returns 与扫码/授权登录一致的账号归属结果；免登未配置或不在容器内时返回 null
 */
export async function feishuInAppLogin(): Promise<AuthResolution | null> {
  if (!isInFeishu()) return null
  const config = await getFeishuInAppConfig()
  if (!config.enabled) return null
  await loadSdk()
  const code = await requestAuthCode(config.appId)
  return exchangeFeishuInAppCode(code)
}
