import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { apiExternalAppHealth } from '@/api/agent'
import { DEFAULT_BUILTIN_MENUS, type ExternalAppRegistry } from '@/config/nav'
import { fetchExternalAppRegistry } from '@/api/system'
import ExternalAppView from '@/views/ExternalAppView.vue'

vi.mock('@/api/agent', () => ({
  apiExternalAppHealth: vi.fn<(url: string) => Promise<void>>(),
}))
vi.mock('@/api/navigationLayout', () => ({
  fetchNavigationLayout: vi.fn().mockResolvedValue({ revision: 'none', layout: null }),
}))
vi.mock('@/api/system', () => ({
  fetchBuiltinMenuRegistry: vi.fn().mockResolvedValue({ version: 1, menus: DEFAULT_BUILTIN_MENUS }),
  fetchExternalAppRegistry: vi.fn<() => Promise<ExternalAppRegistry>>().mockResolvedValue({
    version: 1,
    apps: [{ id: 'test-reader', label: '测试资料库', icon: 'ri-apps-2-line', group: 'TOOLS', order: 1,
      visible: true, appUrl: '/test-reader/', healthUrl: '/api/test-reader/health', openMode: 'TAB' }],
  }),
}))

const health = vi.mocked(apiExternalAppHealth)

async function mountApp(id = 'test-reader') {
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

    expect(health).toHaveBeenCalledWith('/api/test-reader/health')
    const frame = wrapper.get('iframe')
    expect(frame.attributes('src')).toBe('/test-reader/')
    expect(frame.attributes('title')).toBe('测试资料库')

    await frame.trigger('load')
    expect(wrapper.text()).toContain('「测试资料库」页面已载入')
    expect(wrapper.text()).not.toContain('已连接')
    wrapper.unmount()
  })

  it('服务不可用时保留失败说明、重试和新窗口入口', async () => {
    health.mockRejectedValue(new Error('连接超时'))
    const wrapper = await mountApp()

    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.text()).toContain('连接超时')
    expect(wrapper.get('a').attributes('href')).toBe('/test-reader/')
    expect(wrapper.get('.state-card button').text()).toBe('重试')
    wrapper.unmount()
  })

  it('旧注册表中的自媒体 Agent 不会恢复健康探测或嵌入入口', async () => {
    vi.mocked(fetchExternalAppRegistry).mockResolvedValueOnce({ version: 1, apps: [{
      id: 'media-agent', label: '自媒体 Agent', icon: 'ri-apps-2-line', group: 'TOOLS', order: 1,
      visible: true, appUrl: '/agent/', healthUrl: '/api/agent/health', openMode: 'TAB',
    }] })
    const wrapper = await mountApp('media-agent')
    expect(wrapper.text()).toContain('这个外部应用尚未注册')
    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(health).not.toHaveBeenCalled()
    wrapper.unmount()
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
