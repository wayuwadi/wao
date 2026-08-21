import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'

const companionApi = vi.hoisted(() => ({
  fetchCompanionPairing: vi.fn(),
  approveCompanionPairing: vi.fn(),
  fetchCompanionDevices: vi.fn(),
  revokeCompanionDevice: vi.fn(),
}))

vi.mock('@/api/companion', () => companionApi)

describe('桌面宠物设置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    companionApi.fetchCompanionDevices.mockResolvedValue([{
      id: 'device-1', name: '书房电脑', platform: 'windows-x64',
      createdAt: '2026-08-21T10:00:00', lastSeenAt: '2026-08-21T10:01:00',
    }])
    companionApi.fetchCompanionPairing.mockResolvedValue({
      code: 'ABCD-EFGH', deviceName: '书房电脑', platform: 'windows-x64',
      status: 'PENDING', expiresAt: '2026-08-21T10:10:00',
    })
    companionApi.approveCompanionPairing.mockResolvedValue({
      code: 'ABCD-EFGH', deviceName: '书房电脑', platform: 'windows-x64',
      status: 'APPROVED', expiresAt: '2026-08-21T10:10:00',
    })
    companionApi.revokeCompanionDevice.mockResolvedValue(undefined)
  })

  it('从深链预览并批准设备，再允许撤销已连接设备', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=companion&code=ABCD-EFGH')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(companionApi.fetchCompanionPairing).toHaveBeenCalledWith('ABCD-EFGH')
    expect(wrapper.get('.companion-code').text()).toBe('ABCD-EFGH')
    expect(wrapper.text()).toContain('书房电脑')
    expect(wrapper.get('.companion-download.windows').attributes('href'))
      .toBe('/downloads/companion/WAO-Companion-win-x64.zip')
    expect(wrapper.get('.companion-download.macos').attributes('href'))
      .toBe('/downloads/companion/WAO-Companion-macos-universal.zip')

    await wrapper.get('.companion-approve-row .btn-primary').trigger('click')
    await flushPromises()
    expect(companionApi.approveCompanionPairing).toHaveBeenCalledWith('ABCD-EFGH')
    expect(wrapper.text()).toContain('已批准，请回到桌宠完成连接')

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await wrapper.get('.companion-device-row .revoke').trigger('click')
    await flushPromises()
    expect(companionApi.revokeCompanionDevice).toHaveBeenCalledWith('device-1')
    expect(wrapper.find('.companion-device-row').exists()).toBe(false)
  })

  it('配对码过期时保留明确错误和重试入口', async () => {
    companionApi.fetchCompanionPairing.mockRejectedValueOnce(new Error('配对码不存在或已经过期'))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=companion&code=EXPR-CODE')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.get('.companion-error').text()).toContain('配对码不存在或已经过期')
    expect(wrapper.get('.companion-error button').text()).toContain('重新读取')
    expect(wrapper.find('.companion-approve-row').exists()).toBe(false)
  })

  it('重复批准失败时不伪造成功状态', async () => {
    companionApi.approveCompanionPairing.mockRejectedValueOnce(new Error('该设备已经批准'))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=companion&code=ABCD-EFGH')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    await flushPromises()
    await wrapper.get('.companion-approve-row .btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.get('.companion-error').text()).toContain('该设备已经批准')
    expect(wrapper.text()).not.toContain('已批准，请回到桌宠完成连接')
  })
})
