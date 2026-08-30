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

describe('ChatSyncConfigModal 群聊润色配置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.fetchChatSyncConfig.mockResolvedValue({
      appId: 'cli-app', appSecretMask: 'secr••••cret', appSecretSet: true,
      chatId: 'oc-chat', chatName: 'WAO 群', kbId: '', senders: [],
    })
    api.fetchQuickCollectConfig.mockResolvedValue({
      enabled: false, projectId: '', verificationToken: '', hasVerificationToken: false,
      encryptKey: '', hasEncryptKey: false, mentionOpenIds: [],
    })
    api.fetchFeishuTextPolishConfig.mockResolvedValue({
      enabled: true, webhookId: 'fw-polish', webhookName: '润色机器人', mentionName: '润色机器人',
    })
    api.fetchWebhookEndpoints.mockResolvedValue([{
      id: 'fw-polish', name: '润色机器人', description: '', urlMask: 'open.feishu.cn/…abcd',
      urlSet: true, enabled: true, systemDefault: false,
      createdAt: '2026-08-30T10:00:00', updatedAt: '2026-08-30T10:00:00',
    }])
    api.saveQuickCollectConfig.mockResolvedValue({
      enabled: false, projectId: '', verificationToken: '', hasVerificationToken: false,
      encryptKey: '', hasEncryptKey: false, mentionOpenIds: [],
    })
    api.saveFeishuTextPolishConfig.mockResolvedValue({
      enabled: true, webhookId: 'fw-polish', webhookName: '润色机器人', mentionName: '文案机器人',
    })
    api.saveChatSyncConfig.mockResolvedValue({
      appId: 'cli-app', appSecretMask: 'secr••••cret', appSecretSet: true,
      chatId: 'oc-chat', chatName: 'WAO 群', kbId: '', senders: [],
    })
  })

  it('读取已选 Webhook，并随群聊配置一起保存触发名称', async () => {
    const wrapper = mount(ChatSyncConfigModal, { global: { plugins: [createPinia()] } })
    await flushPromises()

    const section = wrapper.findAll('.section')
      .find((item) => item.text().includes('群聊润色机器人'))!
    expect(section.text()).toContain('润色机器人')
    expect(section.get('select').element.value).toBe('fw-polish')
    await section.get('input[placeholder*="群内机器人名称"]').setValue('文案机器人')
    await wrapper.get('.btn-primary').trigger('click')
    await flushPromises()

    expect(api.saveFeishuTextPolishConfig).toHaveBeenCalledWith({
      enabled: true,
      webhookId: 'fw-polish',
      mentionName: '文案机器人',
    })
    expect(api.saveChatSyncConfig).toHaveBeenCalled()
  })
})
