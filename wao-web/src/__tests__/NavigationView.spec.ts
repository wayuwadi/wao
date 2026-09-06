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
import { useUiStore } from '@/stores/ui'
import { fetchPreference, savePreference } from '@/api/favorites'
import {
  fetchBuiltinMenuRegistry,
  fetchExternalAppRegistry,
  saveBuiltinMenuRegistry,
  saveExternalAppRegistry,
} from '@/api/system'
import { fetchFunctionHealth } from '@/api/functionHealth'

vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn<() => Promise<string>>(),
  savePreference: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
}))

vi.mock('@/api/system', () => ({
  fetchBuiltinMenuRegistry: vi.fn<() => Promise<BuiltinMenuRegistry>>(),
  fetchExternalAppRegistry: vi.fn<() => Promise<ExternalAppRegistry>>(),
  saveBuiltinMenuRegistry:
    vi.fn<(menus: BuiltinMenuRegistry['menus']) => Promise<BuiltinMenuRegistry>>(),
  saveExternalAppRegistry:
    vi.fn<(apps: ExternalAppRegistry['apps']) => Promise<ExternalAppRegistry>>(),
}))

vi.mock('@/api/functionHealth', () => ({
  fetchFunctionHealth:
    vi.fn<() => Promise<import('@/api/functionHealth').FunctionHealthOverview>>(),
  recordFunctionOpen: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
  adjustFunctionScore:
    vi.fn<
      (
        featureId: string,
        delta: -1 | 1,
      ) => Promise<import('@/api/functionHealth').FunctionHealthItem>
    >(),
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
    vi.mocked(saveBuiltinMenuRegistry).mockImplementation(async (menus) => ({ version: 24, menus }))
    vi.mocked(saveExternalAppRegistry).mockImplementation(async (apps) => ({ version: 1, apps }))
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
    vi.mocked(fetchFunctionHealth).mockResolvedValue({
      windowDays: 30,
      generatedAt: '2026-08-31T10:00:00',
      recentOpens: 40,
      functions: [
        {
          featureId: 'games',
          score: 12,
          iterationCount: 11,
          manualAdjustment: 0,
          totalOpens: 20,
          recentOpens: 10,
          recentShare: 25,
          lastOpenedAt: '2026-08-31T09:00:00',
          insightLevel: 'HIGH',
          insight: '使用较频繁但未到 60 分，优先缩短高频操作路径',
        },
      ],
    })
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
    await wrapper.get('.card-open').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/games')

    await router.push('/navigation')
    await wrapper.get('.search input').setValue('隐藏应用')
    await wrapper.get('.card-open').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/apps/hidden-app')
    wrapper.unmount()
  })

  it('点子库入口明确只处理旧档案，新想法回到项目任务', async () => {
    const { wrapper } = await mountNavigation()
    await wrapper.get('.search input').setValue('历史点子')

    expect(wrapper.findAll('.nav-card')).toHaveLength(1)
    expect(wrapper.get('.nav-card').text()).toContain('归并旧版点子档案')
    expect(wrapper.get('.nav-card').text()).toContain('新想法直接进入项目任务')
    wrapper.unmount()
  })

  it('工作台不再展示失效的手机底栏编辑，并继续直达真实小记链路', async () => {
    const { wrapper } = await mountNavigation()
    expect(wrapper.find('.manage').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('编辑手机底栏')
    expect(savePreference).not.toHaveBeenCalledWith('mobile-bottom-nav-v1', expect.any(String))

    await wrapper.get('.quick-actions-section .nav-card').trigger('click')
    expect(useUiStore().modal).toBe('note')
    wrapper.unmount()
  })

  it('使用真实快照展示分数、次数、占比和优化提示', async () => {
    const { wrapper } = await mountNavigation()
    expect(wrapper.text()).toContain('40')
    await wrapper.get('.search input').setValue('游戏厅')
    expect(wrapper.get('.nav-card').text()).toContain('12 分')
    expect(wrapper.get('.nav-card').text()).toContain('30 天 10 次 · 25.0%')
    expect(wrapper.get('.nav-card').text()).toContain('优先缩短高频操作路径')
    wrapper.unmount()
  })

  it('从工作台直接隐藏内置菜单，保存后仍留在当前工作台恢复', async () => {
    const { router, wrapper } = await mountNavigation()
    await wrapper.get('.search input').setValue('项目')
    const card = wrapper.get('.manageable-card')
    expect(card.get('.menu-visibility').text()).toContain('隐藏菜单')

    await card.get('.menu-visibility').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/navigation')
    expect(saveBuiltinMenuRegistry).toHaveBeenCalledWith(
      expect.arrayContaining([expect.objectContaining({ id: 'projects', visible: false })]),
    )
    expect(card.text()).toContain('侧栏已隐藏')
    expect(card.get('.menu-visibility').text()).toContain('显示菜单')
    wrapper.unmount()
  })

  it('从工作台直接恢复已隐藏的接入应用', async () => {
    const { router, wrapper } = await mountNavigation()
    await wrapper.get('.search input').setValue('隐藏应用')
    const card = wrapper.get('.manageable-card')
    expect(card.text()).toContain('侧栏已隐藏')

    await card.get('.menu-visibility').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/navigation')
    expect(saveExternalAppRegistry).toHaveBeenCalledWith([
      expect.objectContaining({ id: 'hidden-app', visible: true }),
    ])
    expect(card.text()).not.toContain('侧栏已隐藏')
    expect(card.get('.menu-visibility').text()).toContain('隐藏菜单')
    wrapper.unmount()
  })

  it('菜单保存失败时恢复服务端状态并可原地重试', async () => {
    vi.mocked(saveBuiltinMenuRegistry)
      .mockRejectedValueOnce(new Error('网络不可用'))
      .mockImplementationOnce(async (menus) => ({ version: 24, menus }))
    const { wrapper } = await mountNavigation()
    await wrapper.get('.search input').setValue('项目')

    await wrapper.get('.menu-visibility').trigger('click')
    await flushPromises()

    expect(wrapper.get('.menu-action-error').text()).toContain('网络不可用')
    expect(wrapper.get('.menu-visibility').text()).toContain('隐藏菜单')
    await wrapper.get('.menu-action-error button').trigger('click')
    await flushPromises()

    expect(saveBuiltinMenuRegistry).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.menu-action-error').exists()).toBe(false)
    expect(wrapper.get('.menu-visibility').text()).toContain('显示菜单')
    wrapper.unmount()
  })
})
