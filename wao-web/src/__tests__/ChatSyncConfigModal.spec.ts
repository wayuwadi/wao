import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ChatSyncConfigModal from '@/components/overlays/ChatSyncConfigModal.vue'
import { fetchQuickCollectConfig, saveQuickCollectConfig } from '@/api/system'

vi.mock('@/api/system', () => ({
  fetchChatSyncConfig: vi.fn(async () => ({
    appId: 'cli_app',
    appSecretMask: 'cli_••••secret',
    appSecretSet: true,
    chatId: 'oc_group',
    chatName: 'WAO 群',
    kbId: '',
    senders: [],
  })),
  fetchQuickCollectConfig: vi.fn(),
  fetchFeishuChatMembers: vi.fn(async () => []),
  fetchFeishuChats: vi.fn(async () => []),
  runChatSync: vi.fn(async () => '同步完成'),
  saveChatSyncConfig: vi.fn(async (payload) => ({
    appId: payload.appId ?? '',
    appSecretMask: 'cli_••••secret',
    appSecretSet: true,
    chatId: payload.chatId ?? '',
    chatName: payload.chatName ?? '',
    kbId: payload.kbId ?? '',
    senders: payload.senders ?? [],
  })),
  saveQuickCollectConfig: vi.fn(),
}))

vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))

const config = {
  enabled: true,
  projectId: '',
  verificationToken: 'toke••••••oken',
  hasVerificationToken: true,
  encryptKey: '',
  hasEncryptKey: false,
  mentionOpenIds: [],
  receivedEmoji: 'GET',
  completedEmoji: 'DONE',
}

describe('飞书快速收集表情工作流设置', () => {
  beforeEach(() => {
    vi.mocked(fetchQuickCollectConfig).mockReset().mockResolvedValue(config)
    vi.mocked(saveQuickCollectConfig).mockReset().mockImplementation(async (payload) => ({
      ...config,
      receivedEmoji: payload.receivedEmoji ?? config.receivedEmoji,
      completedEmoji: payload.completedEmoji ?? config.completedEmoji,
    }))
  })

  it('展示 Get → 保持 → Done 并说明表情事件订阅是必要条件', async () => {
    const wrapper = mount(ChatSyncConfigModal, { global: { plugins: [createPinia()] } })
    await flushPromises()

    expect(wrapper.text()).toContain('已收下 / 人工派工')
    expect(wrapper.text()).toContain('AI Run 中保持')
    expect(wrapper.text()).toContain('完成待验收')
    expect(wrapper.text()).toContain('im.message.reaction.created_v1')
    expect(wrapper.text()).toContain('仅靠 15 秒消息轮询无法收到表情事件')
    expect(wrapper.findAll('.reaction-flow select')
      .map((select) => (select.element as HTMLSelectElement).value))
      .toEqual(['GET', 'DONE'])
  })

  it('保存时让回执与人工触发表情共用同一配置', async () => {
    const wrapper = mount(ChatSyncConfigModal, { global: { plugins: [createPinia()] } })
    await flushPromises()
    const selects = wrapper.findAll('.reaction-flow select')
    await selects[0]!.setValue('StatusInFlight')
    await selects[1]!.setValue('CheckMark')
    await wrapper.get('.btn-primary').trigger('click')
    await flushPromises()

    expect(saveQuickCollectConfig).toHaveBeenCalledWith(expect.objectContaining({
      receivedEmoji: 'StatusInFlight',
      completedEmoji: 'CheckMark',
    }))
  })
})
