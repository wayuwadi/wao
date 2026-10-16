import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import PaneResizeHandle from '@/components/ui/PaneResizeHandle.vue'

const props = { label: '目录宽度', storageKey: 'width:a', defaultWidth: 262, min: 200, max: 520 }
describe('侧栏宽度', () => {
  beforeEach(() => localStorage.clear())
  it('键盘调整、限制边界、恢复默认并按账号隔离', async () => {
    localStorage.setItem('width:a', '510')
    const w = mount(PaneResizeHandle, { props })
    expect(w.attributes('aria-valuenow')).toBe('510')
    await w.trigger('keydown', { key: 'ArrowRight' })
    expect(localStorage.getItem('width:a')).toBe('520')
    await w.trigger('keydown', { key: 'Home' })
    expect(w.attributes('aria-valuenow')).toBe('262')
    await w.trigger('keydown', { key: 'ArrowLeft' })
    expect(w.attributes('aria-valuenow')).toBe('246')
    await w.setProps({ storageKey: 'width:b' })
    expect(w.attributes('aria-valuenow')).toBe('262')
    expect(localStorage.getItem('width:a')).toBe('246')
    w.unmount()
  })
  it('指针拖拽可加宽，取消后停止，双击恢复', async () => {
    const w = mount(PaneResizeHandle, { props })
    const send = async (type: string, x: number) => {
      const event = new MouseEvent(type, { button: 0, clientX: x })
      Object.defineProperty(event, 'pointerId', { value: 1 })
      w.element.dispatchEvent(event)
      await nextTick()
    }
    await send('pointerdown', 262)
    await send('pointermove', 362)
    expect(w.attributes('aria-valuenow')).toBe('362')
    await w.trigger('pointercancel')
    await send('pointermove', 500)
    expect(w.attributes('aria-valuenow')).toBe('362')
    await w.trigger('dblclick')
    expect(w.attributes('aria-valuenow')).toBe('262')
    w.unmount()
  })
})
