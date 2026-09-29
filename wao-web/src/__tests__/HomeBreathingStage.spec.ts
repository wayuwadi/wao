import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import HomeBreathingStage from '@/components/home/HomeBreathingStage.vue'
import { useSessionStore } from '@/stores/session'
import type { AuthSession } from '@/api/auth'

function setup(member = 'breathing-user-a') {
  const pinia = createPinia(),
    session = useSessionStore(pinia)
  session.account = { member: { id: member } } as AuthSession
  const wrapper = mount(HomeBreathingStage, {
    attachTo: document.body,
    global: { plugins: [pinia] },
  })
  return { wrapper, session }
}
const mounted: ReturnType<typeof setup>['wrapper'][] = []
function create(member?: string) {
  const result = setup(member)
  mounted.push(result.wrapper)
  return result
}
beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
})
afterEach(() => {
  mounted.forEach((w) => w.unmount())
  mounted.length = 0
  vi.useRealTimers()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
describe('首页呼吸练习', () => {
  it('默认静默；场景和日夜偏好按成员隔离，换号终止练习', async () => {
    const { wrapper, session } = create()
    expect(wrapper.classes()).toContain('idle')
    expect(wrapper.get('#galaxy').isVisible()).toBe(false)
    await wrapper.get('[aria-label="切换日间模式"]').trigger('click')
    expect(wrapper.classes()).toContain('day')
    await wrapper.get('[aria-label="调整画面与呼吸"]').trigger('click')
    await wrapper.findAll('.variant')[1]!.trigger('click')
    await wrapper.findAll('.panel .switch')[3]!.setValue(false)
    await wrapper.get('.primary').trigger('click')
    expect(wrapper.classes()).toContain('running')
    session.account = { member: { id: 'breathing-user-b' } } as AuthSession
    await flushPromises()
    expect(wrapper.classes()).toContain('idle')
    expect(wrapper.classes()).not.toContain('day')
    expect(wrapper.get('.ambient').classes()).toContain('halo')
    session.account = { member: { id: 'breathing-user-a' } } as AuthSession
    await flushPromises()
    expect(wrapper.classes()).toContain('day')
    expect(wrapper.get('.ambient').classes()).toContain('horizon')
  })
  it('自定义非法时长不可开始；暂停时计时与相位都保持', async () => {
    const { wrapper } = create()
    await wrapper.get('.duration button:last-child').trigger('click')
    await wrapper.get('input[type="number"]').setValue('0')
    expect(wrapper.get('.primary').attributes('disabled')).toBeDefined()
    await wrapper.get('input[type="number"]').setValue('7')
    await wrapper.get('[aria-label="调整画面与呼吸"]').trigger('click')
    await wrapper.findAll('.panel .switch')[3]!.setValue(false)
    await wrapper.get('.primary').trigger('click')
    await vi.advanceTimersByTimeAsync(3000)
    await wrapper.get('.primary').trigger('click')
    const time = wrapper.get('.timer').text(),
      scale = wrapper.get('.orb').attributes('style')
    await vi.advanceTimersByTimeAsync(20000)
    expect(wrapper.get('.timer').text()).toBe(time)
    expect(wrapper.get('.orb').attributes('style')).toBe(scale)
    await wrapper.get('.primary').trigger('click')
    await vi.advanceTimersByTimeAsync(1000)
    expect(wrapper.get('.timer').text()).toContain('06:56')
  })
  it('结束弹窗只出现一次，关闭后静默；卸载清理计时任务', async () => {
    const { wrapper } = create()
    await wrapper.get('[aria-label="调整画面与呼吸"]').trigger('click')
    await wrapper.findAll('.panel .switch')[3]!.setValue(false)
    await wrapper.get('.panel-foot button').trigger('click')
    await vi.advanceTimersByTimeAsync(10001)
    expect(wrapper.get('[role="dialog"]').text()).toContain('10 秒体验已结束')
    expect(wrapper.get('.stage-content').attributes('inert')).toBeDefined()
    await wrapper.get('[role="dialog"] button').trigger('click')
    await vi.advanceTimersByTimeAsync(10000)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.classes()).toContain('idle')
    wrapper.unmount()
    mounted.pop()
    expect(vi.getTimerCount()).toBe(0)
  })
})
