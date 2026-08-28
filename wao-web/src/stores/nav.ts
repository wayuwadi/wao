import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  DEFAULT_EXTERNAL_APPS,
  DEFAULT_BUILTIN_MENUS,
  MODULES,
  PERSONAL_ITEMS,
  externalAppIdFromItem,
  externalAppItemId,
  externalAppPath,
  normalizeBuiltinMenu,
  normalizeExternalApp,
  normalizeMobileNavItems,
  resolveNavGroups,
  resolveDockItems,
  type ExternalAppConfig,
  type BuiltinMenuConfig,
  type MobileNavItemId,
  type NavItemId,
  type BuiltinMenuGroupCode,
  type MenuPlacement,
} from '@/config/nav'
import { fetchPreference, savePreference } from '@/api/favorites'
import {
  fetchBuiltinMenuRegistry,
  fetchExternalAppRegistry,
  saveBuiltinMenuRegistry,
  saveExternalAppRegistry,
} from '@/api/system'
import type { ModuleId } from '@/types'

/** 导航 store：侧栏整栏折叠态 + 各分组折叠态 + 「个人」组自定义顺序。 */

const NAV_KEY = 'wao-nav-v1'
const PERSONAL_ORDER_PREF = 'nav-personal-order'
const MOBILE_NAV_PREF = 'mobile-bottom-nav-v1'

interface Persisted {
  collapsed: boolean
  foldedGroups: Record<string, boolean>
  personalOrder?: string[]
}

function load(): Persisted {
  try {
    const raw = localStorage.getItem(NAV_KEY)
    if (raw) return JSON.parse(raw) as Persisted
  } catch {
    /* ignore */
  }
  return { collapsed: false, foldedGroups: {} }
}

/** 校正一份存下来的顺序：去掉未知项、补上新增模块，保证不丢菜单。 */
function normalizeOrder(order: unknown): ModuleId[] {
  const saved = Array.isArray(order)
    ? order.filter((id): id is ModuleId => PERSONAL_ITEMS.includes(id as ModuleId))
    : []
  const missing = PERSONAL_ITEMS.filter((id) => !saved.includes(id))
  return [...saved, ...missing]
}

export const useNavStore = defineStore('nav', () => {
  const persisted = load()
  const collapsed = ref(persisted.collapsed)
  const foldedGroups = ref<Record<string, boolean>>(persisted.foldedGroups)
  /** 「个人」组顺序：本地缓存先渲染，登录后再用服务端偏好覆盖。 */
  const personalOrder = ref<ModuleId[]>(normalizeOrder(persisted.personalOrder))
  /** 外部应用注册表为全局配置；接口失败时保留安全默认项，不能让现有入口凭空消失。 */
  const externalApps = ref<ExternalAppConfig[]>(DEFAULT_EXTERNAL_APPS.map(normalizeExternalApp))
  const builtinMenus = ref<BuiltinMenuConfig[]>(DEFAULT_BUILTIN_MENUS.map(normalizeBuiltinMenu))
  const externalAppsState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
  const externalAppsError = ref('')
  const builtinMenusState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
  const builtinMenusError = ref('')
  const mobileItems = ref<MobileNavItemId[]>(normalizeMobileNavItems(null))
  const mobileItemsState = ref<'idle' | 'loading' | 'loaded' | 'saving' | 'saved' | 'error'>('idle')
  const mobileItemsError = ref('')
  const menuMutationState = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const menuMutationError = ref('')
  const groups = computed(() =>
    resolveNavGroups(personalOrder.value, externalApps.value, builtinMenus.value),
  )
  const dockItems = computed(() => resolveDockItems(externalApps.value, builtinMenus.value))

  function persist() {
    localStorage.setItem(
      NAV_KEY,
      JSON.stringify({
        collapsed: collapsed.value,
        foldedGroups: foldedGroups.value,
        personalOrder: personalOrder.value,
      }),
    )
  }

  /** 整栏折叠 / 展开（236px ↔ 62px）。 */
  function toggleCollapse() {
    collapsed.value = !collapsed.value
    persist()
  }

  /** 折叠 / 展开某个分组的子项。 */
  function toggleGroup(label: string) {
    foldedGroups.value = { ...foldedGroups.value, [label]: !foldedGroups.value[label] }
    persist()
  }

  function isFolded(label: string) {
    return !!foldedGroups.value[label]
  }

  /** 从服务端拉取跨设备的「个人」组顺序；没存过或后端不可用时保持本地值。 */
  async function loadPersonalOrder() {
    try {
      const raw = await fetchPreference(PERSONAL_ORDER_PREF)
      if (raw) {
        personalOrder.value = normalizeOrder(JSON.parse(raw))
        persist()
      }
    } catch {
      /* 本地顺序照常可用 */
    }
  }

  /** 拖拽后的新顺序：本地立即生效，服务端异步落库。 */
  function setPersonalOrder(order: ModuleId[]) {
    personalOrder.value = normalizeOrder(order)
    persist()
    void savePreference(PERSONAL_ORDER_PREF, JSON.stringify(personalOrder.value)).catch(() => {
      /* 落库失败下次进来会回到旧顺序，但本次会话内保持用户拖出的样子 */
    })
  }

  /** 从成员级偏好恢复手机底栏；未配置、损坏或暂时离线时保留安全默认入口。 */
  async function loadMobileItems(force = false) {
    if (mobileItemsState.value === 'loading' || mobileItemsState.value === 'saving') return
    if (!force && ['loaded', 'saved'].includes(mobileItemsState.value)) return
    mobileItemsState.value = 'loading'
    mobileItemsError.value = ''
    try {
      const raw = await fetchPreference(MOBILE_NAV_PREF)
      mobileItems.value = normalizeMobileNavItems(raw ? JSON.parse(raw) : null)
      mobileItemsState.value = 'loaded'
    } catch (error) {
      mobileItemsState.value = 'error'
      mobileItemsError.value = error instanceof Error ? error.message : '手机底栏读取失败'
    }
  }

  /** 保存当前成员的手机底栏，供直接拖拽和工作台编辑共同复用。 */
  async function saveMobileItems(items: MobileNavItemId[]) {
    const normalized = normalizeMobileNavItems(items)
    mobileItems.value = normalized
    mobileItemsState.value = 'saving'
    mobileItemsError.value = ''
    try {
      await savePreference(MOBILE_NAV_PREF, JSON.stringify(normalized))
      mobileItemsState.value = 'saved'
    } catch (error) {
      mobileItemsState.value = 'error'
      mobileItemsError.value = error instanceof Error ? error.message : '手机底栏保存失败'
      throw error
    }
  }

  /** 从服务端读取外部应用注册表；失败时回退安全默认项并允许显式重试。 */
  async function loadExternalApps(force = false) {
    if (externalAppsState.value === 'loading') return
    if (!force && externalAppsState.value === 'loaded') return
    externalAppsState.value = 'loading'
    externalAppsError.value = ''
    try {
      externalApps.value = (await fetchExternalAppRegistry()).apps.map(normalizeExternalApp)
      externalAppsState.value = 'loaded'
    } catch (error) {
      externalAppsState.value = 'error'
      externalAppsError.value = error instanceof Error ? error.message : '外部应用注册表读取失败'
    }
  }
  async function loadBuiltinMenus(force = false) {
    if (builtinMenusState.value === 'loading') return
    if (!force && builtinMenusState.value === 'loaded') return
    builtinMenusState.value = 'loading'
    builtinMenusError.value = ''
    try {
      builtinMenus.value = (await fetchBuiltinMenuRegistry()).menus.map(normalizeBuiltinMenu)
      builtinMenusState.value = 'loaded'
    } catch (error) {
      builtinMenusState.value = 'error'
      builtinMenusError.value = error instanceof Error ? error.message : '内置菜单注册表读取失败'
      /* 坏配置或短暂离线时保留代码默认导航，不能让菜单消失。 */
    }
  }
  function applyBuiltinMenus(menus: BuiltinMenuConfig[]) {
    builtinMenus.value = menus.map(normalizeBuiltinMenu)
    builtinMenusState.value = 'loaded'
    builtinMenusError.value = ''
  }

  async function reloadMenuRegistries() {
    const [builtinRegistry, externalRegistry] = await Promise.all([
      fetchBuiltinMenuRegistry(),
      fetchExternalAppRegistry(),
    ])
    applyBuiltinMenus(builtinRegistry.menus)
    applyExternalApps(externalRegistry.apps)
  }

  async function persistMenuRegistries(
    nextBuiltin: BuiltinMenuConfig[],
    nextExternal: ExternalAppConfig[],
  ) {
    if (menuMutationState.value === 'saving') throw new Error('菜单正在保存，请稍后再试')
    const previousBuiltin = builtinMenus.value.map((menu) => ({ ...menu }))
    const previousExternal = externalApps.value.map((app) => ({ ...app }))
    const builtinChanged = JSON.stringify(previousBuiltin) !== JSON.stringify(nextBuiltin)
    const externalChanged = JSON.stringify(previousExternal) !== JSON.stringify(nextExternal)
    if (!builtinChanged && !externalChanged) return
    applyBuiltinMenus(nextBuiltin)
    applyExternalApps(nextExternal)
    menuMutationState.value = 'saving'
    menuMutationError.value = ''
    try {
      const [savedBuiltin, savedExternal] = await Promise.all([
        builtinChanged
          ? saveBuiltinMenuRegistry(nextBuiltin)
          : Promise.resolve({ version: 0, menus: nextBuiltin }),
        externalChanged
          ? saveExternalAppRegistry(nextExternal)
          : Promise.resolve({ version: 0, apps: nextExternal }),
      ])
      applyBuiltinMenus(savedBuiltin.menus)
      applyExternalApps(savedExternal.apps)
      menuMutationState.value = 'saved'
    } catch (error) {
      menuMutationState.value = 'error'
      menuMutationError.value = error instanceof Error ? error.message : '菜单保存失败'
      try {
        await reloadMenuRegistries()
      } catch {
        applyBuiltinMenus(previousBuiltin)
        applyExternalApps(previousExternal)
      }
      throw error
    }
  }

  function menuConfig(id: NavItemId) {
    const externalId = externalAppIdFromItem(id)
    return externalId
      ? externalApps.value.find((app) => app.id === externalId)
      : builtinMenus.value.find((menu) => menu.id === id)
  }

  /** 保存一次侧栏拖拽；目标列表里的内置菜单和外部应用共同重建顺序。 */
  async function moveMenu(
    id: NavItemId,
    placement: MenuPlacement,
    group: BuiltinMenuGroupCode,
    targetId?: NavItemId,
  ) {
    const nextBuiltin = builtinMenus.value.map((menu) => ({ ...menu }))
    const nextExternal = externalApps.value.map((app) => ({ ...app }))
    const externalId = externalAppIdFromItem(id)
    const movingBuiltin = externalId ? undefined : nextBuiltin.find((menu) => menu.id === id)
    const movingExternal = externalId
      ? nextExternal.find((app) => app.id === externalId)
      : undefined
    const moving = movingExternal ?? movingBuiltin
    if (!moving) throw new Error('菜单不存在，请刷新后重试')
    if (movingExternal && group === 'ROOT') throw new Error('外部应用不能移动到顶层')
    moving.placement = placement
    if (movingExternal) movingExternal.group = group as Exclude<BuiltinMenuGroupCode, 'ROOT'>
    if (movingBuiltin) movingBuiltin.group = group

    type OrderCandidate = {
      id: NavItemId
      menu: {
        visible: boolean
        placement?: MenuPlacement
        group: BuiltinMenuGroupCode
        order: number
      }
      source: number
    }
    const candidates: OrderCandidate[] = [
      ...nextBuiltin.map((menu) => ({ id: menu.id as NavItemId, menu, source: 0 })),
      ...nextExternal.map((app) => ({
        id: externalAppItemId(app.id) as NavItemId,
        menu: app,
        source: 1,
      })),
    ]
      .filter(
        (item) =>
          item.id !== id &&
          item.menu.visible &&
          (item.menu.placement ?? 'MENU') === placement &&
          (placement === 'DOCK' || item.menu.group === group),
      )
      .sort((left, right) => left.menu.order - right.menu.order || left.source - right.source)
    const targetIndex = targetId ? candidates.findIndex((item) => item.id === targetId) : -1
    candidates.splice(targetIndex >= 0 ? targetIndex : candidates.length, 0, {
      id,
      menu: moving,
      source: externalId ? 1 : 0,
    })
    candidates.forEach((item, index) => {
      item.menu.order = (index + 1) * 10
    })
    await persistMenuRegistries(nextBuiltin, nextExternal)
  }

  async function renameMenu(id: NavItemId, label: string) {
    const trimmed = label.trim()
    if (!trimmed || trimmed.length > 24) throw new Error('菜单名称不能为空且不超过 24 个字符')
    const nextBuiltin = builtinMenus.value.map((menu) => ({ ...menu }))
    const nextExternal = externalApps.value.map((app) => ({ ...app }))
    const externalId = externalAppIdFromItem(id)
    const target = externalId
      ? nextExternal.find((app) => app.id === externalId)
      : nextBuiltin.find((menu) => menu.id === id)
    if (!target) throw new Error('菜单不存在，请刷新后重试')
    target.label = trimmed
    await persistMenuRegistries(nextBuiltin, nextExternal)
  }

  /** 从侧栏右键菜单即时修改显隐；隐藏项可在设置页重新开启。 */
  async function setMenuVisibility(id: NavItemId, visible: boolean) {
    const nextBuiltin = builtinMenus.value.map((menu) => ({ ...menu }))
    const nextExternal = externalApps.value.map((app) => ({ ...app }))
    const externalId = externalAppIdFromItem(id)
    const target = externalId
      ? nextExternal.find((app) => app.id === externalId)
      : nextBuiltin.find((menu) => menu.id === id)
    if (!target) throw new Error('菜单不存在，请刷新后重试')
    target.visible = visible
    await persistMenuRegistries(nextBuiltin, nextExternal)
  }

  /** 把菜单放到当前分组或停靠区的最前面，并立即持久化。 */
  async function pinMenu(id: NavItemId) {
    const configured = menuConfig(id)
    if (!configured) throw new Error('菜单不存在，请刷新后重试')
    const placement = configured.placement ?? 'MENU'
    const group = configured.group as BuiltinMenuGroupCode
    const peers = [
      ...builtinMenus.value.map((menu) => ({ id: menu.id as NavItemId, menu })),
      ...externalApps.value.map((app) => ({ id: externalAppItemId(app.id) as NavItemId, menu: app })),
    ].filter((item) => item.id !== id && item.menu.visible
      && (item.menu.placement ?? 'MENU') === placement
      && (placement === 'DOCK' || item.menu.group === group))
      .sort((left, right) => left.menu.order - right.menu.order)
    await moveMenu(id, placement, group, peers[0]?.id)
  }

  /** 设置页保存成功后立即刷新导航，不等页面重载。 */
  function applyExternalApps(apps: ExternalAppConfig[]) {
    externalApps.value = apps.map(normalizeExternalApp)
    externalAppsState.value = 'loaded'
    externalAppsError.value = ''
  }

  function externalAppById(id: string) {
    return externalApps.value.find((app) => app.id === id)
  }

  function itemMeta(id: NavItemId | undefined) {
    const externalId = externalAppIdFromItem(id)
    if (externalId) {
      const app = externalAppById(externalId)
      return app
        ? {
            id: externalAppItemId(app.id),
            label: app.label,
            icon: app.icon,
            path: externalAppPath(app.id),
            newPagePath: app.appUrl,
            openMode: app.openMode,
          }
        : undefined
    }
    if (!id) return undefined
    const module = MODULES[id as ModuleId]
    const configured = builtinMenus.value.find((menu) => menu.id === id)
    return configured
      ? { ...module, label: configured.label, icon: configured.icon, openMode: configured.openMode }
      : { ...module, openMode: 'TAB' as const }
  }

  return {
    collapsed,
    foldedGroups,
    personalOrder,
    groups,
    dockItems,
    externalApps,
    builtinMenus,
    externalAppsState,
    externalAppsError,
    builtinMenusState,
    builtinMenusError,
    mobileItems,
    mobileItemsState,
    mobileItemsError,
    menuMutationState,
    menuMutationError,
    toggleCollapse,
    toggleGroup,
    isFolded,
    loadPersonalOrder,
    setPersonalOrder,
    loadMobileItems,
    saveMobileItems,
    loadExternalApps,
    loadBuiltinMenus,
    applyExternalApps,
    applyBuiltinMenus,
    reloadMenuRegistries,
    moveMenu,
    renameMenu,
    setMenuVisibility,
    pinMenu,
    menuConfig,
    externalAppById,
    itemMeta,
  }
})
