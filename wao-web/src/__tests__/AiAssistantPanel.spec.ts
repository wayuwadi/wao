import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AiAssistantPanel from '@/components/assistant/AiAssistantPanel.vue'
import { useUiStore } from '@/stores/ui'
import {
  createAssistantConversation,
  fetchAssistantConversation,
  fetchAiCapabilities,
  fetchAssistantConversations,
  fetchAssistantSourceOptions,
  fetchAssistantStatus,
  streamChat,
} from '@/api/assistant'

vi.mock('@/api/assistant', () => ({
  fetchAssistantStatus: vi.fn<(typeof import('@/api/assistant'))['fetchAssistantStatus']>(),
  fetchAssistantConversations: vi.fn<(typeof import('@/api/assistant'))['fetchAssistantConversations']>(),
  fetchAssistantConversation: vi.fn<(typeof import('@/api/assistant'))['fetchAssistantConversation']>(),
  fetchAssistantSourceOptions: vi.fn<(typeof import('@/api/assistant'))['fetchAssistantSourceOptions']>(),
  fetchAiCapabilities: vi.fn<(typeof import('@/api/assistant'))['fetchAiCapabilities']>(),
  createAssistantConversation: vi.fn<(typeof import('@/api/assistant'))['createAssistantConversation']>(),
  deleteAssistantConversation: vi.fn<(typeof import('@/api/assistant'))['deleteAssistantConversation']>(),
  streamChat: vi.fn<(typeof import('@/api/assistant'))['streamChat']>(),
}))

async function router() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  await router.push('/')
  await router.isReady()
  return router
}

describe('AiAssistantPanel', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchAiCapabilities).mockResolvedValue([{
      id: 'local-codex', label: '本地 Codex', provider: 'codex', model: null,
      executionMode: 'RUNNER', available: true, message: '安全只读 Runner 在线',
    }])
    vi.mocked(fetchAssistantSourceOptions).mockResolvedValue({
      chats: [{ id: 'oc_chat', name: 'WAO 项目群' }],
      senders: ['娃哥', '娃弟'],
      projects: [{ id: 'p1', name: 'WAO' }],
      earliestChatDate: '2026-06-01',
      latestChatDate: '2026-08-11',
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  it('关闭状态不预取来源选项，首次打开时再读取真实数据', async () => {
    vi.mocked(fetchAssistantStatus).mockResolvedValue({ configured: true, message: '已连接本地 Codex' })
    vi.mocked(fetchAssistantConversations).mockResolvedValue([])
    const testRouter = await router()
    const wrapper = mount(AiAssistantPanel, {
      props: { modelValue: false },
      global: { plugins: [createPinia(), testRouter] },
      attachTo: document.body,
    })

    await flushPromises()
    expect(fetchAssistantSourceOptions).not.toHaveBeenCalled()

    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    expect(fetchAssistantSourceOptions).toHaveBeenCalledOnce()
    expect(document.body.textContent).toContain('全部 WAO 资产')
    wrapper.unmount()
  })

  it('面板打开时会在短暂断开后自动重新连接本地 Codex', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchAssistantStatus)
      .mockRejectedValueOnce(new Error('temporary 502'))
      .mockResolvedValue({ configured: true, message: '已连接本地 Codex' })
    vi.mocked(fetchAssistantConversations).mockResolvedValue([])
    const testRouter = await router()
    const wrapper = mount(AiAssistantPanel, {
      props: { modelValue: true },
      global: { plugins: [createPinia(), testRouter] },
      attachTo: document.body,
    })

    await flushPromises()
    expect(document.body.textContent).toContain('无法读取 AI 状态')

    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(fetchAssistantStatus).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).not.toContain('无法读取 AI 状态')
    expect(document.querySelector<HTMLTextAreaElement>('.assistant-input textarea')?.disabled).toBe(false)

    wrapper.unmount()
  })

  it('打开工作台时展示当前成员的历史会话和消息', async () => {
    vi.mocked(fetchAssistantStatus).mockResolvedValue({ configured: true, message: '已连接 Linux 主节点' })
    vi.mocked(fetchAssistantConversations).mockResolvedValue([{
      id: 'cv1', title: '项目复盘', preview: '这是复盘建议', updatedAt: '2026-08-11T10:00:00',
    }])
    vi.mocked(fetchAssistantConversation).mockResolvedValue({
      conversation: { id: 'cv1', title: '项目复盘', preview: '这是复盘建议', updatedAt: '2026-08-11T10:00:00' },
      messages: [
        { id: 'm1', role: 'user', content: '帮我复盘项目', createdAt: '2026-08-11T09:59:00' },
        { id: 'm2', role: 'assistant', content: '这是复盘建议', createdAt: '2026-08-11T10:00:00' },
      ],
    })
    const testRouter = await router()
    const wrapper = mount(AiAssistantPanel, {
      props: { modelValue: true },
      global: { plugins: [createPinia(), testRouter] },
      attachTo: document.body,
    })

    await flushPromises()

    expect(document.body.textContent).toContain('我的问答知识库')
    expect(document.body.textContent).toContain('按需联网')
    expect(document.body.textContent).toContain('项目复盘')
    expect(document.body.textContent).toContain('帮我复盘项目')
    expect(document.body.textContent).toContain('这是复盘建议')
    wrapper.unmount()
  })

  it('页面带入的问题只成为可编辑的新会话草稿，不自动发送', async () => {
    vi.mocked(fetchAssistantStatus).mockResolvedValue({ configured: true, message: '已连接本地 Codex' })
    vi.mocked(fetchAssistantConversations).mockResolvedValue([{
      id: 'cv1', title: '旧会话', preview: '旧内容', updatedAt: '2026-08-11T10:00:00',
    }])
    const testRouter = await router()
    const pinia = createPinia()
    const ui = useUiStore(pinia)
    ui.openAssistant('请结合今天的成长启发追问我')
    const wrapper = mount(AiAssistantPanel, {
      props: { modelValue: true },
      global: { plugins: [pinia, testRouter] },
      attachTo: document.body,
    })

    await flushPromises()

    expect(document.querySelector<HTMLTextAreaElement>('.assistant-input textarea')?.value)
      .toBe('请结合今天的成长启发追问我')
    expect(document.querySelector('.chat-heading b')?.textContent).toBe('新对话')
    expect(ui.assistantDraft).toBe('')
    expect(fetchAssistantConversation).not.toHaveBeenCalled()
    expect(createAssistantConversation).not.toHaveBeenCalled()
    expect(streamChat).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('发送时携带群聊、发言人和日期来源筛选', async () => {
    vi.mocked(fetchAssistantStatus).mockResolvedValue({ configured: true, message: '已连接本地 Codex' })
    vi.mocked(fetchAssistantConversations).mockResolvedValue([])
    vi.mocked(createAssistantConversation).mockResolvedValue({
      id: 'cv1', title: '新对话', updatedAt: '2026-08-11T10:00:00',
    })
    vi.mocked(streamChat).mockImplementation(async function* () { yield '找到了' })
    const testRouter = await router()
    const wrapper = mount(AiAssistantPanel, {
      props: { modelValue: true },
      global: { plugins: [createPinia(), testRouter] },
      attachTo: document.body,
    })
    await flushPromises()

    document.querySelector<HTMLButtonElement>('.source-summary')!.click()
    await flushPromises()
    const chips = Array.from(document.querySelectorAll<HTMLButtonElement>('.filter-chips button'))
    chips.find((item) => item.textContent === 'WAO 项目群')!.click()
    chips.find((item) => item.textContent === '娃哥')!.click()
    const dates = Array.from(document.querySelectorAll<HTMLInputElement>('.date-filter input'))
    dates[0]!.value = '2026-07-01'
    dates[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    dates[1]!.value = '2026-07-31'
    dates[1]!.dispatchEvent(new Event('input', { bubbles: true }))
    const textarea = document.querySelector<HTMLTextAreaElement>('.assistant-input textarea')!
    textarea.value = '之前预算怎么说的？'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    document.querySelector<HTMLButtonElement>('.send-btn')!.click()
    await flushPromises()

    expect(streamChat).toHaveBeenCalledWith(
      '之前预算怎么说的？', [], undefined, 'cv1', expect.objectContaining({
        assetTypes: ['chat', 'knowledge', 'project', 'task'],
        chatIds: ['oc_chat'],
        senders: ['娃哥'],
        dateFrom: '2026-07-01',
        dateTo: '2026-07-31',
      }),
      '',
    )
    expect(document.body.textContent).toContain('找到了')
    wrapper.unmount()
  })
  async function openPanel() {
    vi.mocked(fetchAssistantStatus).mockResolvedValue({ configured: true, message: '已连接本地 Codex' })
    vi.mocked(fetchAssistantConversations).mockResolvedValue([
      { id: 'cv1', title: '会话一', updatedAt: '2026-09-08T10:00:00' },
      { id: 'cv2', title: '会话二', updatedAt: '2026-09-08T10:00:00' },
    ])
    vi.mocked(fetchAssistantConversation).mockImplementation(async id => ({
      conversation: { id, title: id, updatedAt: '' },
      messages: [{ id: 'm-' + id, role: 'assistant', content: '内容-' + id, createdAt: '' }],
    }))
    const wrapper = mount(AiAssistantPanel, {
      props: { modelValue: true }, global: { plugins: [createPinia(), await router()] }, attachTo: document.body,
    })
    await flushPromises()
    return wrapper
  }

  function typeDraft(text: string) {
    const textarea = document.querySelector<HTMLTextAreaElement>('textarea')!
    textarea.value = text
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    return textarea
  }

  it('会话草稿独立，刷新后恢复当前历史与未发送输入', async () => {
    let wrapper = await openPanel()
    typeDraft('会话一的草稿')
    document.querySelectorAll<HTMLButtonElement>('.history-item')[1]!.click()
    await flushPromises()
    expect(document.querySelector('textarea')!.value).toBe('')
    typeDraft('会话二的草稿')
    wrapper.unmount()
    wrapper = await openPanel()
    expect(document.querySelector('textarea')!.value).toBe('会话二的草稿')
    document.querySelectorAll<HTMLButtonElement>('.history-item')[0]!.click()
    await flushPromises()
    expect(document.querySelector('textarea')!.value).toBe('会话一的草稿')
    wrapper.unmount()
  })

  it('迟到历史不能覆盖新会话或它的草稿', async () => {
    const wrapper = await openPanel()
    let resolve!: (value: Awaited<ReturnType<typeof fetchAssistantConversation>>) => void
    vi.mocked(fetchAssistantConversation).mockReturnValueOnce(new Promise(r => { resolve = r }))
    document.querySelectorAll<HTMLButtonElement>('.history-item')[1]!.click()
    await flushPromises()
    document.querySelector<HTMLButtonElement>('.new-chat-btn')!.click()
    await flushPromises()
    typeDraft('新的问题')
    resolve({ conversation: { id: 'cv2', title: '旧会话', updatedAt: '' }, messages: [{ id: 'late', role: 'assistant', content: '迟到内容', createdAt: '' }] })
    await flushPromises()
    expect(document.querySelector('.assistant-list')!.textContent).not.toContain('迟到内容')
    expect(document.querySelector('textarea')!.value).toBe('新的问题')
    wrapper.unmount()
  })

  it('请求失败保留草稿并明确重试，中文输入法确认不发送', async () => {
    const wrapper = await openPanel()
    vi.mocked(streamChat).mockImplementation(async function* () { throw new Error('网络中断'); yield '' })
    const textarea = typeDraft('继续提问')
    textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true }))
    await flushPromises()
    expect(streamChat).not.toHaveBeenCalled()
    document.querySelector<HTMLButtonElement>('.send-btn')!.click()
    await flushPromises()
    expect(document.querySelector('textarea')!.value).toBe('继续提问')
    expect(document.querySelector('[role="alert"]')!.textContent).toContain('网络中断')
    expect(document.querySelector('.message-column')!.textContent).not.toContain('网络中断')
    vi.mocked(streamChat).mockImplementation(async function* () { yield '重试后的真实返回' })
    document.querySelector<HTMLButtonElement>('[role="alert"] button')!.click()
    await flushPromises()
    expect(document.querySelector('textarea')!.value).toBe('')
    expect(document.body.textContent).toContain('对话已保存到 WAO')
    wrapper.unmount()
  })

  it('历史读取失败可原地重试，离线也能编辑草稿', async () => {
    const wrapper = await openPanel()
    vi.mocked(fetchAssistantConversation).mockRejectedValueOnce(new Error('读取失败'))
    document.querySelectorAll<HTMLButtonElement>('.history-item')[1]!.click()
    await flushPromises()
    expect(document.querySelector('.send-btn')!.hasAttribute('disabled')).toBe(true)
    document.querySelector<HTMLButtonElement>('.inline-error button')!.click()
    await flushPromises()
    expect(document.body.textContent).toContain('内容-cv2')
    wrapper.unmount()
  })

  it('常驻页和浮层切换时保留同一会话与草稿', async () => {
    const wrapper = await openPanel()
    typeDraft('切换页签的草稿')
    await wrapper.setProps({ modelValue: false, embedded: true })
    await flushPromises()
    expect(wrapper.find('.embedded textarea').element).toHaveProperty('value', '切换页签的草稿')
    await wrapper.setProps({ embedded: false })
    await wrapper.setProps({ embedded: true })
    await flushPromises()
    expect(wrapper.find('.embedded textarea').element).toHaveProperty('value', '切换页签的草稿')
    expect(wrapper.find('.embedded').text()).toContain('内容-cv1')
    wrapper.unmount()
  })

})
