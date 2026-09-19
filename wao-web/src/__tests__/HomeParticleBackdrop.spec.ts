import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import HomeParticleBackdrop from '@/components/home/HomeParticleBackdrop.vue'

let callbacks: Map<number, FrameRequestCallback>
let nextFrame: number
let media: { matches: boolean; addEventListener: ReturnType<typeof vi.fn>; removeEventListener: ReturnType<typeof vi.fn> }
let onIntersection: IntersectionObserverCallback
let resizeDisconnect: ReturnType<typeof vi.fn>
let intersectionDisconnect: ReturnType<typeof vi.fn>
let context: { clearRect: ReturnType<typeof vi.fn>; setTransform: ReturnType<typeof vi.fn>; beginPath: ReturnType<typeof vi.fn>; arc: ReturnType<typeof vi.fn>; fill: ReturnType<typeof vi.fn> }

beforeEach(() => {
  localStorage.clear()
  callbacks = new Map()
  nextFrame = 0
  context = { clearRect: vi.fn(), setTransform: vi.fn(), beginPath: vi.fn(), arc: vi.fn(), fill: vi.fn() }
  media = { matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }
  resizeDisconnect = vi.fn()
  intersectionDisconnect = vi.fn()
  vi.stubGlobal('CanvasRenderingContext2D', class {})
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D)
  vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 900, height: 320 } as DOMRect)
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.stubGlobal('matchMedia', vi.fn(() => media))
  vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => { callbacks.set(++nextFrame, callback); return nextFrame }))
  vi.stubGlobal('cancelAnimationFrame', vi.fn((id: number) => { callbacks.delete(id) }))
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect = resizeDisconnect })
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: IntersectionObserverCallback) { onIntersection = callback }
    observe() {}
    disconnect = intersectionDisconnect
  })
})

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

function advance(time: number) {
  const entries = [...callbacks.entries()]
  callbacks.clear()
  entries.forEach(([, callback]) => callback(time))
}

it('播放会重绘，暂停后停止申请动画帧且刷新后保持暂停', async () => {
  const wrapper = mount(HomeParticleBackdrop, { props: { active: true } })
  await nextTick()
  expect(callbacks.size).toBe(1)
  const initialDraws = context.clearRect.mock.calls.length
  advance(100)
  advance(150)
  expect(context.clearRect.mock.calls.length).toBeGreaterThan(initialDraws)
  await wrapper.get('button').trigger('click')
  expect(callbacks.size).toBe(0)
  expect(wrapper.get('button').text()).toBe('播放动效')
  wrapper.unmount()
  const refreshed = mount(HomeParticleBackdrop, { props: { active: true } })
  await nextTick()
  expect(refreshed.get('button').attributes('aria-pressed')).toBe('true')
  expect(callbacks.size).toBe(0)
  await refreshed.get('button').trigger('click')
  expect(callbacks.size).toBe(1)
  refreshed.unmount()
})

it('系统减少动态效果时只绘制静态背景，并响应系统偏好变化', async () => {
  media.matches = true
  const wrapper = mount(HomeParticleBackdrop, { props: { active: true } })
  await nextTick()
  expect(context.clearRect).toHaveBeenCalled()
  expect(callbacks.size).toBe(0)
  expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('已减少动效')
  media.matches = false
  media.addEventListener.mock.calls[0]![1]()
  await nextTick()
  expect(callbacks.size).toBe(1)
  expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
  wrapper.unmount()
  expect(media.removeEventListener).toHaveBeenCalledWith('change', media.addEventListener.mock.calls[0]![1])
})

it('收起、离开视口、隐藏页面均停止动画，恢复后只有一个循环，卸载清理资源', async () => {
  const wrapper = mount(HomeParticleBackdrop, { props: { active: true } })
  await wrapper.setProps({ active: false })
  expect(callbacks.size).toBe(0)
  expect(wrapper.find('button').exists()).toBe(false)
  await wrapper.setProps({ active: true })
  expect(callbacks.size).toBe(1)
  onIntersection([{ isIntersecting: false } as IntersectionObserverEntry], {} as IntersectionObserver)
  expect(callbacks.size).toBe(0)
  onIntersection([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver)
  expect(callbacks.size).toBe(1)
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
  document.dispatchEvent(new Event('visibilitychange'))
  expect(callbacks.size).toBe(0)
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  document.dispatchEvent(new Event('visibilitychange'))
  document.dispatchEvent(new Event('visibilitychange'))
  expect(callbacks.size).toBe(1)
  wrapper.unmount()
  expect(callbacks.size).toBe(0)
  expect(resizeDisconnect).toHaveBeenCalledOnce()
  expect(intersectionDisconnect).toHaveBeenCalledOnce()
  document.dispatchEvent(new Event('visibilitychange'))
  expect(callbacks.size).toBe(0)
})

it('本机存储不可用仍可暂停，Canvas 不可用时不显示无效按钮', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  const wrapper = mount(HomeParticleBackdrop, { props: { active: true } })
  await nextTick()
  await wrapper.get('button').trigger('click')
  expect(callbacks.size).toBe(0)
  wrapper.unmount()
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
  const fallback = mount(HomeParticleBackdrop, { props: { active: true } })
  await nextTick()
  expect(fallback.find('button').exists()).toBe(false)
  expect(callbacks.size).toBe(0)
  fallback.unmount()
})
