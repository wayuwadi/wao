import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import HomeBreathingStage from '@/components/home/HomeBreathingStage.vue'
import { useSessionStore } from '@/stores/session'
import type { AuthSession } from '@/api/auth'
let wrapper: ReturnType<typeof mount<typeof HomeBreathingStage>>
function setup() {
  const pinia = createPinia()
  wrapper = mount(HomeBreathingStage, { attachTo: document.body, global: { plugins: [pinia] } })
  return useSessionStore(pinia)
}
beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
})
afterEach(() => {
  wrapper?.unmount()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.replaceChildren()
})
describe('公共练习状态', () => {
  it('访客、成员独立偏好；主动认证保留暂停计时与节奏，恢复不覆盖成员设置', async () => {
    localStorage.setItem(
      'wao-breathing-guest:v1',
      JSON.stringify({ minutes: 1, inhale: 3, exhale: 4, scene: 'horizon' }),
    )
    localStorage.setItem(
      'wao-home-breathing:member',
      JSON.stringify({ minutes: 30, inhale: 5, exhale: 7, scene: 'blank' }),
    )
    const original = localStorage.getItem('wao-home-breathing:member')
    const session = setup()
    await wrapper.get('.primary').trigger('click')
    await vi.advanceTimersByTimeAsync(3000)
    wrapper.vm.pauseForAuth()
    session.account = { member: { id: 'member' } } as AuthSession
    await flushPromises()
    expect(wrapper.classes()).toContain('paused')
    expect(wrapper.get('.timer').text()).toContain('00:57')
    expect(wrapper.get('.bottom-note').text()).toContain('吸 3 秒 · 呼 4 秒')
    expect(wrapper.get('.ambient').classes()).toContain('blank')
    expect(localStorage.getItem('wao-home-breathing:member')).toBe(original)
    await vi.advanceTimersByTimeAsync(60000)
    expect(wrapper.get('.timer').text()).toContain('00:57')
    session.account = { member: { id: 'other' } } as AuthSession
    await flushPromises()
    expect(wrapper.classes()).toContain('idle')
  })
  it('活跃切换明确确认；取消保留暂停进度，确认静坐不显示吸呼指令', async () => {
    setup()
    await wrapper.get('.primary').trigger('click')
    await vi.advanceTimersByTimeAsync(1250)
    await wrapper.findAll('.mode-picker button')[1]!.trigger('click')
    const remaining = wrapper.get('.overlay').text()
    expect(remaining).toContain('14:59')
    await wrapper.get('.overlay .primary').trigger('click')
    expect(wrapper.classes()).toContain('paused')
    await wrapper.findAll('.mode-picker button')[1]!.trigger('click')
    await wrapper.get('.overlay .text-button').trigger('click')
    expect(wrapper.classes()).toContain('idle')
    await wrapper.get('.primary').trigger('click')
    expect(wrapper.get('.phase').text()).toBe('只是安静坐着')
    expect(wrapper.get('.bottom-note').text()).not.toMatch(/吸.*秒/)
    const scale = wrapper.get('.orb').attributes('style')
    await vi.advanceTimersByTimeAsync(3000)
    expect(wrapper.get('.orb').attributes('style')).toBe(scale)
    await wrapper.get('.actions .text-button').trigger('click')
    expect(wrapper.get('[role=dialog]').text()).toContain('本次静坐已结束')
    expect(wrapper.get('[role=dialog]').text()).toContain('进入工作空间')
  })
  it('禁用存储仍可练习，明确提示；音频异常不阻断计时', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.stubGlobal(
      'AudioContext',
      class {
        constructor() {
          throw new Error('audio unavailable')
        }
      },
    )
    setup()
    expect(wrapper.get('[role=alert]').text()).toContain('本次设置仍可使用')
    await wrapper.get('.sound-toggle').trigger('click')
    await wrapper.get('.primary').trigger('click')
    await flushPromises()
    expect(wrapper.classes()).toContain('running')
    expect(wrapper.text()).toContain('声音暂不可用')
  })
  it('恢复快照只恢复本次练习，普通卸载重进回到静默', async () => {
    setup()
    wrapper.vm.restore({ mode: 'sitting', total: 60000, remaining: 42000, inhale: 3, exhale: 4 })
    await flushPromises()
    expect(wrapper.classes()).toContain('paused')
    expect(wrapper.get('.timer').text()).toContain('00:42')
    expect(localStorage.length).toBe(0)
    wrapper.unmount()
    setup()
    expect(wrapper.classes()).toContain('idle')
  })
})
