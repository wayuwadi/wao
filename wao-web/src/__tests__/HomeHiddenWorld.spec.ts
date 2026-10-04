import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import HomeHiddenWorld from '@/components/home/HomeHiddenWorld.vue'
import { companionEditing, companionQuiet } from '@/composables/quietCompanions'
import type { WorldStory } from '@/components/home/companionWorld'

let media: EventTarget & { matches: boolean }, now: number
enableAutoUnmount(afterEach)
beforeEach(() => {
  vi.useFakeTimers(); now = 0
  companionEditing.value = companionQuiet.value = false
  media = Object.assign(new EventTarget(), { matches: false })
  vi.stubGlobal('matchMedia', () => media)
  vi.spyOn(performance, 'now').mockImplementation(() => now)
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
})
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })
const anchors = () => Array.from({ length: 4 }, (_, i) => ({ x: 100 + i * 150, y: 400, size: 50, available: true }))
function event(target: Element = document.createElement('div'), detail = 1) {
  const e = new MouseEvent('click', { clientX: 120, clientY: 90, detail }); Object.defineProperty(e, 'target', { value: target }); return e
}
describe('留白物件与收口', () => {
  it('只有一个泡泡，落下后发出接住剧情；戳破改为寻找且不再接住', async () => {
    const w = mount(HomeHiddenWorld, { props: { anchors } })
    w.vm.tap(event()); now = 400; w.vm.tap(event()); await nextTick()
    expect(document.querySelectorAll('.world-bubble')).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(2300)
    expect(w.emitted<[WorldStory]>('story')?.some(e => e[0].kind === 'catch')).toBe(true)
    now = 3000; w.vm.tap(event()); await nextTick()
    const bubble = document.querySelector('.world-bubble')!
    now = 3500
    bubble.dispatchEvent(new MouseEvent('click', { clientX: 200, clientY: 200, bubbles: true, detail: 1 })); await nextTick()
    expect(w.emitted<[WorldStory]>('story')?.slice(-1)[0]?.[0].kind).toBe('miss')
    await vi.advanceTimersByTimeAsync(3000)
    expect(w.emitted<[WorldStory]>('story')?.filter(e => e[0].kind === 'catch')).toHaveLength(1)
    w.unmount(); expect(vi.getTimerCount()).toBe(0)
  })
  it('按钮和选区保留原行为，节拍和双点可确定触发，物件有限额', async () => {
    const w = mount(HomeHiddenWorld, { props: { anchors } })
    w.vm.tap(event(document.createElement('button'))); await nextTick()
    expect(w.emitted('story')).toBeUndefined()
    for (now of [0, 450, 900]) w.vm.tap(event())
    expect(w.emitted<[WorldStory]>('story')?.slice(-1)[0]?.[0].kind).toBe('wave')
    now = 3000; w.vm.tap(event(undefined, 2)); await nextTick()
    expect(w.emitted<[WorldStory]>('story')?.slice(-1)[0]?.[0].kind).toBe('orbit')
    for (let i = 0; i < 20; i++) w.vm.story({ kind: 'reverse', source: 3, tier: 'common' })
    await nextTick(); expect(document.querySelectorAll('.world-mark').length).toBeLessThanOrEqual(32)
    w.unmount()
  })
  it('集齐后的接力只在动画结束后出现一次，安静和离页清除所有计时器', async () => {
    const w = mount(HomeHiddenWorld, { props: { anchors } })
    w.vm.celebrated(); now = 1000; w.vm.tap(event())
    expect(w.emitted('story')).toBeUndefined()
    now = 3500; w.vm.tap(event())
    expect(w.emitted<[WorldStory]>('story')?.[0]?.[0].kind).toBe('pass')
    now = 4400; w.vm.tap(event())
    expect(w.emitted<[WorldStory]>('story')?.filter(e => e[0].kind === 'pass')).toHaveLength(1)
    companionQuiet.value = true; await nextTick()
    expect(document.querySelector('.home-hidden-world')).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
    w.vm.tap(event()); expect(w.emitted<[WorldStory]>('story')?.slice(-1)[0]?.[0].kind).not.toBe('catch')
    w.unmount()
  })
  it('减少动态与后台状态拒绝物件，回来不补播，编辑中同样安静', async () => {
    const w = mount(HomeHiddenWorld, { props: { anchors } })
    w.vm.tap(event()); await nextTick(); expect(document.querySelector('.world-bubble')).not.toBeNull()
    media.matches = true; media.dispatchEvent(new Event('change')); await nextTick()
    w.vm.tap(event()); expect(document.querySelector('.home-hidden-world')).toBeNull()
    media.matches = false; media.dispatchEvent(new Event('change'))
    companionEditing.value = true; await nextTick(); w.vm.tap(event()); expect(document.querySelector('.home-hidden-world')).toBeNull()
    companionEditing.value = false; await nextTick()
    vi.mocked(Object.getOwnPropertyDescriptor(document, 'hidden')!.get!).mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange')); w.vm.tap(event())
    await vi.advanceTimersByTimeAsync(3000)
    expect(w.emitted<[WorldStory]>('story')?.filter(e => e[0].kind === 'catch')).toHaveLength(0)
    w.unmount()
  })
  it('留白真实指针路径识别画圈，释放抑制合成泡泡；触屏拖动不捕获', async () => {
    const w = mount(HomeHiddenWorld, { props: { anchors } })
    const target = document.createElement('main')
    target.setPointerCapture = vi.fn(); target.hasPointerCapture = () => false
    const pointer = (x: number, y: number, pointerType = 'mouse') => {
      const e = new MouseEvent('pointermove', { clientX: x, clientY: y })
      Object.defineProperties(e, { pointerId: { value: 1 }, pointerType: { value: pointerType }, target: { value: target }, currentTarget: { value: target } })
      return e as PointerEvent
    }
    w.vm.down(pointer(200, 200))
    for (let i = 1; i <= 32; i++) w.vm.move(pointer(120 + 80 * Math.cos(i * Math.PI / 16), 200 + 80 * Math.sin(i * Math.PI / 16)))
    w.vm.up(pointer(200, 200)); await nextTick()
    expect(w.emitted<[WorldStory]>('story')?.slice(-1)[0]?.[0].kind).toBe('orbit')
    expect(document.querySelectorAll('.world-mark')).toHaveLength(12)
    w.vm.tap(event()); expect(document.querySelector('.world-bubble')).toBeNull()
    expect(target.setPointerCapture).toHaveBeenCalled()
    target.setPointerCapture = vi.fn()
    w.vm.clear(); w.vm.down(pointer(200, 200, 'touch'))
    for (let i = 0; i < 10; i++) w.vm.move(pointer(200 + i * 20, 200, 'touch'))
    w.vm.up(pointer(400, 200, 'touch'))
    expect(target.setPointerCapture).not.toHaveBeenCalled()
    w.unmount()
  })
  it('泡泡覆盖第二下连点时仍接续节拍，触屏双点仍可得到星系', async () => {
    const w = mount(HomeHiddenWorld, { props: { anchors } })
    w.vm.tap(event()); await nextTick(); now = 400
    document.querySelector('.world-bubble')!.dispatchEvent(new MouseEvent('click', { detail: 1, bubbles: true }))
    now = 800; w.vm.tap(event())
    expect(w.emitted<[WorldStory]>('story')?.slice(-1)[0]?.[0].kind).toBe('wave')
    w.vm.clear(); now = 4000; w.vm.tap(event()); await nextTick(); now = 4200
    document.querySelector('.world-bubble')!.dispatchEvent(new MouseEvent('click', { detail: 2, bubbles: true }))
    expect(w.emitted<[WorldStory]>('story')?.slice(-1)[0]?.[0].kind).toBe('orbit')
    w.unmount()
  })
})
