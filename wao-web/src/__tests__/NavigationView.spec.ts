import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import NavigationView from '@/views/NavigationView.vue'
import {
  DEFAULT_BUILTIN_MENUS,
  type BuiltinMenuRegistry,
  type ExternalAppRegistry,
} from '@/config/nav'
import { NAVIGATION_CATALOG } from '@/config/navigationCatalog'
import { MODULES } from '@/config/nav'
import { fetchPreference, savePreference } from '@/api/favorites'
import { fetchBuiltinMenuRegistry, fetchExternalAppRegistry } from '@/api/system'

vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn<() => Promise<string>>(),
  savePreference: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
}))

vi.mock('@/api/system', () => ({
  fetchBuiltinMenuRegistry: vi.fn<() => Promise<BuiltinMenuRegistry>>(),
  fetchExternalAppRegistry: vi.fn<() => Promise<ExternalAppRegistry>>(),
}))

async function mountNavigation() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/navigation', component: NavigationView },
      { path: '/games', component: { template: '<div />' } },
      { path: '/apps/:appId', component: { template: '<div />' } },
      { path: '/quick-links/:linkId', component: { template: '<div />' } },
      { path: '/settings', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/navigation')
  await router.isReady()
  const wrapper = mount(NavigationView, { global: { plugins: [router] } })
  await flushPromises()
  return { router, wrapper }
}

describe('全量导航页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchBuiltinMenuRegistry).mockResolvedValue({
      version: 24,
      menus: DEFAULT_BUILTIN_MENUS,
    })
    vi.mocked(fetchExternalAppRegistry).mockResolvedValue({
      version: 1,
      apps: [
        {
          id: 'hidden-app',
          label: '隐藏应用',
          icon: 'ri-apps-2-line',
          group: 'TOOLS',
          order: 10,
          visible: false,
          appUrl: '/hidden/',
          healthUrl: '',
          openMode: 'TAB',
        },
      ],
    })
    vi.mocked(fetchPreference).mockResolvedValue(
      JSON.stringify({
        version: 2,
        links: [
          {
            id: 'my-link',
            label: '我的资料',
            subtitle: '个人链接',
            icon: 'ri-links-line',
            url: '/docs/',
            openMode: 'TAB',
            keywords: ['ziliao'],
          },
        ],
      }),
    )
  })

  it('完整目录覆盖所有非兼容模块，且不受侧栏显隐影响', async () => {
    const catalogIds = NAVIGATION_CATALOG.flatMap((section) => section.items.map((item) => item.id))
    const expected = Object.keys(MODULES).filter(
      (id) => !['home', 'navigation', 'settings', 'chat', 'chat-reviews'].includes(id),
    )
    expect(new Set(catalogIds)).toEqual(new Set(expected))

    const { wrapper } = await mountNavigation()
    expect(wrapper.text()).toContain('游戏厅')
    expect(wrapper.text()).toContain('记忆中心')
    expect(wrapper.text()).toContain('隐藏应用')
    expect(wrapper.text()).toContain('我的资料')
    expect(wrapper.findAll('.hidden-badge').length).toBeGreaterThan(0)
    wrapper.unmount()
  })

  it('可搜索 Codex 记忆入口，并打开侧栏已隐藏的真实模块和应用', async () => {
    const { router, wrapper } = await mountNavigation()
    await wrapper.get('.search input').setValue('Codex MCP')
    expect(wrapper.findAll('.nav-card')).toHaveLength(1)
    expect(wrapper.get('.nav-card').text()).toContain('记忆中心')

    await wrapper.get('.search input').setValue('游戏厅')
    await wrapper.get('.nav-card').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/games')

    await router.push('/navigation')
    await wrapper.get('.search input').setValue('隐藏应用')
    await wrapper.get('.nav-card').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/apps/hidden-app')
    wrapper.unmount()
  })

  it('从完整工作台编辑并保存当前成员的手机底栏', async () => {
    const { wrapper } = await mountNavigation()
    await wrapper.get('.manage.primary').trigger('click')
    expect(wrapper.text()).toContain('点选要放到底栏的功能（2/4）')

    await wrapper.get('.search input').setValue('游戏厅')
    await wrapper.get('.nav-card').trigger('click')
    expect(wrapper.get('.nav-card').classes()).toContain('selected')

    await wrapper.get('.manage.primary').trigger('click')
    await flushPromises()
    expect(savePreference).toHaveBeenCalledWith(
      'mobile-bottom-nav-v1',
      JSON.stringify(['projects', 'action:note', 'games']),
    )
    expect(wrapper.text()).toContain('底栏已保存')
    wrapper.unmount()
  })
})
