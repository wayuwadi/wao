import { describe, expect, it, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'

describe('浮层 Esc 关闭顺序', () => {
  it('只关闭最上层，消费过的按键不处理，销毁后移除监听', () => {
    const scope = effectScope()
    const outer = ref(true), inner = ref(true)
    const closeOuter = vi.fn(), closeInner = vi.fn(() => { inner.value = false })
    scope.run(() => {
      useOverlayDismiss(() => outer.value, closeOuter)
      useOverlayDismiss(() => inner.value, closeInner)
    })
    const key = () => new KeyboardEvent('keydown', { key: 'Escape', cancelable: true })
    const consumed = key(); consumed.preventDefault(); document.dispatchEvent(consumed)
    expect(closeInner).not.toHaveBeenCalled()
    document.dispatchEvent(key())
    expect(closeInner).toHaveBeenCalledTimes(1); expect(closeOuter).not.toHaveBeenCalled()
    document.dispatchEvent(key()); expect(closeOuter).toHaveBeenCalledTimes(1)
    scope.stop(); document.dispatchEvent(key()); expect(closeOuter).toHaveBeenCalledTimes(1)
  })
})
