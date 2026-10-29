import { expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import DrawingCanvas from '@/components/drawings/DrawingCanvas.vue'
import type { ReactElement } from 'react'
import { ref } from 'vue'
import { pageVisibleKey } from '@/composables/pageVisibility'
const render = vi.hoisted(() => vi.fn())
vi.mock('@excalidraw/excalidraw', () => ({
  Excalidraw: () => null,
  serializeAsJSON: (elements: unknown[], appState: unknown, files: unknown) =>
    JSON.stringify({ elements, appState, files }),
}))
vi.mock('react-dom/client', () => ({ createRoot: () => ({ render, unmount: vi.fn() }) }))
it('画布拦截 Ctrl/Cmd+S，卸载后恢复浏览器快捷键', () => {
  const wrapper = mount(DrawingCanvas, {
    props: { sceneJson: '{}', sceneKey: 'test', name: '测试画布' },
  })
  for (const modifier of ['ctrlKey', 'metaKey']) {
    const event = new KeyboardEvent('keydown', { key: 's', [modifier]: true, cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
  }
  const normal = new KeyboardEvent('keydown', { key: 's', cancelable: true })
  expect(wrapper.emitted('save')).toHaveLength(2)
  window.dispatchEvent(normal)
  expect(normal.defaultPrevented).toBe(false)
  wrapper.unmount()
  const after = new KeyboardEvent('keydown', { key: 's', ctrlKey: true, cancelable: true })
  window.dispatchEvent(after)
  expect(after.defaultPrevented).toBe(false)
})

it('初始化归一化仅报告就绪，后续真实编辑才触发保存', async () => {
  const wrapper = mount(DrawingCanvas, {
    props: { sceneJson: '{"elements":[{"id":"shape"}]}', sceneKey: 'one', name: '测试画布' },
  })
  const element = render.mock.calls[render.mock.calls.length - 1]![0] as ReactElement<{
    onChange: (elements: unknown[], state: unknown, files: unknown) => void
    excalidrawAPI?: () => void
  }>
  element.props.excalidrawAPI?.()
  await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()))
  const normalized = [{ id: 'shape', version: 1, isDeleted: false }]
  element.props.onChange(normalized, { viewBackgroundColor: '#ffffff' }, {})
  expect(wrapper.emitted('ready')).toHaveLength(1)
  expect(wrapper.emitted('change')).toBeUndefined()
  element.props.onChange(
    normalized,
    { viewBackgroundColor: '#ffffff', selectedElementIds: { shape: true } },
    {},
  )
  expect(wrapper.emitted('change')).toBeUndefined()
  element.props.onChange(
    [{ ...normalized[0], version: 2, x: 120 }],
    { viewBackgroundColor: '#ffffff' },
    {},
  )
  expect(wrapper.emitted('change')).toHaveLength(1)
  wrapper.unmount()
})

it('隐藏页签不抢占当前页面的保存快捷键', () => {
  const visible = ref(false)
  const wrapper = mount(DrawingCanvas, {
    props: { sceneJson: '{}', sceneKey: 'hidden', name: '后台草图' },
    global: { provide: { [pageVisibleKey as symbol]: visible } },
  })
  const event = new KeyboardEvent('keydown', { key: 's', ctrlKey: true, cancelable: true })
  window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(false)
  expect(wrapper.emitted('save')).toBeUndefined()
  wrapper.unmount()
})
