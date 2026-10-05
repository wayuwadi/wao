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
export type MenuPlacement = 'MENU' | 'DOCK' | 'TOP'
export type MenuGroupCode = string
export type BuiltinMenuGroupCode = 'ROOT' | MenuGroupCode
export type ExternalAppItemId = `external:${string}`
export type NavItemId = ModuleId | ExternalAppItemId
/** 已全站下线的独立功能；旧个人布局和历史页签不得使入口重新出现。 */
export const RETIRED_MODULE_IDS = new Set<ModuleId>([
  'personal-principles', 'crawler-capabilities', 'account-resources', 'infrastructure',
  'ledger', 'english-learning', 'vocabulary', 'food', 'weekly-report', 'devices',
  'games', 'data-center', 'chatgpt', 'appearance', 'memory', 'ai-sessions',
  'life-review', 'system-reports', 'ideas', 'skills', 'skill-connections',
  'updates', 'action-plans', 'workspaces', 'decisions', 'workday-rhythm', 'pet-world',
])
export const RETIRED_EXTERNAL_APP_IDS = new Set(['media-agent', 'jumpserver', 'weknora'])
export type MobileActionItemId = 'action:note'
export type MobileNavItemId = NavItemId | MobileActionItemId

/** 手机底栏只保留高频动作；固定的“更多”不计入成员可配置名额。 */
export const MOBILE_NAV_MAX_ITEMS = 4
export const DEFAULT_MOBILE_NAV_ITEMS: MobileNavItemId[] = ['projects', 'action:note']

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
  placement?: MenuPlacement
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
  placement?: MenuPlacement
}

export interface BuiltinMenuRegistry {
  version: number
  menus: BuiltinMenuConfig[]
}

export function normalizeMenuOpenMode(value: unknown): MenuOpenMode {
  return value === 'NEW_PAGE' ? 'NEW_PAGE' : 'TAB'
}

export function normalizeMenuPlacement(value: unknown): MenuPlacement {
  return value === 'DOCK' || value === 'TOP' ? value : 'MENU'
}

export function normalizeExternalApp(app: ExternalAppConfig): ExternalAppConfig {
  return {
    ...app,
    openMode: normalizeMenuOpenMode(app.openMode),
    placement: normalizeMenuPlacement(app.placement),
  }
}

export function normalizeBuiltinMenu(menu: BuiltinMenuConfig): BuiltinMenuConfig {
  return {
    ...menu,
    label: menu.id === 'changelog' && menu.label === '功能探索' ? '版本更新' : menu.label,
    openMode: normalizeMenuOpenMode(menu.openMode),
    placement: normalizeMenuPlacement(menu.placement),
  }
}

export const DEFAULT_EXTERNAL_APPS: ExternalAppConfig[] = [
  {
    id: 'getnote',
    label: '得到大脑',
    icon: 'ri-video-upload-line',
    group: 'TOOLS',
    order: 10,
    visible: true,
    appUrl: 'https://www.biji.com/',
    healthUrl: '',
    openMode: 'NEW_PAGE',
  },
]

export const MODULES: Record<ModuleId, ModuleMeta> = {
  chatgpt: { id: 'chatgpt', label: 'ChatGPT对话', icon: 'ri-openai-line', path: '/chatgpt' },
  'ai-sessions': {
    id: 'ai-sessions',
    label: 'AI 会话',
    icon: 'ri-chat-history-line',
    path: '/ai-sessions',
  },
  notes: { id: 'notes', label: '小记', icon: 'ri-sticky-note-line', path: '/notes' },
  home: { id: 'home', label: '首页', icon: 'ri-home-5-line', path: '/' },
  navigation: {
    id: 'navigation',
    label: '全部功能',
    icon: 'ri-compass-discover-line',
    path: '/navigation',
  },
  updates: { id: 'updates', label: '更新日记', icon: 'ri-history-line', path: '/updates' },
  changelog: {
    id: 'changelog',
    label: '版本更新',
    icon: 'ri-compass-discover-line',
    path: '/features',
  },
  projects: { id: 'projects', label: '项目', icon: 'ri-stack-line', path: '/projects' },
  ideas: { id: 'ideas', label: '点子库', icon: 'ri-lightbulb-flash-line', path: '/ideas' },
  capabilities: { id: 'capabilities', label: '能力中心', icon: 'ri-box-3-line', path: '/capabilities' },
  skills: {
    id: 'skills',
    label: 'WAO-HUB',
    icon: 'ri-sparkling-2-line',
    path: '/wao/hub',
    newPagePath: '/hub',
  },
  'skill-connections': {
    id: 'skill-connections',
    label: 'Skill 接入',
    icon: 'ri-apps-2-line',
    path: '/skill-connections',
  },
  calendar: { id: 'calendar', label: '日历', icon: 'ri-calendar-2-line', path: '/calendar' },
  todo: { id: 'todo', label: '待办', icon: 'ri-checkbox-multiple-line', path: '/todo' },
  knowledge: { id: 'knowledge', label: '知识库', icon: 'ri-book-2-line', path: '/knowledge' },
  files: { id: 'files', label: '文件与图床', icon: 'ri-hard-drive-2-line', path: '/files' },
  'reference-images': {
    id: 'reference-images',
    label: '参考图库',
    icon: 'ri-image-2-line',
    path: '/reference-images',
  },
  automation: {
    id: 'automation',
    label: '自动化',
    icon: 'ri-flashlight-line',
    path: '/automation',
  },
  'crawler-capabilities': {
    id: 'crawler-capabilities',
    label: '爬虫能力',
    icon: 'ri-code-box-line',
    path: '/crawler-capabilities',
  },
  'account-resources': {
    id: 'account-resources',
    label: '账号资源库',
    icon: 'ri-contacts-book-2-line',
    path: '/account-resources',
  },
  games: { id: 'games', label: '游戏厅', icon: 'ri-gamepad-line', path: '/games' },
  'pet-world': {
    id: 'pet-world',
    label: '松风庭院',
    icon: 'ri-landscape-line',
    path: '/pet-world',
  },
  infrastructure: {
    id: 'infrastructure',
    label: '基础设施',
    icon: 'ri-server-line',
    path: '/infrastructure',
  },
  devices: {
    id: 'devices',
    label: '终端设备',
    icon: 'ri-webcam-line',
    path: '/devices',
  },
  'request-monitoring': {
    id: 'request-monitoring',
    label: '请求监控',
    icon: 'ri-pulse-line',
    path: '/request-monitoring',
  },
  'data-center': {
    id: 'data-center',
    label: '数据中心',
    icon: 'ri-database-2-line',
    path: '/data-center',
  },
  'text-polish': {
    id: 'text-polish',
    label: '润色',
    icon: 'ri-magic-line',
    path: '/text-polish',
  },
  dashboard: {
    id: 'dashboard',
    label: '使用统计',
    icon: 'ri-bar-chart-box-line',
    path: '/dashboard',
  },
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
  'personal-principles': {
    id: 'personal-principles',
    label: '我的准则',
    icon: 'ri-shield-check-line',
    path: '/personal-principles',
  },
  decisions: {
    id: 'decisions',
    label: '决策管理',
    icon: 'ri-route-line',
    path: '/decisions',
  },
  appearance: {
    id: 'appearance',
    label: '电子衣库',
    icon: 'ri-shirt-line',
    path: '/appearance',
  },
  food: {
    id: 'food',
    label: '饮食管理',
    icon: 'ri-restaurant-2-line',
    path: '/food',
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
  chat: { id: 'chat', label: '聊天', icon: 'ri-chat-3-line', path: '/chat' },
  'chat-reviews': {
    id: 'chat-reviews',
    label: '聊天复盘',
    icon: 'ri-chat-history-line',
    path: '/chat-reviews',
  },
  'system-reports': {
    id: 'system-reports',
    label: '系统报告',
    icon: 'ri-bar-chart-box-line',
    path: '/system-reports',
  },
  memory: { id: 'memory', label: '记忆中心', icon: 'ri-brain-line', path: '/memories' },
  workspaces: {
    id: 'workspaces',
    label: '链接空间',
    icon: 'ri-layout-grid-line',
    path: '/workspaces',
  },
  vault: { id: 'vault', label: '密码本', icon: 'ri-key-2-line', path: '/vault' },
  ledger: { id: 'ledger', label: '账务本', icon: 'ri-money-cny-circle-line', path: '/ledger' },
  settings: { id: 'settings', label: '设置', icon: 'ri-settings-3-line', path: '/settings' },
}
export const DEFAULT_BUILTIN_MENUS: BuiltinMenuConfig[] = (
  [
    { id: 'home', label: '首页', icon: 'ri-home-5-line', group: 'ROOT', order: 10, visible: true },
    {
      id: 'notes',
      label: '小记',
      icon: 'ri-sticky-note-line',
      group: 'ROOT',
      order: 15,
      visible: true,
    },
    {
      id: 'navigation',
      label: '全部功能',
      icon: 'ri-compass-discover-line',
      group: 'ROOT',
      order: 20,
      visible: true,
    },
    {
      id: 'projects',
      label: '项目',
      icon: 'ri-stack-line',
      group: 'PUBLIC',
      order: 10,
      visible: true,
    },
    {
      id: 'skills',
      label: 'WAO-HUB',
      icon: 'ri-sparkling-2-line',
      group: 'PUBLIC',
      order: 12,
      visible: false,
    },
    {
      id: 'changelog',
      label: '版本更新',
      icon: 'ri-compass-discover-line',
      group: 'PUBLIC',
      order: 14,
      visible: false,
    },
    {
      id: 'skill-connections',
      label: 'Skill 接入',
      icon: 'ri-apps-2-line',
      group: 'PUBLIC',
      order: 13,
      visible: false,
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
      id: 'decisions',
      label: '决策管理',
      icon: 'ri-route-line',
      group: 'PERSONAL',
      order: 16,
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
      id: 'life-review',
      label: '人生回顾',
      icon: 'ri-route-line',
      group: 'PERSONAL',
      order: 27,
      visible: false,
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
      id: 'reference-images',
      label: '参考图库',
      icon: 'ri-image-2-line',
      group: 'PERSONAL',
      order: 32,
      visible: true,
    },
    {
      id: 'drawings',
      label: '草图',
      icon: 'ri-draft-line',
      group: 'PERSONAL',
      order: 50,
      visible: false,
    },
    {
      id: 'vault',
      label: '密码本',
      icon: 'ri-key-2-line',
      group: 'PERSONAL',
      order: 60,
      visible: false,
    },
    {
      id: 'tianren',
      label: '天人一卦',
      icon: 'ri-compass-3-line',
      group: 'PERSONAL',
      order: 70,
      visible: false,
    },
    {
      id: 'text-polish',
      label: '润色',
      icon: 'ri-magic-line',
      group: 'TOOLS',
      order: 4,
      visible: true,
    },
    {
      id: 'capabilities', label: '能力中心', icon: 'ri-box-3-line', group: 'TOOLS', order: 15, visible: true,
    },
    {
      id: 'dashboard',
      label: '使用统计',
      icon: 'ri-bar-chart-box-line',
      group: 'TOOLS',
      order: 5,
      visible: false,
    },
    {
      id: 'automation',
      label: '自动化',
      icon: 'ri-flashlight-line',
      group: 'TOOLS',
      order: 10,
      visible: false,
    },
    {
      id: 'files',
      label: '文件与图床',
      icon: 'ri-hard-drive-2-line',
      group: 'TOOLS',
      order: 20,
      visible: false,
    },
    {
      id: 'pet-world',
      label: '松风庭院',
      icon: 'ri-landscape-line',
      group: 'TOOLS',
      order: 31,
      visible: false,
    },
    {
      id: 'request-monitoring',
      label: '请求监控',
      icon: 'ri-pulse-line',
      group: 'TOOLS',
      order: 40,
      visible: false,
    },
  ] as Omit<BuiltinMenuConfig, 'openMode'>[]
).filter((menu) => !RETIRED_MODULE_IDS.has(menu.id))
  .map((menu) => ({ ...menu, openMode: 'TAB' }))

/** 「个人」组的默认顺序；成员拖拽后的自定义顺序存服务端偏好，见 stores/nav.ts。 */
export const PERSONAL_ITEMS: ModuleId[] = [
  'todo',
  'workday-rhythm',
  'action-plans',
  'personal-principles',
  'decisions',
  'appearance',
  'food',
  'calendar',
  'weekly-report',
  'life-review',
  'workspaces',
  'memory',
  'knowledge',
  'reference-images',
  'english-learning',
  'vocabulary',
  'drawings',
  'vault',
  'ledger',
  'tianren',
]

const BASE_NAV_GROUPS: { label: string; items: ModuleId[] }[] = [
  { label: '', items: ['home', 'notes', 'navigation'] },
  {
    label: '公共',
    items: ['projects', 'updates', 'ideas', 'skills', 'skill-connections'],
  },
  { label: '个人', items: PERSONAL_ITEMS },
  {
    label: '工具',
    items: [
      'capabilities',
      'dashboard',
      'automation',
      'crawler-capabilities',
      'account-resources',
      'files',
      'games',
      'infrastructure',
      'devices',
      'request-monitoring',
    ],
  },
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
              menu.id !== 'chat' &&
              !RETIRED_MODULE_IDS.has(menu.id) &&
              menu.visible &&
              normalizeMenuPlacement(menu.placement) === 'MENU' &&
              { ROOT: '', PUBLIC: '公共', PERSONAL: '个人', TOOLS: '工具' }[menu.group] === label,
          )
          .sort((a, b) => a.order - b.order)
          .map((menu) => menu.id) as NavItemId[],
      }))
    : BASE_NAV_GROUPS.map((group) => ({
        label: group.label,
        items: (group.label === '个人' ? personalOrder : group.items)
          .filter((id) => !RETIRED_MODULE_IDS.has(id)) as NavItemId[],
      }))

  for (const target of groups) {
    const existingOrders = new Map(
      target.items.map((id, index) => [
        id,
        builtinMenus?.find((menu) => menu.id === id)?.order ?? (index + 1) * 10,
      ]),
    )
    const configured = externalApps.filter(
      (app) =>
        app.visible &&
        normalizeMenuPlacement(app.placement) === 'MENU' &&
        GROUP_LABELS[app.group] === target.label,
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

/** 合成底部图标或顶部按钮；入口仍使用原菜单 id，不复制成快捷链接。 */
export function resolveDockItems(
  externalApps: ExternalAppConfig[],
  builtinMenus: BuiltinMenuConfig[],
  placement: 'DOCK' | 'TOP' = 'DOCK',
): NavItemId[] {
  return [
    ...builtinMenus
      .filter((menu) => !RETIRED_MODULE_IDS.has(menu.id) && menu.visible && normalizeMenuPlacement(menu.placement) === placement)
      .map((menu) => ({ id: menu.id as NavItemId, order: menu.order, sourceOrder: 0 })),
    ...externalApps
      .filter((app) => app.visible && normalizeMenuPlacement(app.placement) === placement)
      .map((app) => ({
        id: externalAppItemId(app.id) as NavItemId,
        order: app.order,
        sourceOrder: 1,
      })),
  ]
    .sort((left, right) => left.order - right.order || left.sourceOrder - right.sourceOrder)
    .map((item) => item.id)
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

export function isMobileNavItemId(value: unknown): value is MobileNavItemId {
  return (
    value === 'action:note' ||
    (typeof value === 'string' &&
      value !== 'chat' &&
      !RETIRED_MODULE_IDS.has(value as ModuleId) &&
      !RETIRED_EXTERNAL_APP_IDS.has(value.replace(/^external:/, '')) &&
      (value.startsWith('external:') || Object.prototype.hasOwnProperty.call(MODULES, value)))
  )
}

/** 校正成员保存的手机底栏，损坏或空配置回退到项目 + 小记。 */
export function normalizeMobileNavItems(value: unknown): MobileNavItemId[] {
  const items = Array.isArray(value)
    ? value.filter(isMobileNavItemId).filter((id, index, all) => all.indexOf(id) === index)
    : []
  return items.length ? items.slice(0, MOBILE_NAV_MAX_ITEMS) : [...DEFAULT_MOBILE_NAV_ITEMS]
}

/** 左侧导航默认分组；首次加载注册表前也保证 Agent 位于「公共」。 */
export const NAV_GROUPS = resolveNavGroups(
  PERSONAL_ITEMS,
  DEFAULT_EXTERNAL_APPS,
  DEFAULT_BUILTIN_MENUS,
)

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
      path: route.fullPath,
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
  if (kind === 'quick-link') {
    const id = String(route.params.linkId)
    return {
      key: `quick-link:${id}`,
      label: '快捷链接',
      labelPlaceholder: true,
      icon: 'ri-links-line',
      path: route.path,
      closable: true,
    }
  }
  const mod = route.meta.module as ModuleId | undefined
  if (mod) {
    const tab = moduleTab(mod)
    // Skill 市场有站内子路由；复用同一页签时保留详情、发布或管理页的位置。
    if (mod === 'capabilities' || mod === 'skills' && route.path.startsWith('/wao/skills')) tab.path = route.fullPath
    return tab
  }
  return null
}
