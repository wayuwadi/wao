import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useTabsStore } from '@/stores/tabs'

/** 「g 前缀」跳转表：先按 g，再按对应键跳转到模块。 */
const GO_ROUTES: Record<string, string> = {
  h: '/',
  p: '/projects',
  k: '/knowledge',
  f: '/files',
  a: '/automation',
  v: '/vault',
  s: '/settings',
}

/** 快捷键分组，供帮助浮层展示（label 与实现保持一致）。 */
export const SHORTCUT_GROUPS: { title: string; items: [keys: string, desc: string][] }[] = [
  {
    title: '全局',
    items: [
      ['⌘/Ctrl K', '打开搜索'],
      ['/', '打开搜索'],
      ['N', '新建任务'],
      ['Shift N', '新建项目'],
      ['?', '显示 / 隐藏快捷键'],
      ['Esc', '逐层关闭浮层'],
    ],
  },
  {
    title: '页签',
    items: [
      ['⌘/Ctrl W', '关闭当前页签'],
    ],
  },
  {
    title: '跳转（先按 G）',
    items: [
      ['G H', '首页'],
      ['G P', '项目'],
      ['G K', '知识库'],
      ['G F', '文件与图床'],
      ['G A', '自动化'],
      ['G V', '密码库'],
      ['G S', '设置'],
    ],
  },
]

function isEditable(el: EventTarget | null): boolean {
  const n = el as HTMLElement | null
  if (!n) return false
  return n.tagName === 'INPUT' || n.tagName === 'TEXTAREA' || n.isContentEditable
}

/**
 * 全局快捷键。挂在 AppLayout：
 * - ⌘/Ctrl+W 关闭当前页签（而非浏览器标签，尽力 preventDefault）；
 * - 单键动作 N / Shift+N / / / ?，以及 G 前缀跳转；
 * - 输入框内、弹窗 / 搜索开启时不触发单键动作，避免误伤打字。
 */
export function useShortcuts() {
  const router = useRouter()
  const ui = useUiStore()
  const tabs = useTabsStore()

  let goPending = false
  let goTimer: ReturnType<typeof setTimeout> | null = null

  function closeActiveTab() {
    const next = tabs.close(tabs.activeKey)
    if (next) router.push(next.path)
  }

  function onKey(e: KeyboardEvent) {
    // ⌘/Ctrl+W：关闭当前页签，替代关闭浏览器标签页。
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'w') {
      e.preventDefault()
      closeActiveTab()
      return
    }
    // ? 帮助：即使在其它浮层上也可切换（但不在输入框中）。
    if (e.key === '?' && !isEditable(e.target)) {
      e.preventDefault()
      ui.toggleHelp()
      return
    }
    // 其余单键动作：避开修饰键、输入框与已开启的浮层。
    if (e.metaKey || e.ctrlKey || e.altKey) return
    if (isEditable(e.target)) return
    if (ui.modal || ui.searchOpen || ui.helpOpen) return

    if (goPending) {
      goPending = false
      if (goTimer) clearTimeout(goTimer)
      const path = GO_ROUTES[e.key.toLowerCase()]
      if (path) {
        e.preventDefault()
        router.push(path)
      }
      return
    }

    switch (e.key) {
      case 'g':
      case 'G':
        goPending = true
        goTimer = setTimeout(() => (goPending = false), 900)
        break
      case 'n':
        e.preventDefault()
        ui.openModal('task')
        break
      case 'N':
        e.preventDefault()
        ui.openModal('project')
        break
      case '/':
        e.preventDefault()
        ui.openSearch()
        break
      default:
        break
    }
  }

  onMounted(() => window.addEventListener('keydown', onKey))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
}
