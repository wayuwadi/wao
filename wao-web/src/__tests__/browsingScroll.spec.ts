import { expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { useBrowsingScroll } from '@/composables/browsingScroll'

it('等待数据后恢复位置，用户滚动优先，显式定位不被后续布局恢复覆盖', async () => {
  const position = ref(700),
    ready = ref(false),
    scope = ref('files')
  let resized = () => {},
    stopRestoring = () => {}
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback: () => void) {
        resized = callback
      }
      observe() {}
      disconnect() {}
    },
  )
  const Host = defineComponent({
    setup() {
      const root = ref<HTMLElement>()
      ;({ stopRestoring } = useBrowsingScroll(
        root,
        position,
        () => ready.value,
        () => scope.value,
      ))
      return { root }
    },
    template: '<div class="content"><div ref="root">内容</div></div>',
  })
  const wrapper = mount(Host, { attachTo: document.body })
  const container = wrapper.element as HTMLElement
  await flushPromises()
  expect(container.scrollTop).toBe(0)
  ready.value = true
  await flushPromises()
  expect(container.scrollTop).toBe(700)
  await wrapper.trigger('wheel')
  container.scrollTop = 900
  await wrapper.trigger('scroll')
  expect(position.value).toBe(900)
  resized()
  await flushPromises()
  expect(container.scrollTop).toBe(900)
  scope.value = 'images'
  position.value = 100
  await flushPromises()
  expect(container.scrollTop).toBe(100)
  stopRestoring()
  container.scrollTop = 350
  resized()
  await flushPromises()
  expect(container.scrollTop).toBe(350)
  wrapper.unmount()
  vi.unstubAllGlobals()
})
