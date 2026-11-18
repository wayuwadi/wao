import { expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import DrawingCanvas from '@/components/drawings/DrawingCanvas.vue'
import type { ReactElement } from 'react'
import { ref } from 'vue'
import { pageVisibleKey } from '@/composables/pageVisibility'
import type { DrawingTidyCanvas } from '@/utils/drawingTidyClient'
const { render, calculate } = vi.hoisted(() => ({ render: vi.fn(), calculate: vi.fn() }))
vi.mock('@excalidraw/excalidraw', () => ({
  CaptureUpdateAction: { NEVER: 'NEVER', IMMEDIATELY: 'IMMEDIATELY' },
  Excalidraw: () => null,
  newElementWith: (_element: unknown, update: unknown) => update,
  serializeAsJSON: (elements: unknown[], appState: unknown, files: unknown) =>
    JSON.stringify({ elements, appState, files }),
}))
vi.mock('react-dom/client', () => ({ createRoot: () => ({ render, unmount: vi.fn() }) }))
vi.mock('@/utils/drawingTidyClient', () => ({ calculateDrawingTidy: calculate }))

it('预览不写入画布，过期结果被拒绝，应用时只生成一笔撤销记录', async () => {
  const wrapper = mount(DrawingCanvas, {
    props: { sceneJson: '{}', sceneKey: 'tidy', name: '测试画布', snapMode: 'objects' },
  })
  const { props } = render.mock.lastCall![0] as ReactElement<{
    excalidrawAPI: (api: object) => void
    onChange: (elements: unknown[], state: unknown, files: unknown) => void
  }>
  const initial = { id: 'shape', x: 12, version: 1, versionNonce: 2 }
  const state = { objectsSnapModeEnabled: true, selectedElementIds: { shape: true } }
  let elements = [initial]
  const updateScene = vi.fn()
  props.excalidrawAPI({
    getAppState: () => state, getFiles: () => ({}),
    getSceneElementsIncludingDeleted: () => elements, updateScene,
  })
  props.onChange(elements, state, {})
  const canvas = wrapper.emitted('ready')?.[0]?.[0] as DrawingTidyCanvas
  const signal = new AbortController().signal
  const changed = { ...initial, x: 0 }
  calculate.mockResolvedValue({ changed: [changed], moved: 1, routed: 0, skipped: 0 })
  const stale = await canvas.prepare({ mode: 'layout', scope: 'selection' }, signal)
  expect(updateScene).not.toHaveBeenCalled()
  elements = [{ ...initial, version: 2 }]
  expect(() => stale.apply()).toThrow('草图已发生变化')
  expect(updateScene).not.toHaveBeenCalled()
  const fresh = await canvas.prepare({ mode: 'light', scope: 'selection' }, signal)
  fresh.apply()
  expect(updateScene).toHaveBeenCalledExactlyOnceWith({ elements: [changed], captureUpdate: 'IMMEDIATELY' })
  wrapper.unmount()
})

it.each(['metaKey', 'ctrlKey'])('选择工具将 %s＋左键交给原有的多选逻辑', (modifier) => {
  const wrapper = mount(DrawingCanvas, {
    props: { sceneJson: '{}', sceneKey: 'test', name: '测试画布', snapMode: 'objects' },
  })
  const { props } = render.mock.lastCall![0] as ReactElement<{
    excalidrawAPI: (api: { getAppState: () => { activeTool: { type: string } } }) => void
  }>
  const activeTool = { type: 'selection' }
  props.excalidrawAPI({ getAppState: () => ({ activeTool }) })
  const canvas = document.createElement('canvas')
  canvas.className = 'excalidraw__canvas interactive'
  wrapper.element.append(canvas)

  function dispatch(type: string, options: PointerEventInit) {
    const event = new PointerEvent(type, { pointerId: 1, pointerType: 'mouse', bubbles: true, ...options })
    canvas.dispatchEvent(event)
    return event
  }

  const down = dispatch('pointerdown', { [modifier]: true })
  expect(down.shiftKey).toBe(true)
  expect(down.metaKey || down.ctrlKey).toBe(false)
  expect(dispatch('pointerup', {}).shiftKey).toBe(true)
  expect(dispatch('pointerdown', {}).shiftKey).toBe(false)

  activeTool.type = 'rectangle'
  const draw = dispatch('pointerdown', { [modifier]: true })
  expect(draw.shiftKey).toBe(false)
  expect(draw.metaKey || draw.ctrlKey).toBe(true)
  wrapper.unmount()
})

it('画布拦截 Ctrl/Cmd+S，卸载后恢复浏览器快捷键', () => {
  const wrapper = mount(DrawingCanvas, {
    props: { sceneJson: '{}', sceneKey: 'test', name: '测试画布', snapMode: 'objects' },
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
    props: { sceneJson: '{"elements":[{"id":"shape"}]}', sceneKey: 'one', name: '测试画布', snapMode: 'off' },
  })
  const element = render.mock.calls[render.mock.calls.length - 1]![0] as ReactElement<{
    onChange: (elements: unknown[], state: unknown, files: unknown) => void
    excalidrawAPI: (api: object) => void
  }>
  element.props.excalidrawAPI({})
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
    props: { sceneJson: '{}', sceneKey: 'hidden', name: '后台草图', snapMode: 'objects' },
    global: { provide: { [pageVisibleKey as symbol]: visible } },
  })
  const event = new KeyboardEvent('keydown', { key: 's', ctrlKey: true, cancelable: true })
  window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(false)
  expect(wrapper.emitted('save')).toBeUndefined()
  wrapper.unmount()
})

it('吸附偏好优先于文件网格设置，切换时不修改草图或撤销栈', async () => {
  const wrapper = mount(DrawingCanvas, {
    props: {
      sceneJson: '{"appState":{"gridModeEnabled":true}}',
      sceneKey: 'legacy',
      name: '旧草图',
      snapMode: 'objects',
    },
  })
  const { props } = render.mock.lastCall![0] as ReactElement<{
    initialData: { appState: Record<string, boolean> }
    onChange: (elements: unknown[], state: Record<string, boolean>, files: object) => void
    excalidrawAPI: (api: { updateScene: ReturnType<typeof vi.fn> }) => void
  }>
  const updateScene = vi.fn()
  props.excalidrawAPI({ updateScene })
  expect(props.initialData.appState).toEqual({ objectsSnapModeEnabled: true, gridModeEnabled: false })
  props.onChange([], props.initialData.appState, {})

  await wrapper.setProps({ snapMode: 'grid' })
  expect(updateScene).toHaveBeenCalledWith({
    appState: { objectsSnapModeEnabled: false, gridModeEnabled: true },
    captureUpdate: 'NEVER',
  })
  props.onChange([], { gridModeEnabled: true, objectsSnapModeEnabled: false }, {})
  props.onChange([], { gridModeEnabled: false, objectsSnapModeEnabled: false }, {})
  expect(wrapper.emitted('update:snapMode')).toEqual([['off']])
  expect(wrapper.emitted('change')).toBeUndefined()

  props.onChange([{ id: 'shape' }], { gridModeEnabled: false }, {})
  const [sceneJson] = wrapper.emitted('change')![0]!
  expect(JSON.parse(sceneJson as string).appState.gridModeEnabled).toBe(true)
  wrapper.unmount()
})
