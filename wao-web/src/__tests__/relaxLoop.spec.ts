import { expect, it, vi } from 'vitest'
import { defineComponent, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { useGameLoop } from '@/components/relax/useGameLoop'

it('失焦后台与停用时暂停，回来不补跑后台时间，卸载清理动画', async () => {
  let frame: FrameRequestCallback | undefined
  const request = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((fn) => {
    frame = fn
    return 1
  })
  const cancel = vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {
    frame = undefined
  })
  const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  const active = ref(true),
    step = vi.fn()
  const w = mount(
    defineComponent({
      setup() {
        useGameLoop(active, step)
        return () => null
      },
    }),
  )
  frame!(100)
  frame!(116)
  expect(step).toHaveBeenLastCalledWith(0.016)
  hidden.mockReturnValue(true)
  document.dispatchEvent(new Event('visibilitychange'))
  expect(frame).toBeUndefined()
  hidden.mockReturnValue(false)
  document.dispatchEvent(new Event('visibilitychange'))
  frame!(60000)
  expect(step).toHaveBeenCalledTimes(1)
  frame!(60016)
  expect(step).toHaveBeenCalledTimes(2)
  active.value = false
  await nextTick()
  expect(frame).toBeUndefined()
  w.unmount()
  const calls = request.mock.calls.length
  document.dispatchEvent(new Event('visibilitychange'))
  expect(request).toHaveBeenCalledTimes(calls)
  expect(cancel).toHaveBeenCalled()
  vi.restoreAllMocks()
})
