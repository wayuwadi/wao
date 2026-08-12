import { describe, expect, it } from 'vitest'
import {
  DEFAULT_EXTERNAL_APPS,
  DEFAULT_BUILTIN_MENUS,
  MODULES,
  NAV_GROUPS,
  PERSONAL_ITEMS,
  externalAppItemId,
  resolveNavGroups,
} from '@/config/nav'
import router from '@/router'
import type { ModuleId } from '@/types'

describe('侧边栏导航配置', () => {
  it('内置菜单都有模块元数据，外部应用使用命名空间标识', () => {
    const ids = NAV_GROUPS.flatMap((group) => group.items)
    const externalIds = ids.filter((id) => id.startsWith('external:'))
    const missingModules = ids
      .filter((id) => !id.startsWith('external:'))
      .filter((id) => !MODULES[id as ModuleId])

    expect(externalIds).toEqual(externalIds.filter((id) => /^external:[a-z0-9-]+$/.test(id)))
    expect(missingModules).toEqual([])
  })

  it('自媒体 Agent 默认出现在公共组，并保留旧地址重定向', () => {
    const publicGroup = NAV_GROUPS.find((group) => group.label === '公共')
    expect(publicGroup?.items).toEqual(['projects', externalAppItemId('media-agent')])
    expect(router.getRoutes().find((route) => route.path === '/media-agent')?.redirect).toBe(
      '/apps/media-agent',
    )
    const route = router.getRoutes().find((item) => item.name === 'external-app')
    expect(route?.path).toBe('/apps/:appId')
    expect(route?.meta.tabKind).toBe('external')
  })

  it('多个外部应用可分组排序和隐藏，且不破坏个人拖拽顺序', () => {
    const personalOrder = [...PERSONAL_ITEMS].reverse()
    const groups = resolveNavGroups(personalOrder, [
      { ...DEFAULT_EXTERNAL_APPS[0]!, group: 'PERSONAL', order: 15 },
      {
        id: 'data-studio',
        label: '数据工作台',
        icon: 'ri-dashboard-3-line',
        group: 'TOOLS',
        order: 0,
        visible: true,
        appUrl: '/studio/',
        healthUrl: '',
      },
      {
        id: 'hidden-app',
        label: '隐藏应用',
        icon: 'ri-apps-2-line',
        group: 'PUBLIC',
        order: 0,
        visible: false,
        appUrl: '/hidden/',
        healthUrl: '',
      },
    ])

    expect(groups.find((group) => group.label === '个人')?.items).toEqual([
      personalOrder[0],
      externalAppItemId('media-agent'),
      ...personalOrder.slice(1),
    ])
    expect(groups.find((group) => group.label === '工具')?.items[0]).toBe(
      externalAppItemId('data-studio'),
    )
    expect(groups.flatMap((group) => group.items)).not.toContain(externalAppItemId('hidden-app'))
  })

  it('回收站仍只保留知识库内入口', () => {
    const ids = NAV_GROUPS.flatMap((group) => group.items)
    expect(ids).not.toContain('trash')
    expect(router.getRoutes().some((route) => route.name === 'trash')).toBe(false)
    expect(router.getRoutes().some((route) => route.name === 'knowledge')).toBe(true)
  })

  it('游戏厅默认位于工具组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '工具')?.items).toContain('games')
    const route = router.getRoutes().find((item) => item.name === 'games')
    expect(route?.path).toBe('/games')
    expect(route?.meta.module).toBe('games')
  })

  it('天人一卦默认位于个人组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).toContain('tianren')
    const route = router.getRoutes().find((item) => item.name === 'tianren')
    expect(route?.path).toBe('/tianren')
    expect(route?.meta.module).toBe('tianren')
  })

  it('草图默认位于个人组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).toContain('drawings')
    const route = router.getRoutes().find((item) => item.name === 'drawings')
    expect(route?.path).toBe('/drawings')
    expect(route?.meta.module).toBe('drawings')
  })

  it('内置菜单注册表可改名、换组、调序和隐藏', () => {
    const menus = DEFAULT_BUILTIN_MENUS.map((menu) => ({ ...menu }))
    const calendar = menus.find((menu) => menu.id === 'calendar')!
    calendar.label = '家庭日历'
    calendar.group = 'PUBLIC'
    calendar.order = 1
    menus.find((menu) => menu.id === 'vault')!.visible = false

    const groups = resolveNavGroups(PERSONAL_ITEMS, [], menus)

    expect(groups.find((group) => group.label === '公共')?.items[0]).toBe('calendar')
    expect(groups.flatMap((group) => group.items)).not.toContain('vault')
  })
})
