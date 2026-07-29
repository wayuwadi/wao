import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiAgentHealth } from '@/api/agent'
import MediaAgentView from '@/views/MediaAgentView.vue'

vi.mock('@/api/agent', () => ({ apiAgentHealth: vi.fn() }))

const health = vi.mocked(apiAgentHealth)

describe('MediaAgentView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('探测成功后加载独立页面，并在 iframe 就绪后显示已连接', async () => {
    health.mockResolvedValue({ service: 'wao-agent-server', status: 'UP' })
    const wrapper = mount(MediaAgentView)
    await flushPromises()

    const frame = wrapper.get('iframe')
    expect(frame.attributes('src')).toBe('/agent/')
    expect(wrapper.text()).toContain('正在连接 Agent')

    await frame.trigger('load')
    expect(wrapper.text()).toContain('Agent 已连接')
  })

  it('服务不可用时提供失败说明、重试和新窗口入口', async () => {
    health.mockRejectedValue(new Error('连接超时'))
    const wrapper = mount(MediaAgentView)
    await flushPromises()

    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.text()).toContain('Agent 暂时无法连接')
    expect(wrapper.text()).toContain('连接超时')
    expect(wrapper.get('a').attributes('href')).toBe('/agent/')
    expect(wrapper.get('.state-card button').text()).toBe('重试')
  })
})
