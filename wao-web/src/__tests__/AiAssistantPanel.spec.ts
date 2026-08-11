import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AiAssistantPanel from '@/components/assistant/AiAssistantPanel.vue'
import { fetchAssistantConversation, fetchAssistantConversations, fetchAssistantStatus } from '@/api/assistant'

vi.mock('@/api/assistant', () => ({
  fetchAssistantStatus: vi.fn(),
  fetchAssistantConversations: vi.fn(),
  fetchAssistantConversation: vi.fn(),
  createAssistantConversation: vi.fn(),
  deleteAssistantConversation: vi.fn(),
  streamChat: vi.fn(),
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
  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    document.body.innerHTML = ''
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
    expect(document.body.textContent).toContain('项目复盘')
    expect(document.body.textContent).toContain('帮我复盘项目')
    expect(document.body.textContent).toContain('这是复盘建议')
    wrapper.unmount()
  })
})
