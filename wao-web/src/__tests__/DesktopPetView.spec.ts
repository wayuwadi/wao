import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DesktopPetView from '@/views/DesktopPetView.vue'
vi.mock('@/composables/visibilityPolling', () => ({ createVisibilityPoller: () => ({ start: vi.fn(), stop: vi.fn() }) }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ authed: true, userId: 'm1' }) }))

afterEach(() => vi.unstubAllGlobals())
describe('桌面宠物入口', () => {
  async function setup(version: string) {
    setActivePinia(createPinia())
    const location = { search: `?native=${version}`, href: '' }
    vi.stubGlobal('location', location)
    const wrapper = mount(DesktopPetView, { global: { stubs: { PetSprite: true, PetMessenger: { emits: ['open'], template: '<button class="notice" @click="$emit(\'open\')">新消息</button>' } } } })
    await flushPromises()
    return { wrapper, location }
  }
  it('本体开关工作区，气泡独立打开新消息', async () => {
    const { wrapper, location } = await setup('3')
    await wrapper.get('.pet-avatar').trigger('click')
    expect(location.href).toBe('/__desktop_pet__/toggle-workspace')
    await wrapper.get('.pet-avatar').trigger('contextmenu')
    expect(location.href).toBe('/__desktop_pet__/open-chat')
    await wrapper.get('.notice').trigger('click')
    expect(location.href).toBe('/__desktop_pet__/open-chat')
    await wrapper.get('[aria-label="隐藏桌面宠物"]').trigger('click')
    expect(location.href).toBe('/__desktop_pet__/hide')
    wrapper.unmount()
  })
  it('旧客户端回到工作区且不发送未知动作', async () => {
    const { wrapper, location } = await setup('2')
    await wrapper.get('.pet-avatar').trigger('click')
    expect(location.href).toBe('/__desktop_pet__/workspace')
    wrapper.unmount()
  })
  it('拖动结束不会误收起工作区', async () => {
    const { wrapper, location } = await setup('3')
    wrapper.get('.pet-avatar').element.dispatchEvent(new MouseEvent('pointerdown', { button: 0, clientX: 10, clientY: 10, bubbles: true }))
    wrapper.get('.pet-avatar').element.dispatchEvent(new MouseEvent('pointermove', { buttons: 1, clientX: 40, clientY: 10, bubbles: true }))
    await wrapper.get('.pet-avatar').trigger('click')
    expect(location.href).toBe('/__desktop_pet__/drag')
    wrapper.unmount()
  })
})
