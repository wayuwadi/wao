import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ChatSyncConfigModal from '@/components/overlays/ChatSyncConfigModal.vue'

const api = vi.hoisted(() => ({
  fetchChatSyncConfig: vi.fn(),
  fetchFeishuChatMembers: vi.fn(),
  fetchFeishuChats: vi.fn(),
  fetchQuickCollectConfig: vi.fn(),
  runChatSync: vi.fn(),
  saveChatSyncConfig: vi.fn(),
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
    api.fetchFeishuChatMembers.mockResolvedValue([])
    api.fetchFeishuChats.mockResolvedValue([])
    api.runChatSync.mockResolvedValue('同步完成')
    api.saveQuickCollectConfig.mockImplementation(async (payload) => ({
      ...quickConfig,
      receivedEmoji: payload.receivedEmoji ?? quickConfig.receivedEmoji,
      triggerEmoji: payload.triggerEmoji ?? quickConfig.triggerEmoji,
      completedEmoji: payload.completedEmoji ?? quickConfig.completedEmoji,
    }))
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
    expect(wrapper.text()).toContain('im.message.receive_v1')
    expect(wrapper.text()).toContain('10 分钟')
    expect(wrapper.text()).not.toContain('立即同步今天')
    expect(wrapper.text()).not.toContain('补录历史')
    expect(api.runChatSync).not.toHaveBeenCalled()
    expect(wrapper.findAll('.reaction-flow select')
      .map((select) => (select.element as HTMLSelectElement).value))
      .toEqual(['GET', 'StatusInFlight', 'DONE'])
  })

  it('保存三阶段表情配置且不再展示群聊润色机器人', async () => {
    const wrapper = mount(ChatSyncConfigModal, { global: { plugins: [createPinia()] } })
    await flushPromises()
    const selects = wrapper.findAll('.reaction-flow select')
    await selects[0]!.setValue('CheckMark')
    await selects[1]!.setValue('StatusInFlight')
    await selects[2]!.setValue('DONE')

    expect(wrapper.text()).not.toContain('群聊润色机器人')
    await wrapper.get('.btn-primary').trigger('click')
    await flushPromises()

    expect(api.saveQuickCollectConfig).toHaveBeenCalledWith(expect.objectContaining({
      receivedEmoji: 'CheckMark',
      triggerEmoji: 'StatusInFlight',
      completedEmoji: 'DONE',
    }))
    expect(api.saveChatSyncConfig).toHaveBeenCalled()
  })
})
