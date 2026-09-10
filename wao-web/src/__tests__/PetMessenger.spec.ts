import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

describe('宠物消息提示', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())
  it('隐藏消息详情，点击打开聊天且不发送任何内容', async () => {
    const ui = useUiStore()
    ui.chatUnreadCount = 1
    ui.chatLatestUnread = message('a', '小王')
    const wrapper = mount(PetMessenger)
    expect(wrapper.text()).toContain('有新消息')
    expect(wrapper.text()).not.toContain('小王')
    expect(wrapper.text()).not.toContain('来看看任务')
    expect(wrapper.find('textarea').exists()).toBe(false)
    await wrapper.get('button').trigger('click')
    expect(ui.chatOpen).toBe(true)
    expect(sendSharedChatMessage).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('八秒收起，同一消息重挂载不再提示，新消息可再提示一次', async () => {
    const ui = useUiStore()
    ui.chatUnreadCount = 1
    ui.chatLatestUnread = message('a', '小王')
    const wrapper = mount(PetMessenger)
    await vi.advanceTimersByTimeAsync(8000)
    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
    const reopened = mount(PetMessenger)
    expect(reopened.find('button').exists()).toBe(false)
    ui.chatLatestUnread = message('b', '小李')
    await flushPromises()
    expect(reopened.find('button').exists()).toBe(true)
    reopened.unmount()
  })
})
