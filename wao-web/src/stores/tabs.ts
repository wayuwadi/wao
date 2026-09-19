import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { Tab, ModuleId } from '@/types'
import { externalAppItemId, externalAppPath, moduleTab, MODULES } from '@/config/nav'

/**
 * 页签 store：多页签栏的可视化路由栈。
 * 「首页」固定不可关闭且恒在最左；页签列表 + 激活项持久化到 localStorage。
 * 成员手动置顶的页签会在批量关闭时保留，仍可通过单独关闭明确移除。
 * 关闭操作返回「应导航到的新激活页签」，由调用方（AppLayout）执行 router.push。
 */

const TABS_KEY = 'wao-tabs-v1'

/** 已下线的模块页签：老浏览器里存着的记录要在加载时丢掉，否则会留下一个点不开的死页签。 */
const RETIRED_TAB_KEYS = new Set(['whiteboard', 'trash', 'chat'])

interface Persisted {
  tabs: Tab[]
  activeKey: string
}

function load(): Persisted {
  try {
    const raw = localStorage.getItem(TABS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Persisted
      const tabs = (parsed.tabs ?? [])
        .filter((t) => !RETIRED_TAB_KEYS.has(t.key))
        .map((tab) => {
          const normalized = { ...tab, pinned: tab.pinned === true }
          // 系统模块沿用当前名称；显式自定义、动态实体与外部应用标题不覆盖。
          if (!tab.customLabel && Object.prototype.hasOwnProperty.call(MODULES, tab.key)) {
            normalized.label = MODULES[tab.key as ModuleId].label
          }
          return tab.key === 'mediaAgent'
            ? {
                ...normalized,
                key: externalAppItemId('media-agent'),
                path: externalAppPath('media-agent'),
              }
            : normalized
        })
      const activeKey = RETIRED_TAB_KEYS.has(parsed.activeKey)
        ? 'home'
        : parsed.activeKey === 'mediaAgent'
          ? externalAppItemId('media-agent')
          : parsed.activeKey
      return { tabs, activeKey }
    }
  } catch {
    /* ignore */
  }
  return { tabs: [], activeKey: 'home' }
}

export const useTabsStore = defineStore('tabs', () => {
  const persisted = load()
  const tabs = ref<Tab[]>(persisted.tabs.length ? persisted.tabs : [moduleTab('home')])
  const activeKey = ref(persisted.activeKey || 'home')
  ensureHome()

  function ensureHome() {
    if (!tabs.value.some((t) => t.key === 'home')) tabs.value.unshift(moduleTab('home'))
  }

  function persist() {
    localStorage.setItem(TABS_KEY, JSON.stringify({ tabs: tabs.value, activeKey: activeKey.value }))
  }

  /**
   * 打开（或复用）一个页签并激活它。
   *
   * <p>占位标签（「项目 p3」这种由 id 拼出来的）不得覆盖已经回填好的真名：
   * 每次路由变化都会重新推导页签，而项目内切换 tab / view 时详情视图的回填
   * watcher 不会重跑（项目名没变），标题就会一直卡在编号上，直到刷新才恢复。
   */
  function open(tab: Tab) {
    const existing = tabs.value.find((t) => t.key === tab.key)
    if (existing) {
      existing.path = tab.path
      const keepResolved = tab.labelPlaceholder && !existing.labelPlaceholder
      if (tab.label && !keepResolved && !existing.customLabel) {
        existing.label = tab.label
        existing.labelPlaceholder = tab.labelPlaceholder
      }
    } else {
      tabs.value.push(tab)
    }
    activeKey.value = tab.key
    persist()
  }

  function activate(key: string) {
    if (tabs.value.some((t) => t.key === key)) {
      activeKey.value = key
      persist()
    }
  }

  /**
   * 把一个页签拖到另一个页签的位置上。
   *
   * <p>首页固定钉在最左：它既不能被拖走，别的页签也不能插到它前面 —— 那是唯一
   * 不可关闭的锚点，位置乱了反而不好找。
   *
   * @param fromKey 被拖动的页签
   * @param toKey   落点页签
   */
  function move(fromKey: string, toKey: string) {
    if (fromKey === toKey || fromKey === 'home') return
    const from = tabs.value.findIndex((t) => t.key === fromKey)
    let to = tabs.value.findIndex((t) => t.key === toKey)
    if (from < 0 || to < 0) return
    if (toKey === 'home') to = 1
    const [moved] = tabs.value.splice(from, 1)
    if (!moved) return
    tabs.value.splice(to, 0, moved)
    persist()
  }

  /** 切换成员页签的置顶状态；首页本身已经固定，不重复提供置顶操作。 */
  function togglePin(key: string) {
    const index = tabs.value.findIndex((item) => item.key === key)
    const tab = tabs.value[index]
    if (!tab?.closable) return
    tab.pinned = !tab.pinned
    tabs.value.splice(index, 1)
    if (tab.pinned) {
      const firstUnpinned = tabs.value.findIndex((item, itemIndex) => itemIndex > 0 && !item.pinned)
      tabs.value.splice(firstUnpinned < 0 ? tabs.value.length : firstUnpinned, 0, tab)
    } else {
      const lastPinned = tabs.value.reduce((result, item, itemIndex) => item.pinned ? itemIndex : result, 0)
      tabs.value.splice(lastPinned + 1, 0, tab)
    }
    persist()
  }

  /** 成员可在页签右键菜单修改当前浏览器里的展示名称。 */
  function rename(key: string, label: string) {
    const normalized = label.trim()
    if (!normalized || normalized.length > 24) return false
    const tab = tabs.value.find((item) => item.key === key)
    if (!tab?.closable) return false
    tab.label = normalized
    tab.labelPlaceholder = false
    tab.customLabel = true
    persist()
    return true
  }

  /** 详情视图加载实体后回填页签标签；回填后即不再是占位。 */
  function setLabel(key: string, label: string) {
    const t = tabs.value.find((t) => t.key === key)
    if (t && label && !t.customLabel) {
      t.label = label
      t.labelPlaceholder = false
      persist()
    }
  }

  /** 外部应用注册表变化后同步已经打开的页签名称与图标。 */
  function setMeta(key: string, patch: Pick<Tab, 'label' | 'icon'>) {
    const tab = tabs.value.find((item) => item.key === key)
    if (tab) {
      if (!tab.customLabel) tab.label = patch.label
      tab.icon = patch.icon
      tab.labelPlaceholder = false
      persist()
    }
  }

  function activeTab(): Tab | undefined {
    return tabs.value.find((t) => t.key === activeKey.value)
  }

  /** 关闭一个页签；若关闭的是激活页签，激活相邻页签。返回新激活页签用于导航。 */
  function close(key: string): Tab | null {
    const idx = tabs.value.findIndex((t) => t.key === key)
    const target = tabs.value[idx]
    if (idx < 0 || !target || !target.closable) return null
    const wasActive = activeKey.value === key
    tabs.value.splice(idx, 1)
    ensureHome()
    if (wasActive) {
      const next = tabs.value[idx] ?? tabs.value[idx - 1] ?? tabs.value[0]
      if (next) {
        activeKey.value = next.key
        persist()
        return next
      }
    }
    persist()
    return null
  }

  function closeMany(predicate: (t: Tab, i: number) => boolean): Tab | null {
    const kept = tabs.value.filter((t, i) => !t.closable || t.pinned || !predicate(t, i))
    tabs.value = kept
    ensureHome()
    if (!tabs.value.some((t) => t.key === activeKey.value)) {
      const next = tabs.value[tabs.value.length - 1] ?? tabs.value[0]
      if (next) {
        activeKey.value = next.key
        persist()
        return next
      }
    }
    persist()
    return null
  }

  /** 关闭其它（保留首页、目标与置顶页签）。 */
  function closeOthers(key: string) {
    return closeMany((t) => t.key !== key)
  }

  /** 关闭目标左边未置顶的可关页签。 */
  function closeLeft(key: string) {
    const idx = tabs.value.findIndex((t) => t.key === key)
    return closeMany((_t, i) => i < idx)
  }

  /** 关闭目标右边未置顶的可关页签。 */
  function closeRight(key: string) {
    const idx = tabs.value.findIndex((t) => t.key === key)
    return closeMany((_t, i) => i > idx)
  }

  /** 关闭全部未置顶页签（保留首页与成员置顶页签）。 */
  function closeAll() {
    return closeMany(() => true)
  }

  /** 明确关闭所有非置顶页签；语义与置顶保护一致。 */
  function closeUnpinned() {
    return closeMany((tab) => !tab.pinned)
  }

  return {
    tabs,
    activeKey,
    open,
    activate,
    move,
    togglePin,
    rename,
    setLabel,
    setMeta,
    activeTab,
    close,
    closeOthers,
    closeLeft,
    closeRight,
    closeAll,
    closeUnpinned,
  }
})
