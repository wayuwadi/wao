import { afterEach, expect, it } from 'vitest'
import { defineComponent, ref } from 'vue'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import MobileShellMenu from '@/components/nav/MobileShellMenu.vue'

enableAutoUnmount(afterEach)
it('窄屏入口能挂载原导航与工具，关闭后保持原组件及输入', async () => {
  const Host = defineComponent({
    components: { MobileShellMenu },
    setup: () => ({ open: ref(false), mobile: ref(true) }),
    template: `<MobileShellMenu :open="open" @close="open = false" />
      <Teleport defer to="#mobile-navigation-slot" :disabled="!mobile"><input aria-label="导航查找" /></Teleport>
      <Teleport defer to="#mobile-tools-slot" :disabled="!mobile"><button>已有工具</button></Teleport>
      <button class="more" @click="open = true">更多</button><button class="resize" @click="mobile = !mobile">切换宽度</button>`,
  })
  const wrapper = mount(Host, { attachTo: document.body })
  await flushPromises()
  const input = document.querySelector<HTMLInputElement>('[aria-label="导航查找"]')!
  expect(input.closest('#mobile-navigation-slot')).not.toBeNull()
  expect(wrapper.get('.mobile-menu-backdrop').isVisible()).toBe(false)
  await wrapper.get('.more').trigger('click')
  await flushPromises()
  expect(wrapper.get('.mobile-menu-backdrop').isVisible()).toBe(true)
  expect(document.activeElement?.getAttribute('aria-label')).toBe('关闭完整菜单')
  input.value = '保留输入'
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
  await flushPromises()
  expect(wrapper.get('.mobile-menu-backdrop').isVisible()).toBe(false)
  await wrapper.get('.resize').trigger('click')
  expect(document.querySelector('[aria-label="导航查找"]')).toBe(input)
  expect(input.closest('#mobile-navigation-slot')).toBeNull()
  expect(input.value).toBe('保留输入')
  await wrapper.get('.resize').trigger('click')
  expect(input.closest('#mobile-navigation-slot')).not.toBeNull()
  await wrapper.get('.more').trigger('click')
  await wrapper.get('[aria-label="关闭完整菜单"]').trigger('click')
  expect(wrapper.get('.mobile-menu-backdrop').isVisible()).toBe(false)
})

