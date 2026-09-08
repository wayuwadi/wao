import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import DailyReminderSettingsPanel from '@/components/settings/DailyReminderSettingsPanel.vue'
import type { NotificationReminder } from '@/api/notificationReminders'

const api = vi.hoisted(() => ({
  fetch: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  test: vi.fn(),
}))

vi.mock('@/api/notificationReminders', () => ({
  fetchNotificationReminders: api.fetch,
  createNotificationReminder: api.create,
  updateNotificationReminder: api.update,
  deleteNotificationReminder: api.remove,
  testNotificationReminder: api.test,
}))

const reminders: NotificationReminder[] = [
  {
    id: 'am',
    title: '早间打卡提醒',
    content: '记得打卡',
    time: '08:50',
    enabled: true,
    workdayOnly: false,
    lastResult: '尚未运行',
    nextFireAt: '2026-09-01T08:50:00',
  },
  {
    id: 'lunch',
    title: '午餐提醒',
    content: '去吃饭',
    time: '12:00',
    enabled: true,
    workdayOnly: false,
    lastResult: '尚未运行',
    nextFireAt: '2026-09-01T12:00:00',
  },
  {
    id: 'nap',
    title: '午休提醒',
    content: '准备睡觉',
    time: '12:30',
    enabled: true,
    workdayOnly: false,
    lastResult: '尚未运行',
    nextFireAt: '2026-09-01T12:30:00',
  },
  {
    id: 'pm',
    title: '下班休息与打卡提醒',
    content: '休息并打卡',
    time: '18:00',
    enabled: true,
    workdayOnly: true,
    lastResult: '尚未运行',
    nextFireAt: '2026-09-01T18:00:00',
  },
]

describe('每日提醒设置', () => {
  beforeEach(() => {
    for (const mock of Object.values(api)) mock.mockReset()
    api.fetch.mockResolvedValue({ canManageSystem: true, reminders: structuredClone(reminders) })
    api.update.mockImplementation(async (_id, value) => ({
      ...structuredClone(reminders[0]),
      ...value,
    }))
  })

  it('展示四条真实提醒，编辑后保存完整草稿', async () => {
    const wrapper = mount(DailyReminderSettingsPanel)
    await flushPromises()

    expect(wrapper.findAll('.reminder-row')).toHaveLength(4)
    expect(wrapper.findAll('.time-field span').map((item) => item.text())).toEqual([
      '每日',
      '每日',
      '每日',
      '工作日',
    ])
    expect(
      wrapper
        .findAll('input[type="time"]')
        .map((input) => (input.element as HTMLInputElement).value),
    ).toEqual(['08:50', '12:00', '12:30', '18:00'])
    const first = wrapper.findAll('.reminder-row')[0]!
    await first.get('textarea').setValue('早上好，记得打卡。')
    expect(first.text()).toContain('有未保存修改')
    expect((wrapper.vm as unknown as { hasDirty: boolean }).hasDirty).toBe(true)

    await first.get('.primary').trigger('click')
    await flushPromises()
    expect(api.update).toHaveBeenCalledWith(
      'am',
      expect.objectContaining({
        time: '08:50',
        content: '早上好，记得打卡。',
        enabled: true,
      }),
    )
    expect(wrapper.text()).toContain('已保存')
  })

  it('新增失败时保留草稿，未保存前禁止试发', async () => {
    api.create.mockRejectedValueOnce(new Error('目标群尚未配置'))
    const wrapper = mount(DailyReminderSettingsPanel)
    await flushPromises()

    await wrapper.get('.reminder-head button').trigger('click')
    const draft = wrapper.findAll('.reminder-row')[4]!
    await draft.get('input[aria-label="提醒标题"]').setValue('喝水提醒')
    await draft.get('textarea').setValue('起来喝水')
    expect(draft.get('.ghost').attributes('disabled')).toBeDefined()
    await draft.get('.primary').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.reminder-row')).toHaveLength(5)
    expect(wrapper.text()).toContain('目标群尚未配置')
    expect((draft.get('textarea').element as HTMLTextAreaElement).value).toBe('起来喝水')
  })

  it('普通成员只能查看提醒', async () => {
    api.fetch.mockResolvedValueOnce({
      canManageSystem: false,
      reminders: structuredClone(reminders),
    })
    const wrapper = mount(DailyReminderSettingsPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('仅管理员可编辑')
    expect(wrapper.find('.actions').exists()).toBe(false)
    expect(
      wrapper
        .findAll('.reminder-row input')
        .every((input) => input.attributes('disabled') !== undefined),
    ).toBe(true)
  })
})
