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
    taskActivity: false,
    taskSuccess: false,
    aiRunSuccess: false,
    aiRunFailure: true,
    manualTask: true,
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
    const wrapper = mount(NotificationSettingsPanel)
    await flushPromises()

    const systemRows = wrapper.findAll('.policy-card')[0]!.findAll('.policy-row')
    expect((systemRows[2]!.get('input').element as HTMLInputElement).checked).toBe(false)
    expect((systemRows[3]!.get('input').element as HTMLInputElement).checked).toBe(false)
    expect((systemRows[4]!.get('input').element as HTMLInputElement).checked).toBe(true)
    await systemRows[3]!.get('input').setValue(true)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    expect((wrapper.vm as unknown as { confirmLeave: () => boolean }).confirmLeave()).toBe(false)
    confirm.mockRestore()

    await wrapper.get('footer button').trigger('click')
    await flushPromises()

    expect(api.save).toHaveBeenCalledWith(expect.objectContaining({
      system: expect.objectContaining({ aiRunSuccess: true, aiRunFailure: true }),
    }))
    expect(wrapper.text()).toContain('已保存')
  })

  it('普通成员只能改自己的站内偏好，保存失败后保留草稿并可重试', async () => {
    api.fetch.mockResolvedValueOnce({ ...structuredClone(settings), canManageSystem: false })
    api.save.mockRejectedValueOnce(new Error('网络不可用')).mockResolvedValueOnce({
      ...structuredClone(settings), canManageSystem: false, personal: { ...settings.personal, loginActivity: false },
    })
    const wrapper = mount(NotificationSettingsPanel)
    await flushPromises()

    expect(wrapper.findAll('.policy-card')[0]!.findAll('input').every((input) => input.attributes('disabled') !== undefined)).toBe(true)
    const personalRows = wrapper.findAll('.policy-card')[1]!.findAll('.policy-row')
    await personalRows[5]!.get('input').setValue(false)
    await wrapper.get('footer button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('网络不可用')

    await wrapper.get('.inline-error button').trigger('click')
    await flushPromises()
    expect(api.save).toHaveBeenLastCalledWith(expect.not.objectContaining({ system: expect.anything() }))
    expect(wrapper.text()).toContain('已保存')
  })
})
