import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { useNavStore } from '@/stores/nav'
import { useSessionStore } from '@/stores/session'
import type { AuthSession } from '@/api/auth'
import { fetchNavigationLayout, saveNavigationLayout } from '@/api/navigationLayout'
import { fetchBuiltinMenuRegistry, fetchExternalAppRegistry } from '@/api/system'
import { DEFAULT_BUILTIN_MENUS, DEFAULT_EXTERNAL_APPS } from '@/config/nav'
import { snapshotNavigation } from '@/config/navigationLayout'
import { recommendedLayout } from '@/config/navigationCatalog'
vi.mock('@/api/navigationLayout', () => ({
  fetchNavigationLayout: vi.fn(),
  saveNavigationLayout: vi.fn(),
}))
vi.mock('@/api/system', () => ({
  fetchBuiltinMenuRegistry: vi.fn(),
  fetchExternalAppRegistry: vi.fn(),
}))
vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn().mockResolvedValue(''),
  savePreference: vi.fn(),
}))
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const customMenus = DEFAULT_BUILTIN_MENUS.map((menu) =>
  menu.id === 'projects'
    ? {
        ...menu,
        label: '我的项目',
        placement: 'TOP' as const,
        order: 13,
        openMode: 'NEW_PAGE' as const,
      }
    : menu,
)
const customApps = DEFAULT_EXTERNAL_APPS.map((app) => ({
  ...app,
  placement: 'DOCK' as const,
  order: app.order + 3,
}))
function login(id: string) {
  useSessionStore().account = { member: { id } } as AuthSession
}
describe('个人导航快照', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchBuiltinMenuRegistry).mockResolvedValue({ version: 1, menus: clone(customMenus) })
    vi.mocked(fetchExternalAppRegistry).mockResolvedValue({ version: 1, apps: clone(customApps) })
    vi.mocked(fetchNavigationLayout).mockResolvedValue({ revision: 'none', layout: null })
    vi.mocked(saveNavigationLayout).mockImplementation(async (_revision, layout) => ({
      revision: 'saved',
      layout: clone(layout),
    }))
  })
  it('首次读取无损继承名称、位置、打开方式及非整十顺序，保存不带系统地址', async () => {
    const nav = useNavStore()
    await nav.loadMenus()
    expect(saveNavigationLayout).not.toHaveBeenCalled()
    expect(nav.menuConfig('projects')).toMatchObject(
      customMenus.find((menu) => menu.id === 'projects')!,
    )
    await nav.renameMenu('calendar', '我的日历')
    const saved = vi.mocked(saveNavigationLayout).mock.calls[0]![1]
    expect(saved.items.find((item) => item.id === 'projects')).toMatchObject({
      label: '我的项目',
      placement: 'TOP',
      order: 13,
      openMode: 'NEW_PAGE',
    })
    expect(saved.items.find((item) => item.id === 'external:getnote')).not.toHaveProperty('appUrl')
  })
  it('推荐分类只做草稿，不重置顶部停靠、隐藏项、自定义名称；原快照可撤销', async () => {
    const nav = useNavStore()
    await nav.loadMenus()
    const before = nav.currentLayout()
    const recommended = recommendedLayout(before)
    expect(nav.currentLayout()).toEqual(before)
    expect(recommended.items.filter((item) => item.placement !== 'MENU')).toEqual(
      before.items.filter((item) => item.placement !== 'MENU'),
    )
    await nav.saveLayout(recommended)
    await nav.saveLayout(before)
    expect(nav.currentLayout()).toEqual(before)
  })
  it('冲突保留本地意图，禁止旧快照重放；读取后可以重新编辑', async () => {
    const nav = useNavStore()
    await nav.loadMenus()
    vi.mocked(saveNavigationLayout).mockRejectedValueOnce(
      Object.assign(new Error('版本冲突'), { status: 409 }),
    )
    await expect(nav.moveMenu('projects', 'DOCK', 'TOOLS')).rejects.toThrow('版本冲突')
    expect(nav.layoutConflict).toBe(true)
    expect(nav.menuConfig('projects')?.placement).toBe('TOP')
    expect(nav.pendingLayout?.items.find((item) => item.id === 'projects')?.placement).toBe('DOCK')
    await expect(nav.retryLayout()).rejects.toThrow('另一设备')
    expect(saveNavigationLayout).toHaveBeenCalledTimes(1)
    await nav.discardLayoutDraft()
    expect(nav.layoutConflict).toBe(false)
  })
  it('普通失败可重试同一快照，锁定只禁止日常拖拽', async () => {
    const nav = useNavStore()
    await nav.loadMenus()
    vi.mocked(saveNavigationLayout).mockRejectedValueOnce(new Error('离线'))
    await expect(nav.moveMenu('projects', 'DOCK', 'TOOLS')).rejects.toThrow('离线')
    await nav.retryLayout()
    expect(nav.menuConfig('projects')?.placement).toBe('DOCK')
    await nav.setLayoutLocked(true)
    expect(nav.canMoveMenus).toBe(false)
    expect(nav.canEditLayout).toBe(true)
  })
  it('旧账号延迟返回不得污染新账号布局', async () => {
    login('alice')
    const nav = useNavStore()
    let resolveOld!: (value: Awaited<ReturnType<typeof fetchNavigationLayout>>) => void
    vi.mocked(fetchNavigationLayout).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        }),
    )
    const oldLoad = nav.loadMenus()
    const bob = snapshotNavigation(
      customMenus.map((menu) => ({
        ...menu,
        label: menu.id === 'projects' ? 'Bob 项目' : menu.label,
      })),
      customApps,
    )
    vi.mocked(fetchNavigationLayout).mockResolvedValue({ revision: 'bob', layout: bob })
    login('bob')
    await flushPromises()
    resolveOld({ revision: 'alice', layout: snapshotNavigation(customMenus, customApps) })
    await oldLoad
    expect(nav.menuConfig('projects')?.label).toBe('Bob 项目')
    expect(nav.layoutRevision).toBe('bob')
  })
  it('新增能力不占用已定制位置，移除应用不能由个人快照复活', async () => {
    const own = snapshotNavigation(
      customMenus.filter((menu) => menu.id !== 'calendar'),
      customApps,
    )
    vi.mocked(fetchNavigationLayout).mockResolvedValue({ revision: 'own', layout: own })
    vi.mocked(fetchExternalAppRegistry).mockResolvedValue({
      version: 2,
      apps: customApps.filter((app) => app.id !== 'getnote'),
    })
    const nav = useNavStore()
    await nav.loadMenus()
    expect(nav.menuConfig('calendar')?.visible).toBe(false)
    expect(nav.menuConfig('external:getnote')).toBeUndefined()
  })
})
