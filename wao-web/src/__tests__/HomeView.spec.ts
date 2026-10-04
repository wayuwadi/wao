import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import HomeView from '@/views/HomeView.vue'
import { restJourneyRequested } from '@/composables/restJourney'

vi.mock('@/components/home/HomeBreathingStage.vue', () => ({ default: { template: '<section aria-label="呼吸练习"><slot name="information" /></section>' } }))
vi.mock('@/components/home/HomeTianrenGlance.vue', () => ({ default: { template: '<span>今日一卦</span>' } }))
vi.mock('@/api/codexMemberships', () => ({ fetchCodexMemberships: vi.fn().mockResolvedValue([]) }))
vi.mock('@/components/calendar/WeatherPanel.vue', () => ({ default: { template: '<span>天气</span>' } }))
vi.mock('@/api/ai', () => ({ fetchAiRuns: vi.fn(), fetchAiRunners: vi.fn(), fetchAiWorkflows: vi.fn() }))

describe('首页 AI Run 下线', () => {
  it('保留天气、卦象和呼吸入口，不加载执行队列', async () => {
    const wrapper = mount(HomeView, { global: { plugins: [createPinia()] } })
    expect(wrapper.text()).toContain('今日一卦')
    expect(wrapper.text()).toContain('天气')
    expect(wrapper.text()).not.toMatch(/和伙伴玩|记个念头|新灵感|递一段文字/)
    expect(wrapper.text()).toContain('按住留白或右键')
    expect(wrapper.findComponent({ name: 'ContextActionMenu' }).exists()).toBe(false)
    expect(wrapper.get('.rest-entrance').text()).toContain('休息一下')
    expect(wrapper.text()).not.toContain('功能探索')
    restJourneyRequested.value = false
    await wrapper.get('.rest-entrance').trigger('click')
    expect(restJourneyRequested.value).toBe(true)
    expect(wrapper.get('.rest-entrance').attributes('disabled')).toBeDefined()
    restJourneyRequested.value = false
    expect(wrapper.find('[aria-label="呼吸练习"]').exists()).toBe(false)
    expect(wrapper.text()).not.toMatch(/AI Run|Runner|执行电脑|AI 工作流/)
    const api = await import('@/api/ai')
    expect(api.fetchAiRuns).not.toHaveBeenCalled()
    expect(api.fetchAiRunners).not.toHaveBeenCalled()
    expect(api.fetchAiWorkflows).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('旧执行队列返回数据也不会恢复目标、策略或运行详情入口', async () => {
    const api = await import('@/api/ai')
    vi.mocked(api.fetchAiRuns).mockResolvedValue([{ id: 'retired-run', status: 'running', goalTitle: '旧目标' }] as never)
    const wrapper = mount(HomeView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    expect(wrapper.find('.goal-launcher, .auto-run-policy, .run-detail-meta, .run-activity-detail').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('旧目标')
    expect(api.fetchAiRuns).not.toHaveBeenCalled()
    expect(wrapper.find('.rest-entrance').exists()).toBe(true)
    wrapper.unmount()
  })
})
