import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { fetchNetworkOutageWindow, saveNetworkOutageWindow } from '@/api/system'

const notificationApi = vi.hoisted(() => ({ fetch: vi.fn(), save: vi.fn() }))

vi.mock('@/api/notificationSettings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/notificationSettings')>()
  return {
    ...actual,
    fetchNotificationSettings: notificationApi.fetch,
    saveNotificationSettings: notificationApi.save,
  }
})

vi.mock('@/api/system', () => ({
  fetchWebhookEndpoints: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  createWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  updateWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  deleteWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  testWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  fetchBuiltinMenuRegistry: vi.fn<() => Promise<unknown>>(),
  fetchExternalAppRegistry: vi.fn<() => Promise<unknown>>(),
  fetchSiteInfo: vi.fn<() => Promise<unknown>>(),
  fetchNetworkOutageWindow: vi.fn<() => Promise<unknown>>().mockResolvedValue({
    enabled: true,
    startTime: '00:00',
    endTime: '06:00',
  }),
  saveBuiltinMenuRegistry: vi.fn<() => Promise<unknown>>(),
  saveExternalAppRegistry: vi.fn<() => Promise<unknown>>(),
  saveSiteInfo: vi.fn<() => Promise<unknown>>(),
  saveNetworkOutageWindow: vi
    .fn<(value: { enabled: boolean; startTime: string; endTime: string }) => Promise<unknown>>()
    .mockImplementation(async (value) => value),
}))

describe('设置页 Webhook 草稿路由保护', () => {
  const originalConfirm = window.confirm
  let wrapper: VueWrapper
  let router: Router

  beforeEach(async () => {
    const notificationSettings = {
      canManageSystem: true,
      system: {
        enabled: true,
        quietHoursEnabled: false,
        quietStart: '22:00',
        quietEnd: '08:00',
        taskActivity: false,
        taskSuccess: false,
        aiRunSuccess: false,
        aiRunFailure: true,
        manualTask: true,
        reminders: true,
        deliveryFailure: true,
        automation: true,
      },
      personal: {
        enabled: true,
        taskCreated: true,
        taskCompleted: true,
        aiRunSuccess: true,
        aiRunFailure: true,
        loginActivity: true,
      },
    }
    notificationApi.fetch.mockReset().mockResolvedValue(structuredClone(notificationSettings))
    notificationApi.save.mockReset().mockResolvedValue(structuredClone(notificationSettings))
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
    expect(
      (wrapper.get('input[placeholder="例如 WAO 主群"]').element as HTMLInputElement).value,
    ).toBe('未保存机器人')
  })

  it('断网时段覆盖加载、草稿防丢和保存成功状态', async () => {
    const entry = wrapper.findAll('.snav-item').find((button) => button.text() === '断网时段')
    await entry!.trigger('click')
    await flushPromises()

    expect(fetchNetworkOutageWindow).toHaveBeenCalledOnce()
    const times = wrapper.findAll('input[type="time"]')
    await times[1]!.setValue('06:30')
    expect(wrapper.text()).toContain('有未保存修改')

    window.confirm = vi.fn<(message?: string) => boolean>(() => false)
    await router.push('/other')
    expect(router.currentRoute.value.path).toBe('/settings')

    await wrapper.get('form.site-form').trigger('submit')
    await flushPromises()
    expect(saveNetworkOutageWindow).toHaveBeenCalledWith({
      enabled: true,
      startTime: '00:00',
      endTime: '06:30',
    })
    expect(wrapper.text()).toContain('已保存')
  })

  it('通知设置保留设置侧栏，并在切换菜单配置时保护未保存草稿', async () => {
    const notificationEntry = wrapper
      .findAll('.snav-item')
      .find((button) => button.text() === '通知设置')
    await notificationEntry!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/settings?tab=notifications')
    expect(wrapper.get('.notification-settings').text()).toContain('系统 · 群聊通知')
    expect(wrapper.get('.project-policy').text()).toContain('按项目通知')
    expect(notificationEntry!.classes()).toContain('on')

    await wrapper.get('.quiet-row > label input').setValue(true)
    window.confirm = vi.fn<(message?: string) => boolean>(() => false)
    const navigationEntry = wrapper
      .findAll('.snav-item')
      .find((button) => button.text() === '我的导航')
    await navigationEntry!.trigger('click')

    expect(window.confirm).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.fullPath).toBe('/settings?tab=notifications')
    expect((wrapper.get('.quiet-row > label input').element as HTMLInputElement).checked).toBe(true)
  })
})
