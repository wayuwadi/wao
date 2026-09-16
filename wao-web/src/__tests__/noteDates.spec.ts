import { describe, expect, it } from 'vitest'
import { matchesNoteDate } from '@/utils/noteDates'

describe('小记按本地创建日期筛选', () => {
  it('包含当天零点，排除次日零点，并跨月定位昨天', () => {
    const now = new Date(2026, 8, 1, 12)
    expect(matchesNoteDate('2026-09-01T00:00:00', 'today', now)).toBe(true)
    expect(matchesNoteDate('2026-09-02T00:00:00', 'today', now)).toBe(false)
    expect(matchesNoteDate('2026-08-31T23:59:59', 'yesterday', now)).toBe(true)
    expect(matchesNoteDate('2026-09-01T00:00:00', 'yesterday', now)).toBe(false)
    expect(matchesNoteDate('无时间', 'today', now)).toBe(false)
    expect(matchesNoteDate('无时间', 'all', now)).toBe(true)
  })
})
