import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { useWebPetStore } from '@/stores/webPet'
import type { AuthSession } from '@/api/auth'
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
    await wrapper.findAll('.snav-item').find(button => button.text() === '管理与连接')!.trigger('click')
    await flushPromises()
    const entry = wrapper.findAll('.settings-entry').find(button => button.text().includes('Webhook 配置'))
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

  it('下线设置不显示入口，旧链接和页内跳转继续回退到组织与成员', async () => {
    const labels = wrapper.findAll('.snav-item').map(button => button.text())
    for (const label of ['断网时段', '版本与更新', 'WAO AI 能力']) expect(labels).not.toContain(label)
    for (const tab of ['network-outage', 'version', 'wao-ai-capabilities']) {
      await router.replace('/settings?tab=' + tab)
      await flushPromises()
      expect(wrapper.get('.snav-item[aria-current="page"]').text()).toBe('管理与连接')
      expect(wrapper.text()).toContain('组织与成员')
      expect(wrapper.find('.version-settings').exists()).toBe(false)
      expect(wrapper.find('.wao-ai-capabilities').exists()).toBe(false)
    }
    expect(fetchNetworkOutageWindow).not.toHaveBeenCalled()
    expect(saveNetworkOutageWindow).not.toHaveBeenCalled()
  })

  it('通知设置保留设置侧栏，并在切换菜单配置时保护未保存草稿', async () => {
    const notificationEntry = wrapper
      .findAll('.snav-item')
      .find((button) => button.text() === '通知与提醒')
    await notificationEntry!.trigger('click')
    await flushPromises()
    await wrapper.findAll('.settings-entry').find(button => button.text().includes('站内与共享通知'))!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/settings?tab=notification-policy')
    expect(wrapper.get('.notification-settings').text()).toContain('系统 · 共享通知策略')
    expect(wrapper.find('.project-policy').exists()).toBe(false)
    expect(notificationEntry!.classes()).toContain('on')

    await wrapper.get('.quiet-row > label input').setValue(true)
    window.confirm = vi.fn<(message?: string) => boolean>(() => false)
    const navigationEntry = wrapper
      .findAll('.snav-item')
      .find((button) => button.text() === '个人与账号')
    await navigationEntry!.trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.fullPath).toBe('/settings?tab=notification-policy')
    expect((wrapper.get('.quiet-row > label input').element as HTMLInputElement).checked).toBe(true)
  })
  it('进入恢复草稿的宠物设置不误触发离开确认', async () => {
    useSessionStore().account = { member: { id: 'pet-guard' } } as AuthSession
    useWebPetStore().loadState = 'loaded'
    localStorage.setItem('wao-web-pet-settings-draft-v1:pet-guard', JSON.stringify({ showTaskNotices: false }))
    window.confirm = vi.fn(() => false)
    expect(wrapper.findAll('.snav-item').some(button => button.text() === '宠物设置')).toBe(false)
    await router.push('/settings?tab=pet')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/settings?tab=pet')
    expect(window.confirm).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已恢复未保存的宠物设置')
    await wrapper.findAll('.snav-item').find(button => button.text() === '外观与陪伴')!.trigger('click')
    await flushPromises()
    expect(window.confirm).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.fullPath).toBe('/settings?tab=pet')
    window.confirm = vi.fn(() => true)
    await wrapper.findAll('.snav-item').find(button => button.text() === '管理与连接')!.trigger('click')
    await flushPromises()
    expect(window.confirm).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.fullPath).toBe('/settings?tab=management')
    expect(wrapper.findAll('.snav-item').find(button => button.text() === '管理与连接')!.classes()).toContain('on')
    localStorage.removeItem('wao-web-pet-settings-draft-v1:pet-guard')
  })

})
