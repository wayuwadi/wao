import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useNavStore } from '@/stores/nav'
import SideNav from '@/components/nav/SideNav.vue'

vi.mock('@/api/navigationLayout', () => ({
  fetchNavigationLayout: vi.fn().mockResolvedValue({ revision: 'none', layout: null }),
  saveNavigationLayout: vi.fn(),
}))

vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn<() => Promise<string>>().mockResolvedValue(''),
  savePreference: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
  fetchFavorites: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  apiAddFavorite: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
  apiRemoveFavorite: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
}))

vi.mock('@/api/system', () => ({
  fetchExternalAppRegistry: vi
    .fn<() => Promise<never>>()
    .mockRejectedValue(new Error('测试中保持默认菜单')),
  fetchBuiltinMenuRegistry: vi
    .fn<() => Promise<never>>()
    .mockRejectedValue(new Error('测试中保持默认菜单')),
}))

describe('手机高密度左侧导航', () => {
  const originalMatchMedia = window.matchMedia
  const originalScrollIntoView = HTMLElement.prototype.scrollIntoView
  const scrollIntoView = vi.fn<() => void>()

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem(
      'wao-nav-v1',
      JSON.stringify({
        collapsed: false,
        foldedGroups: { 个人: true, 工具: true },
      }),
    )
    window.matchMedia = ((query: string) => ({
      matches: query === '(max-width: 760px)',
      media: query,
      addEventListener: vi.fn<() => void>(),
      removeEventListener: vi.fn<() => void>(),
    })) as unknown as typeof window.matchMedia
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView,
    })
    setActivePinia(createPinia())
  })

  afterEach(() => {
    window.matchMedia = originalMatchMedia
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: originalScrollIntoView,
    })
  })

  async function mountNavigation(path = '/calendar') {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' }, meta: { module: 'home' } },
        { path: '/projects', component: { template: '<div />' }, meta: { module: 'projects' } },
        { path: '/todo', component: { template: '<div />' }, meta: { module: 'todo' } },
        { path: '/calendar', component: { template: '<div />' }, meta: { module: 'calendar' } },
        { path: '/automation', component: { template: '<div />' }, meta: { module: 'automation' } },
        { path: '/settings', component: { template: '<div />' }, meta: { module: 'settings' } },
        { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
      ],
    })
    await router.push(path)
    await router.isReady()
    const wrapper = mount(SideNav, {
      global: { plugins: [router], stubs: { QuickLinks: true } },
    })
    await flushPromises()
    return { router, wrapper }
  }

  it('复用 PC 完整菜单并忽略桌面分组折叠状态', async () => {
    const { wrapper } = await mountNavigation()
    const labels = wrapper.findAll('.item').map((item) => item.attributes('title'))

    expect(wrapper.findAll('.group-label').map((item) => item.text())).toEqual(
      expect.arrayContaining(['个人', '工具']),
    )
    expect(labels).toEqual(expect.arrayContaining(['项目', '待办', '日历']))
    expect(wrapper.find('.mobile-more-item').exists()).toBe(false)
    expect(wrapper.get('.item.active').attributes('title')).toBe('日历')
    expect(wrapper.get('.item.active').attributes('draggable')).toBe('false')
    expect(scrollIntoView).toHaveBeenCalled()
    expect(JSON.parse(localStorage.getItem('wao-nav-v1')!).foldedGroups).toEqual({
      个人: true,
      工具: true,
    })
    wrapper.unmount()
  })

  it('手机菜单仍打开原真实路由，设置入口保持可用', async () => {
    const { router, wrapper } = await mountNavigation('/')
    await wrapper.get('[title="项目"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/projects')

    await wrapper.get('.user').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/settings')
    wrapper.unmount()
  })

  it('桌面继续应用保存的分组折叠偏好和菜单拖拽能力', async () => {
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn<() => void>(),
      removeEventListener: vi.fn<() => void>(),
    })) as unknown as typeof window.matchMedia
    setActivePinia(createPinia())

    const { wrapper } = await mountNavigation('/')
    const labels = wrapper.findAll('.item').map((item) => item.attributes('title'))
    expect(labels).not.toEqual(expect.arrayContaining(['待办', '自动化']))
    expect(wrapper.get('[title="项目"]').attributes('draggable')).toBe('false')
    const nav = useNavStore()
    nav.builtinMenusState = 'loaded'
    nav.externalAppsState = 'loaded'
    nav.layoutLoaded = true
    await flushPromises()
    expect(wrapper.get('[title="项目"]').attributes('draggable')).toBe('true')
    wrapper.unmount()
  })
})

describe('手机高密度壳与安全区', () => {
  it('左栏、紧凑顶栏和页签共同保留，正文不再为底栏留白', () => {
    const sideNav = readFileSync(join(process.cwd(), 'src/components/nav/SideNav.vue'), 'utf8')
    const layout = readFileSync(join(process.cwd(), 'src/layouts/AppLayout.vue'), 'utf8')
    const tabs = readFileSync(join(process.cwd(), 'src/components/tabs/TabBar.vue'), 'utf8')

    expect(sideNav).toContain('width: clamp(88px, 24vw, 104px)')
    expect(sideNav).toContain('height: 100dvh')
    expect(layout).not.toContain('padding-bottom: calc(64px + env(safe-area-inset-bottom))')
    expect(layout).toContain('bottom: calc(12px + env(safe-area-inset-bottom))')
    expect(tabs).not.toMatch(/@media \(max-width: 760px\)[\s\S]*?\.tabbar-shell \{\s*display: none/)
  })

  it('主站继续可安装并声明完整安全区', () => {
    const index = readFileSync(join(process.cwd(), 'index.html'), 'utf8')
    const manifest = JSON.parse(
      readFileSync(join(process.cwd(), 'public/manifest.webmanifest'), 'utf8'),
    ) as { display: string; start_url: string; shortcuts: Array<{ url: string }> }

    expect(index).toContain('viewport-fit=cover')
    expect(index).toContain('rel="manifest"')
    expect(index).toContain('apple-mobile-web-app-capable')
    expect(manifest.display).toBe('standalone')
    expect(manifest.start_url).toBe('/projects')
    expect(manifest.shortcuts.map((item) => item.url)).toEqual(['/projects', '/navigation'])
  })

  it('持久会话 Cookie 与服务端会话统一为三十天', () => {
    const server = readFileSync(
      join(process.cwd(), '../wao-server/src/main/resources/application.yml'),
      'utf8',
    )
    expect(server).toMatch(/session:\n\s+timeout: 30d/)
    expect(server).toMatch(/same-site: lax\n\s+#[^\n]+\n\s+max-age: 30d/)
  })
})
