import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SideNav from '@/components/nav/SideNav.vue'
import { useNavStore } from '@/stores/nav'
import { fetchPreference, savePreference } from '@/api/favorites'

vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn<(key: string) => Promise<string>>().mockResolvedValue(''),
  savePreference: vi
    .fn<(key: string, value: string) => Promise<void>>()
    .mockResolvedValue(undefined),
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

describe('手机底部模块导航', () => {
  const originalMatchMedia = window.matchMedia
  const originalScrollIntoView = HTMLElement.prototype.scrollIntoView
  const scrollIntoView = vi.fn<() => void>()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchPreference).mockResolvedValue('')
    vi.mocked(savePreference).mockResolvedValue(undefined)
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
    scrollIntoView.mockReset()
  })

  async function mountNavigation(path = '/calendar') {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' }, meta: { module: 'home' } },
        { path: '/navigation', component: { template: '<div />' }, meta: { module: 'navigation' } },
        { path: '/projects', component: { template: '<div />' }, meta: { module: 'projects' } },
        { path: '/calendar', component: { template: '<div />' }, meta: { module: 'calendar' } },
        { path: '/games', component: { template: '<div />' }, meta: { module: 'games' } },
        { path: '/wao/skills', component: { template: '<div />' }, meta: { module: 'skills' } },
      ],
    })
    await router.push(path)
    await router.isReady()
    const wrapper = mount(SideNav, { global: { plugins: [router], stubs: { QuickLinks: true } } })
    await flushPromises()
    return wrapper
  }

  it('默认只保留项目、小记和固定的更多入口', async () => {
    const wrapper = await mountNavigation()
    const labels = wrapper.findAll('.item').map((item) => item.attributes('title'))

    expect(labels).toEqual(['项目', '小记', '更多'])
    wrapper.unmount()
  })

  it('从成员偏好恢复自定义底栏并高亮当前模块', async () => {
    vi.mocked(fetchPreference).mockImplementation(async (key) =>
      key === 'mobile-bottom-nav-v1' ? JSON.stringify(['calendar', 'projects']) : '',
    )
    const wrapper = await mountNavigation('/calendar')

    expect(wrapper.get('.item.active').attributes('title')).toBe('日历')
    expect(wrapper.findAll('.item').map((item) => item.attributes('title'))).toEqual([
      '日历',
      '项目',
      '更多',
    ])
    wrapper.unmount()
  })

  it('小记直接触发真实创建入口，更多打开完整工作台', async () => {
    const wrapper = await mountNavigation('/')
    await wrapper.get('[title="小记"]').trigger('click')
    expect(wrapper.emitted('new-note')).toHaveLength(1)

    await wrapper.get('.mobile-more-item').trigger('click')
    await flushPromises()
    expect(wrapper.vm.$route.fullPath).toBe('/navigation')
    wrapper.unmount()
  })

  it('菜单默认在 WAO 页签路由打开，也可配置为浏览器新页面', async () => {
    const wrapper = await mountNavigation('/')
    const projectButton = wrapper
      .findAll('.item')
      .find((item) => item.attributes('title') === '项目')!

    await projectButton.trigger('click')
    await flushPromises()
    expect(wrapper.vm.$route.fullPath).toBe('/projects')

    await wrapper.vm.$router.push('/')
    const nav = useNavStore()
    nav.applyBuiltinMenus(
      nav.builtinMenus.map((menu) =>
        menu.id === 'projects' ? { ...menu, openMode: 'NEW_PAGE' } : menu,
      ),
    )
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    await projectButton.trigger('click')

    expect(open).toHaveBeenCalledWith('/projects', '_blank', 'noopener,noreferrer')
    expect(wrapper.vm.$route.fullPath).toBe('/')
    open.mockRestore()
    wrapper.unmount()
  })

  it('长按拖动后立即保存成员级顺序', async () => {
    vi.useFakeTimers()
    const wrapper = await mountNavigation('/')
    const project = wrapper.get('[data-mobile-nav-id="projects"]')
    const note = wrapper.get('[data-mobile-nav-id="action:note"]')
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: vi.fn<() => Element>(() => note.element),
    })

    const dispatchPointer = (type: string, clientX: number) => {
      const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientX, clientY: 10 })
      Object.defineProperty(event, 'pointerType', { value: 'touch' })
      project.element.dispatchEvent(event)
    }
    dispatchPointer('pointerdown', 10)
    await vi.advanceTimersByTimeAsync(430)
    dispatchPointer('pointermove', 80)
    dispatchPointer('pointerup', 80)
    await flushPromises()

    expect(wrapper.findAll('.mobile-nav-item').map((item) => item.attributes('title'))).toEqual([
      '小记',
      '项目',
    ])
    expect(savePreference).toHaveBeenCalledWith(
      'mobile-bottom-nav-v1',
      JSON.stringify(['action:note', 'projects']),
    )
    Reflect.deleteProperty(document, 'elementFromPoint')
    vi.useRealTimers()
    wrapper.unmount()
  })

  it('桌面仍应用并保留原有分组折叠偏好', async () => {
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn<() => void>(),
      removeEventListener: vi.fn<() => void>(),
    })) as unknown as typeof window.matchMedia
    setActivePinia(createPinia())

    const wrapper = await mountNavigation('/')
    const labels = wrapper.findAll('.item').map((item) => item.attributes('title'))

    expect(labels).not.toEqual(expect.arrayContaining(['待办', '自动化', '游戏厅']))
    expect(JSON.parse(localStorage.getItem('wao-nav-v1')!).foldedGroups).toEqual({
      个人: true,
      工具: true,
    })
    wrapper.unmount()
  })
})

describe('手机安全区', () => {
  it('主站可安装为独立 H5 应用并为底部导航完整留白', () => {
    const index = readFileSync(join(process.cwd(), 'index.html'), 'utf8')
    const layout = readFileSync(join(process.cwd(), 'src/layouts/AppLayout.vue'), 'utf8')
    const manifest = JSON.parse(
      readFileSync(join(process.cwd(), 'public/manifest.webmanifest'), 'utf8'),
    ) as { display: string; start_url: string; shortcuts: Array<{ url: string }> }

    expect(index).toContain('viewport-fit=cover')
    expect(index).toContain('rel="manifest"')
    expect(index).toContain('apple-mobile-web-app-capable')
    expect(manifest.display).toBe('standalone')
    expect(manifest.start_url).toBe('/projects')
    expect(manifest.shortcuts.map((item) => item.url)).toEqual(['/projects', '/navigation'])
    expect(layout).toContain('padding-bottom: calc(64px + env(safe-area-inset-bottom))')
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
