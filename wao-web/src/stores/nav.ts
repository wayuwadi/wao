import { computed, ref, watch } from 'vue'
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
  resolveDockItems,
  type ExternalAppConfig,
  type BuiltinMenuConfig,
  type MobileNavItemId,
  type NavItemId,
  type BuiltinMenuGroupCode,
  type MenuPlacement,
} from '@/config/nav'
import { fetchPreference, savePreference } from '@/api/favorites'
import { fetchBuiltinMenuRegistry, fetchExternalAppRegistry } from '@/api/system'
import type { ModuleId } from '@/types'
import { useSessionStore } from './session'
import { fetchNavigationLayout, saveNavigationLayout } from '@/api/navigationLayout'
import {
  LEGACY_NAVIGATION_GROUPS,
  resolveLayoutGroups,
  snapshotNavigation,
  validateNavigationLayout,
  type NavigationLayout,
} from '@/config/navigationLayout'

/** 导航 store：侧栏整栏折叠态 + 各分组折叠态 + 「个人」组自定义顺序。 */

const NAV_KEY = 'wao-nav-v1'
const PERSONAL_ORDER_PREF = 'nav-personal-order'
const MOBILE_NAV_PREF = 'mobile-bottom-nav-v1'

interface Persisted {
  collapsed: boolean
  foldedGroups: Record<string, boolean>
  personalOrder?: string[]
}

function load(memberId = ''): Persisted {
  try {
    const raw =
      (memberId ? localStorage.getItem(NAV_KEY + ':' + memberId) : null) ??
      localStorage.getItem(NAV_KEY)
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
  const session = useSessionStore()
  const persisted = load(session.userId)
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
  const globalBuiltin = ref<BuiltinMenuConfig[]>(DEFAULT_BUILTIN_MENUS.map(normalizeBuiltinMenu))
  const globalExternal = ref<ExternalAppConfig[]>(DEFAULT_EXTERNAL_APPS.map(normalizeExternalApp))
  const personalLayout = ref<NavigationLayout | null>(null)
  const layoutRevision = ref('none')
  const layoutLoaded = ref(false)
  const layoutLoading = ref(false)
  const layoutError = ref('')
  const layoutConflict = ref(false)
  const pendingLayout = ref<NavigationLayout | null>(null)
  const layoutGroups = computed(() => personalLayout.value?.groups ?? LEGACY_NAVIGATION_GROUPS)
  const layoutLocked = computed(() => personalLayout.value?.locked ?? false)
  const groups = computed(() =>
    resolveLayoutGroups(builtinMenus.value, externalApps.value, layoutGroups.value),
  )
  const draggedMenuId = ref<NavItemId | null>(null)
  const topItems = computed(() => resolveDockItems(externalApps.value, builtinMenus.value, 'TOP'))
  const dockItems = computed(() => resolveDockItems(externalApps.value, builtinMenus.value))
  const canEditLayout = computed(
    () =>
      layoutLoaded.value &&
      !layoutLoading.value &&
      builtinMenusState.value === 'loaded' &&
      externalAppsState.value === 'loaded' &&
      menuMutationState.value !== 'saving',
  )
  const canMoveMenus = computed(
    () => canEditLayout.value && !layoutLocked.value && !layoutConflict.value,
  )
  let generation = 0
  let loadPromise: Promise<void> | null = null

  function rebuildLayout() {
    const overrides = new Map(personalLayout.value?.items.map((item) => [item.id, item]) ?? [])
    const known = new Set(globalBuiltin.value.map((item) => item.id))
    const extra = Object.values(MODULES)
      .filter(
        (item) =>
          item.id !== 'settings' &&
          item.id !== 'chat' &&
          item.id !== 'chat-reviews' &&
          !known.has(item.id as BuiltinMenuConfig['id']),
      )
      .map(
        (item) =>
          ({
            id: item.id,
            label: item.label,
            icon: item.icon,
            group: 'PERSONAL',
            order: 1000,
            visible: false,
            openMode: 'TAB',
            placement: 'MENU',
          }) as BuiltinMenuConfig,
      )
    builtinMenus.value = [...globalBuiltin.value, ...extra].map((item) => {
      const own = overrides.get(item.id)
      // 前端 V1 新入口不依赖后端菜单发布；已有个人显隐、位置和名称仍优先。
      const changelogDefault = item.id === 'changelog' && !own
        ? {
            ...DEFAULT_BUILTIN_MENUS.find((menu) => menu.id === 'changelog'),
            group: layoutGroups.value.some((group) => group.id === 'PUBLIC') ? 'PUBLIC' : 'ROOT',
          }
        : {}
      return normalizeBuiltinMenu({
        ...item,
        ...(personalLayout.value ? { visible: false } : {}),
        ...changelogDefault,
        ...own,
      } as BuiltinMenuConfig)
    })
    externalApps.value = globalExternal.value.map((app) => {
      const own = overrides.get(externalAppItemId(app.id))
      return normalizeExternalApp({
        ...app,
        ...(personalLayout.value ? { visible: false } : {}),
        ...own,
        id: app.id,
      } as ExternalAppConfig)
    })
  }

  watch(
    () => session.userId,
    () => {
      generation++
      loadPromise = null
      personalLayout.value = null
      layoutRevision.value = 'none'
      layoutLoaded.value = false
      layoutLoading.value = false
      layoutError.value = ''
      layoutConflict.value = false
      pendingLayout.value = null
      globalBuiltin.value = DEFAULT_BUILTIN_MENUS.map(normalizeBuiltinMenu)
      globalExternal.value = DEFAULT_EXTERNAL_APPS.map(normalizeExternalApp)
      builtinMenusState.value = 'idle'
      externalAppsState.value = 'idle'
      builtinMenusError.value = ''
      externalAppsError.value = ''
      menuMutationState.value = 'idle'
      menuMutationError.value = ''
      draggedMenuId.value = null
      const preferences = load(session.userId)
      foldedGroups.value = preferences.foldedGroups
      personalOrder.value = normalizeOrder(preferences.personalOrder)
      mobileItems.value = normalizeMobileNavItems(null)
      mobileItemsState.value = 'idle'
      mobileItemsError.value = ''
      rebuildLayout()
      if (session.userId) void loadMenus()
    },
    { flush: 'sync' },
  )

  function persist() {
    localStorage.setItem(
      session.userId ? NAV_KEY + ':' + session.userId : NAV_KEY,
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
    foldedGroups.value = { ...foldedGroups.value, [label]: !isFolded(label) }
    persist()
  }

  function isFolded(label: string) {
    const legacyLabel = LEGACY_NAVIGATION_GROUPS.find((group) => group.id === label)?.label
    return !!(foldedGroups.value[label] ?? (legacyLabel ? foldedGroups.value[legacyLabel] : false))
  }

  /** 从服务端拉取跨设备的「个人」组顺序；没存过或后端不可用时保持本地值。 */
  async function loadPersonalOrder() {
    const token = generation
    try {
      const raw = await fetchPreference(PERSONAL_ORDER_PREF)
      if (token !== generation) return
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
    const token = generation
    mobileItemsState.value = 'loading'
    mobileItemsError.value = ''
    try {
      const raw = await fetchPreference(MOBILE_NAV_PREF)
      if (token !== generation) return
      mobileItems.value = normalizeMobileNavItems(raw ? JSON.parse(raw) : null)
      mobileItemsState.value = 'loaded'
    } catch (error) {
      if (token !== generation) return
      mobileItemsState.value = 'error'
      mobileItemsError.value = error instanceof Error ? error.message : '手机底栏读取失败'
    }
  }

  /** 保存当前成员的手机底栏，供直接拖拽和工作台编辑共同复用。 */
  async function saveMobileItems(items: MobileNavItemId[]) {
    const token = generation
    const normalized = normalizeMobileNavItems(items)
    mobileItems.value = normalized
    mobileItemsState.value = 'saving'
    mobileItemsError.value = ''
    try {
      await savePreference(MOBILE_NAV_PREF, JSON.stringify(normalized))
      if (token !== generation) return
      mobileItemsState.value = 'saved'
    } catch (error) {
      if (token !== generation) return
      mobileItemsState.value = 'error'
      mobileItemsError.value = error instanceof Error ? error.message : '手机底栏保存失败'
      throw error
    }
  }

  /** 同批读取全局来源和个人布局，避免顶部、侧栏先后加载造成位置跳变。 */
  async function loadMenus(force = false): Promise<void> {
    if (loadPromise) return loadPromise
    if ((!force && layoutLoaded.value) || menuMutationState.value === 'saving') return
    const token = generation
    layoutLoading.value = true
    layoutError.value = ''
    builtinMenusState.value = 'loading'
    externalAppsState.value = 'loading'
    const operation = (async () => {
      try {
        const [builtin, external, personal] = await Promise.all([
          fetchBuiltinMenuRegistry(),
          fetchExternalAppRegistry(),
          fetchNavigationLayout(),
        ])
        if (token !== generation) return
        const parsed = personal.layout ? validateNavigationLayout(personal.layout) : null
        globalBuiltin.value = builtin.menus.map(normalizeBuiltinMenu)
        globalExternal.value = external.apps.map(normalizeExternalApp)
        personalLayout.value = parsed
        layoutRevision.value = personal.revision
        layoutLoaded.value = true
        builtinMenusState.value = 'loaded'
        externalAppsState.value = 'loaded'
        builtinMenusError.value = ''
        externalAppsError.value = ''
        rebuildLayout()
      } catch (error) {
        if (token !== generation) return
        layoutError.value = error instanceof Error ? error.message : '个人导航读取失败，请重试'
        builtinMenusError.value = layoutError.value
        externalAppsError.value = layoutError.value
        builtinMenusState.value = 'error'
        externalAppsState.value = 'error'
      } finally {
        if (token === generation) {
          layoutLoading.value = false
          loadPromise = null
        }
      }
    })()
    loadPromise = operation
    return operation
  }
  function loadExternalApps(force = false) {
    return loadMenus(force)
  }
  function loadBuiltinMenus(force = false) {
    return loadMenus(force)
  }
  function reloadMenuRegistries() {
    return loadMenus(true)
  }

  function applyBuiltinMenus(menus: BuiltinMenuConfig[]) {
    globalBuiltin.value = menus.map(normalizeBuiltinMenu)
    builtinMenusState.value = 'loaded'
    builtinMenusError.value = ''
    rebuildLayout()
  }

  /** 获取可编辑快照；不把应用地址等系统属性写入个人偏好。 */
  function currentLayout(): NavigationLayout {
    return snapshotNavigation(
      builtinMenus.value,
      externalApps.value,
      layoutGroups.value,
      layoutLocked.value,
    )
  }

  async function saveLayout(next: NavigationLayout, expectedRevision = layoutRevision.value) {
    if (!canEditLayout.value) throw new Error('导航尚未读取完成或正在保存，请稍后重试')
    if (layoutConflict.value)
      throw new Error('另一设备已修改导航，请先读取最新布局再调整；当前草稿仍保留')
    if (expectedRevision !== layoutRevision.value)
      throw new Error('导航已发生变化，草稿仍保留，请重新读取后再调整')
    const canonical = validateNavigationLayout(JSON.parse(JSON.stringify(next)))
    const previous = personalLayout.value
    const token = generation
    pendingLayout.value = canonical
    menuMutationState.value = 'saving'
    menuMutationError.value = ''
    personalLayout.value = canonical
    rebuildLayout()
    try {
      const saved = await saveNavigationLayout(expectedRevision, canonical)
      if (token !== generation) return
      personalLayout.value = saved.layout ? validateNavigationLayout(saved.layout) : null
      layoutRevision.value = saved.revision
      pendingLayout.value = null
      menuMutationState.value = 'saved'
      rebuildLayout()
    } catch (error) {
      if (token !== generation) return
      personalLayout.value = previous
      rebuildLayout()
      layoutConflict.value = (error as { status?: number })?.status === 409
      menuMutationState.value = 'error'
      menuMutationError.value =
        error instanceof Error ? error.message : '个人导航保存失败，原位置已恢复'
      throw error
    }
  }

  async function retryLayout() {
    if (pendingLayout.value) await saveLayout(pendingLayout.value)
    else await loadMenus(true)
  }

  /** 明确放弃冲突草稿后再读取；不会自动把旧快照重放到新版本。 */
  async function discardLayoutDraft() {
    pendingLayout.value = null
    layoutConflict.value = false
    menuMutationError.value = ''
    menuMutationState.value = 'idle'
    await loadMenus(true)
  }

  async function setLayoutLocked(locked: boolean) {
    await saveLayout({ ...currentLayout(), locked })
  }

  async function persistMenuRegistries(
    nextBuiltin: BuiltinMenuConfig[],
    nextExternal: ExternalAppConfig[],
  ) {
    await saveLayout(
      snapshotNavigation(nextBuiltin, nextExternal, layoutGroups.value, layoutLocked.value),
    )
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
    moving.visible = true
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
          (placement !== 'MENU' || item.menu.group === group),
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

  /** 创建成员自己的侧栏分组；菜单仍沿用已有目录，只变更个人展示布局。 */
  async function createMenuGroup(label: string) {
    const trimmed = label.trim()
    if (!trimmed || trimmed.length > 24) throw new Error('分组名称不能为空且不超过 24 个字符')
    const next = currentLayout()
    if (next.groups.length >= 30) throw new Error('最多可以创建 30 个导航分组')
    if (next.groups.some((group) => group.label.toLocaleLowerCase() === trimmed.toLocaleLowerCase())) {
      throw new Error('已有同名分组，请换一个名称')
    }
    const id = `custom-${crypto.randomUUID().replace(/-/g, '')}`
    next.groups.push({ id, label: trimmed })
    await saveLayout(next)
    return id
  }

  /** 隐藏分组内当前位于侧栏的菜单；可在「全部功能」中重新固定并保留原分组。 */
  function canHideMenuGroup(groupId: string) {
    if (!groupId || groupId === 'ROOT') return false
    const layout = currentLayout()
    const group = layout.groups.find((item) => item.id === groupId)
    if (!group) return false
    if (layout.items.some((item) => item.group === groupId && item.placement === 'MENU' && item.visible)) {
      return true
    }
    return groupId.startsWith('custom-') && !layout.items.some((item) => item.group === groupId)
  }

  async function hideMenuGroup(groupId: string) {
    const next = currentLayout()
    const group = next.groups.find((item) => item.id === groupId)
    if (!group || groupId === 'ROOT') throw new Error('该分组不能隐藏')
    const items = next.items.filter((item) => item.group === groupId && item.placement === 'MENU' && item.visible)
    if (!items.length) {
      if (!groupId.startsWith('custom-') || next.items.some((item) => item.group === groupId)) {
        throw new Error('分组中没有可隐藏的侧栏菜单')
      }
      next.groups = next.groups.filter((item) => item.id !== groupId)
      await saveLayout(next)
      return 'removed-empty-group' as const
    }
    items.forEach((item) => { item.visible = false })
    await saveLayout(next)
    return 'hidden-menus' as const
  }

  /** 把菜单放到当前分组或停靠区的最前面，并立即持久化。 */
  async function pinMenu(id: NavItemId) {
    const configured = menuConfig(id)
    if (!configured) throw new Error('菜单不存在，请刷新后重试')
    const placement = configured.placement ?? 'MENU'
    const group = configured.group as BuiltinMenuGroupCode
    const peers = [
      ...builtinMenus.value.map((menu) => ({ id: menu.id as NavItemId, menu })),
      ...externalApps.value.map((app) => ({
        id: externalAppItemId(app.id) as NavItemId,
        menu: app,
      })),
    ]
      .filter(
        (item) =>
          item.id !== id &&
          item.menu.visible &&
          (item.menu.placement ?? 'MENU') === placement &&
          (placement !== 'MENU' || item.menu.group === group),
      )
      .sort((left, right) => left.menu.order - right.menu.order)
    await moveMenu(id, placement, group, peers[0]?.id)
  }

  /** 设置页保存成功后立即刷新导航，不等页面重载。 */
  function applyExternalApps(apps: ExternalAppConfig[]) {
    globalExternal.value = apps.map(normalizeExternalApp)
    externalAppsState.value = 'loaded'
    externalAppsError.value = ''
    rebuildLayout()
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
      ? {
          ...module,
          label:
            configured.label === '工作台' && id === 'navigation'
              ? '全部功能'
              : configured.label === '工作台' && id === 'workspaces'
                ? '链接空间'
                : configured.label,
          icon: configured.icon,
          openMode: configured.openMode,
        }
      : { ...module, openMode: 'TAB' as const }
  }

  return {
    collapsed,
    foldedGroups,
    personalOrder,
    groups,
    dockItems,
    topItems,
    draggedMenuId,
    canMoveMenus,
    layoutRevision,
    canEditLayout,
    layoutGroups,
    layoutLocked,
    layoutLoaded,
    layoutLoading,
    layoutError,
    layoutConflict,
    pendingLayout,
    currentLayout,
    saveLayout,
    retryLayout,
    discardLayoutDraft,
    setLayoutLocked,
    loadMenus,
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
    createMenuGroup,
    canHideMenuGroup,
    hideMenuGroup,
    pinMenu,
    menuConfig,
    externalAppById,
    itemMeta,
  }
})
