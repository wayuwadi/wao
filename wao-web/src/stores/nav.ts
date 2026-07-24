import { ref } from 'vue'
import { defineStore } from 'pinia'

/** 导航 store：侧栏整栏折叠态 + 各分组折叠态，持久化到 localStorage。 */

const NAV_KEY = 'wao-nav-v1'

interface Persisted {
  collapsed: boolean
  foldedGroups: Record<string, boolean>
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

export const useNavStore = defineStore('nav', () => {
  const persisted = load()
  const collapsed = ref(persisted.collapsed)
  const foldedGroups = ref<Record<string, boolean>>(persisted.foldedGroups)

  function persist() {
    localStorage.setItem(
      NAV_KEY,
      JSON.stringify({ collapsed: collapsed.value, foldedGroups: foldedGroups.value }),
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

  return { collapsed, foldedGroups, toggleCollapse, toggleGroup, isFolded }
})
