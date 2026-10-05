import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installStandalonePhoneViewport } from '@/utils/standalonePhoneViewport'

describe('主屏幕手机视口恢复', () => {
  let dispose: (() => void) | undefined
  let angle = 0
  let orientation: EventTarget
  const viewport = () => document.querySelector<HTMLMetaElement>('meta[name="viewport"]')!

  beforeEach(() => {
    angle = 0
    orientation = new EventTarget()
    Object.defineProperty(orientation, 'angle', { get: () => angle })
    vi.stubGlobal('screen', { width: 390, height: 844, orientation })
    vi.stubGlobal('navigator', { maxTouchPoints: 5 })
    vi.stubGlobal('matchMedia', vi.fn((query: string) => ({ matches: query === '(display-mode: standalone)' })))
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(980)
    vi.stubGlobal('visualViewport', { scale: 0.398 })
    document.head.insertAdjacentHTML('beforeend', '<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">')
  })
  afterEach(() => {
    dispose?.()
    dispose = undefined
    document.querySelectorAll('meta[name="viewport"]').forEach(meta => meta.remove())
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('390 手机误用 980 布局视口时在挂载前恢复宽度，保留安全区和缩放', () => {
    dispose = installStandalonePhoneViewport()
    expect(viewport().content).toBe('width=390, initial-scale=1.0, viewport-fit=cover')
    expect(viewport().content).not.toMatch(/maximum-scale|minimum-scale|user-scalable/)
  })
  it('旧入口未声明视口时补齐唯一声明', () => {
    viewport().remove()
    dispose = installStandalonePhoneViewport()
    expect(document.querySelectorAll('meta[name="viewport"]')).toHaveLength(1)
    expect(viewport().content).toContain('width=390')
  })
  it('兼容 iOS 的 standalone 标记', () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false })))
    vi.stubGlobal('navigator', { maxTouchPoints: 5, standalone: true })
    dispose = installStandalonePhoneViewport()
    expect(viewport().content).toContain('width=390')
  })
  it.each([
    ['普通浏览器', () => vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false })))],
    ['桌面设备', () => vi.stubGlobal('navigator', { maxTouchPoints: 0 })],
    ['平板', () => vi.stubGlobal('screen', { width: 820, height: 1180, orientation })],
    ['正常手机视口', () => vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(390)],
    ['用户放大阅读', () => vi.stubGlobal('visualViewport', { scale: 2 })],
    ['正常横屏', () => { angle = 90 }],
  ])('%s 不重写视口', (_, setup) => {
    setup()
    dispose = installStandalonePhoneViewport()
    expect(viewport().content).toContain('width=device-width')
  })
  it('后台恢复可以校正，但普通 resize 和双指放大不重置视口', () => {
    const layout = vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(390)
    dispose = installStandalonePhoneViewport()
    layout.mockReturnValue(980)
    window.dispatchEvent(new Event('resize'))
    expect(viewport().content).toContain('width=device-width')
    window.dispatchEvent(new Event('pageshow'))
    expect(viewport().content).toContain('width=390')
    vi.stubGlobal('visualViewport', { scale: 2 })
    const set = vi.spyOn(viewport(), 'setAttribute')
    window.dispatchEvent(new Event('pageshow'))
    expect(set).not.toHaveBeenCalled()
  })
  it('已修复的手机转屏时跟随长边，销毁后不再监听', () => {
    dispose = installStandalonePhoneViewport()
    angle = 90
    orientation.dispatchEvent(new Event('change'))
    expect(viewport().content).toContain('width=844')
    angle = 0
    window.dispatchEvent(new Event('orientationchange'))
    expect(viewport().content).toContain('width=390')
    dispose()
    angle = 90
    orientation.dispatchEvent(new Event('change'))
    window.dispatchEvent(new Event('orientationchange'))
    expect(viewport().content).toContain('width=390')
  })
})
