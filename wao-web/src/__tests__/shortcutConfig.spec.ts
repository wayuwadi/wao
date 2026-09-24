import { describe, expect, it } from 'vitest'
import {
  cloneShortcutSettings,
  normalizeShortcut,
  parseShortcutSettings,
  shortcutMatches,
  validateShortcutSettings,
} from '@/utils/shortcutConfig'

describe('可配置快捷键', () => {
  it('规范化跨平台组合键并按真实键盘事件匹配', () => {
    expect(normalizeShortcut('ctrl+alt+q')).toBe('CommandOrControl+Alt+Q')
    expect(
      shortcutMatches(
        new KeyboardEvent('keydown', { key: 'q', ctrlKey: true, altKey: true }),
        'CommandOrControl+Alt+Q',
      ),
    ).toBe(true)
    expect(normalizeShortcut('Q')).toBeNull()
  })

  it('拒绝同一作用域重复并保留安全默认值', () => {
    const settings = cloneShortcutSettings()
    settings.window['new-task'] = settings.window['new-note']
    expect(validateShortcutSettings(settings)).toContain('不能重复')
    expect(parseShortcutSettings({ window: { chat: 'Alt+F' }, global: { 'new-note': '' } }))
      .not.toHaveProperty('window.chat')
    expect(parseShortcutSettings({ global: { 'new-note': '' } }).global['new-note']).toBe('')
  })
})
