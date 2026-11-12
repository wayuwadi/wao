import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { useNavStore } from '@/stores/nav'
import { useSessionStore } from '@/stores/session'
import type { AuthSession } from '@/api/auth'
import { fetchNavigationLayout, saveNavigationLayout } from '@/api/navigationLayout'
import { fetchBuiltinMenuRegistry, fetchExternalAppRegistry } from '@/api/system'
import { FIXED_NAV_IDS, DEFAULT_BUILTIN_MENUS, normalizeMobileNavItems } from '@/config/nav'
import { NAVIGATION_CATALOG } from '@/config/navigationCatalog'
import router from '@/router'
vi.mock('@/api/navigationLayout', () => ({ fetchNavigationLayout: vi.fn(), saveNavigationLayout: vi.fn() }))
vi.mock('@/api/system', () => ({ fetchBuiltinMenuRegistry: vi.fn(), fetchExternalAppRegistry: vi.fn() }))
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
describe('开发人员管理的固定导航', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); setActivePinia(createPinia()) })
  it('固定菜单、功能目录和旧手机配置移除倒数日、番茄钟与算一卦', () => {
    const retired = ['focus', 'countdowns', 'tianren']
    const nav = useNavStore()
    for (const id of retired) {
      expect(FIXED_NAV_IDS).not.toContain(id)
      expect(nav.groups.flatMap(group => group.items)).not.toContain(id)
      expect(DEFAULT_BUILTIN_MENUS.map(menu => menu.id)).not.toContain(id)
      expect(NAVIGATION_CATALOG.flatMap(section => section.items.map(item => item.id))).not.toContain(id)
    }
    expect(FIXED_NAV_IDS).toContain('calendar')
    expect(normalizeMobileNavItems([...retired, 'calendar'])).toEqual(['calendar'])
    expect(router.resolve('/focus').matched.slice(-1)[0]?.redirect).toBe('/todo')
    expect(router.resolve('/countdowns').matched.slice(-1)[0]?.redirect).toEqual({ path: '/calendar', query: { dates: '1' } })
  })
  it('不读取旧个人布局和全局菜单，加载失败也保持完整菜单', async () => {
    vi.mocked(fetchExternalAppRegistry).mockRejectedValue(new Error('offline'))
    const nav = useNavStore()
    await nav.loadMenus()
    expect(nav.groups.flatMap(g => g.items)).toEqual(FIXED_NAV_IDS)
    expect(fetchNavigationLayout).not.toHaveBeenCalled()
    expect(fetchBuiltinMenuRegistry).not.toHaveBeenCalled()
    expect(nav.canEditLayout).toBe(false)
    expect(nav.canMoveMenus).toBe(false)
    expect(nav.topItems).toEqual([])
    expect(nav.dockItems).toEqual([])
    await expect(nav.saveLayout(nav.currentLayout())).rejects.toThrow('统一维护')
    await expect(nav.saveMobileItems(['vault'])).rejects.toThrow('统一维护')
    expect(saveNavigationLayout).not.toHaveBeenCalled()
  })
  it('切换账号丢弃迟到的外部应用，固定菜单保持一致', async () => {
    let resolveOld!: (value: { version: number; apps: [] }) => void
    vi.mocked(fetchExternalAppRegistry).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const nav = useNavStore()
    const old = nav.loadMenus()
    vi.mocked(fetchExternalAppRegistry).mockResolvedValue({ version: 1, apps: [] })
    useSessionStore().account = { member: { id: 'new' } } as AuthSession
    await flushPromises()
    resolveOld({ version: 1, apps: [] }); await old
    expect(nav.groups.flatMap(g => g.items)).toEqual(FIXED_NAV_IDS)
    expect(nav.layoutLoaded).toBe(true)
    expect(nav.externalApps).toEqual([])
  })
})
