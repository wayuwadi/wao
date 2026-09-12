export type ConfigurableShortcutAction = 'new-task' | 'new-note' | 'chat'

export interface ShortcutSettings {
  window: Record<ConfigurableShortcutAction, string>
  global: Record<ConfigurableShortcutAction, string>
}

export const SHORTCUT_ACTIONS: ReadonlyArray<{
  id: ConfigurableShortcutAction
  label: string
  description: string
}> = [
  { id: 'new-task', label: '新建任务', description: '直接打开任务记录框' },
  { id: 'new-note', label: '打开小记', description: '进入小记并开始记录' },
  { id: 'chat', label: '打开聊天', description: '打开团队聊天窗口' },
]

export const DEFAULT_SHORTCUT_SETTINGS: ShortcutSettings = {
  window: { 'new-task': 'Alt+Q', 'new-note': 'Alt+E', chat: 'Alt+F' },
  global: { 'new-task': '', 'new-note': 'Alt+Shift+Y', chat: '' },
}

const ACTION_IDS = new Set(SHORTCUT_ACTIONS.map((item) => item.id))
const MODIFIER_ORDER = ['CommandOrControl', 'Alt', 'Shift'] as const

export function cloneShortcutSettings(settings = DEFAULT_SHORTCUT_SETTINGS): ShortcutSettings {
  return { window: { ...settings.window }, global: { ...settings.global } }
}

/** Normalize stored/captured combinations to the syntax shared by the browser and Tauri. */
export function normalizeShortcut(value: string, allowEmpty = false): string | null {
  const trimmed = value.trim()
  if (!trimmed) return allowEmpty ? '' : null
  const raw = trimmed
    .split('+')
    .map((part) => part.trim())
    .filter(Boolean)
  const key = raw.pop()?.toUpperCase() ?? ''
  if (!/^[A-Z0-9]$/.test(key)) return null
  const modifiers = new Set<string>()
  for (const part of raw) {
    const lower = part.toLowerCase().replace(/⌘/g, 'command')
    if (
      ['ctrl', 'control', 'cmd', 'command', 'meta', 'cmdorctrl', 'commandorcontrol'].includes(lower)
    )
      modifiers.add('CommandOrControl')
    else if (['alt', 'option'].includes(lower)) modifiers.add('Alt')
    else if (lower === 'shift') modifiers.add('Shift')
    else return null
  }
  if (!modifiers.size) return null
  return [...MODIFIER_ORDER.filter((modifier) => modifiers.has(modifier)), key].join('+')
}

export function shortcutFromEvent(event: KeyboardEvent): string | null {
  if (['Alt', 'Shift', 'Control', 'Meta'].includes(event.key)) return null
  const key = event.key.length === 1 ? event.key.toUpperCase() : ''
  if (!/^[A-Z0-9]$/.test(key)) return null
  const parts: string[] = []
  if (event.metaKey || event.ctrlKey) parts.push('CommandOrControl')
  if (event.altKey) parts.push('Alt')
  if (event.shiftKey) parts.push('Shift')
  if (!parts.length) return null
  return [...parts, key].join('+')
}

export function shortcutMatches(event: KeyboardEvent, shortcut: string): boolean {
  return !!shortcut && shortcutFromEvent(event) === shortcut
}

export function parseShortcutSettings(value: unknown): ShortcutSettings {
  const result = cloneShortcutSettings()
  if (!value || typeof value !== 'object') return result
  for (const scope of ['window', 'global'] as const) {
    const source = (value as Record<string, unknown>)[scope]
    if (!source || typeof source !== 'object') continue
    for (const [action, raw] of Object.entries(source)) {
      if (!ACTION_IDS.has(action as ConfigurableShortcutAction) || typeof raw !== 'string') continue
      const normalized = normalizeShortcut(raw, scope === 'global')
      if (normalized !== null) result[scope][action as ConfigurableShortcutAction] = normalized
    }
  }
  return result
}

export function validateShortcutSettings(settings: ShortcutSettings): string {
  for (const scope of ['window', 'global'] as const) {
    const used = new Set<string>()
    for (const item of SHORTCUT_ACTIONS) {
      const normalized = normalizeShortcut(settings[scope][item.id], scope === 'global')
      if (normalized === null)
        return `${item.label}的${scope === 'global' ? '系统级' : '窗口内'}快捷键格式无效`
      settings[scope][item.id] = normalized
      if (normalized && used.has(normalized))
        return `同一组快捷键不能重复使用：${displayShortcut(normalized)}`
      if (normalized) used.add(normalized)
    }
  }
  return ''
}

export function displayShortcut(shortcut: string): string {
  return shortcut.replace('CommandOrControl', 'Ctrl/⌘').replace('Alt', 'Alt/⌥')
}
