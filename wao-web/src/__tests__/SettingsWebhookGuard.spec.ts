import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'

vi.mock('@/api/system', () => ({
  fetchWebhookEndpoints: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  createWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  updateWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  deleteWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  testWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  fetchBuiltinMenuRegistry: vi.fn<() => Promise<unknown>>(),
  fetchExternalAppRegistry: vi.fn<() => Promise<unknown>>(),
  fetchSiteInfo: vi.fn<() => Promise<unknown>>(),
  saveBuiltinMenuRegistry: vi.fn<() => Promise<unknown>>(),
  saveExternalAppRegistry: vi.fn<() => Promise<unknown>>(),
  saveSiteInfo: vi.fn<() => Promise<unknown>>(),
}))

describe('设置页 Webhook 草稿路由保护', () => {
  const originalConfirm = window.confirm
  let wrapper: VueWrapper
  let router: Router

  beforeEach(async () => {
    setActivePinia(createPinia())
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/settings', component: { template: '<div />' } },
        { path: '/other', component: { template: '<div />' } },
      ],
    })
    await router.push('/settings')
    await router.isReady()
    wrapper = mount(SettingsView, { global: { plugins: [router] } })
  })

  afterEach(() => {
    wrapper.unmount()
    window.confirm = originalConfirm
  })

  it('路由离开时拒绝丢弃尚未保存的 Webhook 草稿', async () => {
    const entry = wrapper.findAll('.snav-item').find((button) => button.text() === 'Webhook配置')
    await entry!.trigger('click')
    await flushPromises()
    await wrapper.get('button.primary.small').trigger('click')
    await wrapper.get('input[placeholder="例如 WAO 主群"]').setValue('未保存机器人')
    window.confirm = vi.fn<(message?: string) => boolean>(() => false)

    await router.push('/other')

    expect(router.currentRoute.value.path).toBe('/settings')
    expect(window.confirm).toHaveBeenCalledOnce()
    expect((wrapper.get('input[placeholder="例如 WAO 主群"]').element as HTMLInputElement).value)
      .toBe('未保存机器人')
  })
})
