import { describe, expect, it, vi } from 'vitest'
import { installPreloadErrorRecovery, showPreloadErrorBanner } from '@/utils/preloadErrorRecovery'

function createStorage(initialValue?: string) {
  let value = initialValue ?? null
  return {
    getItem: vi.fn<(key: string) => string | null>(() => value),
    setItem: vi.fn<(key: string, value: string) => void>((_key, next) => {
      value = next
    }),
  }
}

describe('发布后动态分包恢复', () => {
  it('默认不刷新当前编辑页面，重复失败只提示手动恢复', () => {
    const target = new EventTarget()
    const reload = vi.fn<() => void>()
    const showRetry = vi.fn<() => void>()
    const storage = createStorage()
    installPreloadErrorRecovery({ target, reload, showRetry, storage })
    target.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))
    target.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))
    expect(reload).not.toHaveBeenCalled()
    expect(storage.setItem).not.toHaveBeenCalled()
    expect(showRetry).toHaveBeenCalledTimes(2)
  })
  it('首次加载失败时阻止异常并只刷新一次', () => {
    const target = new EventTarget()
    const storage = createStorage()
    const reload = vi.fn<() => void>()
    const showRetry = vi.fn<() => void>()
    installPreloadErrorRecovery({ target, storage, reload, showRetry, now: () => 1_000, autoReload: true })

    const event = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(true)
    expect(storage.setItem).toHaveBeenCalledWith('wao:preload-error-reload-at', '1000')
    expect(reload).toHaveBeenCalledOnce()
    expect(showRetry).not.toHaveBeenCalled()
  })

  it('限流窗口内复发时显示手动重试而不循环刷新', () => {
    const target = new EventTarget()
    const storage = createStorage('1000')
    const reload = vi.fn<() => void>()
    const showRetry = vi.fn<() => void>()
    installPreloadErrorRecovery({ target, storage, reload, showRetry, now: () => 30_000 })

    target.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))

    expect(reload).not.toHaveBeenCalled()
    expect(showRetry).toHaveBeenCalledOnce()
  })

  it('限流窗口结束后允许下一次自动刷新', () => {
    const target = new EventTarget()
    const storage = createStorage('1000')
    const reload = vi.fn<() => void>()
    installPreloadErrorRecovery({
      target,
      storage,
      reload,
      showRetry: vi.fn<() => void>(),
      now: () => 61_001,
      autoReload: true,
    })

    target.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))

    expect(reload).toHaveBeenCalledOnce()
  })

  it('浏览器存储不可用时直接提供手动恢复入口', () => {
    const target = new EventTarget()
    const reload = vi.fn<() => void>()
    const showRetry = vi.fn<() => void>()
    installPreloadErrorRecovery({
      target,
      storage: {
        getItem: () => {
          throw new Error('storage disabled')
        },
        setItem: vi.fn<(key: string, value: string) => void>(),
      },
      reload,
      showRetry,
    })

    target.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))

    expect(reload).not.toHaveBeenCalled()
    expect(showRetry).toHaveBeenCalledOnce()
  })

  it('卸载后不再处理事件', () => {
    const target = new EventTarget()
    const reload = vi.fn<() => void>()
    const dispose = installPreloadErrorRecovery({
      target,
      storage: createStorage(),
      reload,
      showRetry: vi.fn<() => void>(),
    })

    dispose()
    target.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))

    expect(reload).not.toHaveBeenCalled()
  })

  it('手动恢复提示只出现一次且按钮会解除限流后刷新', () => {
    document.body.innerHTML = ''
    const reload = vi.fn<() => void>()
    const storage = { removeItem: vi.fn<(key: string) => void>() }

    showPreloadErrorBanner({ reload, storage })
    showPreloadErrorBanner({ reload, storage })

    expect(document.querySelectorAll('#wao-preload-error-recovery')).toHaveLength(1)
    const button = document.querySelector<HTMLButtonElement>('#wao-preload-error-recovery button')!
    expect(button.textContent).toBe('立即刷新')
    button.click()
    expect(storage.removeItem).toHaveBeenCalledWith('wao:preload-error-reload-at')
    expect(reload).toHaveBeenCalledOnce()
  })
})
