const PRELOAD_ERROR_EVENT = 'vite:preloadError'
const RELOAD_MARKER_KEY = 'wao:preload-error-reload-at'
const RELOAD_COOLDOWN_MS = 60_000
const BANNER_ID = 'wao-preload-error-recovery'

type EventTargetLike = Pick<EventTarget, 'addEventListener' | 'removeEventListener'>
type StorageLike = Pick<Storage, 'getItem' | 'setItem'>

export interface PreloadErrorRecoveryOptions {
  target?: EventTargetLike
  storage?: StorageLike
  now?: () => number
  reload?: () => void
  showRetry?: () => void
  cooldownMs?: number
}

interface PreloadErrorBannerOptions {
  doc?: Document
  storage?: Pick<Storage, 'removeItem'>
  reload?: () => void
}

export function showPreloadErrorBanner(options: PreloadErrorBannerOptions = {}) {
  const doc = options.doc ?? document
  const reload = options.reload ?? (() => window.location.reload())
  let storage = options.storage
  if (!storage) {
    try {
      storage = window.sessionStorage
    } catch {
      // 部分隐私策略连 sessionStorage 属性读取都会拒绝；按钮仍可主动刷新。
    }
  }
  if (doc.getElementById(BANNER_ID)) return

  const banner = doc.createElement('aside')
  banner.id = BANNER_ID
  banner.setAttribute('role', 'alert')
  banner.style.cssText = [
    'position:fixed',
    'top:16px',
    'left:50%',
    'transform:translateX(-50%)',
    'z-index:10000',
    'display:flex',
    'align-items:center',
    'gap:12px',
    'max-width:calc(100vw - 32px)',
    'padding:12px 16px',
    'border:1px solid #ffe58f',
    'border-radius:10px',
    'background:#fffbe6',
    'box-shadow:0 8px 24px rgba(0,0,0,.14)',
    'color:#614700',
    'font:14px/1.5 system-ui,sans-serif',
  ].join(';')

  const message = doc.createElement('span')
  message.textContent = '页面版本加载失败，请刷新后继续。仍在编辑且尚未保存的内容请先复制。'

  const button = doc.createElement('button')
  button.type = 'button'
  button.textContent = '立即刷新'
  button.style.cssText = [
    'flex:none',
    'padding:5px 12px',
    'border:0',
    'border-radius:6px',
    'background:#d48806',
    'color:#fff',
    'cursor:pointer',
    'font:inherit',
  ].join(';')
  button.addEventListener('click', () => {
    try {
      storage?.removeItem(RELOAD_MARKER_KEY)
    } catch {
      // 浏览器禁用存储时仍允许用户主动刷新。
    }
    reload()
  })

  banner.append(message, button)
  doc.body.append(banner)
}

/**
 * 安装 Vite 动态分包加载失败恢复。首次失败自动刷新，短时间内复发则交给用户手动重试，避免刷新死循环。
 */
export function installPreloadErrorRecovery(options: PreloadErrorRecoveryOptions = {}) {
  const target = options.target ?? window
  const now = options.now ?? Date.now
  const reload = options.reload ?? (() => window.location.reload())
  const showRetry = options.showRetry ?? showPreloadErrorBanner
  const cooldownMs = options.cooldownMs ?? RELOAD_COOLDOWN_MS

  const handlePreloadError = (event: Event) => {
    event.preventDefault()

    let previousReload = 0
    let storage = options.storage
    try {
      storage ??= window.sessionStorage
      previousReload = Number(storage.getItem(RELOAD_MARKER_KEY)) || 0
    } catch {
      showRetry()
      return
    }

    const currentTime = now()
    if (previousReload > 0 && currentTime - previousReload < cooldownMs) {
      showRetry()
      return
    }

    try {
      storage.setItem(RELOAD_MARKER_KEY, String(currentTime))
      reload()
    } catch {
      showRetry()
    }
  }

  target.addEventListener(PRELOAD_ERROR_EVENT, handlePreloadError)
  return () => target.removeEventListener(PRELOAD_ERROR_EVENT, handlePreloadError)
}
