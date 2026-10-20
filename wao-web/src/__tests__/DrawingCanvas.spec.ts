import { expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import DrawingCanvas from '@/components/drawings/DrawingCanvas.vue'
vi.mock('@excalidraw/excalidraw', () => ({ Excalidraw: () => null, serializeAsJSON: vi.fn() }))
vi.mock('react-dom/client', () => ({ createRoot: () => ({ render: vi.fn(), unmount: vi.fn() }) }))
it('画布拦截 Ctrl/Cmd+S，卸载后恢复浏览器快捷键', () => {
  const wrapper = mount(DrawingCanvas, { props: { sceneJson: '{}', sceneKey: 'test', name: '测试画布' } })
  for (const modifier of ['ctrlKey', 'metaKey']) {
    const event = new KeyboardEvent('keydown', { key: 's', [modifier]: true, cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
  }
  const normal = new KeyboardEvent('keydown', { key: 's', cancelable: true })
  window.dispatchEvent(normal)
  expect(normal.defaultPrevented).toBe(false)
  wrapper.unmount()
  const after = new KeyboardEvent('keydown', { key: 's', ctrlKey: true, cancelable: true })
  window.dispatchEvent(after)
  expect(after.defaultPrevented).toBe(false)
})
