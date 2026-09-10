import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PetMessenger from '@/components/chat/PetMessenger.vue'
import { useUiStore } from '@/stores/ui'
import { sendSharedChatMessage, type SharedChatMessage } from '@/api/chat'

vi.mock('@/api/chat', () => ({ sendSharedChatMessage: vi.fn() }))
const message = (id: string, name: string): SharedChatMessage => ({
  id,
  author: { id, name, initial: name, color: '#fff', role: '' },
  content: '来看看任务',
  source: 'WAO',
  deleted: false,
  read: false,
  readCount: 0,
  feishuReadCount: 0,
  reactions: [],
  feishuStatus: 'LOCAL',
  createdAt: '2026-09-10T12:00:00',
})

describe('宠物传话', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.resetAllMocks()
  })
  it('气泡显示真实发送者，写回复时新消息不会改变引用目标', async () => {
    const ui = useUiStore()
    ui.chatUnreadCount = 1
    ui.chatLatestUnread = message('a', '小王')
    const wrapper = mount(PetMessenger)
    expect(wrapper.text()).toContain('小王')
    await wrapper.get('.message-peek').trigger('click')
    await wrapper.get('textarea').setValue('收到，我来处理')
    ui.chatLatestUnread = message('b', '小李')
    ui.chatUnreadCount = 2
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(sendSharedChatMessage).toHaveBeenCalledWith(
      expect.objectContaining({ content: '收到，我来处理', replyToId: 'a' }),
    )
    expect(ui.chatRefreshVersion).toBe(1)
    wrapper.unmount()
  })
  it('失败保留草稿，重试不重复发送，快速回复不冒充全部已读', async () => {
    const ui = useUiStore()
    ui.chatUnreadCount = 2
    ui.chatLatestUnread = message('a', '小王')
    vi.mocked(sendSharedChatMessage).mockRejectedValueOnce(new Error('离线'))
    const wrapper = mount(PetMessenger)
    await wrapper.get('.message-peek').trigger('click')
    await wrapper.get('textarea').setValue('保留这句')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('保留这句')
    const first = vi.mocked(sendSharedChatMessage).mock.calls[0]![0]
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(vi.mocked(sendSharedChatMessage).mock.calls[1]![0]).toEqual(first)
    expect(ui.chatUnreadCount).toBe(2)
    wrapper.unmount()
  })
})
