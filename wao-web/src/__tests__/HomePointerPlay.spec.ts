import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import HomePointerPlay from '@/components/home/HomePointerPlay.vue'
import { companionEditing, companionQuiet } from '@/composables/quietCompanions'
import {
  createCompanionRandomEvents,
  pointerEffects,
} from '@/components/home/companionRandomEvents'
import { companionActivities } from '@/components/home/companionActivities'
import type { WorldStory } from '@/components/home/companionWorld'

let media: EventTarget & { matches: boolean }
enableAutoUnmount(afterEach)
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(0)
  companionEditing.value = companionQuiet.value = false
  media = Object.assign(new EventTarget(), { matches: false })
  vi.stubGlobal('matchMedia', () => media)
  vi.spyOn(performance, 'now').mockImplementation(() => Date.now())
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.spyOn(Math, 'random').mockReturnValue(0)
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
const anchors = () =>
  Array.from({ length: 4 }, (_, i) => ({ x: 100 + i * 150, y: 400, size: 50, available: true }))
const mountPlay = () => mount(HomePointerPlay, { props: { anchors, available: () => true } })
function pointer(
  type = 'pointerdown',
  x = 150,
  y = 150,
  target: Element = document.createElement('div'),
  pointerType = 'mouse',
) {
  const event = new MouseEvent(type, { clientX: x, clientY: y, detail: 1, cancelable: true })
  Object.defineProperties(event, {
    pointerId: { value: 1 },
    pointerType: { value: pointerType },
    target: { value: target },
  })
  return event as PointerEvent
}

describe('统一随机池和按住识别', () => {
  it('十八种特效和六种活动都可抽中，同一事件不连续重复', () => {
    const results = Array.from({ length: 24 }, (_, i) =>
      createCompanionRandomEvents(() => (i + 0.5) / 24)(),
    )
    expect(results.filter((r) => r.type === 'effect').map((r) => r.kind)).toEqual([
      ...pointerEffects,
    ])
    expect(results.filter((r) => r.type === 'activity').map((r) => r.kind)).toEqual(
      companionActivities.map((a) => a.id),
    )
    const pick = createCompanionRandomEvents(() => 0)
    expect(pick()).not.toEqual(pick())
  })
  it('短点不触发，按住半秒只触发一次，松手散去且抑制合成点击', async () => {
    const w = mountPlay()
    w.vm.down(pointer())
    await vi.advanceTimersByTimeAsync(499)
    expect(w.emitted('begin')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(1)
    expect(w.emitted('begin')).toHaveLength(1)
    expect(document.querySelector('.home-pointer-play')?.getAttribute('data-effect')).toBe('breath')
    await vi.advanceTimersByTimeAsync(3500)
    expect(w.emitted('begin')).toHaveLength(1)
    window.dispatchEvent(pointer('pointerup'))
    await nextTick()
    expect(document.querySelector('.home-pointer-play')?.classList.contains('releasing')).toBe(true)
    expect(w.vm.consumeTap(pointer('click'))).toBe(true)
    expect(w.vm.consumeTap(pointer('click'))).toBe(false)
    await vi.advanceTimersByTimeAsync(800)
    expect(document.querySelector('.home-pointer-play')).toBeNull()
    w.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('长时间按住自动收势，晚松手仍不追加轻点，短点没有副作用', async () => {
    const w = mountPlay()
    w.vm.down(pointer())
    await vi.advanceTimersByTimeAsync(10000)
    expect(document.querySelector('.home-pointer-play')).toBeNull()
    expect(w.emitted('begin')).toHaveLength(1)
    w.vm.up(pointer('pointerup'))
    expect(w.vm.consumeTap(pointer('click'))).toBe(true)
    w.vm.down(pointer())
    await vi.advanceTimersByTimeAsync(200)
    w.vm.up(pointer('pointerup'))
    expect(w.vm.consumeTap(pointer('click'))).toBe(false)
    expect(w.emitted('begin')).toHaveLength(1)
    w.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('移走、按钮、文字、触屏滚动和选区不抢走原交互', async () => {
    const w = mountPlay()
    w.vm.down(pointer())
    w.vm.move(pointer('pointermove', 160))
    await vi.advanceTimersByTimeAsync(600)
    expect(w.emitted('begin')).toBeUndefined()
    for (const target of [
      document.createElement('button'),
      document.createElement('a'),
      document.createElement('textarea'),
    ]) {
      w.vm.down(pointer('pointerdown', 150, 150, target))
      const e = pointer('contextmenu', 150, 150, target)
      w.vm.trigger(e)
      expect(e.defaultPrevented).toBe(false)
    }
    w.vm.down(pointer('pointerdown', 150, 150, undefined, 'touch'))
    await vi.advanceTimersByTimeAsync(600)
    expect(w.emitted('begin')).toBeUndefined()
    w.vm.down(pointer())
    vi.spyOn(window, 'getSelection').mockReturnValue({ toString: () => '已选文字' } as Selection)
    await vi.advanceTimersByTimeAsync(600)
    expect(w.emitted('begin')).toBeUndefined()
    const e = pointer('contextmenu')
    w.vm.trigger(e)
    expect(e.defaultPrevented).toBe(false)
  })
  it('全部特效可以渲染且总粒子有限，右键直接触发并防止事件轰炸', async () => {
    for (let i = 0; i < pointerEffects.length; i++) {
      vi.mocked(Math.random).mockReturnValue((i + 0.5) / 24)
      const w = mountPlay(),
        e = pointer('contextmenu')
      w.vm.trigger(e)
      await nextTick()
      expect(e.defaultPrevented).toBe(true)
      expect(document.querySelector('.home-pointer-play')?.getAttribute('data-effect')).toBe(
        pointerEffects[i],
      )
      expect(document.querySelectorAll('.pointer-art i').length).toBeLessThanOrEqual(16)
      const reaction = w.emitted<[WorldStory]>('story')![0]![0]
      expect(reaction.kind === 'attention' || reaction.source >= 0).toBe(true)
      w.vm.trigger(pointer('contextmenu'))
      expect(w.emitted('begin')).toHaveLength(1)
      w.unmount()
      expect(vi.getTimerCount()).toBe(0)
    }
  })
  it('按住也能随机触发六种活动，场景回调清理后仍抑制松手点击', async () => {
    for (let i = 0; i < companionActivities.length; i++) {
      vi.mocked(Math.random).mockReturnValue((18 + i + 0.5) / 24)
      const w = mountPlay()
      w.setProps({ onActivity: () => w.vm.clear() })
      w.vm.down(pointer())
      await vi.advanceTimersByTimeAsync(500)
      expect(w.emitted('activity')?.[0]).toEqual([companionActivities[i]!.id])
      expect(document.querySelector('.home-pointer-play')).toBeNull()
      w.vm.up(pointer('pointerup'))
      expect(w.vm.consumeTap(pointer('click'))).toBe(true)
      w.unmount()
      expect(vi.getTimerCount()).toBe(0)
    }
  })
  it('键盘菜单键等价随机触发，失焦、Esc 和安静清理待触发计时器', async () => {
    const w = mountPlay()
    const e = new KeyboardEvent('keydown', { key: 'F10', shiftKey: true, cancelable: true })
    w.vm.trigger(e, 1)
    await nextTick()
    expect(e.defaultPrevented).toBe(true)
    expect(document.querySelector('.home-pointer-play')).not.toBeNull()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    expect(document.querySelector('.home-pointer-play')).toBeNull()
    w.vm.down(pointer())
    window.dispatchEvent(new Event('blur'))
    await vi.advanceTimersByTimeAsync(600)
    expect(w.emitted('begin')).toHaveLength(1)
    w.vm.down(pointer())
    companionQuiet.value = true
    await nextTick()
    await vi.advanceTimersByTimeAsync(600)
    expect(w.emitted('begin')).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })
  it('减少动态、编辑、后台和图形失败不触发，卸载不留计时器', async () => {
    const w = mountPlay()
    w.vm.down(pointer())
    media.matches = true
    media.dispatchEvent(new Event('change'))
    await vi.advanceTimersByTimeAsync(600)
    expect(w.emitted('begin')).toBeUndefined()
    media.matches = false
    media.dispatchEvent(new Event('change'))
    companionEditing.value = true
    await nextTick()
    w.vm.trigger(pointer('contextmenu'))
    expect(w.emitted('begin')).toBeUndefined()
    companionEditing.value = false
    await nextTick()
    w.vm.down(pointer())
    vi.mocked(Object.getOwnPropertyDescriptor(document, 'hidden')!.get!).mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(600)
    expect(w.emitted('begin')).toBeUndefined()
    vi.mocked(Object.getOwnPropertyDescriptor(document, 'hidden')!.get!).mockReturnValue(false)
    await w.setProps({ available: () => false })
    w.vm.trigger(pointer('contextmenu'))
    expect(w.emitted('begin')).toBeUndefined()
    w.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})
