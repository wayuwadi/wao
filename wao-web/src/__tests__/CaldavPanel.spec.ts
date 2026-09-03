import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import CaldavPanel from '@/components/calendar/CaldavPanel.vue'
import {
  apiAddCaldavAccount,
  fetchCaldavAccounts,
  type AddCaldavAccountPayload,
  type CaldavAccount,
  type CaldavCalendar,
} from '@/api/caldav'

vi.mock('@/api/caldav', () => ({
  fetchCaldavAccounts: vi.fn<() => Promise<CaldavAccount[]>>(),
  apiAddCaldavAccount: vi.fn<(payload: AddCaldavAccountPayload) => Promise<CaldavAccount>>(),
  apiDeleteCaldavAccount: vi.fn<(id: string) => Promise<void>>(),
  apiSyncCaldavAccount: vi.fn<(id: string) => Promise<CaldavAccount>>(),
  apiToggleCaldavCalendar: vi.fn<(id: string, enabled: boolean) => Promise<CaldavCalendar>>(),
}))

vi.mock('@/composables/toast', () => ({ toast: vi.fn<(message: string, icon?: string) => void>() }))

describe('CalDAV 账户面板', () => {
  beforeEach(() => {
    vi.mocked(fetchCaldavAccounts).mockReset().mockResolvedValue([])
    vi.mocked(apiAddCaldavAccount).mockReset().mockResolvedValue({
      id: 'cda1',
      name: '企业微信日程',
      serverUrl: 'https://caldav.wecom.work',
      username: 'wecom-user',
      status: 'ok',
      lastSyncAt: '2026-09-03 14:00',
      syncError: null,
      calendars: [{ id: 'cdc1', name: '企业微信日程', color: '#07C160', enabled: true }],
    })
  })

  it('可从企业微信入口直接带入官方服务器并完成连接', async () => {
    const wrapper = mount(CaldavPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('企业微信日程')
    await wrapper.find('.wecom-entry').trigger('click')

    const inputs = wrapper.findAll('.site-form input')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('企业微信日程')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('caldav.wecom.work')
    expect(wrapper.find('.provider-guide a').attributes('href')).toContain('/help2/pc/20223')

    await inputs[2]!.setValue('wecom-user')
    await inputs[3]!.setValue('one-time-password')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(apiAddCaldavAccount).toHaveBeenCalledWith({
      name: '企业微信日程',
      serverUrl: 'caldav.wecom.work',
      username: 'wecom-user',
      password: 'one-time-password',
    })
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(wrapper.text()).toContain('已连接')
  })

  it('账户读取失败时显示原因并提供可执行的重试入口', async () => {
    vi.mocked(fetchCaldavAccounts)
      .mockRejectedValueOnce(new Error('网络暂不可用'))
      .mockResolvedValueOnce([])
    const wrapper = mount(CaldavPanel)
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('网络暂不可用')
    expect(wrapper.text()).not.toContain('还没有连接日历账户')
    await wrapper.find('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(fetchCaldavAccounts).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('还没有连接日历账户')
  })
})
