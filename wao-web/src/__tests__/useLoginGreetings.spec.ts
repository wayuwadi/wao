import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import { useLoginGreetings } from '@/composables/useLoginGreetings'

let wrapper: VueWrapper
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(10000)
})
afterEach(() => {
  wrapper?.unmount()
  vi.useRealTimers()
})
function setup(value = true) {
  const enabled = ref(value)
  const play = vi.fn<(index: number) => number | undefined>().mockReturnValue(350)
  let greetings!: ReturnType<typeof useLoginGreetings>
  wrapper = mount({
    setup() {
      greetings = useLoginGreetings(enabled, play)
      return () => null
    },
  })
  return { enabled, play, ...greetings }
}
describe('角色文字与短音效', () => {
  it('音效不叠加也不排队，同角色六秒冷却', () => {
    const greeting = setup()
    greeting.greet(0)
    greeting.greet(1)
    expect(greeting.play).toHaveBeenCalledTimes(1)
    expect(greeting.active.value).toBe(1)
    vi.advanceTimersByTime(350)
    greeting.greet(0)
    expect(greeting.play).toHaveBeenCalledTimes(1)
    greeting.greet(1)
    expect(greeting.play).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(6000)
    greeting.greet(0)
    expect(greeting.play).toHaveBeenCalledTimes(3)
  })
  it('静音时只展示文字；气泡到期、关闭和卸载清理计时器', () => {
    const greeting = setup(false)
    greeting.greet(4)
    expect(greeting.active.value).toBe(4)
    expect(greeting.play).not.toHaveBeenCalled()
    vi.advanceTimersByTime(3200)
    expect(greeting.active.value).toBeNull()
    greeting.enabled.value = true
    greeting.greet(2)
    greeting.enabled.value = false
    expect(greeting.sounding.value).toBe(false)
    greeting.dismiss()
    expect(greeting.active.value).toBeNull()
    greeting.greet(3)
    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('音效不可用时不锁定冷却，不影响文字和后续重试', () => {
    const greeting = setup()
    greeting.play.mockReturnValueOnce(undefined)
    greeting.greet(0)
    expect(greeting.active.value).toBe(0)
    expect(greeting.sounding.value).toBe(false)
    greeting.greet(0)
    expect(greeting.play).toHaveBeenCalledTimes(2)
    expect(greeting.sounding.value).toBe(true)
  })
})
