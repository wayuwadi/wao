import { describe, expect, it } from 'vitest'
import {
  DEFAULT_EXTERNAL_APPS,
  DEFAULT_BUILTIN_MENUS,
  MODULES,
  NAV_GROUPS,
  PERSONAL_ITEMS,
  externalAppItemId,
  resolveNavGroups,
  tabFromRoute,
} from '@/config/nav'
import router from '@/router'
import type { ModuleId } from '@/types'
import type { RouteLocationNormalized } from 'vue-router'

describe('侧边栏导航配置', () => {
  it('默认导航不再提供独立点子库入口', () => {
    expect(DEFAULT_BUILTIN_MENUS.map((menu) => menu.id)).not.toContain('ideas')
  })

  it('内置菜单都有模块元数据，外部应用使用命名空间标识', () => {
    const ids = NAV_GROUPS.flatMap((group) => group.items)
    const externalIds = ids.filter((id) => id.startsWith('external:'))
    const missingModules = ids
      .filter((id) => !id.startsWith('external:'))
      .filter((id) => !MODULES[id as ModuleId])

    expect(externalIds).toEqual(externalIds.filter((id) => /^external:[a-z0-9-]+$/.test(id)))
    expect(missingModules).toEqual([])
    expect(DEFAULT_BUILTIN_MENUS.every((menu) => menu.openMode === 'TAB')).toBe(true)
    expect(DEFAULT_EXTERNAL_APPS.every((app) => app.openMode === 'TAB')).toBe(true)
  })

  it('Skill 市场同时保留公开路由和 WAO 页签路由', () => {
    expect(MODULES.skills.path).toBe('/wao/skills')
    expect(MODULES.skills.newPagePath).toBe('/skills')
    expect(router.getRoutes().find((route) => route.name === 'skill-market')?.path).toBe('/skills')
    const inApp = router.getRoutes().find((route) => route.name === 'wao-skill-market')
    expect(inApp?.path).toBe('/wao/skills')
    expect(router.resolve('/wao/skills').meta.module).toBe('skills')
    expect(router.resolve('/wao/skills').meta.tabKind).toBe('module')
    expect(
      tabFromRoute(router.resolve('/wao/skills/manage') as RouteLocationNormalized)?.path,
    ).toBe('/wao/skills/manage')
  })

  it('公共组保留兼容模块顺序，自媒体 Agent 保留旧地址重定向', () => {
    const publicGroup = NAV_GROUPS.find((group) => group.label === '公共')
    expect(publicGroup?.items).toEqual([
      'projects',
      'ideas',
      'skills',
      externalAppItemId('media-agent'),
      'chat-reviews',
      'system-reports',
    ])
    expect(router.getRoutes().find((route) => route.path === '/media-agent')?.redirect).toBe(
      '/apps/media-agent',
    )
    const route = router.getRoutes().find((item) => item.name === 'external-app')
    expect(route?.path).toBe('/apps/:appId')
    expect(route?.meta.tabKind).toBe('external')
  })

  it('历史点子归并页保留兼容路由但不进入默认菜单', () => {
    expect(MODULES.ideas.path).toBe('/ideas')
    expect(NAV_GROUPS.find((group) => group.label === '公共')?.items).toContain('ideas')
    expect(DEFAULT_BUILTIN_MENUS.map((menu) => menu.id)).not.toContain('ideas')
    expect(router.resolve('/ideas').meta.module).toBe('ideas')
    expect(router.getRoutes().some((route) => route.path.startsWith('/s/i'))).toBe(false)
  })

  it('聊天复盘作为双方共享模块位于公共组', () => {
    expect(NAV_GROUPS.find((group) => group.label === '公共')?.items).toContain('chat-reviews')
    expect(PERSONAL_ITEMS).not.toContain('chat-reviews')
    const route = router.getRoutes().find((item) => item.name === 'chat-reviews')
    expect(route?.path).toBe('/chat-reviews')
    expect(route?.meta.module).toBe('chat-reviews')
  })

  it('系统报告作为团队共享模块提供日周月切换入口', () => {
    expect(NAV_GROUPS.find((group) => group.label === '公共')?.items).toContain('system-reports')
    const route = router.getRoutes().find((item) => item.name === 'system-reports')
    expect(route?.path).toBe('/system-reports')
    expect(route?.meta.module).toBe('system-reports')
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
        openMode: 'NEW_PAGE',
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
        openMode: 'TAB',
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

  it('请求监控默认位于工具组并以独立 Tab 打开', () => {
    expect(NAV_GROUPS.find((group) => group.label === '工具')?.items).toContain(
      'request-monitoring',
    )
    const route = router.getRoutes().find((item) => item.name === 'request-monitoring')
    expect(route?.path).toBe('/request-monitoring')
    expect(route?.meta.module).toBe('request-monitoring')
    expect(route?.meta.tabKind).toBe('module')
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

  it('单词本默认位于个人组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).toContain('vocabulary')
    const route = router.getRoutes().find((item) => item.name === 'vocabulary')
    expect(route?.path).toBe('/vocabulary')
    expect(route?.meta.module).toBe('vocabulary')
  })

  it('工作台默认位于个人组并使用成员私有空间路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).toContain('workspaces')
    const route = router.getRoutes().find((item) => item.name === 'workspaces')
    expect(route?.path).toBe('/workspaces')
    expect(route?.meta.module).toBe('workspaces')
  })

  it('英语训练默认位于个人组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).toContain('english-learning')
    const route = router.getRoutes().find((item) => item.name === 'english-learning')
    expect(route?.path).toBe('/english-learning')
    expect(route?.meta.module).toBe('english-learning')
  })

  it('周报生成默认位于个人组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).toContain('weekly-report')
    const route = router.getRoutes().find((item) => item.name === 'weekly-report')
    expect(route?.path).toBe('/weekly-report')
    expect(route?.meta.module).toBe('weekly-report')
  })

  it('今日节奏默认位于个人组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).toContain('workday-rhythm')
    const route = router.getRoutes().find((item) => item.name === 'workday-rhythm')
    expect(route?.path).toBe('/workday-rhythm')
    expect(route?.meta.module).toBe('workday-rhythm')
  })

  it('人生回顾默认位于个人组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).toContain('life-review')
    const route = router.getRoutes().find((item) => item.name === 'life-review')
    expect(route?.path).toBe('/life-review')
    expect(route?.meta.module).toBe('life-review')
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
