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
    expect(wrapper.text()).toContain('「自媒体 Agent」页面已载入')
    expect(wrapper.text()).not.toContain('已连接')
    wrapper.unmount()
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

it('嵌入迟迟不加载时保留页面并提供独立打开，重试重新计时', async () => {
  vi.useFakeTimers()
  health.mockResolvedValue()
  const wrapper = await mountApp()
  try {
    await vi.advanceTimersByTimeAsync(15_000)
    expect(wrapper.text()).toContain('加载时间较长')
    expect(wrapper.find('.overlay').exists()).toBe(false)
    expect(wrapper.find('iframe').exists()).toBe(true)
    expect(wrapper.get('a').attributes('target')).toBe('_blank')
    await wrapper.get('.actions button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.overlay').exists()).toBe(true)
    await wrapper.get('iframe').trigger('load')
    expect(wrapper.text()).not.toContain('加载时间较长')
  } finally { wrapper.unmount(); vi.useRealTimers() }
})
