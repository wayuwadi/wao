import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { useMobileVisualViewport } from '@/composables/mobileVisualViewport'

class Viewport extends EventTarget {
  height = 844
  offsetTop = 0
  scale = 1
}
let viewport: Viewport
let media: EventTarget & { matches: boolean }
let wrapper: VueWrapper | undefined
const Probe = defineComponent({
  setup: useMobileVisualViewport,
  template: '<div :style="fullscreenStyle" :data-keyboard="keyboardOpen" />',
})
const originalHeight = window.innerHeight

beforeEach(() => {
  viewport = new Viewport()
  media = Object.assign(new EventTarget(), { matches: true })
  vi.stubGlobal('visualViewport', viewport)
  vi.stubGlobal('matchMedia', () => media)
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: 844 })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: originalHeight })
})
function edit() {
  const input = document.createElement('div')
  input.setAttribute('contenteditable', 'true')
  input.tabIndex = 0
  document.body.append(input)
  input.focus()
  return input
}
async function resize(height: number, top = 0, scale = 1) {
  Object.assign(viewport, { height, offsetTop: top, scale })
  viewport.dispatchEvent(new Event('resize'))
  await nextTick()
}

describe('手机编辑可见视口', () => {
  it('键盘收缩后跟随高度和偏移，收起时恢复', async () => {
    wrapper = mount(Probe)
    edit()
    await resize(420, 38)
    expect(wrapper.attributes('data-keyboard')).toBe('true')
    expect(wrapper.attributes('style')).toContain('--note-visible-height: 420px')
    expect(wrapper.attributes('style')).toContain('--note-visible-top: 38px')
    await resize(844)
    expect(wrapper.attributes('data-keyboard')).toBe('false')
    expect(wrapper.attributes('style')).toContain('--note-visible-height: 844px')
  })
  it('浏览器工具栏缩小和没有编辑焦点时不隐藏导航', async () => {
    wrapper = mount(Probe)
    await resize(420)
    expect(wrapper.attributes('data-keyboard')).toBe('false')
    edit()
    await resize(760)
    expect(wrapper.attributes('data-keyboard')).toBe('false')
  })
  it('失焦恢复导航，输入保留', async () => {
    wrapper = mount(Probe)
    const input = edit()
    input.textContent = '未保存的小记'
    await resize(420)
    input.blur()
    await nextTick()
    expect(wrapper.attributes('data-keyboard')).toBe('false')
    expect(input.textContent).toBe('未保存的小记')
  })
  it('双指缩放不当作键盘，也不限制可见框', async () => {
    wrapper = mount(Probe)
    edit()
    await resize(420, 40, 2)
    expect(wrapper.attributes('data-keyboard')).toBe('false')
    expect(wrapper.attributes('style') ?? '').not.toContain('--note-visible-height')
  })
  it('切回桌面不应用手机高度，手机编辑框仍是同一个', async () => {
    wrapper = mount(Probe)
    const input = edit()
    await resize(420)
    media.matches = false
    const event = Object.assign(new Event('change'), { matches: false })
    media.dispatchEvent(event)
    await nextTick()
    expect(wrapper.attributes('data-keyboard')).toBe('false')
    expect(wrapper.attributes('style') ?? '').not.toContain('--note-visible-height')
    expect(document.activeElement).toBe(input)
  })
  it('旧浏览器无 VisualViewport 时保留 CSS 回退', () => {
    vi.stubGlobal('visualViewport', undefined)
    wrapper = mount(Probe)
    expect(wrapper.attributes('data-keyboard')).toBe('false')
    expect(wrapper.attributes('style') ?? '').not.toContain('--note-visible-height')
  })
  it('卸载后移除事件，避免反复进入小记积累监听', () => {
    const remove = vi.spyOn(viewport, 'removeEventListener')
    const removeDocument = vi.spyOn(document, 'removeEventListener')
    wrapper = mount(Probe)
    wrapper.unmount()
    wrapper = undefined
    expect(remove.mock.calls.map(([type]) => type)).toEqual(['resize', 'scroll'])
    expect(removeDocument.mock.calls.map(([type]) => type)).toEqual(expect.arrayContaining(['focusin', 'focusout']))
  })
})
