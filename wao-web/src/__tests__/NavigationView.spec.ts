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
import { fetchNavigationLayout, saveNavigationLayout } from '@/api/navigationLayout'
import { useNavStore } from '@/stores/nav'
import { useUiStore } from '@/stores/ui'
import { fetchPreference, savePreference } from '@/api/favorites'
import {
  fetchBuiltinMenuRegistry,
  fetchExternalAppRegistry,
  saveBuiltinMenuRegistry,
  saveExternalAppRegistry,
} from '@/api/system'
import { fetchFunctionHealth } from '@/api/functionHealth'

vi.mock('@/api/navigationLayout', () => ({
  fetchNavigationLayout: vi.fn(),
  saveNavigationLayout: vi.fn(),
}))

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
    vi.mocked(fetchNavigationLayout).mockResolvedValue({ revision: 'none', layout: null })
    vi.mocked(saveNavigationLayout).mockImplementation(async (_revision, layout) => ({
      revision: crypto.randomUUID(),
      layout,
    }))
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
      (id) => !['navigation', 'chat', 'chat-reviews'].includes(id),
    )
    expect(new Set(catalogIds)).toEqual(new Set(expected))

    const { wrapper } = await mountNavigation()
    await wrapper.get('.domains button').trigger('click')
    const seen = [wrapper.text()]
    while (wrapper.get('button[aria-label="下一页"]').attributes('disabled') === undefined) {
      await wrapper.get('button[aria-label="下一页"]').trigger('click')
      seen.push(wrapper.text())
    }
    expect(seen.join(' ')).toContain('游戏厅')
    expect(seen.join(' ')).toContain('记忆中心')
    expect(seen.join(' ')).toContain('隐藏应用')
    expect(seen.join(' ')).toContain('我的资料')
    await wrapper.get('.customize-toggle').trigger('click')
    expect(wrapper.text()).toContain('未固定')
    wrapper.unmount()
  })

  it('把明确规划放到同分类末尾，功能探索可搜索打开且不保存个人布局', async () => {
    const before = NAVIGATION_CATALOG.map((section) => section.items.map((item) => item.id))
    const { wrapper, router } = await mountNavigation()
    await wrapper.get('.domains button').trigger('click')
    await wrapper
      .findAll('.domains button')
      .find((button) => button.text().includes('AI 与自动化'))!
      .trigger('click')
    const ai = wrapper
      .findAll('.catalog-section')
      .find((section) => section.find('h2').text() === 'AI 与自动化')!
    const rows = ai.findAll('.function-row')
    expect(rows[rows.length - 1]!.text()).toContain('外部应用授权尚未开放')
    expect(rows[rows.length - 1]!.get('.maturity-label').text()).toBe('规划中')
    expect(rows[rows.length - 2]!.text()).toContain('真实爬虫源码')
    expect(NAVIGATION_CATALOG.map((section) => section.items.map((item) => item.id))).toEqual(
      before,
    )
    await wrapper.get('.search input').setValue('新功能')
    expect(wrapper.find('.maturity-label').exists()).toBe(false)
    await wrapper.get('.function-open').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/features')
    expect(saveNavigationLayout).not.toHaveBeenCalled()
    expect(saveBuiltinMenuRegistry).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('可搜索 Codex 记忆入口，并打开侧栏已隐藏的真实模块和应用', async () => {
    const { router, wrapper } = await mountNavigation()
    await wrapper.get('.search input').setValue('文件库')
    expect(wrapper.findAll('.function-row')).toHaveLength(1)
    expect(wrapper.get('.function-row').text()).toContain('文件与图床')
    await wrapper.get('.search input').setValue('Codex MCP')
    expect(wrapper.findAll('.function-row')).toHaveLength(1)
    expect(wrapper.get('.function-row').text()).toContain('记忆中心')

    await wrapper.get('.search input').setValue('游戏厅')
    await wrapper.get('.function-open').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/games')

    await router.push('/navigation')
    await wrapper.get('.search input').setValue('隐藏应用')
    await wrapper.get('.function-open').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/apps/hidden-app')
    wrapper.unmount()
  })

  it('点子库入口明确只处理旧档案，新想法回到项目任务', async () => {
    const { wrapper } = await mountNavigation()
    await wrapper.get('.search input').setValue('历史点子')

    expect(wrapper.findAll('.function-row')).toHaveLength(1)
    expect(wrapper.get('.function-row').text()).toContain('归并旧版点子档案')
    expect(wrapper.get('.function-row').text()).toContain('新想法直接进入项目任务')
    wrapper.unmount()
  })

  it('工作台不再展示失效的手机底栏编辑，并继续直达真实小记链路', async () => {
    const { router, wrapper } = await mountNavigation()
    expect(wrapper.find('.manage').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('编辑手机底栏')
    expect(savePreference).not.toHaveBeenCalledWith('mobile-bottom-nav-v1', expect.any(String))

    await wrapper.get('.search input').setValue('小记')
    await wrapper.get('.function-open').trigger('click')
    await flushPromises()
    expect(useUiStore().modal).toBeNull()
    expect(router.currentRoute.value.path).toBe('/notes')
    wrapper.unmount()
  })

  it('打开功能时不读取治理统计，治理页再展示真实快照', async () => {
    const { router, wrapper } = await mountNavigation()
    expect(fetchFunctionHealth).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('30 天真实打开')
    await router.replace('/navigation?view=governance')
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.text()).toContain('当前账号近 30 天真实打开'))
    expect(fetchFunctionHealth).toHaveBeenCalled()
    expect(wrapper.text()).toContain('40')
    expect(wrapper.text()).toContain('优先缩短高频操作路径')
    wrapper.unmount()
  })

  it('功能治理默认按打开次数倒序，点击表头切换正序和倒序', async () => {
    const item = {
      score: 12,
      iterationCount: 11,
      manualAdjustment: 0,
      totalOpens: 20,
      recentShare: 25,
      lastOpenedAt: '2026-08-31T09:00:00',
      insightLevel: 'HIGH' as const,
      insight: '使用较频繁但未到 60 分，优先缩短高频操作路径',
    }
    vi.mocked(fetchFunctionHealth).mockResolvedValue({
      windowDays: 30,
      generatedAt: '2026-08-31T10:00:00',
      recentOpens: 15,
      functions: [
        { ...item, featureId: 'projects', recentOpens: 5 },
        { ...item, featureId: 'games', recentOpens: 10 },
      ],
    })
    const { router, wrapper } = await mountNavigation()
    await router.replace('/navigation?view=governance')
    await flushPromises()

    const readOrder = () => wrapper.findAll('tbody tr').map((row) => row.attributes('data-feature-id'))
    expect(readOrder()).toEqual(['games', 'projects'])
    const sort = wrapper.get('.sort-trigger')
    expect(sort.attributes('aria-label')).toContain('倒序')
    await sort.trigger('click')
    expect(wrapper.get('th[aria-sort="ascending"]')).toBeDefined()
    expect(readOrder()).toEqual(['projects', 'games'])
    await sort.trigger('click')
    expect(wrapper.get('th[aria-sort="descending"]')).toBeDefined()
    expect(readOrder()).toEqual(['games', 'projects'])
    wrapper.unmount()
  })

  it('隐藏菜单仍可发现，恢复外部应用及跨位置只保存个人快照', async () => {
    const { wrapper } = await mountNavigation()
    await wrapper.get('.search input').setValue('隐藏应用')
    await wrapper.get('.customize-toggle').trigger('click')
    expect(wrapper.text()).toContain('未固定')
    await wrapper.get('.position select').setValue('TOP')
    await flushPromises()
    expect(saveNavigationLayout).toHaveBeenCalledWith(
      'none',
      expect.objectContaining({
        items: expect.arrayContaining([
          expect.objectContaining({ id: 'external:hidden-app', visible: true, placement: 'TOP' }),
        ]),
      }),
    )
    expect(wrapper.text()).toContain('顶部菜单')
    expect(saveBuiltinMenuRegistry).not.toHaveBeenCalled()
    expect(saveExternalAppRegistry).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('菜单保存失败恢复位置，并保留可重试的草稿', async () => {
    vi.mocked(saveNavigationLayout).mockRejectedValueOnce(new Error('网络不可用'))
    const { wrapper } = await mountNavigation()
    await wrapper.get('.customize-toggle').trigger('click')
    await wrapper.get('.search input').setValue('隐藏应用')
    await wrapper.get('.position select').setValue('TOP')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('网络不可用')
    expect((wrapper.get('.position select').element as HTMLSelectElement).value).toBe('HIDDEN')
    await useNavStore().retryLayout()
    await flushPromises()
    expect(useNavStore().menuConfig('external:hidden-app')).toMatchObject({
      placement: 'TOP',
      visible: true,
    })
    wrapper.unmount()
  })

  it('旧工作台名称可找到链接空间，整理入口直达个人菜单设置', async () => {
    const { router, wrapper } = await mountNavigation()
    await wrapper.get('.search input').setValue('工作台')
    expect(wrapper.text()).toContain('链接空间')
    expect(wrapper.find('.position').exists()).toBe(false)
    expect(wrapper.find('.drag').exists()).toBe(false)
    await wrapper.get('.customize-toggle').trigger('click')
    expect(wrapper.find('.position').exists()).toBe(true)
    expect(wrapper.text()).toContain('每次调整会自动保存')
    await wrapper.get('.customize-toggle').trigger('click')
    expect(wrapper.find('.position').exists()).toBe(false)
    expect((wrapper.get('.search input').element as HTMLInputElement).value).toBe('工作台')
    expect(saveNavigationLayout).not.toHaveBeenCalled()
    await wrapper.get('.customize-toggle').trigger('click')
    await wrapper.get('.more-settings').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/settings?tab=navigation')
    wrapper.unmount()
  })
})
