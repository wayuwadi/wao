import type { ModuleId, Tab } from '@/types'
import type { RouteLocationNormalized } from 'vue-router'

/** 模块元数据 —— 图标 / 标签 / 路由，源自原型 vGlobal() 的 pages/icons/groups。 */
export interface ModuleMeta {
  id: NavItemId
  label: string
  icon: string
  path: string
}

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
}

export interface BuiltinMenuRegistry {
  version: number
  menus: BuiltinMenuConfig[]
}

export const DEFAULT_EXTERNAL_APPS: ExternalAppConfig[] = [{
  id: 'media-agent',
  label: '自媒体 Agent',
  icon: 'ri-sparkling-2-line',
  group: 'PUBLIC',
  order: 20,
  visible: true,
  appUrl: '/agent/',
  healthUrl: '/api/agent/health',
}]

export const MODULES: Record<ModuleId, ModuleMeta> = {
  home: { id: 'home', label: '首页', icon: 'ri-home-5-line', path: '/' },
  projects: { id: 'projects', label: '项目', icon: 'ri-stack-line', path: '/projects' },
  calendar: { id: 'calendar', label: '日历', icon: 'ri-calendar-2-line', path: '/calendar' },
  todo: { id: 'todo', label: '待办', icon: 'ri-checkbox-multiple-line', path: '/todo' },
  knowledge: { id: 'knowledge', label: '知识库', icon: 'ri-book-2-line', path: '/knowledge' },
  files: { id: 'files', label: '文件与图床', icon: 'ri-hard-drive-2-line', path: '/files' },
  automation: { id: 'automation', label: '自动化', icon: 'ri-flashlight-line', path: '/automation' },
  games: { id: 'games', label: '游戏厅', icon: 'ri-gamepad-line', path: '/games' },
  vault: { id: 'vault', label: '密码本', icon: 'ri-key-2-line', path: '/vault' },
  settings: { id: 'settings', label: '设置', icon: 'ri-settings-3-line', path: '/settings' },
}
export const DEFAULT_BUILTIN_MENUS: BuiltinMenuConfig[] = [
  { id: 'home', label: '首页', icon: 'ri-home-5-line', group: 'ROOT', order: 10, visible: true },
  { id: 'projects', label: '项目', icon: 'ri-stack-line', group: 'PUBLIC', order: 10, visible: true },
  { id: 'todo', label: '待办', icon: 'ri-checkbox-multiple-line', group: 'PERSONAL', order: 10, visible: true },
  { id: 'calendar', label: '日历', icon: 'ri-calendar-2-line', group: 'PERSONAL', order: 20, visible: true },
  { id: 'knowledge', label: '知识库', icon: 'ri-book-2-line', group: 'PERSONAL', order: 30, visible: true },
  { id: 'vault', label: '密码本', icon: 'ri-key-2-line', group: 'PERSONAL', order: 40, visible: true },
  { id: 'automation', label: '自动化', icon: 'ri-flashlight-line', group: 'TOOLS', order: 10, visible: true },
  { id: 'files', label: '文件与图床', icon: 'ri-hard-drive-2-line', group: 'TOOLS', order: 20, visible: true },
  { id: 'games', label: '游戏厅', icon: 'ri-gamepad-line', group: 'TOOLS', order: 30, visible: true },
]

/** 「个人」组的默认顺序；成员拖拽后的自定义顺序存服务端偏好，见 stores/nav.ts。 */
export const PERSONAL_ITEMS: ModuleId[] = ['todo', 'calendar', 'knowledge', 'vault']

const BASE_NAV_GROUPS: { label: string; items: ModuleId[] }[] = [
  { label: '', items: ['home'] },
  { label: '公共', items: ['projects'] },
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
          .filter((menu) => menu.visible && ({ ROOT: '', PUBLIC: '公共', PERSONAL: '个人', TOOLS: '工具' })[menu.group] === label)
          .sort((a, b) => a.order - b.order)
          .map((menu) => menu.id) as NavItemId[],
      }))
    : BASE_NAV_GROUPS.map((group) => ({
        label: group.label,
        items: (group.label === '个人' ? [...personalOrder] : [...group.items]) as NavItemId[],
      }))

  for (const target of groups) {
    const existingOrders = new Map(target.items.map((id, index) => [
      id,
      builtinMenus?.find((menu) => menu.id === id)?.order ?? (index + 1) * 10,
    ]))
    const configured = externalApps.filter(
      (app) => app.visible && GROUP_LABELS[app.group] === target.label,
    )
    target.items.push(...configured.map((app) => externalAppItemId(app.id)))
    const appOrders = new Map(configured.map((app) => [externalAppItemId(app.id), app.order]))
    target.items.sort((left, right) =>
      (appOrders.get(left as ExternalAppItemId) ?? existingOrders.get(left) ?? 999)
      - (appOrders.get(right as ExternalAppItemId) ?? existingOrders.get(right) ?? 999),
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
    return { key: `proj:${id}`, label: `项目 ${id}`, labelPlaceholder: true, icon: 'ri-stack-line', path: route.path, closable: true }
  }
  if (kind === 'kb') {
    const id = String(route.params.id)
    return { key: `kb:${id}`, label: `知识库 ${id}`, labelPlaceholder: true, icon: 'ri-book-2-line', path: route.path, closable: true }
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
  if (mod) return moduleTab(mod)
  return null
}
