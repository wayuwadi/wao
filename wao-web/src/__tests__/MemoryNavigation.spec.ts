import { describe, expect, it } from 'vitest'
import { legacyMemorySettingsRedirect, legacyNotificationSettingsRedirect } from '@/router'

describe('记忆中心入口兼容', () => {
  it.each(['memory-mcp', 'api'])('把旧设置入口 %s 迁移到 Codex 连接', (tab) => {
    expect(legacyMemorySettingsRedirect(tab)).toEqual({
      path: '/memories', query: { section: 'mcp' }, replace: true,
    })
  })

  it('不改写其他设置入口', () => {
    expect(legacyMemorySettingsRedirect('prefs')).toBeUndefined()
  })

  it('保留内嵌通知设置入口，并把旧独立地址迁回设置页', () => {
    expect(legacyMemorySettingsRedirect('notifications')).toBeUndefined()
    expect(legacyNotificationSettingsRedirect()).toEqual({
      path: '/settings', query: { tab: 'notifications' }, replace: true,
    })
  })
})
