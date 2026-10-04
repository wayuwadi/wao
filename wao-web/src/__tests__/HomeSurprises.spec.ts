import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import HomeSurprises from '@/components/home/HomeSurprises.vue'
import { companionQuiet, companionEditing } from '@/composables/quietCompanions'

let media: EventTarget & { matches: boolean }
beforeEach(() => {
  vi.useFakeTimers()
  companionQuiet.value = companionEditing.value = false
  media = Object.assign(new EventTarget(), { matches: false })
  vi.stubGlobal('matchMedia', () => media)
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
})
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })

describe('首页短暂装饰层', () => {
  it('三种彩蛋都可展示、自动收起，Esc 立即中止', async () => {
    const w = mount(HomeSurprises)
    for (const style of ['bubbles', 'stars', 'chorus'] as const) {
      w.vm.celebrate(style); await nextTick()
      expect(document.querySelector('.surprise-celebration')?.classList.contains(style)).toBe(true)
      await vi.advanceTimersByTimeAsync(3200)
      expect(document.querySelector('.surprise-celebration')).toBeNull()
    }
    w.vm.celebrate('chorus'); await nextTick()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); await nextTick()
    expect(document.querySelector('.home-surprises')).toBeNull()
    w.unmount()
  })
  it('点击效果有限额，按钮与选中文本不拦截，卸载清理计时器', async () => {
    const w = mount(HomeSurprises)
    const blank = document.createElement('div'), button = document.createElement('button')
    document.body.append(blank, button)
    const tap = (target: Element) => { const e = new MouseEvent('click', { clientX: 70, clientY: 90 }); Object.defineProperty(e, 'target', { value: target }); w.vm.tap(e) }
    tap(button); await nextTick(); expect(document.querySelector('.tap-effect')).toBeNull()
    for (let i = 0; i < 20; i++) tap(blank)
    await nextTick(); expect(document.querySelectorAll('.tap-effect')).toHaveLength(8)
    await vi.advanceTimersByTimeAsync(1200); expect(document.querySelector('.home-surprises')).toBeNull()
    tap(blank); w.unmount(); expect(vi.getTimerCount()).toBe(0)
  })
  it('安静、编辑、后台、减少动态效果立即清空并拒绝补播', async () => {
    const w = mount(HomeSurprises)
    w.vm.celebrate('bubbles'); await nextTick()
    companionQuiet.value = true; await nextTick()
    expect(document.querySelector('.surprise-celebration')).toBeNull()
    w.vm.celebrate('stars'); await nextTick(); expect(document.querySelector('.surprise-celebration')).toBeNull()
    companionQuiet.value = false; companionEditing.value = true; await nextTick()
    w.vm.celebrate('stars'); await nextTick(); expect(document.querySelector('.surprise-celebration')).toBeNull()
    companionEditing.value = false; await nextTick()
    w.vm.celebrate('chorus'); await nextTick()
    vi.mocked(Object.getOwnPropertyDescriptor(document, 'hidden')!.get!).mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange')); await nextTick()
    expect(document.querySelector('.surprise-celebration')).toBeNull()
    vi.mocked(Object.getOwnPropertyDescriptor(document, 'hidden')!.get!).mockReturnValue(false)
    media.matches = true; media.dispatchEvent(new Event('change'))
    w.vm.celebrate('bubbles'); await nextTick(); expect(document.querySelector('.surprise-celebration')).toBeNull()
    w.unmount()
  })
})
