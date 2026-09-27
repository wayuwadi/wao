import {
  externalAppItemId,
  normalizeMenuPlacement,
  type BuiltinMenuConfig,
  type ExternalAppConfig,
  type NavItemId,
  type MenuOpenMode,
  type MenuPlacement,
} from './nav'

export interface NavigationGroup {
  id: string
  label: string
}
export interface NavigationLayoutItem {
  id: NavItemId
  label: string
  icon: string
  group: string
  order: number
  visible: boolean
  openMode: MenuOpenMode
  placement: MenuPlacement
}
export interface NavigationLayout {
  schemaVersion: 1
  groups: NavigationGroup[]
  items: NavigationLayoutItem[]
  locked: boolean
}
export const LEGACY_NAVIGATION_GROUPS: NavigationGroup[] = [
  { id: 'ROOT', label: '' },
  { id: 'PUBLIC', label: '公共' },
  { id: 'PERSONAL', label: '个人' },
  { id: 'TOOLS', label: '工具' },
]

/** 首次保存快照保留每个既有入口字段；外部应用只取显示字段，不复制地址和授权。 */
export function snapshotNavigation(
  builtin: BuiltinMenuConfig[],
  external: ExternalAppConfig[],
  groups = LEGACY_NAVIGATION_GROUPS,
  locked = false,
): NavigationLayout {
  const items: NavigationLayoutItem[] = [
    ...builtin.map((menu) => ({ ...menu, placement: normalizeMenuPlacement(menu.placement) })),
    ...external.map((app) => ({
      id: externalAppItemId(app.id),
      label: app.label,
      icon: app.icon,
      group: app.group,
      order: app.order,
      visible: app.visible,
      openMode: app.openMode,
      placement: normalizeMenuPlacement(app.placement),
    })),
  ]
  return { schemaVersion: 1, groups: groups.map((group) => ({ ...group })), items, locked }
}

/** 所有旧分组与自定义分组保留稳定 id，重命名分组不改变所属关系。 */
export function resolveLayoutGroups(
  builtin: BuiltinMenuConfig[],
  external: ExternalAppConfig[],
  groups: NavigationGroup[],
) {
  const entries = snapshotNavigation(builtin, external, groups).items
  const known = new Set(groups.map((g) => g.id))
  const missing = [...new Set(entries.map((item) => item.group))].filter((id) => !known.has(id))
  return [...groups, ...missing.map((id) => ({ id, label: id }))].map((group) => ({
    ...group,
    configuredMenuCount: entries.filter((item) => item.group === group.id && item.placement === 'MENU' && item.id !== 'chat').length,
    items: entries
      .filter(
        (item) =>
          item.group === group.id &&
          item.visible &&
          item.placement === 'MENU' &&
          item.id !== 'chat',
      )
      .sort((a, b) => a.order - b.order)
      .map((item) => item.id),
  }))
}

/** 服务端损坏或未来版本不能被当成无偏好，避免下一次保存覆盖原记录。 */
export function validateNavigationLayout(value: NavigationLayout): NavigationLayout {
  if (
    value?.schemaVersion !== 1 ||
    !Array.isArray(value.groups) ||
    !Array.isArray(value.items) ||
    typeof value.locked !== 'boolean' ||
    !value.groups.some((g) => g.id === 'ROOT')
  ) {
    throw new Error('导航布局版本无法读取，原配置仍保留')
  }
  const groups = new Set(value.groups.map((group) => group.id))
  if (
    groups.size !== value.groups.length ||
    new Set(value.items.map((item) => item.id)).size !== value.items.length ||
    value.items.some(
      (item) =>
        !groups.has(item.group) ||
        typeof item.label !== 'string' ||
        typeof item.visible !== 'boolean' ||
        !['MENU', 'TOP', 'DOCK'].includes(item.placement) ||
        !['TAB', 'NEW_PAGE'].includes(item.openMode),
    )
  )
    throw new Error('导航布局内容无法读取，原配置仍保留')
  return structuredClone(value)
}
