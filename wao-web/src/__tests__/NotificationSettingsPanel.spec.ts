import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import NotificationSettingsPanel from '@/components/settings/NotificationSettingsPanel.vue'
import type { NotificationSettings } from '@/api/notificationSettings'

const api = vi.hoisted(() => ({ fetch: vi.fn(), save: vi.fn() }))

vi.mock('@/api/notificationSettings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/notificationSettings')>()
  return {
    ...actual,
    fetchNotificationSettings: api.fetch,
    saveNotificationSettings: api.save,
  }
})
const settings: NotificationSettings = {
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

describe('通知设置', () => {
  beforeEach(() => {
    api.fetch.mockReset().mockResolvedValue(structuredClone(settings))
    api.save.mockReset().mockImplementation(async (value) => ({
      ...structuredClone(settings),
      system: value.system ?? settings.system,
      personal: value.personal,
    }))
  })

  it('展示低噪音系统默认值，保存管理员共享策略与当前成员偏好', async () => {
    const wrapper = mount(NotificationSettingsPanel, {
      global: { stubs: { DailyReminderSettingsPanel: true } },
    })
    await flushPromises()

    const systemRows = wrapper.findAll('.policy-card')[0]!.findAll('.policy-row')
    expect((systemRows[2]!.get('input').element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.text()).not.toContain('AI Run')
    expect((systemRows[4]!.get('input').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.text()).toContain('Jenkins 失败通知')
    expect(wrapper.text()).toContain('每日沉默时段')
    await wrapper.get('.quiet-row > label input').setValue(true)
    await wrapper.get('input[aria-label="沉默开始时间"]').setValue('21:30')
    expect(wrapper.text()).toContain('有未保存修改')
    await wrapper.get('#notification-system-taskSuccess input').setValue(true)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    expect((wrapper.vm as unknown as { confirmLeave: () => boolean }).confirmLeave()).toBe(false)
    confirm.mockRestore()

    await wrapper.get('footer button').trigger('click')
    await flushPromises()

    expect(api.save).toHaveBeenCalledWith(expect.objectContaining({
      system: expect.objectContaining({ taskSuccess: true, aiRunSuccess: false, aiRunFailure: false }),
      personal: expect.objectContaining({ aiRunSuccess: false, aiRunFailure: false }),
    }))
    expect(wrapper.text()).toContain('已保存')
  })

  it('普通成员只能改自己的站内偏好，保存失败后保留草稿并可重试', async () => {
    api.fetch.mockResolvedValueOnce({ ...structuredClone(settings), canManageSystem: false })
    api.save.mockRejectedValueOnce(new Error('网络不可用')).mockResolvedValueOnce({
      ...structuredClone(settings), canManageSystem: false, personal: { ...settings.personal, enabled: false },
    })
    const wrapper = mount(NotificationSettingsPanel, {
      global: { stubs: { DailyReminderSettingsPanel: true } },
    })
    await flushPromises()

    expect(wrapper.findAll('.policy-card')[0]!.findAll('input').every((input) => input.attributes('disabled') !== undefined)).toBe(true)
    const personalRows = wrapper.findAll('.policy-card')[1]!.findAll('.policy-row')
    await personalRows[0]!.get('input').setValue(false)
    await wrapper.get('footer button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('网络不可用')

    await wrapper.get('.inline-error button').trigger('click')
    await flushPromises()
    expect(api.save).toHaveBeenLastCalledWith(expect.not.objectContaining({ system: expect.anything() }))
    expect(wrapper.text()).toContain('已保存')
  })
  it('草稿和保存失败不冒充已生效，成功后才更新诊断', async () => {
    api.save.mockRejectedValueOnce(new Error('保存失败'))
    const wrapper = mount(NotificationSettingsPanel, { global: { stubs: { DailyReminderSettingsPanel: true } } })
    await flushPromises()
    await wrapper.get('#notification-system-enabled input').setValue(false)
    expect(wrapper.get('.effective-state').text()).toContain('总开关已开启')
    expect(wrapper.get('.draft-status').text()).toContain('不会随草稿改变')
    await wrapper.get('footer button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.effective-state').text()).toContain('总开关已开启')
    await wrapper.get('.inline-error button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.effective-state').text()).toContain('系统通知总开关已关闭')
    expect(wrapper.find('.draft-status').exists()).toBe(false)
    wrapper.unmount()
  })

})
