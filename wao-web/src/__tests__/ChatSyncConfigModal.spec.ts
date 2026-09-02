import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ChatSyncConfigModal from '@/components/overlays/ChatSyncConfigModal.vue'

const api = vi.hoisted(() => ({
  fetchChatSyncConfig: vi.fn(),
  fetchFeishuChatMembers: vi.fn(),
  fetchFeishuChats: vi.fn(),
  fetchFeishuTextPolishConfig: vi.fn(),
  fetchQuickCollectConfig: vi.fn(),
  fetchWebhookEndpoints: vi.fn(),
  runChatSync: vi.fn(),
  saveChatSyncConfig: vi.fn(),
  saveFeishuTextPolishConfig: vi.fn(),
  saveQuickCollectConfig: vi.fn(),
}))

vi.mock('@/api/system', () => api)
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))

const quickConfig = {
  enabled: true,
  projectId: '',
  verificationToken: 'toke••••••oken',
  hasVerificationToken: true,
  encryptKey: '',
  hasEncryptKey: false,
  mentionOpenIds: [],
  receivedEmoji: 'GET',
  triggerEmoji: 'StatusInFlight',
  completedEmoji: 'DONE',
}

describe('飞书群聊同步扩展配置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.fetchChatSyncConfig.mockResolvedValue({
      appId: 'cli-app', appSecretMask: 'secr••••cret', appSecretSet: true,
      chatId: 'oc-chat', chatName: 'WAO 群', kbId: '', senders: [],
    })
    api.fetchQuickCollectConfig.mockResolvedValue(quickConfig)
    api.fetchFeishuTextPolishConfig.mockResolvedValue({
      enabled: true, webhookId: 'fw-polish', webhookName: '润色机器人', mentionName: '润色机器人',
    })
    api.fetchWebhookEndpoints.mockResolvedValue([{
      id: 'fw-polish', name: '润色机器人', description: '', urlMask: 'open.feishu.cn/…abcd',
      urlSet: true, enabled: true, systemDefault: false,
      createdAt: '2026-08-30T10:00:00', updatedAt: '2026-08-30T10:00:00',
    }])
    api.fetchFeishuChatMembers.mockResolvedValue([])
    api.fetchFeishuChats.mockResolvedValue([])
    api.runChatSync.mockResolvedValue('同步完成')
    api.saveQuickCollectConfig.mockImplementation(async (payload) => ({
      ...quickConfig,
      receivedEmoji: payload.receivedEmoji ?? quickConfig.receivedEmoji,
      triggerEmoji: payload.triggerEmoji ?? quickConfig.triggerEmoji,
      completedEmoji: payload.completedEmoji ?? quickConfig.completedEmoji,
    }))
    api.saveFeishuTextPolishConfig.mockResolvedValue({
      enabled: true, webhookId: 'fw-polish', webhookName: '润色机器人', mentionName: '文案机器人',
    })
    api.saveChatSyncConfig.mockResolvedValue({
      appId: 'cli-app', appSecretMask: 'secr••••cret', appSecretSet: true,
      chatId: 'oc-chat', chatName: 'WAO 群', kbId: '', senders: [],
    })
  })

  it('展示快速收集表情流程、事件通道和撤回说明', async () => {
    const wrapper = mount(ChatSyncConfigModal, { global: { plugins: [createPinia()] } })
    await flushPromises()

    expect(wrapper.text()).toContain('已收下')
    expect(wrapper.text()).toContain('人工派工 / AI Run 中')
    expect(wrapper.text()).toContain('完成待验收')
    expect(wrapper.text()).toContain('im.message.reaction.created_v1')
    expect(wrapper.text()).toContain('im.message.recalled_v1')
    expect(wrapper.text()).toContain('对应任务也会自动删除')
    expect(wrapper.text()).toContain('主动读取表情')
    expect(wrapper.findAll('.reaction-flow select')
      .map((select) => (select.element as HTMLSelectElement).value))
      .toEqual(['GET', 'StatusInFlight', 'DONE'])
  })

  it('同时保存三阶段表情与群聊润色配置', async () => {
    const wrapper = mount(ChatSyncConfigModal, { global: { plugins: [createPinia()] } })
    await flushPromises()
    const selects = wrapper.findAll('.reaction-flow select')
    await selects[0]!.setValue('CheckMark')
    await selects[1]!.setValue('StatusInFlight')
    await selects[2]!.setValue('DONE')

    const polishSection = wrapper.findAll('.section')
      .find((item) => item.text().includes('群聊润色机器人'))!
    expect(polishSection.text()).toContain('润色机器人')
    expect(polishSection.get('select').element.value).toBe('fw-polish')
    await polishSection.get('input[placeholder*="群内机器人名称"]').setValue('文案机器人')
    await wrapper.get('.btn-primary').trigger('click')
    await flushPromises()

    expect(api.saveQuickCollectConfig).toHaveBeenCalledWith(expect.objectContaining({
      receivedEmoji: 'CheckMark',
      triggerEmoji: 'StatusInFlight',
      completedEmoji: 'DONE',
    }))
    expect(api.saveFeishuTextPolishConfig).toHaveBeenCalledWith({
      enabled: true,
      webhookId: 'fw-polish',
      mentionName: '文案机器人',
    })
    expect(api.saveChatSyncConfig).toHaveBeenCalled()
  })

  it('没有独立 Webhook 时明确使用 WAO 应用回复原消息', async () => {
    api.fetchFeishuTextPolishConfig.mockResolvedValue({
      enabled: false, webhookId: '', webhookName: '', mentionName: '润色机器人',
    })
    const wrapper = mount(ChatSyncConfigModal, { global: { plugins: [createPinia()] } })
    await flushPromises()

    const polishSection = wrapper.findAll('.section')
      .find((item) => item.text().includes('群聊润色机器人'))!
    expect(polishSection.get('select').find('option:checked').text())
      .toContain('WAO 应用直接回复原消息')
    expect(polishSection.text()).toContain('默认由同一应用回复原消息')
  })
})
