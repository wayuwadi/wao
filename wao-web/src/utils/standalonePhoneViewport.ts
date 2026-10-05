const VIEWPORT = 'width=device-width, initial-scale=1.0, viewport-fit=cover'

/**
 * 主屏幕手机应用若以桌面视口启动，重新声明实际屏幕宽度。
 * 只在启动、后台恢复和转屏时检查，不能用 resize/visualViewport 事件重置用户缩放。
 */
export function installStandalonePhoneViewport() {
  let corrected = false
  function update() {
    const standalone = (navigator as Navigator & { standalone?: boolean }).standalone === true
      || window.matchMedia('(display-mode: standalone)').matches
    const shortEdge = Math.min(screen.width, screen.height)
    if (!standalone || !(navigator.maxTouchPoints > 0) || shortEdge < 320 || shortEdge > 600) return
    // 保留已经放大的阅读状态；宽度判断用布局视口，不能用缩放后的 visualViewport.width。
    if (window.visualViewport && window.visualViewport.scale > 1.01) return
    const angle = screen.orientation?.angle
      ?? (window as Window & { orientation?: number }).orientation
    const landscape = angle == null ? screen.width > screen.height : Math.abs(angle % 180) === 90
    const width = landscape ? Math.max(screen.width, screen.height) : shortEdge
    const layoutWidth = document.documentElement.clientWidth
    if (!corrected && (landscape || layoutWidth <= 760 || layoutWidth <= width * 1.25)) return
    let meta = document.querySelector<HTMLMetaElement>('meta[name="viewport"]')
    if (!meta) {
      meta = document.createElement('meta')
      meta.name = 'viewport'
      document.head.append(meta)
    }
    // 显式像素宽度让继承了桌面视口的 Web App 重新计算布局；横屏时跟随屏幕长边。
    const content = VIEWPORT.replace('device-width', String(width))
    if (meta.content !== content) meta.content = content
    corrected = true
  }
  update()
  window.addEventListener('pageshow', update)
  window.addEventListener('orientationchange', update)
  screen.orientation?.addEventListener('change', update)
  return () => {
    window.removeEventListener('pageshow', update)
    window.removeEventListener('orientationchange', update)
    screen.orientation?.removeEventListener('change', update)
  }
}
