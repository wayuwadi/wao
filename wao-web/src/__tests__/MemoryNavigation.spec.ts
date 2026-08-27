import { describe, expect, it } from 'vitest'
import { legacyMemorySettingsRedirect } from '@/router'

describe('记忆中心入口兼容', () => {
  it.each(['memory-mcp', 'api'])('把旧设置入口 %s 迁移到 Codex 连接', (tab) => {
    expect(legacyMemorySettingsRedirect(tab)).toEqual({
      path: '/memories', query: { section: 'mcp' }, replace: true,
    })
  })

  it('不改写其他设置入口', () => {
    expect(legacyMemorySettingsRedirect('prefs')).toBeUndefined()
  })
})
