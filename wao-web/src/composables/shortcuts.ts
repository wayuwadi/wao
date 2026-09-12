import { onBeforeUnmount, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useTabsStore } from '@/stores/tabs'
import { useSessionStore } from '@/stores/session'
import { useShortcutSettingsStore } from '@/stores/shortcuts'
import {
  SHORTCUT_ACTIONS,
  shortcutMatches,
  type ConfigurableShortcutAction,
} from '@/utils/shortcutConfig'

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
    title: '固定快捷键',
    items: [
      ['⌘/Ctrl K', '打开搜索'],
      ['⌘/Ctrl J', '打开 AI 助手'],
      ['⌘/Ctrl S', '拦截浏览器另存为'],
      ['/', '打开搜索'],
      ['Shift N', '新建项目'],
      ['?', '显示 / 隐藏快捷键'],
      ['Esc', '逐层关闭浮层'],
    ],
  },
  {
    title: '页签',
    items: [['⌘/Ctrl W', '关闭当前页签']],
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
 * - ⌘/Ctrl+S 阻止浏览器打开「另存为」，正文仍沿用各编辑器自身的自动保存；
 * - ⌘/Ctrl+W 关闭当前页签（而非浏览器标签，尽力 preventDefault）；
 * - 可配置的组合键动作、单键 Shift+N / / / ?，以及 G 前缀跳转；
 * - 输入框内、弹窗 / 搜索开启时不触发单键动作，避免误伤打字。
 */
export function useShortcuts() {
  const router = useRouter()
  const ui = useUiStore()
  const tabs = useTabsStore()
  const session = useSessionStore()
  const shortcutSettings = useShortcutSettingsStore()

  let goPending = false
  let goTimer: ReturnType<typeof setTimeout> | null = null

  function closeActiveTab() {
    const next = tabs.close(tabs.activeKey)
    if (next) router.push(next.path)
  }

  function runConfiguredAction(action: ConfigurableShortcutAction) {
    if (action === 'new-task') ui.openModal('task')
    else if (action === 'new-note') ui.openModal('note')
    else ui.openChat()
  }

  function onKey(e: KeyboardEvent) {
    // WAO 的编辑内容各自自动保存；只拦截浏览器「另存为」，不额外触发重复写入。
    // 这条必须早于输入框判断，确保光标在 Tiptap、textarea 或 input 内时同样生效。
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
      e.preventDefault()
      return
    }
    // ⌘/Ctrl+W：关闭当前页签，替代关闭浏览器标签页。
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'w') {
      e.preventDefault()
      closeActiveTab()
      return
    }
    if (!ui.modal && !ui.searchOpen && !ui.helpOpen) {
      const configured = SHORTCUT_ACTIONS.find((item) =>
        shortcutMatches(e, shortcutSettings.settings.window[item.id]),
      )
      if (configured) {
        e.preventDefault()
        runConfiguredAction(configured.id)
        return
      }
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
  onMounted(() => void shortcutSettings.load())
  const stopMemberWatch = watch(
    () => session.userId,
    () => void shortcutSettings.load(true),
  )
  onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKey)
    stopMemberWatch()
  })
}
