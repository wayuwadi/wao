import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import WorkdayNotificationRoutes from '@/components/workday/WorkdayNotificationRoutes.vue'
import { DEFAULT_WORKDAY_RHYTHM } from '@/utils/workdayRhythm'

const api = vi.hoisted(() => ({ webhooks: vi.fn(), chats: vi.fn() }))
vi.mock('@/api/system', () => ({
  fetchWebhookEndpoints: api.webhooks,
  fetchFeishuChats: api.chats,
}))

describe('通知群独立加载', () => {
  it('机器人群读取较慢时，已返回的 Webhook 仍可选择，失败后可重试', async () => {
    let rejectChats!: (reason: Error) => void
    api.webhooks.mockResolvedValue([{ id: 'hook-a', name: '验收群', enabled: true }])
    api.chats.mockReturnValueOnce(
      new Promise((_, reject) => {
        rejectChats = reject
      }),
    )
    const original = structuredClone(DEFAULT_WORKDAY_RHYTHM.energyNotification)
    const wrapper = mount(WorkdayNotificationRoutes, {
      props: { modelValue: original, onlyTianren: true },
    })
    await flushPromises()
    const select = wrapper.get('select')
    expect((select.element as HTMLSelectElement).disabled).toBe(false)
    expect(wrapper.text()).toContain('验收群')
    await select.setValue('WEBHOOK:hook-a')
    const updated = wrapper.emitted('update:modelValue')![0]![0] as typeof original
    expect(updated.tianrenTarget?.webhookId).toBe('hook-a')
    expect(updated.focusTarget).toMatchObject({
      channel: original.channel,
      webhookId: original.webhookId,
      webhookName: original.webhookName,
      chatId: original.chatId,
      chatName: original.chatName,
    })
    expect(original.tianrenTarget).toEqual(DEFAULT_WORKDAY_RHYTHM.energyNotification.tianrenTarget)
    rejectChats(new Error('暂不可用'))
    await flushPromises()
    expect(wrapper.text()).toContain('飞书机器人所在群读取失败')
    api.chats.mockResolvedValue([{ chatId: 'chat-a', name: '机器人群' }])
    await wrapper.get('.route-options-status button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('机器人群')
    expect(wrapper.text()).not.toContain('读取失败')
    wrapper.unmount()
  })
})
