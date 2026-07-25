import type { ModuleId, Tab } from '@/types'
import type { RouteLocationNormalized } from 'vue-router'

/** 模块元数据 —— 图标 / 标签 / 路由，源自原型 vGlobal() 的 pages/icons/groups。 */
export interface ModuleMeta {
  id: ModuleId
  label: string
  icon: string
  path: string
}

export const MODULES: Record<ModuleId, ModuleMeta> = {
  home: { id: 'home', label: '首页', icon: 'ri-home-5-line', path: '/' },
  projects: { id: 'projects', label: '项目', icon: 'ri-stack-line', path: '/projects' },
  calendar: { id: 'calendar', label: '日历', icon: 'ri-calendar-line', path: '/calendar' },
  knowledge: { id: 'knowledge', label: '知识库', icon: 'ri-book-2-line', path: '/knowledge' },
  whiteboard: { id: 'whiteboard', label: '白板 / 画布', icon: 'ri-artboard-line', path: '/whiteboard' },
  files: { id: 'files', label: '文件与图床', icon: 'ri-hard-drive-2-line', path: '/files' },
  automation: { id: 'automation', label: '自动化', icon: 'ri-flashlight-line', path: '/automation' },
  vault: { id: 'vault', label: '密码库', icon: 'ri-key-2-line', path: '/vault' },
  settings: { id: 'settings', label: '设置', icon: 'ri-settings-3-line', path: '/settings' },
}

/** 左侧导航分组（空 label = 不显示分组标题的首页组）。 */
export const NAV_GROUPS: { label: string; items: ModuleId[] }[] = [
  { label: '', items: ['home'] },
  { label: '规划', items: ['projects', 'calendar'] },
  { label: '沉淀', items: ['knowledge', 'whiteboard', 'files'] },
  { label: '系统', items: ['automation', 'vault'] },
]

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
