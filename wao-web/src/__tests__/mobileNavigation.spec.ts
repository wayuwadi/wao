import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SideNav from '@/components/nav/SideNav.vue'
import { useNavStore } from '@/stores/nav'

vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn<() => Promise<string | null>>().mockResolvedValue(null),
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

describe('手机底部模块导航', () => {
  const originalMatchMedia = window.matchMedia
  const originalScrollIntoView = HTMLElement.prototype.scrollIntoView
  const scrollIntoView = vi.fn<() => void>()

  beforeEach(() => {
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

  async function mountNavigation(path = '/games') {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' }, meta: { module: 'home' } },
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

  it('不复用桌面折叠状态，手机仍展示全部可见模块', async () => {
    const wrapper = await mountNavigation()
    const labels = wrapper.findAll('.item').map((item) => item.attributes('title'))

    expect(labels).toEqual(
      expect.arrayContaining([
        '聊天',
        '待办',
        '日历',
        '密码本',
        '自动化',
        '文件与图床',
        '游戏厅',
      ]),
    )
    wrapper.unmount()
  })

  it('进入靠后的模块时把当前入口滚入可见区域', async () => {
    const wrapper = await mountNavigation()

    expect(wrapper.get('.item.active').attributes('title')).toBe('游戏厅')
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', inline: 'center' })
    wrapper.unmount()
  })

  it('提供不依赖系统清单快捷方式的 AI 派发入口', async () => {
    const wrapper = await mountNavigation('/')
    const ai = wrapper.get('.mobile-ai-item')

    expect(ai.attributes('title')).toBe('AI')
    await ai.trigger('click')
    await flushPromises()
    expect(wrapper.vm.$route.fullPath).toBe('/?console=runs#ai-launcher')
    wrapper.unmount()
  })

  it('菜单默认在 WAO 页签路由打开，也可配置为浏览器新页面', async () => {
    const wrapper = await mountNavigation('/')
    const skillButton = wrapper.findAll('.item').find((item) => item.attributes('title') === 'Skill 市场')!

    await skillButton.trigger('click')
    await flushPromises()
    expect(wrapper.vm.$route.fullPath).toBe('/wao/skills')

    await wrapper.vm.$router.push('/')
    const nav = useNavStore()
    nav.applyBuiltinMenus(nav.builtinMenus.map((menu) =>
      menu.id === 'skills' ? { ...menu, openMode: 'NEW_PAGE' } : menu,
    ))
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    await skillButton.trigger('click')

    expect(open).toHaveBeenCalledWith('/skills', '_blank', 'noopener,noreferrer')
    expect(wrapper.vm.$route.fullPath).toBe('/')
    open.mockRestore()
    wrapper.unmount()
  })

  it('得到大脑入口在浏览器新页面打开官方地址', async () => {
    const wrapper = await mountNavigation('/')
    const getnoteButton = wrapper
      .findAll('.item')
      .find((item) => item.attributes('title') === '得到大脑')!
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)

    await getnoteButton.trigger('click')

    expect(open).toHaveBeenCalledWith('https://www.biji.com/', '_blank', 'noopener,noreferrer')
    expect(wrapper.vm.$route.fullPath).toBe('/')
    open.mockRestore()
    wrapper.unmount()
  })

  it('资源管理入口先通过 WAO 会话换取 JumpServer 登录票据', async () => {
    const wrapper = await mountNavigation('/')
    const jumpServerButton = wrapper
      .findAll('.item')
      .find((item) => item.attributes('title') === '资源管理')!
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)

    await jumpServerButton.trigger('click')

    expect(open).toHaveBeenCalledWith('/api/auth/jumpserver', '_blank', 'noopener,noreferrer')
    expect(wrapper.vm.$route.fullPath).toBe('/')
    open.mockRestore()
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
  it('主站启用全屏安全区并为底部导航完整留白', () => {
    const index = readFileSync(join(process.cwd(), 'index.html'), 'utf8')
    const layout = readFileSync(join(process.cwd(), 'src/layouts/AppLayout.vue'), 'utf8')

    expect(index).toContain('viewport-fit=cover')
    expect(layout).toContain('padding-bottom: calc(64px + env(safe-area-inset-bottom))')
  })
})
