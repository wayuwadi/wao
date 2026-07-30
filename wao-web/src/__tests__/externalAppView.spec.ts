import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { apiExternalAppHealth } from '@/api/agent'
import { DEFAULT_EXTERNAL_APPS, type ExternalAppRegistry } from '@/config/nav'
import ExternalAppView from '@/views/ExternalAppView.vue'

vi.mock('@/api/agent', () => ({
  apiExternalAppHealth: vi.fn<(url: string) => Promise<void>>(),
}))
vi.mock('@/api/system', () => ({
  fetchExternalAppRegistry: vi.fn<() => Promise<ExternalAppRegistry>>().mockResolvedValue({
    version: 1,
    apps: DEFAULT_EXTERNAL_APPS,
  }),
}))

const health = vi.mocked(apiExternalAppHealth)

async function mountApp(id = 'media-agent') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/apps/:appId', component: ExternalAppView }],
  })
  await router.push(`/apps/${id}`)
  await router.isReady()
  const wrapper = mount(ExternalAppView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

describe('ExternalAppView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('按注册表探测健康状态并加载应用页面', async () => {
    health.mockResolvedValue()
    const wrapper = await mountApp()

    expect(health).toHaveBeenCalledWith('/api/agent/health')
    const frame = wrapper.get('iframe')
    expect(frame.attributes('src')).toBe('/agent/')
    expect(frame.attributes('title')).toBe('自媒体 Agent')

    await frame.trigger('load')
    expect(wrapper.text()).toContain('「自媒体 Agent」已连接')
  })

  it('服务不可用时保留失败说明、重试和新窗口入口', async () => {
    health.mockRejectedValue(new Error('连接超时'))
    const wrapper = await mountApp()

    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.text()).toContain('连接超时')
    expect(wrapper.get('a').attributes('href')).toBe('/agent/')
    expect(wrapper.get('.state-card button').text()).toBe('重试')
  })
})
