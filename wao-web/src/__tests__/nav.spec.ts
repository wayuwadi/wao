import { describe, expect, it } from 'vitest'
import {
  DEFAULT_EXTERNAL_APPS,
  DEFAULT_BUILTIN_MENUS,
  MODULES,
  NAV_GROUPS,
  PERSONAL_ITEMS,
  externalAppItemId,
  resolveNavGroups,
  resolveDockItems,
  tabFromRoute,
} from '@/config/nav'
import router from '@/router'
import type { ModuleId } from '@/types'
import type { RouteLocationNormalized } from 'vue-router'

describe('侧边栏导航配置', () => {
  it('知识库页签保留所选文档，切回后继续编辑同一篇', () => {
    expect(
      tabFromRoute(router.resolve('/kb/kb-notes?doc=d1') as RouteLocationNormalized)?.path,
    ).toBe('/kb/kb-notes?doc=d1')
  })
  it('首页后固定提供全量导航入口，默认侧栏只保留高频功能', () => {
    expect(NAV_GROUPS.find((group) => group.label === '')?.items).toEqual([
      'home',
      'notes',
      'navigation',
    ])
    expect(MODULES.navigation.path).toBe('/navigation')
    expect(DEFAULT_BUILTIN_MENUS.find((menu) => menu.id === 'navigation')).toMatchObject({
      group: 'ROOT',
      visible: true,
    })
    expect(DEFAULT_BUILTIN_MENUS.find((menu) => menu.id === 'todo')?.visible).toBe(true)
    expect(DEFAULT_BUILTIN_MENUS.map((menu) => menu.id)).not.toContain('games')
  })

  it('下线模块不出现在菜单和路由中', () => {
    const retired = ['personal-principles', 'crawler-capabilities', 'account-resources',
      'infrastructure', 'ledger', 'english-learning', 'vocabulary', 'food', 'weekly-report',
      'devices', 'games', 'data-center', 'chatgpt', 'appearance', 'memory', 'ai-sessions',
      'updates', 'action-plans', 'workspaces', 'decisions', 'workday-rhythm']
    const menus = DEFAULT_BUILTIN_MENUS.map((menu) => menu.id)
    const groups = NAV_GROUPS.flatMap((group) => group.items)
    for (const id of retired) {
      expect(menus).not.toContain(id)
      expect(groups).not.toContain(id)
      expect(router.getRoutes().some((route) => route.meta.module === id)).toBe(false)
    }
  })

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
    expect(DEFAULT_EXTERNAL_APPS.find((app) => app.id === 'media-agent')).toBeUndefined()
    expect(DEFAULT_EXTERNAL_APPS.find((app) => app.id === 'getnote')).toMatchObject({
      label: '得到大脑',
      appUrl: 'https://www.biji.com/',
      openMode: 'NEW_PAGE',
    })
    expect(DEFAULT_EXTERNAL_APPS.find((app) => app.id === 'jumpserver')).toBeUndefined()
  })

  it('Skill 市场同时保留公开路由和 WAO 页签路由', () => {
    expect(MODULES.skills.path).toBe('/wao/hub')
    expect(MODULES.skills.newPagePath).toBe('/hub')
    expect(router.getRoutes().find((route) => route.name === 'skill-market')?.path).toBe('/skills')
    const inApp = router.getRoutes().find((route) => route.name === 'wao-skill-market')
    expect(inApp?.path).toBe('/wao/skills')
    expect(router.resolve('/wao/hub').meta.module).toBe('skills')
    expect(router.resolve('/pets').meta.public).toBe(true)
    expect(router.resolve('/pets/create').meta.public).toBe(false)
    expect(router.resolve('/wao/skills').meta.module).toBe('skills')
    expect(router.resolve('/wao/skills').meta.tabKind).toBe('module')
    expect(
      tabFromRoute(router.resolve('/wao/skills/manage') as RouteLocationNormalized)?.path,
    ).toBe('/wao/skills/manage')
  })

  it('Skill 接入作为独立公共菜单打开真实管理入口', () => {
    expect(MODULES['skill-connections']).toMatchObject({
      label: 'Skill 接入',
      path: '/skill-connections',
    })
    expect(DEFAULT_BUILTIN_MENUS.find((menu) => menu.id === 'skill-connections')).toMatchObject({
      group: 'PUBLIC',
      visible: false,
      openMode: 'TAB',
    })
    expect(NAV_GROUPS.find((group) => group.label === '公共')?.items).not.toContain(
      'skill-connections',
    )
    expect(router.resolve('/skill-connections').meta).toMatchObject({
      module: 'skill-connections',
      tabKind: 'module',
    })
  })


  it('公共组保留活跃应用且 Agent 旧地址不恢复功能', () => {
    const publicGroup = NAV_GROUPS.find((group) => group.label === '公共')
    expect(publicGroup?.items).toEqual([
      'projects',
      'changelog',
      externalAppItemId('weknora'),
    ])
    expect(router.resolve('/media-agent').meta.module).toBeUndefined()
    const route = router.getRoutes().find((item) => item.name === 'external-app')
    expect(route?.path).toBe('/apps/:appId')
    expect(route?.meta.tabKind).toBe('external')
  })


  it('功能探索使用独立入口，旧更新日志地址重定向且保留真实更新日记', () => {
    expect(MODULES.changelog).toMatchObject({ label: '功能探索', path: '/features' })
    expect(router.resolve('/features').meta).toMatchObject({
      module: 'changelog',
      tabKind: 'module',
    })
    expect(router.resolve('/features').meta.public).not.toBe(true)
    expect(router.getRoutes().find((route) => route.path === '/changelog')?.redirect).toBe(
      '/features',
    )
    expect(router.getRoutes().some((route) => route.path === '/updates')).toBe(false)
  })

  it('聊天从菜单和页签退休，旧地址改为打开首页悬浮框', () => {
    expect(NAV_GROUPS.find((group) => group.label === '公共')?.items).not.toContain('chat')
    expect(DEFAULT_BUILTIN_MENUS.map((menu) => menu.id)).not.toContain('chat')
    const route = router.getRoutes().find((item) => item.name === 'chat')
    expect(route?.path).toBe('/chat')
    expect(typeof route?.redirect).toBe('function')
    expect(
      (route!.redirect as (to: { query: Record<string, string> }) => unknown)({ query: {} }),
    ).toEqual({ path: '/', query: { chat: '1' }, replace: true })
  })

  it('历史点子归并页保留兼容路由但不进入默认菜单', () => {
    expect(MODULES.ideas.path).toBe('/ideas')
    expect(NAV_GROUPS.find((group) => group.label === '公共')?.items).not.toContain('ideas')
    expect(DEFAULT_BUILTIN_MENUS.map((menu) => menu.id)).not.toContain('ideas')
    expect(router.resolve('/ideas').meta.module).toBe('ideas')
    expect(router.getRoutes().some((route) => route.path.startsWith('/s/i'))).toBe(false)
  })

  it('聊天复盘入口融合进聊天页并保留旧地址跳转', () => {
    expect(NAV_GROUPS.find((group) => group.label === '公共')?.items).not.toContain('chat-reviews')
    expect(DEFAULT_BUILTIN_MENUS.map((menu) => menu.id)).not.toContain('chat-reviews')
    expect(PERSONAL_ITEMS).not.toContain('chat-reviews')
    const route = router.getRoutes().find((item) => item.name === 'chat-reviews')
    expect(route?.path).toBe('/chat-reviews')
    expect(
      (route!.redirect as (to: { query: Record<string, string> }) => unknown)({ query: {} }),
    ).toEqual({ path: '/', query: { chat: '1', review: '1' }, replace: true })
  })

  it('系统报告从首页进入但保留兼容路由', () => {
    expect(DEFAULT_BUILTIN_MENUS.map((menu) => menu.id)).not.toContain('system-reports')
    expect(NAV_GROUPS.flatMap((group) => group.items)).not.toContain('system-reports')
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

    expect(groups.find((group) => group.label === '个人')?.items).toContain(externalAppItemId('getnote'))
    expect(groups.flatMap((group) => group.items)).not.toContain('games')
    expect(groups.find((group) => group.label === '工具')?.items[0]).toBe(
      externalAppItemId('data-studio'),
    )
    expect(groups.flatMap((group) => group.items)).not.toContain(externalAppItemId('hidden-app'))
  })

  it('底部停靠菜单不在分组中重复出现，并按同一注册表顺序合成图标', () => {
    const builtin = DEFAULT_BUILTIN_MENUS.map((menu) =>
      menu.id === 'projects' ? { ...menu, placement: 'DOCK' as const, order: 20 } : menu,
    )
    const external = DEFAULT_EXTERNAL_APPS.map((app) =>
      app.id === 'getnote' ? { ...app, placement: 'DOCK' as const, order: 10 } : app,
    )

    const groups = resolveNavGroups(PERSONAL_ITEMS, external, builtin)
    expect(groups.flatMap((group) => group.items)).not.toContain('projects')
    expect(groups.flatMap((group) => group.items)).not.toContain(externalAppItemId('getnote'))
    expect(resolveDockItems(external, builtin)).toEqual([
      externalAppItemId('getnote'),
      'projects',
    ])
  })

  it('回收站仍只保留知识库内入口', () => {
    const ids = NAV_GROUPS.flatMap((group) => group.items)
    expect(ids).not.toContain('trash')
    expect(router.getRoutes().some((route) => route.name === 'trash')).toBe(false)
    expect(router.getRoutes().some((route) => route.name === 'knowledge')).toBe(true)
  })


  it('请求监控默认位于工具组并以独立 Tab 打开', () => {
    expect(NAV_GROUPS.find((group) => group.label === '工具')?.items).not.toContain(
      'request-monitoring',
    )
    const route = router.getRoutes().find((item) => item.name === 'request-monitoring')
    expect(route?.path).toBe('/request-monitoring')
    expect(route?.meta.module).toBe('request-monitoring')
    expect(route?.meta.tabKind).toBe('module')
  })

  it('使用统计默认位于工具组并复用真实用量仪表盘', () => {
    expect(MODULES.dashboard).toMatchObject({
      label: '使用统计',
      path: '/dashboard',
    })
    expect(DEFAULT_BUILTIN_MENUS.find((menu) => menu.id === 'dashboard')).toMatchObject({
      group: 'TOOLS',
      visible: false,
      openMode: 'TAB',
    })
    expect(NAV_GROUPS.find((group) => group.label === '工具')?.items).not.toContain('dashboard')
    expect(router.resolve('/dashboard').meta).toMatchObject({
      module: 'dashboard',
      tabKind: 'module',
    })
    expect(tabFromRoute(router.resolve('/dashboard') as RouteLocationNormalized)).toMatchObject({
      key: 'dashboard',
      label: '使用统计',
      path: '/dashboard',
    })
  })

  it('天人一卦默认位于个人组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).not.toContain('tianren')
    const route = router.getRoutes().find((item) => item.name === 'tianren')
    expect(route?.path).toBe('/tianren')
    expect(route?.meta.module).toBe('tianren')
  })




  it('草图默认位于个人组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).not.toContain('drawings')
    const route = router.getRoutes().find((item) => item.name === 'drawings')
    expect(route?.path).toBe('/drawings')
    expect(route?.meta.module).toBe('drawings')
  })






  it('人生回顾默认位于个人组并使用原生模块路由', () => {
    expect(NAV_GROUPS.find((group) => group.label === '个人')?.items).not.toContain('life-review')
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
