import type { ModuleId, Tab } from '@/types'
import type { RouteLocationNormalized } from 'vue-router'

/** 模块元数据 —— 图标 / 标签 / 路由，源自原型 vGlobal() 的 pages/icons/groups。 */
export interface ModuleMeta {
  id: NavItemId
  label: string
  icon: string
  path: string
  /** 需要脱离 WAO 主壳时使用的公开地址；未配置则沿用 path。 */
  newPagePath?: string
}

export type MenuOpenMode = 'TAB' | 'NEW_PAGE'
export type MenuGroupCode = 'PUBLIC' | 'PERSONAL' | 'TOOLS'
export type BuiltinMenuGroupCode = 'ROOT' | MenuGroupCode
export type ExternalAppItemId = `external:${string}`
export type NavItemId = ModuleId | ExternalAppItemId

/** 可由系统设置注册的外部应用；菜单和通用嵌入页都由这份配置驱动。 */
export interface ExternalAppConfig {
  id: string
  label: string
  icon: string
  group: MenuGroupCode
  order: number
  visible: boolean
  appUrl: string
  healthUrl: string
  openMode: MenuOpenMode
}

export interface ExternalAppRegistry {
  version: number
  apps: ExternalAppConfig[]
}

export interface BuiltinMenuConfig {
  id: Exclude<ModuleId, 'settings'>
  label: string
  icon: string
  group: BuiltinMenuGroupCode
  order: number
  visible: boolean
  openMode: MenuOpenMode
}

export interface BuiltinMenuRegistry {
  version: number
  menus: BuiltinMenuConfig[]
}

export function normalizeMenuOpenMode(value: unknown): MenuOpenMode {
  return value === 'NEW_PAGE' ? 'NEW_PAGE' : 'TAB'
}

export function normalizeExternalApp(app: ExternalAppConfig): ExternalAppConfig {
  return { ...app, openMode: normalizeMenuOpenMode(app.openMode) }
}

export function normalizeBuiltinMenu(menu: BuiltinMenuConfig): BuiltinMenuConfig {
  return { ...menu, openMode: normalizeMenuOpenMode(menu.openMode) }
}

export const DEFAULT_EXTERNAL_APPS: ExternalAppConfig[] = [
  {
    id: 'media-agent',
    label: '自媒体 Agent',
    icon: 'ri-sparkling-2-line',
    group: 'PUBLIC',
    order: 30,
    visible: true,
    appUrl: '/agent/',
    healthUrl: '/api/agent/health',
    openMode: 'TAB',
  },
]

export const MODULES: Record<ModuleId, ModuleMeta> = {
  home: { id: 'home', label: '首页', icon: 'ri-home-5-line', path: '/' },
  projects: { id: 'projects', label: '项目', icon: 'ri-stack-line', path: '/projects' },
  ideas: { id: 'ideas', label: '点子库', icon: 'ri-lightbulb-flash-line', path: '/ideas' },
  skills: {
    id: 'skills',
    label: 'Skill 市场',
    icon: 'ri-sparkling-2-line',
    path: '/wao/skills',
    newPagePath: '/skills',
  },
  calendar: { id: 'calendar', label: '日历', icon: 'ri-calendar-2-line', path: '/calendar' },
  todo: { id: 'todo', label: '待办', icon: 'ri-checkbox-multiple-line', path: '/todo' },
  knowledge: { id: 'knowledge', label: '知识库', icon: 'ri-book-2-line', path: '/knowledge' },
  files: { id: 'files', label: '文件与图床', icon: 'ri-hard-drive-2-line', path: '/files' },
  automation: {
    id: 'automation',
    label: '自动化',
    icon: 'ri-flashlight-line',
    path: '/automation',
  },
  games: { id: 'games', label: '游戏厅', icon: 'ri-gamepad-line', path: '/games' },
  drawings: { id: 'drawings', label: '草图', icon: 'ri-draft-line', path: '/drawings' },
  vocabulary: {
    id: 'vocabulary',
    label: '单词本',
    icon: 'ri-book-marked-line',
    path: '/vocabulary',
  },
  'english-learning': {
    id: 'english-learning',
    label: '英语训练',
    icon: 'ri-speak-line',
    path: '/english-learning',
  },
  tianren: { id: 'tianren', label: '天人一卦', icon: 'ri-compass-3-line', path: '/tianren' },
  'workday-rhythm': {
    id: 'workday-rhythm',
    label: '今日节奏',
    icon: 'ri-timer-flash-line',
    path: '/workday-rhythm',
  },
  'action-plans': {
    id: 'action-plans',
    label: '行动计划',
    icon: 'ri-footprint-line',
    path: '/action-plans',
  },
  'weekly-report': {
    id: 'weekly-report',
    label: '周报生成',
    icon: 'ri-file-list-3-line',
    path: '/weekly-report',
  },
  'life-review': {
    id: 'life-review',
    label: '人生回顾',
    icon: 'ri-route-line',
    path: '/life-review',
  },
  'chat-reviews': {
    id: 'chat-reviews',
    label: '聊天复盘',
    icon: 'ri-chat-history-line',
    path: '/chat-reviews',
  },
  memory: { id: 'memory', label: '个人记忆', icon: 'ri-brain-line', path: '/memory' },
  workspaces: {
    id: 'workspaces',
    label: '工作台',
    icon: 'ri-layout-grid-line',
    path: '/workspaces',
  },
  vault: { id: 'vault', label: '密码本', icon: 'ri-key-2-line', path: '/vault' },
  settings: { id: 'settings', label: '设置', icon: 'ri-settings-3-line', path: '/settings' },
}
export const DEFAULT_BUILTIN_MENUS: BuiltinMenuConfig[] = (
  [
    { id: 'home', label: '首页', icon: 'ri-home-5-line', group: 'ROOT', order: 10, visible: true },
    {
      id: 'projects',
      label: '项目',
      icon: 'ri-stack-line',
      group: 'PUBLIC',
      order: 10,
      visible: true,
    },
    {
      id: 'ideas',
      label: '点子库',
      icon: 'ri-lightbulb-flash-line',
      group: 'PUBLIC',
      order: 11,
      visible: true,
    },
    {
      id: 'skills',
      label: 'Skill 市场',
      icon: 'ri-sparkling-2-line',
      group: 'PUBLIC',
      order: 12,
      visible: true,
    },
    {
      id: 'chat-reviews',
      label: '聊天复盘',
      icon: 'ri-chat-history-line',
      group: 'PUBLIC',
      order: 15,
      visible: true,
    },
    {
      id: 'todo',
      label: '待办',
      icon: 'ri-checkbox-multiple-line',
      group: 'PERSONAL',
      order: 10,
      visible: true,
    },
    {
      id: 'action-plans',
      label: '行动计划',
      icon: 'ri-footprint-line',
      group: 'PERSONAL',
      order: 15,
      visible: true,
    },
    {
      id: 'workday-rhythm',
      label: '今日节奏',
      icon: 'ri-timer-flash-line',
      group: 'PERSONAL',
      order: 12,
      visible: true,
    },
    {
      id: 'calendar',
      label: '日历',
      icon: 'ri-calendar-2-line',
      group: 'PERSONAL',
      order: 20,
      visible: true,
    },
    {
      id: 'weekly-report',
      label: '周报生成',
      icon: 'ri-file-list-3-line',
      group: 'PERSONAL',
      order: 25,
      visible: true,
    },
    {
      id: 'life-review',
      label: '人生回顾',
      icon: 'ri-route-line',
      group: 'PERSONAL',
      order: 27,
      visible: true,
    },
    {
      id: 'memory',
      label: '个人记忆',
      icon: 'ri-brain-line',
      group: 'PERSONAL',
      order: 29,
      visible: true,
    },
    {
      id: 'workspaces',
      label: '工作台',
      icon: 'ri-layout-grid-line',
      group: 'PERSONAL',
      order: 28,
      visible: true,
    },
    {
      id: 'knowledge',
      label: '知识库',
      icon: 'ri-book-2-line',
      group: 'PERSONAL',
      order: 30,
      visible: true,
    },
    {
      id: 'english-learning',
      label: '英语训练',
      icon: 'ri-speak-line',
      group: 'PERSONAL',
      order: 35,
      visible: true,
    },
    {
      id: 'vocabulary',
      label: '单词本',
      icon: 'ri-book-marked-line',
      group: 'PERSONAL',
      order: 40,
      visible: true,
    },
    {
      id: 'drawings',
      label: '草图',
      icon: 'ri-draft-line',
      group: 'PERSONAL',
      order: 50,
      visible: true,
    },
    {
      id: 'vault',
      label: '密码本',
      icon: 'ri-key-2-line',
      group: 'PERSONAL',
      order: 60,
      visible: true,
    },
    {
      id: 'tianren',
      label: '天人一卦',
      icon: 'ri-compass-3-line',
      group: 'PERSONAL',
      order: 70,
      visible: true,
    },
    {
      id: 'automation',
      label: '自动化',
      icon: 'ri-flashlight-line',
      group: 'TOOLS',
      order: 10,
      visible: true,
    },
    {
      id: 'files',
      label: '文件与图床',
      icon: 'ri-hard-drive-2-line',
      group: 'TOOLS',
      order: 20,
      visible: true,
    },
    {
      id: 'games',
      label: '游戏厅',
      icon: 'ri-gamepad-line',
      group: 'TOOLS',
      order: 30,
      visible: true,
    },
  ] as Omit<BuiltinMenuConfig, 'openMode'>[]
).map((menu) => ({ ...menu, openMode: 'TAB' }))

/** 「个人」组的默认顺序；成员拖拽后的自定义顺序存服务端偏好，见 stores/nav.ts。 */
export const PERSONAL_ITEMS: ModuleId[] = [
  'todo',
  'workday-rhythm',
  'action-plans',
  'calendar',
  'weekly-report',
  'life-review',
  'workspaces',
  'memory',
  'knowledge',
  'english-learning',
  'vocabulary',
  'drawings',
  'vault',
  'tianren',
]

const BASE_NAV_GROUPS: { label: string; items: ModuleId[] }[] = [
  { label: '', items: ['home'] },
  { label: '公共', items: ['projects', 'ideas', 'skills', 'chat-reviews'] },
  { label: '个人', items: PERSONAL_ITEMS },
  { label: '工具', items: ['automation', 'files', 'games'] },
]

const GROUP_LABELS: Record<MenuGroupCode, string> = {
  PUBLIC: '公共',
  PERSONAL: '个人',
  TOOLS: '工具',
}

/**
 * 合成当前用户真正看到的导航：个人模块沿用成员自己的拖拽顺序，外部应用使用全局注册表。
 * 排序值越小越靠前；相同值保持内置菜单在前，避免旧配置升级后发生无意义跳动。
 */
export function resolveNavGroups(
  personalOrder: ModuleId[],
  externalApps: ExternalAppConfig[],
  builtinMenus?: BuiltinMenuConfig[],
): { label: string; items: NavItemId[] }[] {
  const groups = builtinMenus
    ? ['', '公共', '个人', '工具'].map((label) => ({
        label,
        items: builtinMenus
          .filter(
            (menu) =>
              menu.visible &&
              { ROOT: '', PUBLIC: '公共', PERSONAL: '个人', TOOLS: '工具' }[menu.group] === label,
          )
          .sort((a, b) => a.order - b.order)
          .map((menu) => menu.id) as NavItemId[],
      }))
    : BASE_NAV_GROUPS.map((group) => ({
        label: group.label,
        items: (group.label === '个人' ? [...personalOrder] : [...group.items]) as NavItemId[],
      }))

  for (const target of groups) {
    const existingOrders = new Map(
      target.items.map((id, index) => [
        id,
        builtinMenus?.find((menu) => menu.id === id)?.order ?? (index + 1) * 10,
      ]),
    )
    const configured = externalApps.filter(
      (app) => app.visible && GROUP_LABELS[app.group] === target.label,
    )
    target.items.push(...configured.map((app) => externalAppItemId(app.id)))
    const appOrders = new Map(configured.map((app) => [externalAppItemId(app.id), app.order]))
    target.items.sort(
      (left, right) =>
        (appOrders.get(left as ExternalAppItemId) ?? existingOrders.get(left) ?? 999) -
        (appOrders.get(right as ExternalAppItemId) ?? existingOrders.get(right) ?? 999),
    )
  }
  return groups
}

export function externalAppItemId(id: string): ExternalAppItemId {
  return `external:${id}`
}

export function externalAppIdFromItem(id: NavItemId | undefined): string | null {
  return id?.startsWith('external:') ? id.slice('external:'.length) : null
}

export function externalAppPath(id: string): string {
  return `/apps/${id}`
}

/** 左侧导航默认分组；首次加载注册表前也保证 Agent 位于「公共」。 */
export const NAV_GROUPS = resolveNavGroups(PERSONAL_ITEMS, DEFAULT_EXTERNAL_APPS)

/** 由模块 id 生成页签。首页固定不可关闭。 */
export function moduleTab(id: ModuleId): Tab {
  const m = MODULES[id]
  return { key: id, label: m.label, icon: m.icon, path: m.path, closable: id !== 'home' }
}

/**
 * 由当前路由推导页签描述。模块路由用 MODULES；项目 / 知识库详情为动态页签，
 * 标签先用 id 占位，详情视图加载实体后调用 tabs.setLabel 回填。
 */
export function tabFromRoute(route: RouteLocationNormalized): Tab | null {
  const kind = route.meta.tabKind as string | undefined
  if (kind === 'project') {
    const id = String(route.params.id)
    return {
      key: `proj:${id}`,
      label: `项目 ${id}`,
      labelPlaceholder: true,
      icon: 'ri-stack-line',
      path: route.path,
      closable: true,
    }
  }
  if (kind === 'kb') {
    const id = String(route.params.id)
    return {
      key: `kb:${id}`,
      label: `知识库 ${id}`,
      labelPlaceholder: true,
      icon: 'ri-book-2-line',
      path: route.path,
      closable: true,
    }
  }
  if (kind === 'external') {
    const id = String(route.params.appId)
    return {
      key: externalAppItemId(id),
      label: '外部应用',
      labelPlaceholder: true,
      icon: 'ri-apps-2-line',
      path: route.path,
      closable: true,
    }
  }
  const mod = route.meta.module as ModuleId | undefined
  if (mod) {
    const tab = moduleTab(mod)
    // Skill 市场有站内子路由；复用同一页签时保留详情、发布或管理页的位置。
    if (mod === 'skills' && route.path.startsWith('/wao/skills')) tab.path = route.fullPath
    return tab
  }
  return null
}
