import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatView from '@/views/ChatView.vue'
import {
  fetchSharedChatConfig,
  fetchSharedChatFeishuGroups,
  fetchSharedChatMessages,
  markSharedChatRead,
  retrySharedChatFeishu,
  saveSharedChatConfig,
  sendSharedChatMessage,
  toggleSharedChatReaction,
  uploadSharedChatImage,
  type SharedChatMessage,
} from '@/api/chat'

vi.mock('@/api/chat', () => ({
  fetchSharedChatConfig: vi.fn(),
  fetchSharedChatFeishuGroups: vi.fn(),
  fetchSharedChatMessages: vi.fn<(typeof import('@/api/chat'))['fetchSharedChatMessages']>(),
  markSharedChatRead: vi.fn(),
  retrySharedChatFeishu: vi.fn<(typeof import('@/api/chat'))['retrySharedChatFeishu']>(),
  saveSharedChatConfig: vi.fn(),
  sendSharedChatMessage: vi.fn<(typeof import('@/api/chat'))['sendSharedChatMessage']>(),
  toggleSharedChatReaction: vi.fn(),
  uploadSharedChatImage: vi.fn<(typeof import('@/api/chat'))['uploadSharedChatImage']>(),
}))

const message: SharedChatMessage = {
  id: 'msg1',
  author: { id: 'm1', name: '娃哥', initial: '娃', color: '#6655BE', role: '管理员' },
  content: '从 WAO 发来的消息',
  source: 'WAO',
  deleted: false,
  read: false,
  readCount: 1,
  feishuReadCount: 0,
  reactions: [],
  feishuStatus: 'SENT',
  createdAt: '2026-08-26T10:00:00+08:00',
}

let wrappers: VueWrapper[] = []

async function mountView() {
  const wrapper = mount(ChatView, {
    global: {
      stubs: {
        MarkdownEditor: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<textarea class="markdown-editor-stub" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)"></textarea>',
        },
      },
    },
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
    vi.mocked(fetchSharedChatConfig).mockResolvedValue({
      chatId: '',
      chatName: '',
      configured: false,
    })
    vi.mocked(fetchSharedChatFeishuGroups).mockResolvedValue([])
    vi.mocked(markSharedChatRead).mockResolvedValue(undefined)
    vi.mocked(saveSharedChatConfig).mockResolvedValue({
      chatId: '',
      chatName: '',
      configured: false,
    })
    vi.mocked(toggleSharedChatReaction).mockResolvedValue(message)
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
    expect(fetchSharedChatMessages).toHaveBeenCalledWith()
  })

  it('把聊天正文按净化后的 Markdown 展示', async () => {
    vi.mocked(fetchSharedChatMessages).mockResolvedValue([
      { ...message, content: '**重点** <script>alert(1)</script>' },
    ])
    const wrapper = await mountView()

    expect(wrapper.get('.message-text strong').text()).toBe('重点')
    expect(wrapper.find('.message-text script').exists()).toBe(false)
  })

  it('只从飞书应用真实群列表选择并保存双向目标', async () => {
    vi.mocked(fetchSharedChatFeishuGroups).mockResolvedValue([
      { chatId: 'oc_real', name: '娃屋日常' },
    ])
    vi.mocked(saveSharedChatConfig).mockResolvedValue({
      chatId: 'oc_real',
      chatName: '娃屋日常',
      configured: true,
    })
    const wrapper = await mountView()

    await wrapper.get('.group-trigger').trigger('click')
    await flushPromises()
    await wrapper.findAll('.group-menu > button')[1]!.trigger('click')
    await flushPromises()

    expect(saveSharedChatConfig).toHaveBeenCalledWith('oc_real')
    expect(wrapper.get('.group-trigger').text()).toContain('娃屋日常')
  })

  it('发送时保留引用关系并可添加真实表情回复', async () => {
    const wrapper = await mountView()
    await wrapper.get('.message-actions > button').trigger('click')
    await wrapper.get('textarea').setValue('引用回复')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(sendSharedChatMessage).toHaveBeenCalledWith(
      expect.objectContaining({ content: '引用回复', replyToId: 'msg1' }),
    )

    await wrapper.get('.reaction-picker-wrap > button').trigger('click')
    await wrapper.get('.reaction-picker button').trigger('click')
    await flushPromises()
    expect(toggleSharedChatReaction).toHaveBeenCalledWith('msg1', 'THUMBSUP')
  })

  it('从本机恢复草稿且消息读取不覆盖输入', async () => {
    localStorage.setItem(
      'wao-shared-chat-draft-v2:unknown',
      JSON.stringify({ content: '还没发完', replyToId: '' }),
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
    expect(localStorage.getItem('wao-shared-chat-draft-v2:unknown')).toBeNull()
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

    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toContain('待发送截图.png')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(uploadSharedChatImage).not.toHaveBeenCalled()
    expect(sendSharedChatMessage).toHaveBeenCalledWith(
      expect.objectContaining({ content: expect.stringContaining('/api/images/img-draft/raw') }),
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
