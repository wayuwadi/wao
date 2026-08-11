import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AiAssistantPanel from '@/components/assistant/AiAssistantPanel.vue'
import { fetchAssistantStatus } from '@/api/assistant'

vi.mock('@/api/assistant', () => ({
  fetchAssistantStatus: vi.fn(),
  streamChat: vi.fn(),
}))

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
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div />' } }],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AiAssistantPanel, {
      props: { modelValue: false },
      global: { plugins: [createPinia(), router] },
      attachTo: document.body,
    })

    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    expect(document.body.textContent).toContain('无法读取 AI 状态')

    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(fetchAssistantStatus).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).not.toContain('无法读取 AI 状态')
    expect(document.querySelector<HTMLTextAreaElement>('.assistant-input textarea')?.disabled).toBe(false)

    wrapper.unmount()
  })
})
