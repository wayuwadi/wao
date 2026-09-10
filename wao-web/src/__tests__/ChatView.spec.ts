import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useUiStore } from '@/stores/ui'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'
import type { Editor } from '@tiptap/core'
import ChatView from '@/views/ChatView.vue'
import {
  fetchSharedChatMessages,
  markSharedChatRead,
  sendSharedChatMessage,
  toggleSharedChatReaction,
  uploadSharedChatImage,
  uploadSharedChatFile,
  type SharedChatMessage,
} from '@/api/chat'

vi.mock('@/api/chat', () => ({
  fetchSharedChatMessages: vi.fn<(typeof import('@/api/chat'))['fetchSharedChatMessages']>(),
  markSharedChatRead: vi.fn<(typeof import('@/api/chat'))['markSharedChatRead']>(),
  sendSharedChatMessage: vi.fn<(typeof import('@/api/chat'))['sendSharedChatMessage']>(),
  toggleSharedChatReaction: vi.fn<(typeof import('@/api/chat'))['toggleSharedChatReaction']>(),
  uploadSharedChatFile: vi.fn<(typeof import('@/api/chat'))['uploadSharedChatFile']>(),
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
  feishuStatus: 'LOCAL',
  createdAt: '2026-08-26T10:00:00+08:00',
}

let wrappers: VueWrapper[] = []

async function mountView(props: Record<string, unknown> = {}, realEditor = false) {
  const wrapper = mount(ChatView, {
    props,
    attachTo: document.body,
    global: {
      stubs: {
        MarkdownEditor: realEditor ? false : {
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
    vi.mocked(markSharedChatRead).mockResolvedValue(undefined)
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

  it('点击聊天中的子任务链接直接打开对应任务与子任务', async () => {
    vi.mocked(fetchSharedChatMessages).mockResolvedValue([{ ...message, content: '[查看子任务](/s/t/t123/s456)' }])
    const wrapper = await mountView({ floating: true })
    const ui = useUiStore()
    ui.openChat()
    await wrapper.get('.message-stream a').trigger('click', { button: 0 })
    expect(ui.openTaskId).toBe('t123')
    expect(ui.openTaskSubId).toBe('s456')
    expect(ui.chatOpen).toBe(false)
  })

  it('图片右键菜单复制 PNG 到剪贴板', async () => {
    vi.mocked(fetchSharedChatMessages).mockResolvedValue([{ ...message, content: '![测试](/api/images/i1/raw)' }])
    const write = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { write } })
    vi.stubGlobal('ClipboardItem', class { constructor(public data: unknown) {} })
    const draw = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D)
    const blob = vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback => callback(new Blob(['png'], { type: 'image/png' })))
    const wrapper = await mountView()
    const img = wrapper.get('img')
    Object.defineProperties(img.element, { naturalWidth: { value: 10 }, naturalHeight: { value: 10 } })
    await img.trigger('contextmenu', { clientX: 100, clientY: 100 })
    const menu = document.querySelector<HTMLButtonElement>('.chat-image-menu')!
    expect(menu.textContent).toContain('复制图片')
    menu.click()
    await flushPromises()
    expect(write).toHaveBeenCalledOnce()
    draw.mockRestore()
    blob.mockRestore()
  })

  it('读取 WAO 消息并展示本地发送状态', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('从 WAO 发来的消息')
    expect(wrapper.text()).toContain('已发送')
    expect(fetchSharedChatMessages).toHaveBeenCalledWith()
  })

  it('最小化时保留未读，重新打开后才写入真实已读状态', async () => {
    const wrapper = await mountView({ floating: true, visible: false })

    expect(wrapper.text()).toContain('1 条未读')
    expect(markSharedChatRead).not.toHaveBeenCalled()

    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(markSharedChatRead).toHaveBeenCalledWith('msg1')
    expect(wrapper.text()).not.toContain('1 条未读')
  })

  it('最小化后每十秒读取，重新打开时立即同步', async () => {
    vi.useFakeTimers()
    const wrapper = await mountView({ floating: true, visible: false })
    const initialCalls = vi.mocked(fetchSharedChatMessages).mock.calls.length

    await vi.advanceTimersByTimeAsync(5_000)
    expect(fetchSharedChatMessages).toHaveBeenCalledTimes(initialCalls)

    await vi.advanceTimersByTimeAsync(5_000)
    expect(fetchSharedChatMessages).toHaveBeenCalledTimes(initialCalls + 1)

    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(fetchSharedChatMessages).toHaveBeenCalledTimes(initialCalls + 2)
  })

  it('悬浮模式提供最小化按钮', async () => {
    const wrapper = await mountView({ floating: true })

    await wrapper.get('.minimize-trigger').trigger('click')

    expect(wrapper.emitted('minimize')).toHaveLength(1)
  })

  it('把聊天正文按净化后的 Markdown 展示', async () => {
    vi.mocked(fetchSharedChatMessages).mockResolvedValue([
      { ...message, content: '**重点** <script>alert(1)</script>' },
    ])
    const wrapper = await mountView()

    expect(wrapper.get('.message-text strong').text()).toBe('重点')
    expect(wrapper.find('.message-text script').exists()).toBe(false)
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

  it('真实富文本编辑器 Enter 成功后立即清空且继续输入不带回旧消息', async () => {
    const wrapper = await mountView({}, true)
    await new Promise((resolve) => setTimeout(resolve, 0))
    const rich = wrapper.getComponent(MarkdownEditor)
    const editor = (rich.vm as unknown as { editor: Editor }).editor
    editor.commands.setContent('本次消息', { contentType: 'markdown' })
    editor.view.focus()
    await flushPromises()
    await rich.get('[contenteditable]').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(sendSharedChatMessage).toHaveBeenCalledWith(expect.objectContaining({ content: '本次消息' }))
    expect(editor.getMarkdown()).toBe('')
    expect(editor.isFocused).toBe(true)
    editor.commands.insertContent('下一条')
    await flushPromises()
    expect(editor.getMarkdown()).toBe('下一条')
  })

  it('请求完成前继续编辑时保留新草稿', async () => {
    let complete!: (value: SharedChatMessage) => void
    vi.mocked(sendSharedChatMessage).mockReturnValue(new Promise((resolve) => { complete = resolve }))
    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('正在发送')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('textarea').setValue('下一条新草稿')
    complete(message)
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('下一条新草稿')
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


  it('Enter 发送，Shift+Enter 和中文输入法确认不发送', async () => {
    const wrapper = await mountView()
    const editor = wrapper.get('textarea')
    await editor.setValue('测试快捷发送')
    await editor.trigger('keydown', { key: 'Enter', shiftKey: true })
    await editor.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(sendSharedChatMessage).not.toHaveBeenCalled()
    await editor.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(sendSharedChatMessage).toHaveBeenCalledTimes(1)
  })

  it('任务分享追加到原草稿且不关闭任务', async () => {
    const wrapper = await mountView({ floating: true, visible: false })
    await wrapper.get('textarea').setValue('原来的想法')
    const ui = useUiStore()
    ui.openTask('task1')
    ui.shareToChat('[任务](/s/t/task1)')
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('原来的想法\n\n[任务](/s/t/task1)')
    expect(ui.openTaskId).toBe('task1')
    expect(ui.chatOpen).toBe(true)
    expect(sendSharedChatMessage).not.toHaveBeenCalled()
  })

  it('发送失败保留草稿并以相同幂等标识重试', async () => {
    vi.mocked(sendSharedChatMessage).mockRejectedValueOnce(new Error('网络断开'))
    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('不能丢的消息')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('不能丢的消息')
    const token = vi.mocked(sendSharedChatMessage).mock.calls[0]![0].clientToken
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(vi.mocked(sendSharedChatMessage).mock.calls[1]![0].clientToken).toBe(token)
  })

  it('真实文件落库后才追加下载链接，上传期间新文字不丢失，失败可重选', async () => {
    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('原文')
    const input = wrapper.get<HTMLInputElement>('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['内容'], '验收.txt')] })
    let resolve!: (value: Awaited<ReturnType<typeof uploadSharedChatFile>>) => void
    vi.mocked(uploadSharedChatFile).mockReturnValueOnce(new Promise(r => { resolve = r }))
    await input.trigger('change')
    await wrapper.get('textarea').setValue('原文与新增内容')
    expect(sendSharedChatMessage).not.toHaveBeenCalled()
    resolve({ id: 'file1', name: '验收.txt', size: '6 B', icon: '', time: '', by: '', link: '' })
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toContain('原文与新增内容')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toContain('/api/files/file1/download')
    vi.mocked(uploadSharedChatFile).mockRejectedValueOnce(new Error('上传失败'))
    await input.trigger('change')
    await flushPromises()
    expect(wrapper.text()).toContain('上传失败')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toContain('/api/files/file1/download')
  })

})
