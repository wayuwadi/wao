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
  resolveNavGroups,
  type ExternalAppConfig,
  type BuiltinMenuConfig,
  type NavItemId,
} from '@/config/nav'
import { fetchPreference, savePreference } from '@/api/favorites'
import { fetchBuiltinMenuRegistry, fetchExternalAppRegistry } from '@/api/system'
import type { ModuleId } from '@/types'

/** 导航 store：侧栏整栏折叠态 + 各分组折叠态 + 「个人」组自定义顺序。 */

const NAV_KEY = 'wao-nav-v1'
const PERSONAL_ORDER_PREF = 'nav-personal-order'

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
  const saved = Array.isArray(order) ? order.filter((id): id is ModuleId => PERSONAL_ITEMS.includes(id as ModuleId)) : []
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
  const groups = computed(() => resolveNavGroups(personalOrder.value, externalApps.value, builtinMenus.value))

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
  async function loadBuiltinMenus() {
    try {
      builtinMenus.value = (await fetchBuiltinMenuRegistry()).menus.map(normalizeBuiltinMenu)
    } catch {
      /* 坏配置或短暂离线时保留代码默认导航，不能让菜单消失。 */
    }
  }
  function applyBuiltinMenus(menus: BuiltinMenuConfig[]) {
    builtinMenus.value = menus.map(normalizeBuiltinMenu)
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
      return app ? {
        id: externalAppItemId(app.id),
        label: app.label,
        icon: app.icon,
        path: externalAppPath(app.id),
        newPagePath: app.appUrl,
        openMode: app.openMode,
      } : undefined
    }
    if (!id) return undefined
    const module = MODULES[id as ModuleId]
    const configured = builtinMenus.value.find((menu) => menu.id === id)
    return configured
      ? { ...module, label: configured.label, icon: configured.icon, openMode: configured.openMode }
      : { ...module, openMode: 'TAB' as const }
  }

  return {
    collapsed, foldedGroups, personalOrder, groups,
    externalApps, builtinMenus, externalAppsState, externalAppsError,
    toggleCollapse, toggleGroup, isFolded, loadPersonalOrder, setPersonalOrder,
    loadExternalApps, loadBuiltinMenus, applyExternalApps, applyBuiltinMenus, externalAppById, itemMeta,
  }
})
