import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import HomeStartGuide from '@/components/home/HomeStartGuide.vue'
import { toast } from '@/composables/toast'
const state = vi.hoisted(() => ({ session: null as unknown as { userId: string } }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => state.session }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))
beforeEach(() => { localStorage.clear(); vi.restoreAllMocks(); state.session = reactive({ userId: 'member-a' }) })
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/')
  const wrapper = mount(HomeStartGuide, { global: { plugins: [router] } })
  return { wrapper, router }
}
it('每个起点只导航到对应功能', async () => {
  const { wrapper, router } = await render()
  for (const [index, path] of ['/todo', '/files', '/projects'].entries()) {
    await wrapper.findAll('a')[index]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe(path)
  }
  wrapper.unmount()
})
it('收起偏好刷新后恢复且成员之间隔离', async () => {
  const first = await render()
  await first.wrapper.get('button').trigger('click')
  first.wrapper.unmount()
  const { wrapper } = await render()
  expect(wrapper.find('.start-options').exists()).toBe(false)
  state.session.userId = 'member-b'
  await flushPromises()
  expect(wrapper.find('.start-options').exists()).toBe(true)
  state.session.userId = 'member-a'
  await flushPromises()
  expect(wrapper.find('.start-options').exists()).toBe(false)
  await wrapper.get('button').trigger('click')
  expect(wrapper.findAll('a')).toHaveLength(3)
  wrapper.unmount()
})
it('本机存储不可用仍能收起展开并解释偏好未记住', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  const { wrapper } = await render()
  await wrapper.get('button').trigger('click')
  expect(wrapper.find('.start-options').exists()).toBe(false)
  expect(toast).toHaveBeenCalledWith('显示已切换，但本机无法记住选择', 'ri-information-line')
  await wrapper.get('button').trigger('click')
  expect(wrapper.findAll('a')).toHaveLength(3)
  wrapper.unmount()
})
