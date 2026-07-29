import { ref } from 'vue'
import { defineStore } from 'pinia'
import { PERSONAL_ITEMS } from '@/config/nav'
import { fetchPreference, savePreference } from '@/api/favorites'
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

  return {
    collapsed, foldedGroups, personalOrder,
    toggleCollapse, toggleGroup, isFolded, loadPersonalOrder, setPersonalOrder,
  }
})
