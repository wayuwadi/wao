import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatView from '@/views/ChatView.vue'
import {
  fetchSharedChatMessages,
  retrySharedChatFeishu,
  sendSharedChatMessage,
  uploadSharedChatImage,
  type SharedChatMessage,
} from '@/api/chat'

vi.mock('@/api/chat', () => ({
  fetchSharedChatMessages: vi.fn<(typeof import('@/api/chat'))['fetchSharedChatMessages']>(),
  retrySharedChatFeishu: vi.fn<(typeof import('@/api/chat'))['retrySharedChatFeishu']>(),
  sendSharedChatMessage: vi.fn<(typeof import('@/api/chat'))['sendSharedChatMessage']>(),
  uploadSharedChatImage: vi.fn<(typeof import('@/api/chat'))['uploadSharedChatImage']>(),
}))

const message: SharedChatMessage = {
  id: 'msg1',
  author: { id: 'm1', name: '娃哥', initial: '娃', color: '#6655BE', role: '管理员' },
  content: '从 WAO 发来的消息',
  feishuStatus: 'SENT',
  createdAt: '2026-08-26T10:00:00+08:00',
}

let wrappers: VueWrapper[] = []

async function mountView() {
  const wrapper = mount(ChatView, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('ChatView', () => {
  beforeEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchSharedChatMessages).mockResolvedValue([message])
    vi.mocked(sendSharedChatMessage).mockResolvedValue(message)
    vi.mocked(uploadSharedChatImage).mockResolvedValue({
      id: 'img1',
      name: '截图.png',
      size: '1 KB',
      time: '',
      from: 'WAO 聊天',
      url: '/api/images/img1/raw',
      c1: '',
      c2: '',
    })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn<(value: Blob | MediaSource) => string>(() => 'blob:preview'),
      revokeObjectURL: vi.fn<(url: string) => void>(),
    })
  })

  afterEach(() => {
    wrappers.forEach((wrapper) => wrapper.unmount())
    wrappers = []
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('读取真实消息并展示飞书同步结果', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('从 WAO 发来的消息')
    expect(wrapper.text()).toContain('已同步飞书')
    expect(fetchSharedChatMessages).toHaveBeenCalledWith(undefined)
  })

  it('从本机恢复草稿且消息读取不覆盖输入', async () => {
    localStorage.setItem(
      'wao-shared-chat-draft-v1:unknown',
      JSON.stringify({ content: '还没发完', imageId: '', imageName: '', imageUrl: '' }),
    )
    const wrapper = await mountView()

    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('还没发完')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('还没发完')
  })

  it('发送文字并在请求完成后清除草稿', async () => {
    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('从电脑发消息')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(sendSharedChatMessage).toHaveBeenCalledWith(
      expect.objectContaining({ content: '从电脑发消息' }),
    )
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('')
    expect(localStorage.getItem('wao-shared-chat-draft-v1:unknown')).toBeNull()
  })

  it('恢复已经上传的截图草稿并直接发送，不重复上传', async () => {
    localStorage.setItem(
      'wao-shared-chat-draft-v1:unknown',
      JSON.stringify({
        content: '',
        imageId: 'img-draft',
        imageName: '待发送截图.png',
        imageUrl: '/api/images/img-draft/raw',
      }),
    )
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('待发送截图.png')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(uploadSharedChatImage).not.toHaveBeenCalled()
    expect(sendSharedChatMessage).toHaveBeenCalledWith(
      expect.objectContaining({ imageId: 'img-draft' }),
    )
  })

  it('飞书失败时保留消息并允许重试', async () => {
    const failed = {
      ...message,
      feishuStatus: 'FAILED' as const,
      feishuError: '同步失败，请稍后重试',
    }
    vi.mocked(fetchSharedChatMessages).mockResolvedValue([failed])
    vi.mocked(retrySharedChatFeishu).mockResolvedValue(message)
    const wrapper = await mountView()

    await wrapper.get('.delivery button').trigger('click')
    await flushPromises()

    expect(retrySharedChatFeishu).toHaveBeenCalledWith('msg1')
    expect(wrapper.text()).toContain('已同步飞书')
  })
})
