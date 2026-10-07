import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import FeishuReceiverSettings from '@/components/capabilities/FeishuReceiverSettings.vue'
import {
  fetchChatSyncConfig,
  fetchQuickCollectConfig,
  saveChatSyncConfig,
  saveQuickCollectConfig,
  fetchFeishuChats,
} from '@/api/system'
vi.mock('@/api/system', () => ({
  fetchChatSyncConfig: vi.fn(),
  fetchQuickCollectConfig: vi.fn(),
  saveChatSyncConfig: vi.fn(),
  saveQuickCollectConfig: vi.fn(),
  fetchFeishuChats: vi.fn(),
}))
const cfg = {
  appId: 'app',
  appSecretMask: '••••',
  appSecretSet: true,
  chatId: 'chat',
  chatName: '真实群',
  kbId: 'kb',
  senders: [{ id: 'user', name: '显示名' }],
}
const quick = {
  enabled: true,
  projectId: 'p',
  verificationToken: '••••',
  hasVerificationToken: true,
  encryptKey: '',
  hasEncryptKey: false,
  mentionOpenIds: ['user'],
  receivedEmoji: 'GET',
  triggerEmoji: 'StatusInFlight',
  completedEmoji: 'DONE',
}
let wrapper: VueWrapper
beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  setActivePinia(createPinia())
  useSessionStore().accept({
    accountId: 'wg',
    member: { id: 'wg', name: '娃哥', role: '管理员', initial: 'W', color: '#fff' },
    identities: [],
    createdAt: '',
  })
  vi.mocked(fetchChatSyncConfig).mockResolvedValue(structuredClone(cfg))
  vi.mocked(fetchQuickCollectConfig).mockResolvedValue(structuredClone(quick))
  vi.mocked(saveChatSyncConfig).mockResolvedValue(structuredClone(cfg))
  vi.mocked(saveQuickCollectConfig).mockResolvedValue(structuredClone(quick))
})
afterEach(() => wrapper?.unmount())
async function render() {
  wrapper = mount(FeishuReceiverSettings)
  await flushPromises()
}
describe('共享飞书接收配置', () => {
  it('配置读取失败不是空表单，也不允许写入', async () => {
    vi.mocked(fetchChatSyncConfig).mockRejectedValue(new Error('加载失败'))
    await render()
    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(saveChatSyncConfig).not.toHaveBeenCalled()
  })
  it('群列表读取不隐式保存应用或群选择', async () => {
    vi.mocked(fetchFeishuChats).mockResolvedValue([{ chatId: 'chat', name: '真实群' }])
    await render()
    await wrapper
      .findAll('button')
      .find((row) => row.text() === '读取应用所在的群')!
      .trigger('click')
    await flushPromises()
    expect(fetchFeishuChats).toHaveBeenCalledOnce()
    expect(saveChatSyncConfig).not.toHaveBeenCalled()
  })
  it('保存只提交当前接收组，保留旧群成员与其他快速收集配置', async () => {
    await render()
    await wrapper.findAll('input[type=password]')[0]!.setValue('new-app-secret')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(saveChatSyncConfig).toHaveBeenCalledWith(
      expect.objectContaining({ appId: 'app', appSecret: 'new-app-secret', senders: cfg.senders }),
    )
    expect(saveQuickCollectConfig).toHaveBeenCalledWith({
      enabled: true,
      projectId: 'p',
      verificationToken: undefined,
      encryptKey: undefined,
    })
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })
  it('局部保存失败显示已保存的组，未保存密钥保留重试', async () => {
    await render()
    await wrapper.findAll('input[type=password]')[1]!.setValue('new-event-secret')
    vi.mocked(saveQuickCollectConfig).mockRejectedValue(new Error('事件配置失败'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('应用与群已保存，收集配置保存失败')
    expect((wrapper.findAll('input[type=password]')[1]!.element as HTMLInputElement).value).toBe(
      'new-event-secret',
    )
    expect(wrapper.emitted('saved')).toBeUndefined()
  })
  it('外部共享配置更新时保留本机输入，拒绝覆盖', async () => {
    await render()
    await wrapper.get('input:not([type=password]):not([type=checkbox])').setValue('local-app')
    vi.mocked(fetchChatSyncConfig).mockResolvedValue({ ...cfg, chatId: 'other-chat' })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('共享接收配置已有更新')
    expect(saveChatSyncConfig).not.toHaveBeenCalled()
    expect(
      (wrapper.get('input:not([type=password]):not([type=checkbox])').element as HTMLInputElement)
        .value,
    ).toBe('local-app')
  })
  it('接收草稿不存应用与事件密钥', async () => {
    await render()
    await wrapper.get('input:not([type=password]):not([type=checkbox])').setValue('draft-app')
    await wrapper.findAll('input[type=password]')[0]!.setValue('secret-app')
    await wrapper.findAll('input[type=password]')[1]!.setValue('secret-event')
    await flushPromises()
    const stored = localStorage.getItem('wao-feishu-receiver-draft-v1:wg')
    expect(stored).toContain('draft-app')
    expect(stored).not.toContain('secret-app')
    expect(stored).not.toContain('secret-event')
  })
})
