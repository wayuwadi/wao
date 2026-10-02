import { describe, expect, it, vi } from 'vitest'
import { mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia } from 'pinia'
import HomeView from '@/views/HomeView.vue'

vi.mock('@/components/home/HomeBreathingStage.vue', () => ({ default: { template: '<section aria-label="呼吸练习"><slot name="information" /></section>' } }))
vi.mock('@/components/home/HomeTianrenGlance.vue', () => ({ default: { template: '<span>今日一卦</span>' } }))
vi.mock('@/api/codexMemberships', () => ({ fetchCodexMemberships: vi.fn().mockResolvedValue([]) }))
vi.mock('@/components/calendar/WeatherPanel.vue', () => ({ default: { template: '<span>天气</span>' } }))
vi.mock('@/api/ai', () => ({ fetchAiRuns: vi.fn(), fetchAiRunners: vi.fn(), fetchAiWorkflows: vi.fn() }))

describe('首页 AI Run 下线', () => {
  it('保留天气、卦象和呼吸入口，不加载执行队列', async () => {
    const wrapper = mount(HomeView, { global: { plugins: [createPinia()], stubs: { RouterLink: RouterLinkStub } } })
    expect(wrapper.text()).toContain('今日一卦')
    expect(wrapper.text()).toContain('天气')
    expect(wrapper.findComponent(RouterLinkStub).props('to')).toBe('/breathe')
    expect(wrapper.find('[aria-label="呼吸练习"]').exists()).toBe(false)
    expect(wrapper.text()).not.toMatch(/AI Run|Runner|执行电脑|AI 工作流/)
    const api = await import('@/api/ai')
    expect(api.fetchAiRuns).not.toHaveBeenCalled()
    expect(api.fetchAiRunners).not.toHaveBeenCalled()
    expect(api.fetchAiWorkflows).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
