import type { ModuleId, Tab } from '@/types'
import type { RouteLocationNormalized } from 'vue-router'

/** 模块元数据 —— 图标 / 标签 / 路由，源自原型 vGlobal() 的 pages/icons/groups。 */
export interface ModuleMeta {
  id: ModuleId
  label: string
  icon: string
  path: string
}

export type MenuGroupCode = 'PUBLIC' | 'PERSONAL' | 'TOOLS'

/** 可由系统设置覆盖的自媒体 Agent 一级菜单展示项。 */
export interface MediaAgentMenuConfig {
  label: string
  group: MenuGroupCode
  order: number
  visible: boolean
}

export const DEFAULT_MEDIA_AGENT_MENU: MediaAgentMenuConfig = {
  label: '自媒体 Agent',
  group: 'PUBLIC',
  order: 20,
  visible: true,
}

export const MODULES: Record<ModuleId, ModuleMeta> = {
  home: { id: 'home', label: '首页', icon: 'ri-home-5-line', path: '/' },
  projects: { id: 'projects', label: '项目', icon: 'ri-stack-line', path: '/projects' },
  calendar: { id: 'calendar', label: '日历', icon: 'ri-calendar-2-line', path: '/calendar' },
  todo: { id: 'todo', label: '待办', icon: 'ri-checkbox-multiple-line', path: '/todo' },
  knowledge: { id: 'knowledge', label: '知识库', icon: 'ri-book-2-line', path: '/knowledge' },
  files: { id: 'files', label: '文件与图床', icon: 'ri-hard-drive-2-line', path: '/files' },
  automation: { id: 'automation', label: '自动化', icon: 'ri-flashlight-line', path: '/automation' },
  mediaAgent: { id: 'mediaAgent', label: '自媒体 Agent', icon: 'ri-sparkling-2-line', path: '/media-agent' },
  vault: { id: 'vault', label: '密码本', icon: 'ri-key-2-line', path: '/vault' },
  settings: { id: 'settings', label: '设置', icon: 'ri-settings-3-line', path: '/settings' },
}

/** 「个人」组的默认顺序；成员拖拽后的自定义顺序存服务端偏好，见 stores/nav.ts。 */
export const PERSONAL_ITEMS: ModuleId[] = ['todo', 'calendar', 'knowledge', 'vault']

const BASE_NAV_GROUPS: { label: string; items: ModuleId[] }[] = [
  { label: '', items: ['home'] },
  { label: '公共', items: ['projects'] },
  { label: '个人', items: PERSONAL_ITEMS },
  { label: '工具', items: ['automation', 'files'] },
]

const GROUP_LABELS: Record<MenuGroupCode, string> = {
  PUBLIC: '公共',
  PERSONAL: '个人',
  TOOLS: '工具',
}

/**
 * 合成当前用户真正看到的导航：个人模块沿用成员自己的拖拽顺序，Agent 菜单使用全局配置。
 * 排序值越小越靠前；相同值保持内置菜单在前，避免旧配置升级后发生无意义跳动。
 */
export function resolveNavGroups(
  personalOrder: ModuleId[],
  mediaAgent: MediaAgentMenuConfig,
): { label: string; items: ModuleId[] }[] {
  const groups = BASE_NAV_GROUPS.map((group) => ({
    label: group.label,
    items: group.label === '个人' ? [...personalOrder] : [...group.items],
  }))
  if (!mediaAgent.visible) return groups

  const target = groups.find((group) => group.label === GROUP_LABELS[mediaAgent.group])
  if (!target) return groups
  const existingOrders = new Map(target.items.map((id, index) => [id, (index + 1) * 10]))
  target.items.push('mediaAgent')
  target.items.sort((left, right) => {
    const leftOrder = left === 'mediaAgent' ? mediaAgent.order : (existingOrders.get(left) ?? 999)
    const rightOrder = right === 'mediaAgent' ? mediaAgent.order : (existingOrders.get(right) ?? 999)
    return leftOrder - rightOrder
  })
  return groups
}

/** 左侧导航默认分组；首次加载配置前也保证 Agent 位于「公共」。 */
export const NAV_GROUPS = resolveNavGroups(PERSONAL_ITEMS, DEFAULT_MEDIA_AGENT_MENU)

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
  const mod = route.meta.module as ModuleId | undefined
  if (mod) return moduleTab(mod)
  return null
}
